package ren.imyan.sharerelay

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.TextUtils
import android.util.Log

/** 一次分享进来的内容。 */
data class SharePayload(
    val uris: List<Uri>,
    val text: String?,
    val type: String?
)

object IntentParser {
    private const val TAG = "ShareRelay"

    fun parse(context: Context, intent: Intent?): SharePayload {
        if (intent == null) return SharePayload(emptyList(), null, null)
        val uris = LinkedHashSet<Uri>()

        fun add(o: Any?) {
            when (o) {
                is Uri -> if (o.toString().isNotEmpty()) uris += o
                is List<*> -> o.forEach { add(it) }
                is String -> runCatching { uris += Uri.parse(o) }
            }
        }

        val extras = intent.extras
        if (extras != null) {
            add(extras.get(Intent.EXTRA_STREAM))
        }
        intent.clipData?.let { cd ->
            for (i in 0 until cd.itemCount) {
                add(cd.getItemAt(i).uri)
            }
        }
        if (uris.isEmpty()) {
            intent.data?.let { add(it) }
        }

        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        val type = intent.type

        // 过滤掉无法解析的 URI（少数 App 会塞 null/空）
        val list = uris.filter { it != null && !TextUtils.isEmpty(it.toString()) }
        Log.i(TAG, "parse action=${intent.action} type=$type uris=${list.size} text=${text?.length ?: 0}")
        return SharePayload(list, text, type)
    }

}


