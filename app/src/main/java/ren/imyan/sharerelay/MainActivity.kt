package ren.imyan.sharerelay

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.SpinnerEntry
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.utils.overScrollVertical

class MainActivity : ComponentActivity() {

    private val resumeTick = mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val controller = remember { ThemeController(ColorSchemeMode.System) }
            MiuixTheme(controller = controller) {
                SettingsScreen(resumeTick.value)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick.value += 1
    }
}

@Composable
private fun SettingsScreen(resumeTick: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val versionName = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty() }

    var mode by remember { mutableStateOf(Settings.mode(context)) }
    var excludeSelf by remember { mutableStateOf(Settings.excludeSelf(context)) }
    var excludeQq by remember { mutableStateOf(Settings.excludeQq(context)) }
    var keepLog by remember { mutableStateOf(Settings.keepLog(context)) }
    var rootAvailable by remember { mutableStateOf<Boolean?>(null) }
    var leftoverFiles by remember { mutableStateOf(0) }

    LaunchedEffect(resumeTick) {
        rootAvailable = withContext(Dispatchers.IO) { RootHelper.check(context, true) }
        leftoverFiles = withContext(Dispatchers.IO) {
            RelayProvider.purgeOld(context)
            RelayProvider.sharedDir(context).listFiles()?.size ?: 0
        }
    }

    val banner = when {
        rootAvailable != false -> null
        mode == TransferMode.ROOT -> stringResource(R.string.banner_degraded_root)
        mode == TransferMode.AUTO -> stringResource(R.string.banner_degraded_auto)
        else -> null
    }

    Scaffold(
        topBar = {
            SmallTopAppBar(title = stringResource(R.string.settings_title))
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
            modifier = Modifier.fillMaxSize().overScrollVertical(),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 32.dp
            )
        ) {
            if (banner != null) {
                item {
                    Card(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        colors = CardDefaults.defaultColors(
                            color = MiuixTheme.colorScheme.tertiaryContainer,
                            contentColor = MiuixTheme.colorScheme.onTertiaryContainer
                        )
                    ) {
                        Text(
                            text = banner,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
            }

            item { SmallTitle(stringResource(R.string.section_transfer)) }
            item {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    OverlaySpinnerPreference(
                        items = TransferMode.entries.map { SpinnerEntry(title = it.title(context), summary = it.desc(context)) },
                        selectedIndex = mode.ordinal,
                        title = stringResource(R.string.pref_mode_title),
                        summary = mode.desc(context),
                        onSelectedIndexChange = { index ->
                            val picked = TransferMode.entries[index]
                            mode = picked
                            Settings.setMode(context, picked)
                        }
                    )
                }
            }

            item { SmallTitle(stringResource(R.string.section_panel)) }
            item {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    SwitchPreference(
                        checked = excludeSelf,
                        onCheckedChange = { excludeSelf = it; Settings.setExcludeSelf(context, it) },
                        title = stringResource(R.string.pref_exclude_self_title),
                        summary = stringResource(R.string.pref_exclude_self_summary)
                    )
                    HorizontalDivider()
                    SwitchPreference(
                        checked = excludeQq,
                        onCheckedChange = { excludeQq = it; Settings.setExcludeQq(context, it) },
                        title = stringResource(R.string.pref_exclude_qq_title),
                        summary = stringResource(R.string.pref_exclude_qq_summary)
                    )
                }
            }

            item { SmallTitle(stringResource(R.string.section_other)) }
            item {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    SwitchPreference(
                        checked = keepLog,
                        onCheckedChange = { keepLog = it; Settings.setKeepLog(context, it) },
                        title = stringResource(R.string.pref_keep_log_title),
                        summary = stringResource(R.string.pref_keep_log_summary)
                    )
                    HorizontalDivider()
                    ArrowPreference(
                        title = stringResource(R.string.pref_history_title),
                        summary = stringResource(R.string.pref_history_summary),
                        onClick = { context.startActivity(Intent(context, LogActivity::class.java)) }
                    )
                    if (mode == TransferMode.COPY || leftoverFiles > 0) {
                        HorizontalDivider()
                        ArrowPreference(
                            title = stringResource(R.string.pref_cleanup_title),
                            summary = when {
                                leftoverFiles > 0 && mode == TransferMode.COPY ->
                                    stringResource(R.string.pref_cleanup_summary_count, leftoverFiles)
                                leftoverFiles > 0 ->
                                    stringResource(R.string.pref_cleanup_summary_leftover, leftoverFiles)
                                else -> stringResource(R.string.pref_cleanup_summary)
                            },
                            onClick = {
                                scope.launch {
                                    val n = withContext(Dispatchers.IO) {
                                        var count = 0
                                        RelayProvider.sharedDir(context).listFiles()?.forEach {
                                            if (it.delete()) count++
                                        }
                                        count
                                    }
                                    leftoverFiles = withContext(Dispatchers.IO) {
                                        RelayProvider.purgeOld(context)
            RelayProvider.sharedDir(context).listFiles()?.size ?: 0
                                    }
                                    Toast.makeText(context, context.getString(R.string.toast_cleaned, n), Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    }
                }
            }

            item { SmallTitle(stringResource(R.string.section_about)) }
            item {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    BasicComponent(
                        title = stringResource(R.string.pref_version_title),
                        summary = context.getString(R.string.pref_version_summary, versionName, context.getString(R.string.app_name)),
                        endActions = {
                            Text(
                                text = "ren.imyan.sharerelay",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                        }
                    )
                }
            }
            }
        }
    }
}






