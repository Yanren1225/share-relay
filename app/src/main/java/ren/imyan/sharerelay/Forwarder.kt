package ren.imyan.sharerelay

import android.content.ComponentName
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri

object Forwarder {

    data class Item(
        val uri: Uri,
        val mime: String?,
        val name: String?,
        val size: Long
    )

    fun buildChooser(
        context: Context,
        items: List<Item>,
        text: String?,
        fallbackType: String?,
        excludeSelf: Boolean,
        excludeQq: Boolean
    ): Intent {
        val multi = items.size > 1
        val send = Intent(if (multi) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND)

        val type = when {
            multi -> {
                val first = items.first().mime
                if (items.all { it.mime == first } && !first.isNullOrBlank()) first else "*/*"
            }
            else -> items.firstOrNull()?.mime ?: fallbackType?.takeIf { it.isNotBlank() } ?: "*/*"
        }
        send.type = type

        val clip: ClipData
        if (multi) {
            val list = ArrayList<Uri>(items.size)
            items.forEach { list += it.uri }
            send.putParcelableArrayListExtra(Intent.EXTRA_STREAM, list)
            clip = ClipData.newRawUri("", list[0])
            for (i in 1 until list.size) clip.addItem(ClipData.Item(list[i]))
        } else {
            val u = items.first().uri
            send.putExtra(Intent.EXTRA_STREAM, u)
            clip = ClipData.newRawUri("", u)
        }
        // createChooser 只搬 ClipData 的授权，EXTRA_STREAM 必须同时配上 clipData
        send.clipData = clip
        if (!text.isNullOrBlank()) send.putExtra(Intent.EXTRA_TEXT, text)
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        val chooser = Intent.createChooser(send, context.getString(R.string.chooser_title))
        chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        val excluded = ArrayList<ComponentName>()
        if (excludeSelf) excluded += ComponentName(context, RelayActivity::class.java)
        if (excludeQq) {
            excluded += ComponentName("com.tencent.mobileqq", "com.tencent.mobileqq.activity.JumpActivity")
        }
        if (excluded.isNotEmpty()) {
            chooser.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, excluded.toTypedArray())
        }
        return chooser
    }
}

