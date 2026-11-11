package ren.imyan.sharerelay

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import java.io.File
import java.io.FileOutputStream
/**
 * 分享中转站：收到 QQ（或任意 App）的 ACTION_SEND，按设置的模式把内容转交给系统分享面板。
 *  - ROOT：su 直读原文件 -> 自己的 Provider 供数 -> 立即 finish，不需要等回执
 *  - PASSTHROUGH：原样透传来源 URI，保持本页存活直到分享面板关闭（否则临时 URI 授权会被回收）
 *  - COPY：复制到本应用后分享
 */
class RelayActivity : ComponentActivity() {

    companion object {
        private const val TAG = "ShareRelay"
        private const val REQ_SHARE = 1001
    }

    private var busy = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val progressState = mutableStateOf<CopyProgress?>(null)
    private var progressView = null as androidx.compose.ui.platform.ComposeView?
    private var pendingShow: Runnable? = null

    @Volatile
    private var cancelled = false

    private data class CopyProgress(val title: String, val detail: String, val percent: Float?)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        )
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) { window.isNavigationBarContrastEnforced = false }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) { window.setDecorFitsSystemWindows(false) }
        dispatch(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        dispatch(intent)
    }

    private fun dispatch(intent: Intent?) {
        if (busy) {
            Toast.makeText(this, getString(R.string.toast_busy), Toast.LENGTH_SHORT).show()
            return
        }
        busy = true
        val payload = IntentParser.parse(this, intent)
        val source = safeSource()
        if (payload.uris.isEmpty() && payload.text.isNullOrBlank()) {
            fail(source, getString(R.string.toast_no_content), null, getString(R.string.log_mode_text))
            return
        }
        if (payload.uris.isEmpty()) {
            forwardTextOnly(payload, source)
            return
        }
        Thread { execute(payload, source) }.start()
    }

    private fun execute(payload: SharePayload, source: String?) {
        val configured = Settings.mode(this)
        val rootOk = RootHelper.check(this)
        val mode = when (configured) {
            TransferMode.AUTO, TransferMode.ROOT -> if (rootOk) TransferMode.ROOT else TransferMode.PASSTHROUGH
            else -> configured
        }

        var waitResult = false
        var realMode = mode
        var items: List<Forwarder.Item>? = null
        try {
            when (mode) {
                TransferMode.ROOT -> {
                    items = rootItems(payload)
                    if (items == null) {
                        Log.i(TAG, "root 直读失败，回落到复制")
                        realMode = TransferMode.COPY
                        items = copyItems(payload)
                    }
                }
                TransferMode.PASSTHROUGH -> {
                    if (payload.uris.any { it.scheme == "file" }) {
                        Log.i(TAG, "file:// 透传会被系统拦截，改为复制")
                        realMode = TransferMode.COPY
                        items = copyItems(payload)
                    } else {
                        items = passthroughItems(payload)
                        waitResult = true
                    }
                }
                else -> items = copyItems(payload)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "execute failed", t)
            items = null
        }

        val finalItems = items
        if (finalItems == null || finalItems.isEmpty()) {
            if (cancelled) {
                finish()
                return
            }
            hideProgress()
            fail(source, getString(R.string.error_read_failed), payload, realMode.title(this))
            return
        }

        runOnUiThread {
            try {
                val chooser = Forwarder.buildChooser(
                    this, finalItems, payload.text, payload.type,
                    Settings.excludeSelf(this), Settings.excludeQq(this)
                )
                record(source, payload, realMode, finalItems, success = true, extra = if (waitResult) getString(R.string.log_detail_wait) else getString(R.string.log_detail_direct))
                if (waitResult) {
                    startActivityForResult(chooser, REQ_SHARE)
                } else {
                    startActivity(chooser)
                    finish()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "launch chooser failed", t)
                fail(source, getString(R.string.error_chooser_failed, t.message), payload, realMode.title(this))
            }
        }
    }

    // ------------------------------------------------------------ 三种取数策略

    private fun rootItems(payload: SharePayload): List<Forwarder.Item>? {
        val out = ArrayList<Forwarder.Item>(payload.uris.size)
        for (u in payload.uris) {
            val path = PathResolver.resolve(this, u) ?: return null
            val size = RootHelper.statSize(path)
            if (size < 0) return null
            val name = displayName(u) ?: File(path).name
            val mime = safeMime(u) ?: mimeFromName(name)
            val token = RootVfs.register(path, name, size, mime)
            out += Forwarder.Item(RelayProvider.uriForRoot(token), mime, name, size)
        }
        return out
    }

    private fun passthroughItems(payload: SharePayload): List<Forwarder.Item>? {
        val out = ArrayList<Forwarder.Item>(payload.uris.size)
        for (u in payload.uris) {
            val name = displayName(u)
            val mime = safeMime(u) ?: mimeFromName(name ?: u.lastPathSegment ?: "")
            out += Forwarder.Item(u, mime, name, displaySize(u))
        }
        return out
    }

    private fun copyItems(payload: SharePayload): List<Forwarder.Item>? {
        RelayProvider.purgeOld(this)
        val dir = RelayProvider.sharedDir(this)
        if (!dir.exists() && !dir.mkdirs()) return null
        val total = payload.uris.sumOf { displaySize(it).coerceAtLeast(0L) }
        var doneBytes = 0L
        var lastTick = 0L
        Log.i(TAG, "copy start files=${payload.uris.size} total=$total")
        if (total > 20L * 1024 * 1024) { mainHandler.post { ensureProgressUi() } } else { scheduleProgressShow() }
        val out = ArrayList<Forwarder.Item>(payload.uris.size)
        payload.uris.forEachIndexed { idx, u ->
            if (cancelled) return null
            val raw = displayName(u) ?: "share_$idx"
            val dest = File(dir, "${System.currentTimeMillis()}-$idx-${sanitize(raw)}")
            try {
                val input = contentResolver.openInputStream(u)
                if (input == null) return null
                input.use { ins ->
                    FileOutputStream(dest).use { outs ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            if (cancelled) {
                                outs.flush()
                                runCatching { dest.delete() }
                                return null
                            }
                            val read = ins.read(buf)
                            if (read <= 0) break
                            outs.write(buf, 0, read)
                            doneBytes += read
                            val now = System.currentTimeMillis()
                            if (now - lastTick >= 120) {
                                lastTick = now
                                updateProgress(idx, payload.uris.size, raw, doneBytes, total)
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "copy failed $u", t)
                runCatching { dest.delete() }
                return null
            }
            updateProgress(idx, payload.uris.size, raw, doneBytes, total)
            val mime = safeMime(u) ?: mimeFromName(dest.name)
            out += Forwarder.Item(RelayProvider.uriForShared(dest), mime, raw, dest.length())
        }
        hideProgress()
        return out
    }

    // ------------------------------------------------------------ 复制进度

    private fun scheduleProgressShow() {
        if (progressView != null) return
        val r = Runnable { ensureProgressUi() }
        pendingShow = r
        mainHandler.postDelayed(r, 250)
    }

    private fun hideProgress() {
        pendingShow?.let { mainHandler.removeCallbacks(it) }
        pendingShow = null
        mainHandler.post { progressState.value = null }
    }

    private fun updateProgress(index: Int, count: Int, name: String, done: Long, total: Long) {
        mainHandler.post {
            ensureProgressUi()
            progressState.value = CopyProgress(
                title = if (count > 1) getString(R.string.progress_copying_multi, index + 1, count) else getString(R.string.progress_copying),
                detail = buildString {
                    append(name)
                    if (done > 0) {
                        append("  ").append(formatSize(done))
                        if (total > 0) append(" / ").append(formatSize(total))
                    }
                },
                percent = if (total > 0) (done.toFloat() / total.toFloat()).coerceIn(0f, 1f) else null
            )
        }
    }

    private fun ensureProgressUi() {
        if (progressView != null || isFinishing || isDestroyed) return
        val view = ComposeView(this)
        view.setContent {
            MiuixTheme(colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                CopyProgressOverlay(progressState.value) {
                    cancelled = true
                    finish()
                }
            }
        }
        progressView = view
        setContentView(view)
        window.statusBarColor = 0x99000000.toInt()
        window.navigationBarColor = 0x99000000.toInt()
    }

    @Composable
    private fun CopyProgressOverlay(progress: CopyProgress?, onCancel: () -> Unit) {
        if (progress == null) return
        Box(
            modifier = Modifier.fillMaxSize().background(Color(0x66000000)),
            contentAlignment = Alignment.Center
        ) {
            Card(modifier = Modifier.fillMaxWidth(0.82f)) {
                Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                    Text(text = progress.title, style = MiuixTheme.textStyles.title4)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = progress.detail,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                    Spacer(Modifier.height(16.dp))
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        progress = progress.percent
                    )
                    Spacer(Modifier.height(12.dp))
                    TextButton(text = stringResource(R.string.action_cancel), onClick = onCancel, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }

    // ------------------------------------------------------------ 文本

    private fun forwardTextOnly(payload: SharePayload, source: String?) {
        runOnUiThread {
            try {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = payload.type?.takeIf { it.isNotBlank() } ?: "text/plain"
                    putExtra(Intent.EXTRA_TEXT, payload.text)
                }
                val chooser = Intent.createChooser(send, getString(R.string.chooser_title))
                chooser.putExtra(
                    Intent.EXTRA_EXCLUDE_COMPONENTS,
                    arrayOf(ComponentName(this, RelayActivity::class.java))
                )
                ShareLog.add(
                    this, getString(R.string.log_mode_text), source, getString(R.string.log_plain_text),
                    payload.text?.take(120) ?: "", true
                )
                startActivity(chooser)
                finish()
            } catch (t: Throwable) {
                fail(source, getString(R.string.error_text_share_failed, t.message), payload, getString(R.string.log_mode_text))
            }
        }
    }

    // ------------------------------------------------------------ 工具

    private fun safeSource(): String? = try {
        referrer?.host
    } catch (t: Throwable) {
        null
    }

    private fun safeMime(u: Uri): String? = try {
        contentResolver.getType(u)
    } catch (t: Throwable) {
        null
    }

    private fun displayName(u: Uri): String? = try {
        contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (t: Throwable) {
        null
    }

    private fun displaySize(u: Uri): Long = try {
        contentResolver.query(u, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
        } ?: -1L
    } catch (t: Throwable) {
        -1L
    }

    private fun mimeFromName(name: String): String? {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return null
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
    }

    private fun sanitize(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim()
        val cut = if (cleaned.length > 64) cleaned.takeLast(64) else cleaned
        return cut.ifEmpty { "file" }
    }

    

    private fun record(
        source: String?,
        payload: SharePayload,
        mode: TransferMode,
        items: List<Forwarder.Item>,
        success: Boolean,
        extra: String
    ) {
        val title = when {
            items.size == 1 -> items.first().name ?: payload.text?.take(60) ?: getString(R.string.log_one_file)
            else -> getString(R.string.log_multi_files, items.size)
        }
        val detail = buildString {
            append(getString(R.string.log_detail_mode, mode.title(this@RelayActivity)))
            if (extra.isNotBlank()) append(" / ").append(extra)
            append(" / ").append(getString(R.string.log_detail_type, payload.type ?: "?"))
            append('\n')
            items.forEach { i ->
                append("· ").append(i.name ?: getString(R.string.log_unnamed))
                if (i.size >= 0) append("  ").append(formatSize(i.size))
                append('\n')
            }
        }.trim()
        ShareLog.add(this, mode.title(this), source, title, detail, success)
    }

    private fun fail(source: String?, message: String, payload: SharePayload?, modeLabel: String) {
        val detail = payload?.let { getString(R.string.log_uri_count, it.uris.size) } ?: ""
        ShareLog.add(this, modeLabel, source, getString(R.string.log_failed, message), detail, false)
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SHARE) {
            Log.i(TAG, "chooser 回执 resultCode=$resultCode")
            finish()
        }
    }

    fun formatSize(bytes: Long): String = when {
        bytes < 0 -> ""
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024)
        else -> String.format("%.2f GB", bytes / 1024.0 / 1024 / 1024)
    }
}















