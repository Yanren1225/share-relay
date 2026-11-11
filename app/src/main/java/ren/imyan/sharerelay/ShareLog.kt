package ren.imyan.sharerelay

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class ShareLogEntry(
    val id: String,
    val time: Long,
    val mode: String,
    val source: String?,
    val title: String,
    val detail: String,
    val success: Boolean
)

object ShareLog {
    private const val FILE = "share-log.json"
    private const val MAX = 200

    private fun file(c: Context) = File(c.filesDir, FILE)

    @Synchronized
    fun read(context: Context): MutableList<ShareLogEntry> {
        val f = file(context)
        if (!f.exists()) return mutableListOf()
        return try {
            val arr = JSONArray(f.readText())
            val out = mutableListOf<ShareLogEntry>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out += ShareLogEntry(
                    id = o.optString("id", UUID.randomUUID().toString()),
                    time = o.optLong("time"),
                    mode = o.optString("mode"),
                    source = o.optString("source").ifEmpty { null },
                    title = o.optString("title"),
                    detail = o.optString("detail"),
                    success = o.optBoolean("success", true)
                )
            }
            out
        } catch (e: Throwable) {
            mutableListOf()
        }
    }

    @Synchronized
    fun add(context: Context, mode: String, source: String?, title: String, detail: String, success: Boolean) {
        if (!Settings.keepLog(context)) return
        val list = read(context)
        list.add(
            0,
            ShareLogEntry(UUID.randomUUID().toString(), System.currentTimeMillis(), mode, source, title, detail, success)
        )
        while (list.size > MAX) list.removeAt(list.size - 1)
        save(context, list)
    }

    @Synchronized
    fun clear(context: Context) {
        file(context).delete()
    }

    private fun save(context: Context, list: List<ShareLogEntry>) {
        try {
            val arr = JSONArray()
            list.forEach { e ->
                arr.put(
                    JSONObject()
                        .put("id", e.id)
                        .put("time", e.time)
                        .put("mode", e.mode)
                        .put("source", e.source ?: "")
                        .put("title", e.title)
                        .put("detail", e.detail)
                        .put("success", e.success)
                )
            }
            file(context).writeText(arr.toString())
        } catch (_: Throwable) {
        }
    }

    fun formatTime(t: Long): String =
        SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(t))
}

