package com.eza.hyperglow.ui

import android.content.Intent
import android.app.LocaleManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.LocaleList
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eza.hyperglow.BuildConfig
import com.eza.hyperglow.R
import com.eza.hyperglow.DiagnosticLoggingPreferences
import com.eza.hyperglow.RuntimeCustomization
import com.eza.hyperglow.setDiagnosticLogging
import com.eza.hyperglow.root.utils.ShellUtils
import kotlinx.coroutines.launch
import com.eza.hyperglow.aod.AodRenderPreferences
import com.eza.hyperglow.aod.AodStateBridge
import com.eza.hyperglow.aod.XiaomiCapabilityStore
import com.eza.hyperglow.aod.XiaomiRuntimeSupportState
import com.eza.hyperglow.customization.CustomizationEditorState
import com.eza.hyperglow.customization.CustomizationRepository
import com.eza.hyperglow.customization.SceneCompiler
import com.eza.hyperglow.customization.SurfaceProfile
import com.eza.hyperglow.root.aod.metadataWidgetHeightDp
import com.eza.hyperglow.root.capability.XiaomiCapability
import com.eza.hyperglow.root.projection.LyricRuby
import com.eza.hyperglow.root.projection.LyricSnapshot
import com.eza.hyperglow.root.projection.currentProcessUserId
import com.eza.hyperglow.root.surface.PlacementEngine
import com.eza.hyperglow.update.UpdateAvailability
import com.eza.hyperglow.update.UpdateChecker
import com.eza.hyperglow.root.surface.PlacementEnvironment
import com.eza.hyperglow.root.surface.PlacementRect
import com.eza.hyperglow.root.surface.ResolvedPlacement
import com.eza.hyperglow.root.surface.WidgetMeasurement
import kotlin.math.roundToInt
import java.util.Locale
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar
import top.yukonga.miuix.kmp.basic.FloatingNavigationBarItem
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.window.WindowDialog

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        enableEdgeToEdge()
        window.isNavigationBarContrastEnforced = false
        setContent {
            val controller = remember { ThemeController(colorSchemeMode = ColorSchemeMode.System) }
            MiuixTheme(controller = controller) {
                var editingSurface by rememberSaveable { mutableStateOf<String?>(null) }
                var selectedTabName by rememberSaveable {
                    mutableStateOf(SettingsTab.OVERVIEW.name)
                }
                AnimatedContent(
                    targetState = editingSurface,
                    modifier = Modifier.fillMaxSize(),
                    transitionSpec = {
                        if (targetState != null) {
                            (slideInHorizontally(
                                animationSpec = tween(320, easing = FastOutSlowInEasing),
                                initialOffsetX = { it }
                            ) + fadeIn(tween(220))) togetherWith
                                (slideOutHorizontally(
                                    animationSpec = tween(320, easing = FastOutSlowInEasing),
                                    targetOffsetX = { -it }
                                ) + fadeOut(tween(180)))
                        } else {
                            (slideInHorizontally(
                                animationSpec = tween(320, easing = FastOutSlowInEasing),
                                initialOffsetX = { -it }
                            ) + fadeIn(tween(220))) togetherWith
                                (slideOutHorizontally(
                                    animationSpec = tween(320, easing = FastOutSlowInEasing),
                                    targetOffsetX = { it }
                                ) + fadeOut(tween(180)))
                        }
                    },
                    label = "settingsDestination"
                ) { surface ->
                    if (surface == DIAGNOSTICS_DESTINATION) {
                        DiagnosticsScreen(onBack = { editingSurface = null })
                    } else if (surface != null) {
                        LyricLayoutScreen(
                            initialSurface = surface,
                            onBack = { editingSurface = null }
                        )
                    } else {
                        HomeScreen(
                            showRestartResult = ::showRestartResult,
                            selectedTabName = selectedTabName,
                            onSelectTab = { selectedTabName = it },
                            onOpenDiagnostics = { editingSurface = DIAGNOSTICS_DESTINATION },
                            onOpenLyricLayout = { target -> editingSurface = target }
                        )
                    }
                }
            }
        }
    }

    private fun showRestartResult(succeeded: Boolean) {
        Toast.makeText(
            this,
            getString(
                if (succeeded) R.string.toast_systemui_restarted
                else R.string.toast_systemui_restart_failed
            ),
            Toast.LENGTH_LONG
        ).show()
    }
}

@Composable
private fun HomeScreen(
    showRestartResult: (Boolean) -> Unit,
    selectedTabName: String,
    onSelectTab: (String) -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenLyricLayout: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showRestartDialog by remember { mutableStateOf(false) }
    var showBurnInPatternDialog by remember { mutableStateOf(false) }
    var showBurnInIntervalDialog by remember { mutableStateOf(false) }
    var showPauseLingerDialog by remember { mutableStateOf(false) }
    var showKeepAwakeDurationDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    val selectedTab = SettingsTab.entries.firstOrNull { it.name == selectedTabName }
        ?: SettingsTab.OVERVIEW
    val selectedTabIndex = SettingsTab.entries.indexOf(selectedTab)
    val pagerState = rememberPagerState(initialPage = selectedTabIndex) {
        SettingsTab.entries.size
    }
    LaunchedEffect(pagerState.currentPage) {
        onSelectTab(SettingsTab.entries[pagerState.currentPage].name)
    }
    LaunchedEffect(selectedTabIndex) {
        if (!pagerState.isScrollInProgress && pagerState.currentPage != selectedTabIndex) {
            pagerState.animateScrollToPage(selectedTabIndex)
        }
    }
    val prefs = remember { context.getSharedPreferences(AodRenderPreferences.PREFS, 0) }
    var capabilityReport by remember { mutableStateOf(XiaomiCapabilityStore.read(context)) }
    DisposableEffect(context) {
        val capabilityPrefs = context.getSharedPreferences(XiaomiCapabilityStore.PREFS, 0)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            capabilityReport = XiaomiCapabilityStore.read(context)
        }
        capabilityPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { capabilityPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    var updateAvailability by remember {
        mutableStateOf<UpdateAvailability>(UpdateAvailability.Unknown)
    }
    LaunchedEffect(Unit) {
        updateAvailability = UpdateChecker().refresh(context)
    }
    val initialConfig = remember { AodRenderPreferences.read(context) }
    val initialDocument = remember { CustomizationRepository.loadDocument(context) }
    val supportState = capabilityReport.supportState()
    val aodSupported = capabilityReport.has(XiaomiCapability.AOD_SURFACE)
    val lockscreenSupported = capabilityReport.has(XiaomiCapability.LOCKSCREEN_HOST) &&
        capabilityReport.has(XiaomiCapability.LOCKSCREEN_GEOMETRY)
    val runtimeProfileAvailable = supportState == XiaomiRuntimeSupportState.AVAILABLE ||
        supportState == XiaomiRuntimeSupportState.VERIFIED_PROFILE ||
        supportState == XiaomiRuntimeSupportState.VERIFIED_PROFILE_MISSING_SYMBOLS ||
        supportState == XiaomiRuntimeSupportState.EXPERIMENTAL_ACTIVE
    val positionFollowingSupported = capabilityReport.has(XiaomiCapability.AOD_POSITION_UPDATES)
    val raiseToAodSupported = capabilityReport.has(XiaomiCapability.RAISE_TO_AOD)
    val lockscreenEditorGestureSupported = capabilityReport.has(
        XiaomiCapability.LOCKSCREEN_EDITOR_GESTURE
    )
    var aodEnabled by remember {
        mutableStateOf(
            initialDocument.profiles[SceneCompiler.SURFACE_AOD]?.enabled
                ?: initialConfig.aodEnabled
        )
    }
    var lockscreenEnabled by remember {
        mutableStateOf(
            initialDocument.profiles[SceneCompiler.SURFACE_LOCKSCREEN]?.enabled
                ?: initialConfig.lockscreenEnabled
        )
    }
    var keepAwake by remember { mutableStateOf(initialConfig.keepAwake) }
    var keepAwakeUnsynced by remember { mutableStateOf(initialConfig.keepAwakeUnsynced) }
    var keepAwakeDurationMs by remember { mutableStateOf(initialConfig.keepAwakeDurationMs) }
    var lockscreenKeepAwake by remember {
        mutableStateOf(initialConfig.lockscreenKeepAwake)
    }
    var raiseToAod by remember { mutableStateOf(initialConfig.raiseToAod) }
    var suppressLockscreenEditorLongPress by remember {
        mutableStateOf(initialConfig.suppressLockscreenEditorLongPress)
    }
    var positionFollowing by remember {
        mutableStateOf(initialConfig.experimentalPositionFollowing)
    }
    var burnInPattern by remember { mutableStateOf(initialConfig.burnInPattern) }
    var burnInIntervalMs by remember { mutableStateOf(initialConfig.burnInIntervalMs) }
    var pauseLingerMs by remember { mutableStateOf(initialConfig.pauseLingerMs) }
    var diagnosticLogging by remember {
        mutableStateOf(DiagnosticLoggingPreferences.read(context))
    }

    Scaffold(
        topBar = { TopAppBar(title = stringResource(R.string.app_name)) },
        bottomBar = {
            FloatingNavigationBar {
                FloatingNavigationBarItem(
                    selected = pagerState.currentPage == SettingsTab.OVERVIEW.ordinal,
                    onClick = {
                        scope.launch { pagerState.animateScrollToPage(SettingsTab.OVERVIEW.ordinal) }
                    },
                    icon = MiuixIcons.Regular.Home,
                    label = stringResource(R.string.nav_overview)
                )
                FloatingNavigationBarItem(
                    selected = pagerState.currentPage == SettingsTab.CONFIG.ordinal,
                    onClick = {
                        scope.launch { pagerState.animateScrollToPage(SettingsTab.CONFIG.ordinal) }
                    },
                    icon = MiuixIcons.Regular.Settings,
                    label = stringResource(R.string.nav_settings)
                )
            }
        }
    ) { innerPadding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 1,
            verticalAlignment = Alignment.Top
        ) { page ->
            LazyColumn(
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding() + 12.dp,
                    bottom = innerPadding.calculateBottomPadding() + 20.dp
                )
            ) {
                when (SettingsTab.entries[page]) {
                SettingsTab.OVERVIEW -> {
                    (updateAvailability as? UpdateAvailability.UpdateAvailable)?.let { available ->
                        item {
                            SettingsCard {
                                ArrowPreference(
                                    title = stringResource(R.string.update_available_title),
                                    summary = stringResource(
                                        R.string.update_available_summary,
                                        available.latest.versionName,
                                        BuildConfig.VERSION_NAME
                                    ),
                                    onClick = { openExternalUrl(context, GITHUB_RELEASES_URL) }
                                )
                            }
                        }
                    }
                    item { SmallTitle(text = stringResource(R.string.section_runtime_status)) }
                    item {
                        SettingsCard {
                            BasicComponent(
                                title = stringResource(R.string.label_compatibility),
                                summary = supportStateLabel(
                                    context,
                                    supportState,
                                    capabilityReport.availableCapabilityCount,
                                    capabilityReport.totalCapabilityCount
                                )
                            )
                            BasicComponent(
                                title = stringResource(R.string.label_systemui_aod),
                                summary = "${capabilityReport.systemUiVersion} / ${capabilityReport.aodVersion}"
                            )
                            BasicComponent(
                                title = stringResource(R.string.label_aod_lyrics),
                                summary = runtimeSurfaceSummary(
                                    context = context,
                                    configured = aodEnabled,
                                    supported = aodSupported,
                                    surfaceName = context.getString(R.string.surface_aod)
                                )
                            )
                            BasicComponent(
                                title = stringResource(R.string.label_lockscreen_lyrics),
                                summary = runtimeSurfaceSummary(
                                    context = context,
                                    configured = lockscreenEnabled,
                                    supported = lockscreenSupported,
                                    surfaceName = context.getString(R.string.surface_lockscreen)
                                )
                            )
                            SwitchPreference(
                                diagnosticLogging,
                                { enabled ->
                                    if (updateDiagnosticLogging(context, enabled)) {
                                        diagnosticLogging = enabled
                                    }
                                },
                                stringResource(R.string.label_diagnostic_logging),
                                summary = if (BuildConfig.TRACE_LOGGING_AVAILABLE) {
                                    stringResource(R.string.summary_diagnostic_logging_available)
                                } else {
                                    stringResource(R.string.summary_diagnostic_logging_unavailable)
                                },
                                enabled = BuildConfig.TRACE_LOGGING_AVAILABLE
                            )
                            ArrowPreference(
                                title = if (supportState == XiaomiRuntimeSupportState.NO_SYSTEM_UI_REPORT ||
                                    supportState == XiaomiRuntimeSupportState.UNSUPPORTED_PROFILE ||
                                    supportState == XiaomiRuntimeSupportState.EXPERIMENTAL_ACTIVE ||
                                    capabilityReport.availableCapabilityCount <
                                    capabilityReport.totalCapabilityCount
                                ) {
                                    stringResource(R.string.action_send_compatibility_report)
                                } else {
                                    stringResource(R.string.action_report_problem)
                                },
                                onClick = onOpenDiagnostics
                            )
                            ArrowPreference(
                                title = stringResource(R.string.action_restart_systemui),
                                onClick = { showRestartDialog = true }
                            )
                        }
                    }
                    item { SmallTitle(text = stringResource(R.string.section_spotify_integration)) }
                    item {
                        SettingsCard {
                            ArrowPreference(
                                title = stringResource(R.string.action_download_spicy_ex),
                                onClick = {
                                    openExternalUrl(context, SPICY_EX_GITHUB_URL)
                                }
                            )
                            ArrowPreference(
                                title = stringResource(R.string.action_open_spotify),
                                onClick = {
                                    val launchIntent = context.packageManager
                                        .getLaunchIntentForPackage("com.spotify.music")
                                    if (launchIntent != null) {
                                        context.startActivity(launchIntent)
                                    } else {
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.toast_spotify_not_installed),
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            )
                        }
                    }
                    item { SmallTitle(text = stringResource(R.string.section_project)) }
                    item {
                        SettingsCard {
                            ArrowPreference(
                                title = stringResource(R.string.action_hyperglow_github),
                                onClick = {
                                    openExternalUrl(context, GITHUB_URL)
                                }
                            )
                        }
                    }
                }

                SettingsTab.CONFIG -> {
                    item { SmallTitle(text = stringResource(R.string.section_language)) }
                    item {
                        SettingsCard {
                            ArrowPreference(
                                title = englishInterfaceLanguageLabel(context),
                                summary = uiLanguageLabel(
                                    context,
                                    currentUiLanguage(context)
                                ),
                                onClick = { showLanguageDialog = true }
                            )
                        }
                    }
                    item { SmallTitle(text = stringResource(R.string.section_surfaces)) }
                    item {
                        SettingsCard {
                            SwitchPreference(
                                aodEnabled,
                                { enabled ->
                                    if (!aodSupported) return@SwitchPreference
                                    if (updateCustomizationSurfaceEnabled(
                                            context,
                                            SceneCompiler.SURFACE_AOD,
                                            enabled
                                        )
                                    ) {
                                        aodEnabled = enabled
                                    }
                                },
                                stringResource(R.string.setting_show_aod),
                                summary = if (aodSupported) {
                                    null
                                } else {
                                    stringResource(R.string.summary_show_aod_unsupported)
                                },
                                enabled = aodSupported
                            )
                            SwitchPreference(
                                lockscreenEnabled,
                                { enabled ->
                                    if (!lockscreenSupported) {
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.toast_lockscreen_unsupported),
                                            Toast.LENGTH_LONG
                                        ).show()
                                        return@SwitchPreference
                                    }
                                    if (updateCustomizationSurfaceEnabled(
                                            context,
                                            SceneCompiler.SURFACE_LOCKSCREEN,
                                            enabled
                                        )
                                    ) {
                                        lockscreenEnabled = enabled
                                    }
                                },
                                stringResource(R.string.setting_show_lockscreen),
                                summary = if (lockscreenSupported) {
                                    null
                                } else {
                                    stringResource(R.string.summary_unavailable_systemui_version)
                                },
                                enabled = lockscreenSupported
                            )
                        }
                    }
                    item { SmallTitle(text = stringResource(R.string.section_appearance)) }
                    item {
                        SettingsCard {
                            ArrowPreference(
                                title = stringResource(R.string.title_aod_appearance),
                                onClick = { onOpenLyricLayout(SceneCompiler.SURFACE_AOD) }
                            )
                            ArrowPreference(
                                title = stringResource(R.string.title_lockscreen_appearance),
                                onClick = { onOpenLyricLayout(SceneCompiler.SURFACE_LOCKSCREEN) }
                            )
                        }
                    }
                    item { SmallTitle(text = stringResource(R.string.section_playback_behavior)) }
                    item {
                        SettingsCard {
                            ArrowPreference(
                                title = stringResource(R.string.setting_after_spotify_pauses),
                                summary = pauseLingerLabel(context, pauseLingerMs),
                                onClick = { showPauseLingerDialog = true },
                                enabled = runtimeProfileAvailable && (aodSupported || lockscreenSupported)
                            )
                        }
                    }
                    item { SmallTitle(text = stringResource(R.string.section_aod_behavior)) }
                    item {
                        SettingsCard {
                            SwitchPreference(
                                keepAwake,
                                { enabled ->
                                    prefs.edit().putBoolean(AodRenderPreferences.KEEP_AWAKE, enabled).apply()
                                    keepAwake = enabled
                                },
                                stringResource(R.string.setting_keep_aod_active),
                                summary =
                                    if (aodSupported) {
                                        stringResource(R.string.summary_keep_aod_active)
                                    } else {
                                        stringResource(R.string.summary_unavailable_systemui_profile)
                                    },
                                enabled = aodSupported
                            )
                            ArrowPreference(
                                title = stringResource(R.string.setting_keep_aod_active_for),
                                summary = keepAwakeDurationLabel(context, keepAwakeDurationMs),
                                onClick = { showKeepAwakeDurationDialog = true },
                                enabled = aodSupported && keepAwake
                            )
                            SwitchPreference(
                                keepAwakeUnsynced,
                                { enabled ->
                                    prefs.edit().putBoolean(
                                        AodRenderPreferences.KEEP_AWAKE_UNSYNCED,
                                        enabled
                                    ).apply()
                                    keepAwakeUnsynced = enabled
                                },
                                stringResource(R.string.setting_keep_aod_unsynced),
                                enabled = aodSupported && keepAwake
                            )
                            ArrowPreference(
                                title = stringResource(R.string.setting_aod_clock_image),
                                summary = if (positionFollowingSupported) {
                                    aodMovementLabel(context, positionFollowing, burnInPattern)
                                } else {
                                    stringResource(R.string.summary_aod_placement_unsupported)
                                },
                                onClick = {
                                    if (positionFollowingSupported) {
                                        showBurnInPatternDialog = true
                                    } else {
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.summary_aod_placement_unsupported),
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                },
                                enabled = aodSupported && positionFollowingSupported
                            )
                            if (aodSupported && positionFollowingSupported && positionFollowing &&
                                !burnInPattern.isStaticClockPlacement()
                            ) {
                                ArrowPreference(
                                    title = stringResource(R.string.setting_movement_interval),
                                    summary = burnInIntervalLabel(context, burnInIntervalMs),
                                    onClick = { showBurnInIntervalDialog = true }
                                )
                            }
                        }
                    }
                    item { SmallTitle(text = stringResource(R.string.section_lockscreen_behavior)) }
                    item {
                        SettingsCard {
                            SwitchPreference(
                                lockscreenKeepAwake,
                                { enabled ->
                                    if (updateLockscreenKeepAwake(context, enabled)) {
                                        lockscreenKeepAwake = enabled
                                    }
                                },
                                stringResource(R.string.setting_keep_lockscreen_awake),
                                summary =
                                    stringResource(R.string.summary_keep_lockscreen_awake),
                                enabled = lockscreenSupported && lockscreenEnabled
                            )
                            SwitchPreference(
                                suppressLockscreenEditorLongPress,
                                { enabled ->
                                    if (updateLockscreenEditorLongPress(context, enabled)) {
                                        suppressLockscreenEditorLongPress = enabled
                                    }
                                },
                                stringResource(R.string.setting_block_lockscreen_customization),
                                summary = if (lockscreenEditorGestureSupported) {
                                    stringResource(R.string.summary_block_lockscreen_customization)
                                } else {
                                    stringResource(R.string.summary_unavailable_systemui_version)
                                },
                                enabled = lockscreenEditorGestureSupported
                            )
                        }
                    }
                    item { SmallTitle(text = stringResource(R.string.section_wake_gestures)) }
                    item {
                        SettingsCard {
                            SwitchPreference(
                                raiseToAod,
                                { enabled ->
                                    if (updateRaiseToAod(context, enabled)) {
                                        raiseToAod = enabled
                                    }
                                },
                                stringResource(R.string.setting_raise_to_aod),
                                summary = if (raiseToAodSupported) {
                                    stringResource(R.string.summary_raise_to_aod)
                                } else {
                                    stringResource(R.string.summary_unavailable_systemui_version)
                                },
                                enabled = raiseToAodSupported
                            )
                        }
                    }
                }

                }
            }
        }
    }

    if (showLanguageDialog) {
        val currentLanguage = currentUiLanguage(context)
        WindowDialog(
            title = englishInterfaceLanguageLabel(context),
            summary = stringResource(R.string.dialog_language_summary),
            show = true,
            onDismissRequest = { showLanguageDialog = false }
        ) {
            Column {
                UiLanguage.entries.forEach { language ->
                    RadioButtonPreference(
                        uiLanguageLabel(context, language),
                        currentLanguage == language,
                        {
                            showLanguageDialog = false
                            setUiLanguage(context, language)
                        }
                    )
                }
            }
        }
    }

    if (showRestartDialog) {
        WindowDialog(
            title = stringResource(R.string.dialog_restart_systemui_title),
            summary =
                stringResource(R.string.dialog_restart_systemui_summary),
            show = true,
            onDismissRequest = { showRestartDialog = false }
        ) {
            androidx.compose.foundation.layout.Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    text = stringResource(R.string.action_cancel),
                    modifier = Modifier.weight(1f),
                    onClick = { showRestartDialog = false }
                )
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = stringResource(R.string.action_restart),
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = {
                        showRestartDialog = false
                        scope.launch { showRestartResult(ShellUtils.restartSystemUI()) }
                    }
                )
            }
        }
    }

    if (showBurnInPatternDialog) {
        WindowDialog(
            title = stringResource(R.string.setting_aod_clock_image),
            show = true,
            onDismissRequest = { showBurnInPatternDialog = false }
        ) {
            Column {
                RadioButtonPreference(
                    stringResource(R.string.option_follow_xiaomi),
                    !positionFollowing,
                    {
                        prefs.edit().putBoolean(
                            AodRenderPreferences.EXPERIMENTAL_POSITION_FOLLOWING,
                            false
                        ).apply()
                        positionFollowing = false
                        showBurnInPatternDialog = false
                    }
                )
                BURN_IN_PATTERNS.forEach { value ->
                    RadioButtonPreference(
                        burnInPatternLabel(context, value),
                        positionFollowing && burnInPattern == value,
                        {
                            prefs.edit()
                                .putBoolean(
                                    AodRenderPreferences.EXPERIMENTAL_POSITION_FOLLOWING,
                                    true
                                )
                                .putString(AodRenderPreferences.BURN_IN_PATTERN, value)
                                .apply()
                            positionFollowing = true
                            burnInPattern = value
                            showBurnInPatternDialog = false
                        }
                    )
                }
            }
        }
    }

    if (showPauseLingerDialog) {
        WindowDialog(
            title = stringResource(R.string.setting_after_spotify_pauses),
            summary = stringResource(R.string.dialog_pause_summary),
            show = true,
            onDismissRequest = { showPauseLingerDialog = false }
        ) {
            Column {
                PAUSE_LINGER_OPTIONS.forEach { value ->
                    RadioButtonPreference(
                        pauseLingerLabel(context, value),
                        pauseLingerMs == value,
                        {
                            if (updatePauseLinger(context, value)) pauseLingerMs = value
                            showPauseLingerDialog = false
                        }
                    )
                }
            }
        }
    }

    if (showKeepAwakeDurationDialog) {
        WindowDialog(
            title = stringResource(R.string.setting_keep_aod_active_for),
            summary = stringResource(R.string.dialog_keep_aod_duration_summary),
            show = true,
            onDismissRequest = { showKeepAwakeDurationDialog = false }
        ) {
            Column {
                KEEP_AWAKE_DURATIONS.forEach { value ->
                    RadioButtonPreference(
                        keepAwakeDurationLabel(context, value),
                        keepAwakeDurationMs == value,
                        {
                            if (updateKeepAwakeDuration(context, value)) keepAwakeDurationMs = value
                            showKeepAwakeDurationDialog = false
                        }
                    )
                }
            }
        }
    }

    if (showBurnInIntervalDialog) {
        WindowDialog(
            title = stringResource(R.string.setting_movement_interval),
            show = true,
            onDismissRequest = { showBurnInIntervalDialog = false }
        ) {
            Column {
                BURN_IN_INTERVALS.forEach { value ->
                    RadioButtonPreference(
                        burnInIntervalLabel(context, value),
                        burnInIntervalMs == value,
                        {
                            prefs.edit().putLong(AodRenderPreferences.BURN_IN_INTERVAL_MS, value).apply()
                            burnInIntervalMs = value
                            showBurnInIntervalDialog = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
internal fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth()
    ) {
        Column { content() }
    }
}

private fun String.isStaticClockPlacement(): Boolean =
    this == "static_top" || this == "static_bottom"

private enum class SettingsTab {
    OVERVIEW,
    CONFIG
}

private const val DIAGNOSTICS_DESTINATION = "__diagnostics__"
private const val GITHUB_URL = "https://github.com/amarinne/hyperglow"
private const val GITHUB_RELEASES_URL = "https://github.com/amarinne/hyperglow/releases/latest"
private const val SPICY_EX_GITHUB_URL = "https://github.com/amarinne/spicy-ex/releases"

private fun currentUiLanguage(context: android.content.Context): UiLanguage {
    val tags = context.getSystemService(LocaleManager::class.java)
        ?.applicationLocales
        ?.toLanguageTags()
        .orEmpty()
    return resolveUiLanguage(tags)
}

private fun setUiLanguage(context: android.content.Context, language: UiLanguage) {
    context.getSystemService(LocaleManager::class.java)?.applicationLocales = when (language) {
        UiLanguage.SYSTEM -> LocaleList.getEmptyLocaleList()
        UiLanguage.ENGLISH -> LocaleList.forLanguageTags("en")
        UiLanguage.SIMPLIFIED_CHINESE -> LocaleList.forLanguageTags("zh-CN")
    }
}

private fun uiLanguageLabel(context: android.content.Context, language: UiLanguage): String =
    context.getString(
        when (language) {
            UiLanguage.SYSTEM -> R.string.language_system_default
            UiLanguage.ENGLISH -> R.string.language_english
            UiLanguage.SIMPLIFIED_CHINESE -> R.string.language_simplified_chinese
        }
    )

private fun englishInterfaceLanguageLabel(context: android.content.Context): String = runCatching {
    val configuration = Configuration(context.resources.configuration)
    configuration.setLocales(LocaleList(Locale.ENGLISH))
    context.createConfigurationContext(configuration)
        .getString(R.string.setting_interface_language)
}.getOrElse {
    context.getString(R.string.setting_interface_language)
}

private fun openExternalUrl(context: android.content.Context, url: String) {
    val opened = runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }.isSuccess
    if (!opened) {
        Toast.makeText(context, context.getString(R.string.toast_no_link_handler), Toast.LENGTH_LONG).show()
    }
}

private fun runtimeSurfaceSummary(
    context: android.content.Context,
    configured: Boolean,
    supported: Boolean,
    surfaceName: String
): String = when (resolveRuntimeSurfaceState(configured, supported)) {
    RuntimeSurfaceState.ENABLED -> context.getString(R.string.runtime_enabled)
    RuntimeSurfaceState.DISABLED -> context.getString(R.string.runtime_disabled)
    RuntimeSurfaceState.CONFIGURED_UNAVAILABLE ->
        context.getString(R.string.runtime_configured_unavailable, surfaceName)
    RuntimeSurfaceState.UNAVAILABLE -> context.getString(R.string.runtime_unavailable)
}

private fun supportStateLabel(
    context: android.content.Context,
    state: XiaomiRuntimeSupportState,
    availableCapabilities: Int,
    totalCapabilities: Int
): String = when (state) {
    // A build that runs is described by how much of it resolved, not by a confidence word.
    XiaomiRuntimeSupportState.AVAILABLE ->
        context.getString(R.string.status_available, availableCapabilities, totalCapabilities)
    else -> context.getString(
        when (state) {
            XiaomiRuntimeSupportState.NO_SYSTEM_UI_REPORT -> R.string.status_no_systemui_report
            XiaomiRuntimeSupportState.VERIFIED_PROFILE -> R.string.status_verified_profile
            XiaomiRuntimeSupportState.VERIFIED_PROFILE_MISSING_SYMBOLS ->
                R.string.status_verified_profile_missing_symbols
            XiaomiRuntimeSupportState.UNSUPPORTED_PROFILE -> R.string.status_unsupported_profile
            XiaomiRuntimeSupportState.EXPERIMENTAL_ELIGIBLE -> R.string.status_experimental_eligible
            else -> R.string.status_experimental_active
        }
    )
}

private fun burnInPatternLabel(context: android.content.Context, value: String): String =
    context.getString(
        when (value) {
            "static_top" -> R.string.pattern_keep_top
            "six_zone" -> R.string.pattern_six_positions
            "four_corner" -> R.string.pattern_four_corners
            "vertical_swap" -> R.string.pattern_top_bottom
            else -> R.string.pattern_keep_bottom
        }
    )

private fun aodMovementLabel(
    context: android.content.Context,
    positionFollowing: Boolean,
    pattern: String
): String = if (positionFollowing) {
    burnInPatternLabel(context, pattern)
} else {
    context.getString(R.string.option_follow_xiaomi)
}

private fun burnInIntervalLabel(context: android.content.Context, value: Long): String =
    context.getString(
        when (value) {
            30_000L -> R.string.duration_30_seconds
            120_000L -> R.string.duration_2_minutes
            300_000L -> R.string.duration_5_minutes
            else -> R.string.duration_1_minute
        }
    )

private fun pauseLingerLabel(context: android.content.Context, value: Long): String =
    context.getString(
        when (value) {
            0L -> R.string.duration_clear_immediately
            10_000L -> R.string.duration_10_seconds
            30_000L -> R.string.duration_30_seconds
            -1L -> R.string.duration_keep_indefinitely
            else -> R.string.duration_5_seconds
        }
    )

private val BURN_IN_PATTERNS = listOf(
    "static_top",
    "static_bottom",
    "six_zone",
    "four_corner",
    "vertical_swap"
)

private fun keepAwakeDurationLabel(context: android.content.Context, value: Long): String =
    context.getString(
        when (value) {
            300_000L -> R.string.duration_5_minutes
            600_000L -> R.string.duration_10_minutes
            1_800_000L -> R.string.duration_30_minutes
            3_600_000L -> R.string.duration_1_hour
            7_200_000L -> R.string.duration_2_hours
            else -> R.string.duration_indefinitely
        }
    )

private val KEEP_AWAKE_DURATIONS = listOf(
    300_000L,
    600_000L,
    1_800_000L,
    3_600_000L,
    7_200_000L,
    -1L
)

private val PAUSE_LINGER_OPTIONS = listOf(0L, 5_000L, 10_000L, 30_000L, -1L)

private val BURN_IN_INTERVALS = listOf(30_000L, 60_000L, 120_000L, 300_000L)

@Composable
private fun LyricLayoutScreen(
    initialSurface: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var editorState by remember {
        mutableStateOf(
            CustomizationEditorState(
                CustomizationRepository.loadDocument(context),
                initialSurface
            )
        )
    }
    var activeChoice by remember { mutableStateOf<AodChoice?>(null) }
    var showResetDialog by remember { mutableStateOf(false) }
    val renderPrefs = remember { context.getSharedPreferences(AodRenderPreferences.PREFS, 0) }
    var songChangeInfo by remember {
        mutableStateOf(AodRenderPreferences.read(context).songChangeInfoEnabled)
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val raw = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val bytes = input.readNBytes(SceneCompiler.MAX_CONFIG_BYTES + 1)
                if (bytes.size > SceneCompiler.MAX_CONFIG_BYTES) {
                    error("Appearance file too large")
                }
                bytes.toString(Charsets.UTF_8)
            } ?: error("Appearance file unavailable")
        }.getOrNull()
        val imported = raw != null && CustomizationRepository.importDocument(context, raw)
        if (imported) {
            val document = CustomizationRepository.loadDocument(context)
            syncCustomizationRuntime(context, document)
            editorState = CustomizationEditorState(document, editorState.selectedSurface)
        }
        Toast.makeText(
            context,
            if (imported) {
                context.getString(R.string.toast_appearance_imported)
            } else {
                context.getString(R.string.toast_appearance_invalid)
            },
            Toast.LENGTH_LONG
        ).show()
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val written = runCatching {
            context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                it.write(CustomizationRepository.exportDocument(context))
            } ?: error("Appearance file unavailable")
        }.isSuccess
        Toast.makeText(
            context,
            context.getString(
                if (written) R.string.toast_appearance_exported
                else R.string.toast_appearance_export_failed
            ),
            Toast.LENGTH_LONG
        ).show()
    }

    BackHandler(enabled = activeChoice == null && !showResetDialog, onBack = onBack)

    fun saveEditor(next: CustomizationEditorState): Boolean {
        if (!CustomizationRepository.saveDocument(context, next.document)) {
            Toast.makeText(
                context,
                context.getString(R.string.toast_appearance_save_failed),
                Toast.LENGTH_LONG
            ).show()
            return false
        }
        val document = CustomizationRepository.loadDocument(context)
        syncCustomizationRuntime(context, document)
        editorState = CustomizationEditorState(
            document,
            next.selectedSurface
        )
        return true
    }

    fun updateSelected(updateProfile: (SurfaceProfile) -> SurfaceProfile) {
        saveEditor(editorState.updateSelected(updateProfile))
    }

    LaunchedEffect(Unit) {
        if (editorState.document.linkSurfaces) {
            saveEditor(editorState.setLinkSurfaces(false))
        }
    }

    fun openChoice(
        kind: AodChoiceKind,
        values: List<String>,
        current: String,
        onSelect: (String) -> Unit
    ) {
        activeChoice = AodChoice(kind, values, current, onSelect)
    }

    val selectedProfile = editorState.document.profiles[editorState.selectedSurface] ?: SurfaceProfile()

    Scaffold(
        topBar = {
            TopAppBar(
                title = if (editorState.selectedSurface == SceneCompiler.SURFACE_AOD) {
                    stringResource(R.string.title_aod_appearance)
                } else {
                    stringResource(R.string.title_lockscreen_appearance)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text("←")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding() + 12.dp,
                bottom = innerPadding.calculateBottomPadding() + 20.dp
            )
        ) {
            item { SmallTitle(text = stringResource(R.string.section_placement)) }
            item {
                SettingsCard {
                    AodChoiceRow(AodChoiceKind.POSITION, selectedProfile.anchor) {
                        openChoice(
                            AodChoiceKind.POSITION,
                            listOf(
                                "below_stock_clock",
                                "screen_center",
                                "screen_top_safe",
                                "screen_bottom_safe",
                                "custom_vertical_bias"
                            ),
                            selectedProfile.anchor
                        ) { value -> updateSelected { it.copy(anchor = value) } }
                    }
                    AodChoiceRow(AodChoiceKind.WIDTH, selectedProfile.widthFraction.toString()) {
                        openChoice(
                            AodChoiceKind.WIDTH,
                            listOf("0.7", "0.88", "1.0"),
                            selectedProfile.widthFraction.toString()
                        ) { value -> updateSelected { it.copy(widthFraction = value.toFloat()) } }
                    }
                    if (selectedProfile.anchor == "custom_vertical_bias") {
                        AodChoiceRow(AodChoiceKind.VERTICAL_POSITION, selectedProfile.verticalBias.toString()) {
                            openChoice(
                                AodChoiceKind.VERTICAL_POSITION,
                                listOf("0.25", "0.5", "0.75"),
                                selectedProfile.verticalBias.toString()
                            ) { value -> updateSelected { it.copy(verticalBias = value.toFloat()) } }
                        }
                    }
                    AodChoiceRow(AodChoiceKind.OVERLAP, selectedProfile.collisionPolicy) {
                        openChoice(
                            AodChoiceKind.OVERLAP,
                            listOf("avoid", "behind_system", "hide_optional", "hide_scene"),
                            selectedProfile.collisionPolicy
                        ) { value -> updateSelected { it.copy(collisionPolicy = value) } }
                    }
                }
            }
            item { SmallTitle(text = stringResource(R.string.section_text_language)) }
            item {
                SettingsCard {
                    AodChoiceRow(AodChoiceKind.ALIGNMENT, selectedProfile.alignment) {
                        openChoice(
                            AodChoiceKind.ALIGNMENT,
                            listOf("auto", "start", "center", "end"),
                            selectedProfile.alignment
                        ) { value -> updateSelected { it.copy(alignment = value) } }
                    }
                    AodChoiceRow(AodChoiceKind.SECONDARY_TEXT, selectedProfile.secondaryMode) {
                        openChoice(
                            AodChoiceKind.SECONDARY_TEXT,
                            listOf("Main only", "Transliteration", "Translation", "Both"),
                            selectedProfile.secondaryMode
                        ) { value -> updateSelected { it.copy(secondaryMode = value) } }
                    }
                    if (selectedProfile.secondaryMode != "Main only") {
                        SwitchPreference(
                            selectedProfile.secondaryTextBright,
                            { bright -> updateSelected { it.copy(secondaryTextBright = bright) } },
                            stringResource(R.string.setting_bright_secondary_text)
                        )
                    }
                    SwitchPreference(
                        selectedProfile.rubyVisible,
                        { visible -> updateSelected { it.copy(rubyVisible = visible) } },
                        stringResource(R.string.setting_show_furigana)
                    )
                    AodChoiceRow(AodChoiceKind.LONG_LINES, selectedProfile.overflow) {
                        openChoice(
                            AodChoiceKind.LONG_LINES,
                            listOf("Wrap", "Clip"),
                            selectedProfile.overflow
                        ) { value -> updateSelected { it.copy(overflow = value) } }
                    }
                    if (selectedProfile.overflow == "Wrap") {
                        AodChoiceRow(AodChoiceKind.LYRIC_LINES, selectedProfile.lyricLineLimit.toString()) {
                            openChoice(
                                AodChoiceKind.LYRIC_LINES,
                                listOf("1", "2", "3", "4", "5", "0"),
                                selectedProfile.lyricLineLimit.toString()
                            ) { value ->
                                updateSelected { it.copy(lyricLineLimit = value.toInt()) }
                            }
                        }
                    }
                    SwitchPreference(
                        selectedProfile.adaptiveSectioning,
                        { enabled -> updateSelected { it.copy(adaptiveSectioning = enabled) } },
                        stringResource(R.string.setting_keep_phrases_together)
                    )
                    SwitchPreference(
                        selectedProfile.metadataVisible,
                        { visible -> updateSelected { withMetadataVisible(it, visible) } },
                        stringResource(R.string.setting_show_song_info)
                    )
                    SwitchPreference(
                        songChangeInfo,
                        { enabled ->
                            renderPrefs.edit().putBoolean(
                                AodRenderPreferences.SONG_CHANGE_INFO_ENABLED,
                                enabled
                            ).apply()
                            songChangeInfo = enabled
                        },
                        stringResource(R.string.setting_song_change_info)
                    )
                    if (selectedProfile.metadataVisible) {
                        AodChoiceRow(AodChoiceKind.SONG_INFO_POSITION, selectedProfile.metadataAnchor) {
                            openChoice(
                                AodChoiceKind.SONG_INFO_POSITION,
                                listOf("top", "bottom"),
                                selectedProfile.metadataAnchor
                            ) { value -> updateSelected { it.copy(metadataAnchor = value) } }
                        }
                        TextSizePreference(
                            title = stringResource(R.string.setting_song_info_size),
                            percent = selectedProfile.metadataSizePercent.coerceIn(50, 200),
                            onDecrease = {
                                updateSelected {
                                    it.copy(
                                        metadataSizePercent =
                                            (it.metadataSizePercent - 5).coerceIn(50, 200)
                                    )
                                }
                            },
                            onIncrease = {
                                updateSelected {
                                    it.copy(
                                        metadataSizePercent =
                                            (it.metadataSizePercent + 5).coerceIn(50, 200)
                                    )
                                }
                            }
                        )
                    }
                    AodChoiceRow(AodChoiceKind.TEXT_WEIGHT, selectedProfile.weight) {
                        openChoice(
                            AodChoiceKind.TEXT_WEIGHT,
                            listOf("Regular", "Medium", "Bold"),
                            selectedProfile.weight
                        ) { value -> updateSelected { it.copy(weight = value) } }
                    }
                    TextSizePreference(
                        title = stringResource(R.string.setting_lyric_size),
                        percent = effectiveTextSizePercent(selectedProfile),
                        onDecrease = {
                            updateSelected {
                                it.copy(
                                    textSize = "custom",
                                    textSizeCustom = (effectiveTextSizePercent(it) - 5).coerceIn(50, 200)
                                )
                            }
                        },
                        onIncrease = {
                            updateSelected {
                                it.copy(
                                    textSize = "custom",
                                    textSizeCustom = (effectiveTextSizePercent(it) + 5).coerceIn(50, 200)
                                )
                            }
                        }
                    )
                    AodChoiceRow(AodChoiceKind.FONT, selectedProfile.fontFamily) {
                        openChoice(
                            AodChoiceKind.FONT,
                            listOf("noto", "spotify", "apple"),
                            selectedProfile.fontFamily
                        ) { value -> updateSelected { it.copy(fontFamily = value) } }
                    }
                }
            }
            item { SmallTitle(text = stringResource(R.string.section_effects)) }
            item {
                SettingsCard {
                    AodChoiceRow(AodChoiceKind.WORD_ANIMATION, selectedProfile.animation) {
                        openChoice(
                            AodChoiceKind.WORD_ANIMATION,
                            listOf("Minimal", "Gradient"),
                            selectedProfile.animation
                        ) { value -> updateSelected { it.copy(animation = value) } }
                    }
                    AodChoiceRow(AodChoiceKind.GLOW, selectedProfile.glow) {
                        openChoice(AodChoiceKind.GLOW, listOf("Off", "On"), selectedProfile.glow) { value ->
                            updateSelected { it.copy(glow = value) }
                        }
                    }
                    AodChoiceRow(AodChoiceKind.LINE_PROGRESS, selectedProfile.lineSyncFillMode) {
                        openChoice(
                            AodChoiceKind.LINE_PROGRESS,
                            listOf(
                                "None",
                                "Top to bottom",
                                "Left to right (main only)",
                                "Left to right (whole block)"
                            ),
                            selectedProfile.lineSyncFillMode
                        ) { value -> updateSelected { it.copy(lineSyncFillMode = value) } }
                    }
                    AodChoiceRow(AodChoiceKind.TEXT_BRIGHTNESS, palettePresetName(selectedProfile.palette)) {
                        openChoice(
                            AodChoiceKind.TEXT_BRIGHTNESS,
                            listOf("default", "dimmed"),
                            palettePresetName(selectedProfile.palette)
                        ) { value -> updateSelected { it.copy(palette = palettePreset(value)) } }
                    }
                    AodChoiceRow(
                        AodChoiceKind.TRANSITION_SPEED,
                        selectedProfile.transition.durationMs.toString()
                    ) {
                        openChoice(
                            AodChoiceKind.TRANSITION_SPEED,
                            listOf("200", "320", "500"),
                            selectedProfile.transition.durationMs.toString()
                        ) { value ->
                            updateSelected {
                                it.copy(transition = it.transition.copy(durationMs = value.toInt()))
                            }
                        }
                    }
                }
            }
            if (editorState.selectedSurface == SceneCompiler.SURFACE_LOCKSCREEN) {
                item { SmallTitle(text = stringResource(R.string.section_lockscreen_card)) }
                item {
                    SettingsCard {
                        SwitchPreference(
                            selectedProfile.backgroundStyle != "none",
                            { enabled ->
                                updateSelected {
                                    it.copy(backgroundStyle = if (enabled) "card" else "none")
                                }
                            },
                            stringResource(R.string.setting_show_lyric_card)
                        )
                        val progressEnabled = selectedProfile.widgets.any { it.type == "media_progress" }
                        SwitchPreference(
                            progressEnabled,
                            { enabled ->
                                updateSelected { profile ->
                                    val widgets = profile.widgets.filterNot {
                                        it.type == "media_progress"
                                    }.toMutableList()
                                    if (enabled) {
                                        widgets += com.eza.hyperglow.customization.WidgetSpec(
                                            "media_progress",
                                            optional = true
                                        )
                                    }
                                    profile.copy(widgets = widgets)
                                }
                            },
                            stringResource(R.string.setting_show_playback_progress),
                            summary = stringResource(R.string.summary_show_playback_progress)
                        )
                    }
                }
            }
            item { SmallTitle(text = stringResource(R.string.section_both_surfaces)) }
            item {
                Card(modifier = Modifier.padding(12.dp).fillMaxWidth()) {
                    Column {
                        ArrowPreference(
                            title = stringResource(R.string.action_import_appearance),
                            onClick = { importLauncher.launch(arrayOf("application/json", "text/plain")) }
                        )
                        ArrowPreference(
                            title = stringResource(R.string.action_export_appearance),
                            onClick = { exportLauncher.launch("hyperglow-profile.json") }
                        )
                        ArrowPreference(
                            title = stringResource(R.string.action_reset_surfaces),
                            onClick = { showResetDialog = true }
                        )
                    }
                }
            }
        }
    }

    activeChoice?.let { selected ->
        WindowDialog(
            title = stringResource(selected.kind.titleRes),
            show = true,
            onDismissRequest = { activeChoice = null }
        ) {
            Column {
                selected.values.forEach { value ->
                    RadioButtonPreference(
                        choiceDisplayLabel(context, selected.kind, value),
                        selected.current == value,
                        {
                            selected.onSelect(value)
                            activeChoice = null
                        }
                    )
                }
            }
        }
    }

    if (showResetDialog) {
        WindowDialog(
            title = stringResource(R.string.dialog_reset_title),
            summary =
                stringResource(R.string.dialog_reset_summary),
            show = true,
            onDismissRequest = { showResetDialog = false }
        ) {
            androidx.compose.foundation.layout.Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    text = stringResource(R.string.action_cancel),
                    modifier = Modifier.weight(1f),
                    onClick = { showResetDialog = false }
                )
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = stringResource(R.string.action_restore),
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = {
                        showResetDialog = false
                        val reset = CustomizationRepository.reset(context)
                        if (reset) {
                            val document = CustomizationRepository.loadDocument(context)
                            syncCustomizationRuntime(context, document)
                            editorState = CustomizationEditorState(
                                document,
                                editorState.selectedSurface
                            )
                        }
                        Toast.makeText(
                            context,
                            context.getString(
                                if (reset) R.string.toast_settings_restored
                                else R.string.toast_settings_restore_failed
                            ),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                )
            }
        }
    }
}

internal fun resolvePreviewPlacement(
    profile: com.eza.hyperglow.customization.CompiledSurfaceProfile,
    scenario: String,
    width: Float,
    height: Float
): ResolvedPlacement {
    val environment = previewEnvironment(scenario, width, height)
    val metadataHeight = if (profile.metadataVisible &&
        profile.widgets.any { it.type == "metadata" }
    ) {
        height * 0.10f *
            (metadataWidgetHeightDp(profile.metadataSizePercent) / metadataWidgetHeightDp(100))
    } else 0f
    val progressHeight = if (profile.widgets.any { it.type == "media_progress" }) {
        height * 0.05f
    } else {
        0f
    }
    val desiredHeight = height * profile.maxHeightFraction
    val minimumLyricHeight = height * 0.22f
    val measurements = profile.widgets.mapNotNull { widget ->
        when (widget.type) {
            "lyrics" -> WidgetMeasurement(
                widget,
                (desiredHeight - metadataHeight - progressHeight)
                    .coerceAtLeast(minimumLyricHeight)
            )
            "metadata" -> WidgetMeasurement(widget, metadataHeight)
            "media_progress" -> WidgetMeasurement(widget, progressHeight)
            else -> null
        }
    }
    return PlacementEngine.resolve(profile, environment, measurements, minimumLyricHeight)
}

internal fun previewEnvironment(
    scenario: String,
    width: Float,
    height: Float
): PlacementEnvironment = PlacementEnvironment(
    safeCanvas = PlacementRect(0f, 0f, width, height),
    stockClockBottom = when (scenario) {
        "Full AOD" -> height * 0.18f
        "Normal AOD", "FOD safe region" -> height * 0.34f
        else -> height * 0.26f
    },
    bottomReserveTop = when (scenario) {
        "FOD safe region" -> height * 0.70f
        else -> height * 0.90f
    },
    notificationTop = if (scenario == "Lockscreen · notifications") height * 0.62f else null
)

private fun previewSnapshot(scenario: String): LyricSnapshot = LyricSnapshot(
    revision = 1,
    trackGeneration = 1,
    updatedAtElapsedMs = android.os.SystemClock.elapsedRealtime(),
    visible = true,
    original = if (scenario == "Long/ruby/translated") {
        "これは長いレイアウト検証用の歌詞テキスト"
    } else {
        "今夜も眠れない"
    },
    romanized = "kon'ya mo nemurenai",
    translated = "I cannot sleep tonight",
    metadata = "Preview track · HyperGlow",
    lineLevelSync = true,
    lineStartMs = 0,
    lineEndMs = 4_000,
    durationMs = 180_000,
    positionMs = 1_800,
    sampledAtElapsedMs = android.os.SystemClock.elapsedRealtime(),
    words = emptyList(),
    ruby = if (scenario == "Long/ruby/translated") {
        listOf(LyricRuby(0, 3, "kore wa"))
    } else {
        emptyList()
    }
)

@Composable
private fun AodChoiceRow(kind: AodChoiceKind, value: String, onClick: () -> Unit) {
    val context = LocalContext.current
    ArrowPreference(
        title = stringResource(kind.titleRes),
        summary = choiceDisplayLabel(context, kind, value),
        onClick = onClick
    )
}

@Composable
private fun TextSizePreference(
    title: String,
    percent: Int,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit
) {
    BasicComponent(
        title = title,
        endActions = {
            IconButton(
                onClick = onDecrease,
                enabled = percent > 50,
                backgroundColor = MiuixTheme.colorScheme.surfaceContainerHighest,
                cornerRadius = 24.dp,
                minHeight = 48.dp,
                minWidth = 48.dp
            ) {
                Text("−", fontSize = 24.sp)
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = "$percent%",
                modifier = Modifier
                    .width(64.dp)
                    .align(Alignment.CenterVertically),
                color = MiuixTheme.colorScheme.primary,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.width(12.dp))
            IconButton(
                onClick = onIncrease,
                enabled = percent < 200,
                backgroundColor = MiuixTheme.colorScheme.surfaceContainerHighest,
                cornerRadius = 24.dp,
                minHeight = 48.dp,
                minWidth = 48.dp
            ) {
                Text("+", fontSize = 28.sp)
            }
        }
    )
}

private fun effectiveTextSizePercent(profile: SurfaceProfile): Int = when (profile.textSize) {
    "small" -> 90
    "large" -> 120
    "xlarge" -> 150
    "custom" -> profile.textSizeCustom.coerceIn(50, 200)
    else -> 100
}

private fun choiceDisplayLabel(
    context: android.content.Context,
    kind: AodChoiceKind,
    value: String
): String = when (kind) {
    AodChoiceKind.POSITION -> context.getString(when (value) {
        "below_stock_clock" -> R.string.option_below_clock
        "screen_center" -> R.string.option_screen_center
        "screen_top_safe" -> R.string.option_top_safe_area
        "screen_bottom_safe" -> R.string.option_bottom_safe_area
        "custom_vertical_bias" -> R.string.option_custom_vertical_position
        else -> R.string.option_below_clock
    })
    AodChoiceKind.WIDTH ->
        value.toFloatOrNull()?.let { "${(it * 100).roundToInt()}%" } ?: value
    AodChoiceKind.VERTICAL_POSITION -> context.getString(when (value) {
        "0.25" -> R.string.option_upper
        "0.5", "0.50" -> R.string.option_center
        "0.75" -> R.string.option_lower
        else -> R.string.option_center
    })
    AodChoiceKind.OVERLAP -> context.getString(when (value) {
        "avoid" -> R.string.option_avoid_system_content
        "behind_system" -> R.string.option_allow_overlap
        "hide_optional" -> R.string.option_hide_extra_text
        "hide_scene" -> R.string.option_hide_lyrics_blocked
        else -> R.string.option_avoid_system_content
    })
    AodChoiceKind.ALIGNMENT -> context.getString(when (value) {
        "auto" -> R.string.option_automatic
        "start" -> R.string.option_start
        "center" -> R.string.option_center
        "end" -> R.string.option_end
        else -> R.string.option_automatic
    })
    AodChoiceKind.SONG_INFO_POSITION -> context.getString(
        if (value == "bottom") R.string.option_bottom else R.string.option_top
    )
    AodChoiceKind.LYRIC_LINES -> if (value == "0") {
        context.getString(R.string.option_no_limit)
    } else {
        value
    }
    AodChoiceKind.FONT -> context.getString(when (value) {
        "noto" -> R.string.option_noto_sans
        "spotify" -> R.string.option_spotify_mix
        "apple" -> R.string.option_sf_pro_display
        else -> R.string.option_noto_sans
    })
    AodChoiceKind.TEXT_BRIGHTNESS -> context.getString(
        if (value == "dimmed") R.string.option_dimmed else R.string.option_default
    )
    AodChoiceKind.LINE_PROGRESS -> context.getString(when (value) {
        "None" -> R.string.option_none
        "Top to bottom" -> R.string.option_top_to_bottom
        "Left to right (main only)" -> R.string.option_left_to_right
        "Left to right (whole block)" -> R.string.option_left_to_right_all
        else -> R.string.option_none
    })
    AodChoiceKind.TRANSITION_SPEED -> context.getString(when (value) {
        "200" -> R.string.option_fast
        "500" -> R.string.option_slow
        else -> R.string.option_normal
    })
    AodChoiceKind.SECONDARY_TEXT -> context.getString(when (value) {
        "Transliteration" -> R.string.option_transliteration
        "Translation" -> R.string.option_translation
        "Both" -> R.string.option_both
        else -> R.string.option_main_only
    })
    AodChoiceKind.LONG_LINES -> context.getString(
        if (value == "Clip") R.string.option_clip else R.string.option_wrap
    )
    AodChoiceKind.TEXT_WEIGHT -> context.getString(when (value) {
        "Regular" -> R.string.option_regular
        "Bold" -> R.string.option_bold
        else -> R.string.option_medium
    })
    AodChoiceKind.WORD_ANIMATION -> context.getString(
        if (value == "Minimal") R.string.option_minimal else R.string.option_gradient
    )
    AodChoiceKind.GLOW -> context.getString(
        if (value == "On") R.string.option_on else R.string.option_off
    )
}

private enum class AodChoiceKind(@param:StringRes val titleRes: Int) {
    POSITION(R.string.choice_position),
    WIDTH(R.string.choice_width),
    VERTICAL_POSITION(R.string.choice_vertical_position),
    OVERLAP(R.string.choice_overlap_handling),
    ALIGNMENT(R.string.choice_alignment),
    SECONDARY_TEXT(R.string.choice_secondary_text),
    LONG_LINES(R.string.choice_long_lines),
    LYRIC_LINES(R.string.choice_lyric_lines),
    SONG_INFO_POSITION(R.string.choice_song_info_position),
    TEXT_WEIGHT(R.string.choice_text_weight),
    FONT(R.string.choice_font),
    WORD_ANIMATION(R.string.choice_word_animation),
    GLOW(R.string.choice_glow),
    LINE_PROGRESS(R.string.choice_line_progress_effect),
    TEXT_BRIGHTNESS(R.string.choice_text_brightness),
    TRANSITION_SPEED(R.string.choice_scene_transition_speed)
}

private data class AodChoice(
    val kind: AodChoiceKind,
    val values: List<String>,
    val current: String,
    val onSelect: (String) -> Unit
)

private fun updateCustomizationSurfaceEnabled(
    context: android.content.Context,
    surface: String,
    enabled: Boolean
): Boolean {
    val document = CustomizationRepository.loadDocument(context)
    val profiles = document.profiles.toMutableMap()
    profiles[surface] = (profiles[surface] ?: SurfaceProfile()).copy(enabled = enabled)
    if (!CustomizationRepository.saveDocument(context, document.copy(profiles = profiles))) {
        return false
    }
    syncCustomizationRuntime(context, CustomizationRepository.loadDocument(context))
    return true
}

internal fun withMetadataVisible(profile: SurfaceProfile, visible: Boolean): SurfaceProfile {
    val widgets = profile.widgets.filterNot { it.type == "metadata" }.toMutableList()
    if (visible) {
        widgets += com.eza.hyperglow.customization.WidgetSpec(
            "metadata",
            optional = true
        )
    }
    return profile.copy(metadataVisible = visible, widgets = widgets)
}

private val SEMANTIC_PALETTE_KEYS = setOf(
    "primaryText",
    "secondaryText",
    "metadataText",
    "sungText",
    "unsungText",
    "glow",
    "accent",
    "surfaceScrim"
)

internal fun palettePreset(name: String): Map<String, String> =
    if (name == "dimmed") SEMANTIC_PALETTE_KEYS.associateWith { "dimmed" } else emptyMap()

internal fun palettePresetName(palette: Map<String, String>): String =
    if (palette.isNotEmpty() && palette.values.all { it == "dimmed" }) "dimmed" else "default"

private fun applyDocumentToLegacyPreferences(
    context: android.content.Context,
    document: com.eza.hyperglow.customization.CustomizationDocument
) {
    val aod = document.profiles[SceneCompiler.SURFACE_AOD] ?: SceneCompiler.safeAodProfile()
    val lockscreen = document.profiles[SceneCompiler.SURFACE_LOCKSCREEN]
        ?: SceneCompiler.safeLockscreenProfile()
    context.getSharedPreferences(AodRenderPreferences.PREFS, 0).edit()
        .putBoolean(AodRenderPreferences.AOD_ENABLED, aod.enabled)
        .putBoolean(AodRenderPreferences.LOCKSCREEN_ENABLED, lockscreen.enabled)
        .putString(AodRenderPreferences.ALIGNMENT, aod.alignment)
        .putString(AodRenderPreferences.SECONDARY, aod.secondaryMode)
        .putString(AodRenderPreferences.OVERFLOW, aod.overflow)
        .putString(
            AodRenderPreferences.METADATA_VISIBLE,
            if (aod.metadataVisible) "show" else "hide"
        )
        .putString(AodRenderPreferences.METADATA_ANCHOR, aod.metadataAnchor)
        .putString(AodRenderPreferences.WEIGHT, aod.weight)
        .putString(AodRenderPreferences.TEXT_SIZE, aod.textSize)
        .putInt(AodRenderPreferences.TEXT_SIZE_CUSTOM, aod.textSizeCustom)
        .putString(AodRenderPreferences.FONT_FAMILY, aod.fontFamily)
        .putString(AodRenderPreferences.ANIMATION, aod.animation)
        .putString(AodRenderPreferences.GLOW, aod.glow)
        .putBoolean(AodRenderPreferences.ADAPTIVE_SECTIONING, aod.adaptiveSectioning)
        .commit()
}

private fun syncCustomizationRuntime(
    context: android.content.Context,
    document: com.eza.hyperglow.customization.CustomizationDocument
) {
    applyDocumentToLegacyPreferences(context, document)
    publishRuntimeConfiguration(context)
}

private fun updateLockscreenKeepAwake(
    context: android.content.Context,
    enabled: Boolean
): Boolean {
    val saved = context.getSharedPreferences(AodRenderPreferences.PREFS, 0).edit()
        .putBoolean(AodRenderPreferences.LOCKSCREEN_KEEP_AWAKE, enabled)
        .commit()
    if (!saved) return false
    publishRuntimeConfiguration(context)
    return true
}

private fun updateRaiseToAod(
    context: android.content.Context,
    enabled: Boolean
): Boolean {
    val saved = context.getSharedPreferences(AodRenderPreferences.PREFS, 0).edit()
        .putBoolean(AodRenderPreferences.RAISE_TO_AOD, enabled)
        .commit()
    if (!saved) return false
    publishRuntimeConfiguration(context)
    return true
}

private fun updateLockscreenEditorLongPress(
    context: android.content.Context,
    enabled: Boolean
): Boolean {
    val saved = context.getSharedPreferences(AodRenderPreferences.PREFS, 0).edit()
        .putBoolean(AodRenderPreferences.SUPPRESS_LOCKSCREEN_EDITOR_LONG_PRESS, enabled)
        .commit()
    if (!saved) return false
    publishRuntimeConfiguration(context)
    return true
}

private fun publishRuntimeConfiguration(context: android.content.Context) {
    AodStateBridge.publishConfiguration(
        RuntimeCustomization.loadCompiled(context),
        currentProcessUserId()
    )
}

private fun updateKeepAwakeDuration(context: android.content.Context, value: Long): Boolean {
    val saved = context.getSharedPreferences(AodRenderPreferences.PREFS, 0).edit()
        .putLong(AodRenderPreferences.KEEP_AWAKE_DURATION_MS, value)
        .commit()
    if (!saved) return false
    publishRuntimeConfiguration(context)
    return true
}

private fun updatePauseLinger(context: android.content.Context, value: Long): Boolean {
    val saved = context.getSharedPreferences(AodRenderPreferences.PREFS, 0).edit()
        .putLong(AodRenderPreferences.PAUSE_LINGER_MS, value)
        .commit()
    if (!saved) return false
    publishRuntimeConfiguration(context)
    return true
}

private fun updateDiagnosticLogging(
    context: android.content.Context,
    enabled: Boolean
): Boolean = setDiagnosticLogging(context, enabled)
