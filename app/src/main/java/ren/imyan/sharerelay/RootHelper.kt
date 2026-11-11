package ren.imyan.sharerelay

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.util.Log
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Root 能力封装：su 执行、文件探测。 */
object RootHelper {
    private const val TAG = "ShareRelay"

    @Volatile
    private var cached: Boolean? = null

    data class Result(val code: Int, val out: String, val err: String)

    fun exec(cmd: List<String>, timeoutMs: Long = 10000): Result = try {
        val p = ProcessBuilder(cmd).start()
        val out = StringBuilder()
        val err = StringBuilder()
        val t1 = Thread { runCatching { p.inputStream.bufferedReader().forEachLine { out.appendLine(it) } } }
        val t2 = Thread { runCatching { p.errorStream.bufferedReader().forEachLine { err.appendLine(it) } } }
        t1.start()
        t2.start()
        val finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            p.destroyForcibly()
            t1.join(500)
            t2.join(500)
            Result(-1, out.toString(), "timeout")
        } else {
            t1.join(1000)
            t2.join(1000)
            Result(p.exitValue(), out.toString(), err.toString())
        }
    } catch (e: Throwable) {
        Result(-1, "", e.toString())
    }

    fun su(cmd: String, timeoutMs: Long = 10000): Result = exec(listOf("su", "-c", cmd), timeoutMs)

    fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    fun check(context: Context, force: Boolean = false): Boolean {
        cached?.let { if (!force) return it }
        val r = exec(listOf("su", "-c", "id"), 6000)
        val ok = r.code == 0 && r.out.contains("uid=0")
        cached = ok
        Log.i(TAG, "root check = $ok | ${r.out.trim()} | ${r.err.trim()}")
        return ok
    }

    fun reset() {
        cached = null
    }

    fun statSize(path: String): Long = su("stat -c %s ${quote(path)} 2>/dev/null").out.trim().toLongOrNull() ?: -1L

    fun exists(path: String): Boolean = su("test -e ${quote(path)} && echo yes").out.contains("yes")
}

/** 把来源 App 的 content://Uri 解析成 Root 可读的真实路径（支持 AndroidX FileProvider 配置）。 */
object PathResolver {
    private const val TAG = "ShareRelay"

    fun resolve(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") return uri.path
        if (uri.scheme != "content") return null
        val authority = uri.authority ?: return null
        val pm = context.packageManager
        val info = try {
            pm.resolveContentProvider(authority, PackageManager.GET_META_DATA)
        } catch (e: Throwable) {
            null
        } ?: return null
        val pkg = info.packageName
        if (pkg == context.packageName) return null
        val meta = info.metaData ?: return null
        val resId = meta.getInt("android.support.FILE_PROVIDER_PATHS", 0)
        if (resId == 0) return null
        val res = try {
            pm.getResourcesForApplication(pkg)
        } catch (e: Throwable) {
            return null
        }
        val parser = try {
            res.getXml(resId)
        } catch (e: Throwable) {
            return null
        }
        val segments = uri.pathSegments ?: return null
        if (segments.size < 2) return null
        val name = segments[0]
        val sub = segments.drop(1).joinToString("/")
        var found: File? = null
        try {
            var ev = parser.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    if (parser.getAttributeValue(null, "name") == name) {
                        val base = baseDir(parser.name, pkg) ?: return null
                        val declared = (parser.getAttributeValue(null, "path") ?: "").trimStart('/')
                        val dir = if (declared.isEmpty()) base else File(base, declared)
                        found = File(dir, sub)
                        break
                    }
                }
                ev = parser.next()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "parse file_paths failed: $e")
            return null
        } finally {
            runCatching { parser.close() }
        }
        return found?.absolutePath
    }

    private fun baseDir(tag: String, pkg: String): File? = when (tag) {
        "files-path" -> File("/data/user/0/$pkg/files")
        "cache-path" -> File("/data/user/0/$pkg/cache")
        "external-path" -> Environment.getExternalStorageDirectory()
        "external-files-path" -> File("/storage/emulated/0/Android/data/$pkg/files")
        "external-cache-path" -> File("/storage/emulated/0/Android/data/$pkg/cache")
        "external-media-path" -> File("/storage/emulated/0/Android/media/$pkg")
        "root-path" -> File("/")
        else -> null
    }
}

/** Root 模式下的“虚拟文件表”：token -> 原始路径。Provider 按 token 用 su cat 供数。 */
object RootVfs {
    data class Entry(val path: String, val name: String, val size: Long, val mime: String?, val createdAt: Long)

    /** 目标 App 通常很快读完；超过 10 分钟的 token 直接丢弃，避免进程内无限累积。 */
    private const val MAX_AGE_MS = 10L * 60 * 1000

    private val map = ConcurrentHashMap<String, Entry>()

    fun register(path: String, name: String, size: Long, mime: String?): String {
        val now = System.currentTimeMillis()
        map.entries.forEach { e -> if (now - e.value.createdAt > MAX_AGE_MS) map.remove(e.key) }
        val token = UUID.randomUUID().toString().replace("-", "")
        map[token] = Entry(path, name, size, mime, now)
        return token
    }

    fun entry(token: String): Entry? = map[token]

    fun clear() = map.clear()
}


