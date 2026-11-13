package ren.imyan.sharerelay

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 只读 Provider：
 *  - /shared/<fileName>  复制进本应用的文件
 *  - /root/<token>       Root 直读的原始文件（读取时用 su cat 供数）
 *  - /fd/<token>         分享时趁授权有效预先打开的真实文件 FD（可 seek、零拷贝）
 */
class RelayProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "ren.imyan.sharerelay.files"
        private const val SHARED = "shared"
        private const val ROOT = "root"
        private const val FD = "fd"

        fun sharedDir(c: Context): File = File(c.filesDir, SHARED)

        fun uriForShared(f: File): Uri = Uri.Builder()
            .scheme("content").authority(AUTHORITY)
            .appendPath(SHARED).appendPath(f.name).build()

        fun uriForRoot(token: String): Uri = Uri.Builder()
            .scheme("content").authority(AUTHORITY)
            .appendPath(ROOT).appendPath(token).build()

        fun uriForFd(token: String): Uri = Uri.Builder()
            .scheme("content").authority(AUTHORITY)
            .appendPath(FD).appendPath(token).build()

        /** 清理超过 maxAgeMs 的副本，避免私有目录无限增长；顺手清掉过期的 FD。 */
        fun purgeOld(c: Context, maxAgeMs: Long = 24L * 60 * 60 * 1000) {
            val cutoff = System.currentTimeMillis() - maxAgeMs
            sharedDir(c).listFiles()?.forEach { f ->
                if (f.isFile && f.lastModified() < cutoff) runCatching { f.delete() }
            }
            FdVfs.purgeOld()
        }
    }

    override fun onCreate(): Boolean {
        sharedDir(requireNotNull(context)).mkdirs()
        FdVfs.purgeOld()
        return true
    }

    private fun sharedFile(name: String): File {
        val base = sharedDir(requireNotNull(context))
        val f = File(base, name)
        val basePath = base.canonicalPath
        if (!f.canonicalPath.startsWith(basePath + File.separator)) throw FileNotFoundException("escape")
        if (!f.isFile) throw FileNotFoundException("missing $name")
        return f
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val seg = uri.pathSegments
        if (seg.size == 2 && seg[0] == FD) {
            return FdVfs.take(seg[1]) ?: throw FileNotFoundException("no fd left")
        }
        if (seg.size == 2 && seg[0] == ROOT) {
            val entry = RootVfs.entry(seg[1]) ?: throw FileNotFoundException("unknown token")
            return openRootPipe(entry.path)
        }
        if (seg.size == 2 && seg[0] == SHARED) {
            return ParcelFileDescriptor.open(sharedFile(seg[1]), ParcelFileDescriptor.MODE_READ_ONLY)
        }
        throw FileNotFoundException("bad uri: $uri")
    }

    /** 用 su cat 把原始文件流式写入管道，交给目标 App 读取。 */
    private fun openRootPipe(path: String): ParcelFileDescriptor {
        val pipe = ParcelFileDescriptor.createPipe()
        val readEnd = pipe[0]
        val writeEnd = pipe[1]
        Thread {
            var ok = false
            try {
                val p = ProcessBuilder("su", "-c", "cat " + RootHelper.quote(path)).start()
                FileOutputStream(writeEnd.fileDescriptor).use { out ->
                    p.inputStream.use { input -> input.copyTo(out, 64 * 1024) }
                }
                ok = p.waitFor() == 0
            } catch (t: Throwable) {
                Log.w("ShareRelay", "root cat failed: $t")
            } finally {
                runCatching { writeEnd.close() }
                Log.i("ShareRelay", "root cat done ok=$ok path=$path")
            }
        }.start()
        return readEnd
    }

    override fun getType(uri: Uri): String? {
        val seg = uri.pathSegments
        if (seg.size == 2 && seg[0] == FD) {
            FdVfs.entry(seg[1])?.let { e ->
                e.mime?.let { return it }
                return mimeFromName(e.name)
            }
            return "application/octet-stream"
        }
        if (seg.size == 2 && seg[0] == ROOT) {
            RootVfs.entry(seg[1])?.let { e ->
                e.mime?.let { return it }
                return mimeFromName(e.name)
            }
            return "application/octet-stream"
        }
        if (seg.size == 2 && seg[0] == SHARED) {
            return try {
                mimeFromName(sharedFile(seg[1]).name)
            } catch (e: Throwable) {
                "application/octet-stream"
            }
        }
        return null
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val cursor = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
        val seg = uri.pathSegments
        if (seg.size == 2 && seg[0] == FD) {
            FdVfs.entry(seg[1])?.let { cursor.addRow(arrayOf<Any>(it.name, it.size)) }
        } else if (seg.size == 2 && seg[0] == ROOT) {
            RootVfs.entry(seg[1])?.let { cursor.addRow(arrayOf<Any>(it.name, it.size)) }
        } else if (seg.size == 2 && seg[0] == SHARED) {
            runCatching {
                val f = sharedFile(seg[1])
                cursor.addRow(arrayOf<Any>(displayName(f), f.length()))
            }
        }
        return cursor
    }

    /** 去掉复制时加在文件名前的「时间戳-序号-」前缀，还原给使用方看到的名字。 */
    private fun displayName(f: File): String =
        f.name.replaceFirst(Regex("^\\d+-\\d+-"), "")

    private fun mimeFromName(name: String): String? {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return null
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("read only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("read only")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = throw UnsupportedOperationException("read only")
}

/**
 * 预打开的来源文件 FD 池。
 *
 * 分享时趁来源授权有效（Activity 还活着），对同一个来源 URI 调多次
 * ContentResolver.openFileDescriptor()，每次都会得到互不影响的独立文件描述符和偏移，
 * 目标 App 打开我们的 /fd/<token> 时取一个交给它：
 *  - 零拷贝：没有把文件复制到磁盘；
 *  - 可 seek：是来源 Provider 的真实文件 fd，缩略图、视频拖动都正常；
 *  - 不依赖 Activity 存活：fd 已经打开，即使我们退出，目标仍能读取。
 *
 * 同一个 URI 预留多个 fd：分享面板预览可能读一次，目标 App 正式读取一次，
 * 有的 App 还会二次打开（例如先取元数据再读内容），一次性发完就没有了。
 */
object FdVfs {
    data class Entry(
        val pfds: ArrayDeque<ParcelFileDescriptor>,
        val name: String,
        val size: Long,
        val mime: String?,
        val createdAt: Long
    )

    /** 目标 App 一般很快读完；30 分钟后仍未取走的 fd 直接关掉，避免泄漏。 */
    private const val MAX_AGE_MS = 30L * 60 * 1000

    private val map = ConcurrentHashMap<String, Entry>()

    fun register(pfds: List<ParcelFileDescriptor>, name: String, size: Long, mime: String?): String {
        purgeOld()
        val token = UUID.randomUUID().toString().replace("-", "")
        map[token] = Entry(ArrayDeque(pfds), name, size, mime, System.currentTimeMillis())
        return token
    }

    /** 每次 openFile 取走一个 fd；剩下的留给后续打开。 */
    fun take(token: String): ParcelFileDescriptor? = map[token]?.pfds?.pollFirst()

    fun entry(token: String): Entry? = map[token]

    fun purgeOld() {
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        map.entries.forEach { e ->
            if (e.value.createdAt < cutoff && map.remove(e.key) != null) {
                e.value.pfds.forEach { runCatching { it.close() } }
                Log.i("ShareRelay", "fd purge token=${e.key} name=${e.value.name}")
            }
        }
    }

    fun clear() {
        map.values.forEach { e -> e.pfds.forEach { runCatching { it.close() } } }
        map.clear()
    }
}
