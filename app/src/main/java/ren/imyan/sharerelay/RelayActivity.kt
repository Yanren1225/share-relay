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
import android.os.ParcelFileDescriptor
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

        /** 同一个来源 URI 预先打开的 fd 数量：覆盖分享面板预览 / 目标读取 / 二次打开。 */
        private const val FD_COPIES = 4
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
                    // 优先交给目标 App 一个真实文件 fd（可 seek、缩略图正常），拿不到才用 su 管道
                    items = fdItems(payload) ?: rootItems(payload)
                    if (items == null) {
                        Log.i(TAG, "root 取数失败，回落到复制")
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
                        // 先尝试把来源文件 fd 直接交给目标（零拷贝 + 可 seek + 能补文件名），
                        // 对方 Provider 不支持时才退回原样透传 / 复制。
                        items = fdItems(payload)
                        if (items == null) {
                            val passthrough = passthroughItems(payload)
                            if (passthrough == null) {
                                Log.i(TAG, "来源文件名缺少扩展名，透传改为复制")
                                realMode = TransferMode.COPY
                                items = copyItems(payload)
                            } else {
                                items = passthrough
                                waitResult = true
                            }
                        }
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

    /**
     * 趁来源授权有效，用 openFileDescriptor 拿到真实文件 fd，交给目标 App 的是可 seek 的真实文件：
     *  - 不用复制到磁盘，也不用 su 管道；
     *  - 文件名 / MIME 由我们的 Provider 补齐（QQ 那种无扩展名也能变成 .jpg）；
     *  - fd 已打开，目标晚一点读也不依赖 Activity 是否还活着。
     * 对方 Provider 不给 fd（或给的是不可 seek 的管道）时返回 null，走原有回退逻辑。
     */
    private fun fdItems(payload: SharePayload): List<Forwarder.Item>? {
        val out = ArrayList<Forwarder.Item>(payload.uris.size)
        for (u in payload.uris) {
            if (u.scheme == "file") return null
            val first = try {
                contentResolver.openFileDescriptor(u, "r")
            } catch (t: Throwable) {
                Log.i(TAG, "openFileDescriptor failed for $u: $t")
                null
            } ?: return null
            val seekable = try {
                android.system.Os.lseek(first.fileDescriptor, 0, android.system.OsConstants.SEEK_CUR)
                true
            } catch (t: Throwable) {
                false
            }
            if (!seekable) {
                Log.i(TAG, "来源 fd 不可 seek，放弃 fd 路径")
                runCatching { first.close() }
                return null
            }
            val raw = displayName(u)
            var mime = resolveMime(u, raw, payload.type)
            if (isGenericMime(mime)) mime = sniffMime(u) ?: mime
            val name = nameWithExtension(raw, mime, u.lastPathSegment ?: "share")
            val size = first.statSize.takeIf { it > 0 } ?: displaySize(u)
            val pfds = ArrayList<ParcelFileDescriptor>(FD_COPIES)
            pfds += first
            repeat(FD_COPIES - 1) {
                runCatching { contentResolver.openFileDescriptor(u, "r") }.getOrNull()?.let { pfds += it }
            }
            val token = FdVfs.register(pfds, name, size, mime)
            Log.i(TAG, "fd item name=$name mime=$mime size=$size fds=${pfds.size}")
            out += Forwarder.Item(RelayProvider.uriForFd(token), mime, name, size)
        }
        return out
    }

    private fun rootItems(payload: SharePayload): List<Forwarder.Item>? {
        val out = ArrayList<Forwarder.Item>(payload.uris.size)
        for (u in payload.uris) {
            val path = PathResolver.resolve(this, u) ?: return null
            val size = RootHelper.statSize(path)
            if (size < 0) return null
            val raw = displayName(u) ?: File(path).name
            var mime = resolveMime(u, raw, payload.type)
            // QQ 等 Provider 常报 image/* 或 octet-stream；root 模式也读文件头拿真实类型，
            // 否则目标 App 拿到的还是无后缀文件名 + 问号预览
            if (isGenericMime(mime)) mime = sniffMime(u) ?: mime
            val name = nameWithExtension(raw, mime, "file")
            val token = RootVfs.register(path, name, size, mime)
            out += Forwarder.Item(RelayProvider.uriForRoot(token), mime, name, size)
        }
        return out
    }

    /**
     * 透传：原样转发来源 URI。
     * 但第三方 Provider 的 DISPLAY_NAME 没法改写，如果它只给了一个没有扩展名的名字
     * （QQ 的 FileProvider 就经常这样，例如 "0"），目标 App 会按这个名字保存，
     * 最终得到一个没有后缀的文件。这种情况下宁可变慢：返回 null，让调用方回落到复制。
     */
    private fun passthroughItems(payload: SharePayload): List<Forwarder.Item>? {
        val out = ArrayList<Forwarder.Item>(payload.uris.size)
        for (u in payload.uris) {
            val raw = displayName(u)
            if (!hasUsableExtension(raw)) {
                Log.i(TAG, "来源文件名缺少扩展名（${raw ?: "null"}），透传改为复制")
                return null
            }
            var mime = resolveMime(u, raw ?: u.lastPathSegment, payload.type)
            if (isGenericMime(mime)) mime = sniffMime(u) ?: mime
            out += Forwarder.Item(u, mime, nameWithExtension(raw, mime, "file"), displaySize(u))
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
            var mime = resolveMime(u, raw, payload.type)
            var name = nameWithExtension(raw, mime, "share_$idx")
            var dest = File(dir, "${System.currentTimeMillis()}-$idx-${sanitize(name)}")
            val head = ByteArray(64)
            var headLen = 0
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
                            if (headLen == 0) {
                                headLen = minOf(read, head.size)
                                System.arraycopy(buf, 0, head, 0, headLen)
                            }
                            outs.write(buf, 0, read)
                            doneBytes += read
                            val now = System.currentTimeMillis()
                            if (now - lastTick >= 120) {
                                lastTick = now
                                updateProgress(idx, payload.uris.size, name, doneBytes, total)
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "copy failed $u", t)
                runCatching { dest.delete() }
                return null
            }
            // Provider 的 MIME 经常是 application/octet-stream 或 image/*，用文件头兜底推断真实类型
            if (isGenericMime(mime)) mime = sniffMime(head, headLen) ?: mime
            val finalName = nameWithExtension(raw, mime, "share_$idx")
            if (finalName != name) {
                val renamed = File(dir, "${System.currentTimeMillis()}-$idx-${sanitize(finalName)}")
                if (dest.renameTo(renamed)) { dest = renamed; name = finalName }
            }
            updateProgress(idx, payload.uris.size, name, doneBytes, total)
            out += Forwarder.Item(RelayProvider.uriForShared(dest), mime ?: mimeFromName(dest.name), name, dest.length())
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

    /** 文件名是否已经带了一个可被系统识别的扩展名（1~5 位字母数字）。 */
    private fun hasUsableExtension(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty() || ext.length > 5 || !ext.all { it.isLetterOrDigit() }) return false
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) != null
    }

    /** 目标 App 会把 DISPLAY_NAME 当作保存文件名；缺扩展名时按 MIME 补一个。 */
    private fun nameWithExtension(name: String?, mime: String?, fallback: String): String {
        val base = (name ?: fallback).trim().ifEmpty { fallback }
        if (hasUsableExtension(base)) return base
        val ext = mime?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) } ?: return base
        return "$base.$ext"
    }

    /** Provider 常把通用类型报成 application/octet-stream，这时用文件名/Intent 类型兜底。 */
    private fun resolveMime(u: Uri, raw: String?, fallback: String?): String? {
        val typed = safeMime(u)?.lowercase()
        if (!isGenericMime(typed)) return typed
        return mimeFromName(raw ?: "") ?: fallback ?: typed
    }

    private fun isGenericMime(mime: String?): Boolean =
        mime.isNullOrBlank() || mime == "*/*" || mime == "application/octet-stream" || mime.endsWith("/*")

    /** 读来源文件前 64 字节，用文件头判断真实 MIME（QQ 的 Provider 经常只报 image/ 通配类型）。 */
    private fun sniffMime(u: Uri): String? = try {
        contentResolver.openInputStream(u)?.use { ins ->
            val head = ByteArray(64)
            val n = ins.read(head)
            if (n > 0) sniffMime(head, n) else null
        }
    } catch (t: Throwable) {
        null
    }

    private fun sniffMime(head: ByteArray, len: Int): String? {
        if (len < 4) return null
        fun ascii(off: Int, size: Int) = String(head, off, size, Charsets.US_ASCII)
        if (head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() && head[2] == 0xFF.toByte()) return "image/jpeg"
        if (head[0] == 0x89.toByte() && head[1] == 0x50.toByte() && head[2] == 0x4E.toByte() && head[3] == 0x47.toByte()) return "image/png"
        if (len >= 6 && (ascii(0, 6) == "GIF87a" || ascii(0, 6) == "GIF89a")) return "image/gif"
        if (len >= 12 && ascii(0, 4) == "RIFF" && ascii(8, 4) == "WEBP") return "image/webp"
        if (len >= 12 && ascii(4, 4) == "ftyp") {
            return when (ascii(8, 4).trim()) {
                "heic", "heix", "hevc", "hevx", "mif1", "msf1" -> "image/heic"
                "avif", "avis" -> "image/avif"
                "qt" -> "video/quicktime"
                "mp41", "mp42", "isom", "iso2", "avc1", "M4V", "dash" -> "video/mp4"
                else -> null
            }
        }
        if (len >= 4 && ascii(0, 4) == "%PDF") return "application/pdf"
        if (len >= 4 && head[0] == 0x50.toByte() && head[1] == 0x4B.toByte()) return "application/zip"
        return null
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




















