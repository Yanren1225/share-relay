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

/**
 * 只读 Provider：
 *  - /shared/<fileName>  复制进本应用的文件
 *  - /root/<token>       Root 直读的原始文件（读取时用 su cat 供数）
 */
class RelayProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "ren.imyan.sharerelay.files"
        private const val SHARED = "shared"
        private const val ROOT = "root"

        fun sharedDir(c: Context): File = File(c.filesDir, SHARED)

        fun uriForShared(f: File): Uri = Uri.Builder()
            .scheme("content").authority(AUTHORITY)
            .appendPath(SHARED).appendPath(f.name).build()

        fun uriForRoot(token: String): Uri = Uri.Builder()
            .scheme("content").authority(AUTHORITY)
            .appendPath(ROOT).appendPath(token).build()

        /** 清理超过 maxAgeMs 的副本，避免私有目录无限增长。 */
        fun purgeOld(c: Context, maxAgeMs: Long = 24L * 60 * 60 * 1000) {
            val cutoff = System.currentTimeMillis() - maxAgeMs
            sharedDir(c).listFiles()?.forEach { f ->
                if (f.isFile && f.lastModified() < cutoff) runCatching { f.delete() }
            }
        }
    }

    override fun onCreate(): Boolean {
        sharedDir(requireNotNull(context)).mkdirs()
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
        if (seg.size == 2 && seg[0] == ROOT) {
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



