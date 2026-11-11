package ren.imyan.sharerelay

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.StringRes

enum class TransferMode(@StringRes val titleRes: Int, @StringRes val descRes: Int) {
    AUTO(R.string.mode_auto, R.string.mode_auto_desc),
    ROOT(R.string.mode_root, R.string.mode_root_desc),
    PASSTHROUGH(R.string.mode_passthrough, R.string.mode_passthrough_desc),
    COPY(R.string.mode_copy, R.string.mode_copy_desc);

    fun title(context: Context): String = context.getString(titleRes)
    fun desc(context: Context): String = context.getString(descRes)

    companion object {
        fun of(name: String?): TransferMode = entries.firstOrNull { it.name == name } ?: AUTO
    }
}

object Settings {
    private const val PREF = "sharerelay"

    private fun p(c: Context): SharedPreferences = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun mode(c: Context): TransferMode = TransferMode.of(p(c).getString("mode", null))
    fun setMode(c: Context, m: TransferMode) = p(c).edit().putString("mode", m.name).apply()

    fun excludeSelf(c: Context): Boolean = p(c).getBoolean("exclude_self", true)
    fun setExcludeSelf(c: Context, v: Boolean) = p(c).edit().putBoolean("exclude_self", v).apply()

    fun excludeQq(c: Context): Boolean = p(c).getBoolean("exclude_qq", false)
    fun setExcludeQq(c: Context, v: Boolean) = p(c).edit().putBoolean("exclude_qq", v).apply()

    fun keepLog(c: Context): Boolean = p(c).getBoolean("keep_log", true)
    fun setKeepLog(c: Context, v: Boolean) = p(c).edit().putBoolean("keep_log", v).apply()
}


