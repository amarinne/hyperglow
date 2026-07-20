package com.eza.hyperglow.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eza.hyperglow.BuildConfig
import com.eza.hyperglow.DiagnosticLoggingPreferences
import com.eza.hyperglow.DiagnosticLoggingRuntime
import com.eza.hyperglow.RuntimeCustomization
import com.eza.hyperglow.root.utils.ShellUtils
import kotlinx.coroutines.launch
import com.eza.hyperglow.aod.AodRenderPreferences
import com.eza.hyperglow.aod.AodStateBridge
import com.eza.hyperglow.aod.XiaomiCapabilityStore
import com.eza.hyperglow.customization.CustomizationEditorState
import com.eza.hyperglow.customization.CustomizationRepository
import com.eza.hyperglow.customization.SceneCompiler
import com.eza.hyperglow.customization.SurfaceProfile
import com.eza.hyperglow.root.capability.XiaomiCapability
import com.eza.hyperglow.root.projection.LyricRuby
import com.eza.hyperglow.root.projection.LyricSnapshot
import com.eza.hyperglow.root.projection.currentProcessUserId
import com.eza.hyperglow.root.surface.PlacementEngine
import com.eza.hyperglow.root.surface.PlacementEnvironment
import com.eza.hyperglow.root.surface.PlacementRect
import com.eza.hyperglow.root.surface.ResolvedPlacement
import com.eza.hyperglow.root.surface.WidgetMeasurement
import kotlin.math.roundToInt
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
                    if (surface != null) {
                        LyricLayoutScreen(
                            initialSurface = surface,
                            onBack = { editingSurface = null }
                        )
                    } else {
                        HomeScreen(
                            showRestartResult = ::showRestartResult,
                            selectedTabName = selectedTabName,
                            onSelectTab = { selectedTabName = it },
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
            if (succeeded) "System UI restarted" else "Restart failed. Check root permission.",
            Toast.LENGTH_LONG
        ).show()
    }
}

@Composable
private fun HomeScreen(
    showRestartResult: (Boolean) -> Unit,
    selectedTabName: String,
    onSelectTab: (String) -> Unit,
    onOpenLyricLayout: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showRestartDialog by remember { mutableStateOf(false) }
    var showBurnInPatternDialog by remember { mutableStateOf(false) }
    var showBurnInIntervalDialog by remember { mutableStateOf(false) }
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
    val initialConfig = remember { AodRenderPreferences.read(context) }
    val initialDocument = remember { CustomizationRepository.loadDocument(context) }
    val lockscreenSupported = capabilityReport.has(XiaomiCapability.LOCKSCREEN_HOST) &&
        capabilityReport.has(XiaomiCapability.LOCKSCREEN_GEOMETRY)
    val positionFollowingSupported = capabilityReport.has(XiaomiCapability.AOD_POSITION_UPDATES)
    val raiseToAodSupported = capabilityReport.has(XiaomiCapability.RAISE_TO_AOD)
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
    var aodMetadataVisible by remember {
        mutableStateOf(
            initialDocument.profiles[SceneCompiler.SURFACE_AOD]?.metadataVisible
                ?: (initialConfig.metadataVisible != "hide")
        )
    }
    var lockscreenMetadataVisible by remember {
        mutableStateOf(
            initialDocument.profiles[SceneCompiler.SURFACE_LOCKSCREEN]?.metadataVisible
                ?: (initialConfig.metadataVisible != "hide")
        )
    }
    var keepAwake by remember { mutableStateOf(initialConfig.keepAwake) }
    var lockscreenKeepAwake by remember {
        mutableStateOf(initialConfig.lockscreenKeepAwake)
    }
    var raiseToAod by remember { mutableStateOf(initialConfig.raiseToAod) }
    var positionFollowing by remember {
        mutableStateOf(initialConfig.experimentalPositionFollowing)
    }
    var burnInPattern by remember { mutableStateOf(initialConfig.burnInPattern) }
    var burnInIntervalMs by remember { mutableStateOf(initialConfig.burnInIntervalMs) }
    var diagnosticLogging by remember {
        mutableStateOf(DiagnosticLoggingPreferences.read(context))
    }

    Scaffold(
        topBar = { TopAppBar(title = "HyperGlow") },
        bottomBar = {
            FloatingNavigationBar {
                FloatingNavigationBarItem(
                    selected = pagerState.currentPage == SettingsTab.OVERVIEW.ordinal,
                    onClick = {
                        scope.launch { pagerState.animateScrollToPage(SettingsTab.OVERVIEW.ordinal) }
                    },
                    icon = MiuixIcons.Regular.Home,
                    label = "Overview"
                )
                FloatingNavigationBarItem(
                    selected = pagerState.currentPage == SettingsTab.CONFIG.ordinal,
                    onClick = {
                        scope.launch { pagerState.animateScrollToPage(SettingsTab.CONFIG.ordinal) }
                    },
                    icon = MiuixIcons.Regular.Settings,
                    label = "Config"
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
                    item { SmallTitle(text = "Runtime status") }
                    item {
                        SettingsCard {
                            BasicComponent(
                                title = "AOD lyrics",
                                summary = if (aodEnabled) "Enabled" else "Disabled"
                            )
                            BasicComponent(
                                title = "Lock screen lyrics",
                                summary = when {
                                    !lockscreenSupported -> "Unavailable on the current System UI profile"
                                    lockscreenEnabled -> "Enabled"
                                    else -> "Disabled"
                                }
                            )
                            SwitchPreference(
                                diagnosticLogging,
                                { enabled ->
                                    if (updateDiagnosticLogging(context, enabled)) {
                                        diagnosticLogging = enabled
                                    }
                                },
                                "Diagnostic logging",
                                summary = if (BuildConfig.TRACE_LOGGING_AVAILABLE) {
                                    "Detailed module and System UI traces. May affect performance."
                                } else {
                                    "Unavailable in this build"
                                },
                                enabled = BuildConfig.TRACE_LOGGING_AVAILABLE
                            )
                            ArrowPreference(
                                title = "Restart System UI",
                                onClick = { showRestartDialog = true }
                            )
                        }
                    }
                    item { SmallTitle(text = "Spotify integration") }
                    item {
                        SettingsCard {
                            ArrowPreference(
                                title = "Spicy EX releases",
                                onClick = {
                                    openExternalUrl(context, SPICY_EX_GITHUB_URL)
                                }
                            )
                            ArrowPreference(
                                title = "Open Spotify",
                                onClick = {
                                    val launchIntent = context.packageManager
                                        .getLaunchIntentForPackage("com.spotify.music")
                                    if (launchIntent != null) {
                                        context.startActivity(launchIntent)
                                    } else {
                                        Toast.makeText(
                                            context,
                                            "Spotify is not installed",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            )
                        }
                    }
                    item { SmallTitle(text = "Project") }
                    item {
                        SettingsCard {
                            ArrowPreference(
                                title = "Open GitHub",
                                onClick = {
                                    openExternalUrl(context, GITHUB_URL)
                                }
                            )
                        }
                    }
                }

                SettingsTab.CONFIG -> {
                    item { SmallTitle(text = "Surfaces") }
                    item {
                        SettingsCard {
                            SwitchPreference(
                                aodEnabled,
                                { enabled ->
                                    if (updateCustomizationSurfaceEnabled(
                                            context,
                                            SceneCompiler.SURFACE_AOD,
                                            enabled
                                        )
                                    ) {
                                        aodEnabled = enabled
                                    }
                                },
                                "Show on AOD"
                            )
                            SwitchPreference(
                                lockscreenEnabled,
                                { enabled ->
                                    if (!lockscreenSupported) {
                                        Toast.makeText(
                                            context,
                                            "Lockscreen capability unavailable. Restart System UI to refresh report.",
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
                                "Show on lock screen",
                                summary = if (lockscreenSupported) {
                                    "Display visual-only lyrics while the keyguard is showing"
                                } else {
                                    "Unavailable: lock screen host and geometry were not detected"
                                },
                                enabled = lockscreenSupported
                            )
                            SwitchPreference(
                                aodMetadataVisible,
                                { visible ->
                                    if (updateCustomizationSurfaceMetadata(
                                            context,
                                            SceneCompiler.SURFACE_AOD,
                                            visible
                                        )
                                    ) {
                                        aodMetadataVisible = visible
                                    }
                                },
                                "Show metadata on AOD"
                            )
                            SwitchPreference(
                                lockscreenMetadataVisible,
                                { visible ->
                                    if (updateCustomizationSurfaceMetadata(
                                            context,
                                            SceneCompiler.SURFACE_LOCKSCREEN,
                                            visible
                                        )
                                    ) {
                                        lockscreenMetadataVisible = visible
                                    }
                                },
                                "Show metadata on lock screen"
                            )
                        }
                    }
                    item { SmallTitle(text = "Appearance") }
                    item {
                        SettingsCard {
                            ArrowPreference(
                                title = "AOD appearance",
                                onClick = { onOpenLyricLayout(SceneCompiler.SURFACE_AOD) }
                            )
                            ArrowPreference(
                                title = "Lock screen appearance",
                                onClick = { onOpenLyricLayout(SceneCompiler.SURFACE_LOCKSCREEN) }
                            )
                        }
                    }
                    item { SmallTitle(text = "AOD behavior") }
                    item {
                        SettingsCard {
                            SwitchPreference(
                                keepAwake,
                                { enabled ->
                                    prefs.edit().putBoolean(AodRenderPreferences.KEEP_AWAKE, enabled).apply()
                                    keepAwake = enabled
                                },
                                "Keep AOD active",
                                summary = "Keep AOD active while Spotify's media player exists. Uses more power."
                            )
                            SwitchPreference(
                                positionFollowing,
                                { enabled ->
                                    if (!positionFollowingSupported) {
                                        Toast.makeText(
                                            context,
                                            "AOD position updates are unavailable on this runtime profile.",
                                            Toast.LENGTH_LONG
                                        ).show()
                                        return@SwitchPreference
                                    }
                                    prefs.edit().putBoolean(
                                        AodRenderPreferences.EXPERIMENTAL_POSITION_FOLLOWING,
                                        enabled
                                    ).apply()
                                    positionFollowing = enabled
                                },
                                "Move the AOD clock for lyrics",
                                summary = if (positionFollowingSupported) {
                                    "Keep the stock clock away from lyrics and restore it when Spotify's player is removed"
                                } else {
                                    "Unavailable: stock clock position updates were not detected"
                                },
                                enabled = positionFollowingSupported
                            )
                            ArrowPreference(
                                title = "AOD clock placement",
                                summary = burnInPatternLabel(burnInPattern),
                                onClick = { showBurnInPatternDialog = true }
                            )
                            if (!burnInPattern.isStaticClockPlacement()) {
                                ArrowPreference(
                                    title = "Movement interval",
                                    summary = burnInIntervalLabel(burnInIntervalMs),
                                    onClick = { showBurnInIntervalDialog = true }
                                )
                            }
                        }
                    }
                    item { SmallTitle(text = "Lock screen behavior") }
                    item {
                        SettingsCard {
                            SwitchPreference(
                                lockscreenKeepAwake,
                                { enabled ->
                                    if (updateLockscreenKeepAwake(context, enabled, raiseToAod)) {
                                        lockscreenKeepAwake = enabled
                                    }
                                },
                                "Keep lock screen awake",
                                summary = "Prevent automatic dim and sleep while Spotify is playing and the lyric card is visible. Uses more power.",
                                enabled = lockscreenSupported && lockscreenEnabled
                            )
                        }
                    }
                    item { SmallTitle(text = "Wake gestures") }
                    item {
                        SettingsCard {
                            SwitchPreference(
                                raiseToAod,
                                { enabled ->
                                    if (updateRaiseToAod(context, enabled, lockscreenKeepAwake)) {
                                        raiseToAod = enabled
                                    }
                                },
                                "Raise to show AOD",
                                summary = if (raiseToAodSupported) {
                                    "Keep HyperOS Raise to wake enabled. Lifting shows AOD instead of the full lock screen, even without lyrics."
                                } else {
                                    "Unavailable: the verified pickup wake path was not detected"
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

    if (showRestartDialog) {
        WindowDialog(
            title = "Restart System UI?",
            summary = "Screen, status bar, and AOD will briefly disappear. Root permission is required.",
            show = true,
            onDismissRequest = { showRestartDialog = false }
        ) {
            androidx.compose.foundation.layout.Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    text = "Cancel",
                    modifier = Modifier.weight(1f),
                    onClick = { showRestartDialog = false }
                )
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = "Restart",
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
            title = "AOD clock placement",
            summary = "Used while the lyric card is visible",
            show = true,
            onDismissRequest = { showBurnInPatternDialog = false }
        ) {
            Column {
                BURN_IN_PATTERNS.forEach { (value, label) ->
                    RadioButtonPreference(
                        label,
                        burnInPattern == value,
                        {
                            prefs.edit().putString(AodRenderPreferences.BURN_IN_PATTERN, value).apply()
                            burnInPattern = value
                            showBurnInPatternDialog = false
                        }
                    )
                }
            }
        }
    }

    if (showBurnInIntervalDialog) {
        WindowDialog(
            title = "Movement interval",
            summary = "Shorter intervals move more often and use more AOD power",
            show = true,
            onDismissRequest = { showBurnInIntervalDialog = false }
        ) {
            Column {
                BURN_IN_INTERVALS.forEach { value ->
                    RadioButtonPreference(
                        burnInIntervalLabel(value),
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
private fun SettingsCard(content: @Composable () -> Unit) {
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

private const val GITHUB_URL = "https://github.com/amarinne/hyperglow"
private const val SPICY_EX_GITHUB_URL = "https://github.com/amarinne/spicy-ex/releases"

private fun openExternalUrl(context: android.content.Context, url: String) {
    val opened = runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }.isSuccess
    if (!opened) {
        Toast.makeText(context, "No app can open this link", Toast.LENGTH_LONG).show()
    }
}

private fun burnInPatternLabel(value: String): String =
    BURN_IN_PATTERNS.firstOrNull { it.first == value }?.second ?: "Static clock bottom"

private fun burnInIntervalLabel(value: Long): String = when (value) {
    30_000L -> "30 seconds"
    120_000L -> "2 minutes"
    300_000L -> "5 minutes"
    else -> "1 minute"
}

private val BURN_IN_PATTERNS = listOf(
    "static_top" to "Static system top",
    "static_bottom" to "Static system bottom",
    "six_zone" to "Six-zone sweep",
    "four_corner" to "Four-corner swap",
    "vertical_swap" to "Vertical swap"
)

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

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val raw = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val bytes = input.readNBytes(SceneCompiler.MAX_CONFIG_BYTES + 1)
                if (bytes.size > SceneCompiler.MAX_CONFIG_BYTES) error("Profile exceeds 64 KiB")
                bytes.toString(Charsets.UTF_8)
            } ?: error("Cannot open profile")
        }.getOrNull()
        val imported = raw != null && CustomizationRepository.importDocument(context, raw)
        if (imported) {
            val document = CustomizationRepository.loadDocument(context)
            syncCustomizationRuntime(context, document)
            editorState = CustomizationEditorState(document, editorState.selectedSurface)
        }
        Toast.makeText(
            context,
            if (imported) "Profile imported" else "Profile rejected",
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
            } ?: error("Cannot create profile")
        }.isSuccess
        Toast.makeText(
            context,
            if (written) "Profile exported" else "Export failed",
            Toast.LENGTH_LONG
        ).show()
    }

    BackHandler(enabled = activeChoice == null && !showResetDialog, onBack = onBack)

    fun saveEditor(next: CustomizationEditorState): Boolean {
        if (!CustomizationRepository.saveDocument(context, next.document)) {
            Toast.makeText(context, "Profile save failed", Toast.LENGTH_LONG).show()
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
        title: String,
        values: List<String>,
        current: String,
        onSelect: (String) -> Unit
    ) {
        activeChoice = AodChoice(title, values, current, onSelect)
    }

    val selectedProfile = editorState.document.profiles[editorState.selectedSurface] ?: SurfaceProfile()

    Scaffold(
        topBar = {
            TopAppBar(
                title = if (editorState.selectedSurface == SceneCompiler.SURFACE_AOD) {
                    "AOD appearance"
                } else {
                    "Lock screen appearance"
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
            item { SmallTitle(text = "Placement") }
            item {
                SettingsCard {
                    AodChoiceRow("Anchor", selectedProfile.anchor) {
                        openChoice(
                            "Anchor",
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
                    AodChoiceRow("Width", "${(selectedProfile.widthFraction * 100).roundToInt()}%") {
                        openChoice(
                            "Width",
                            listOf("0.7", "0.88", "1.0"),
                            selectedProfile.widthFraction.toString()
                        ) { value -> updateSelected { it.copy(widthFraction = value.toFloat()) } }
                    }
                    if (selectedProfile.anchor == "custom_vertical_bias") {
                        AodChoiceRow("Vertical position", selectedProfile.verticalBias.toString()) {
                            openChoice(
                                "Vertical position",
                                listOf("0.25", "0.5", "0.75"),
                                selectedProfile.verticalBias.toString()
                            ) { value -> updateSelected { it.copy(verticalBias = value.toFloat()) } }
                        }
                    }
                    AodChoiceRow("System content", selectedProfile.collisionPolicy) {
                        openChoice(
                            "System content",
                            listOf("avoid", "behind_system", "hide_optional", "hide_scene"),
                            selectedProfile.collisionPolicy
                        ) { value -> updateSelected { it.copy(collisionPolicy = value) } }
                    }
                }
            }
            item { SmallTitle(text = "Text and language") }
            item {
                SettingsCard {
                    AodChoiceRow("Alignment", selectedProfile.alignment) {
                        openChoice(
                            "Alignment",
                            listOf("auto", "start", "center", "end"),
                            selectedProfile.alignment
                        ) { value -> updateSelected { it.copy(alignment = value) } }
                    }
                    AodChoiceRow("Secondary text", selectedProfile.secondaryMode) {
                        openChoice(
                            "Secondary text",
                            listOf("Main only", "Transliteration", "Translation", "Both"),
                            selectedProfile.secondaryMode
                        ) { value -> updateSelected { it.copy(secondaryMode = value) } }
                    }
                    AodChoiceRow("Overflow", selectedProfile.overflow) {
                        openChoice(
                            "Overflow",
                            listOf("Wrap", "Clip"),
                            selectedProfile.overflow
                        ) { value -> updateSelected { it.copy(overflow = value) } }
                    }
                    SwitchPreference(
                        selectedProfile.adaptiveSectioning,
                        { enabled -> updateSelected { it.copy(adaptiveSectioning = enabled) } },
                        "Phrase-aware wrapping",
                        summary = "Keep words and phrases together across wrapped lines"
                    )
                    if (selectedProfile.metadataVisible) {
                        AodChoiceRow("Metadata position", selectedProfile.metadataAnchor) {
                            openChoice(
                                "Metadata position",
                                listOf("top", "bottom"),
                                selectedProfile.metadataAnchor
                            ) { value -> updateSelected { it.copy(metadataAnchor = value) } }
                        }
                    }
                    AodChoiceRow("Weight", selectedProfile.weight) {
                        openChoice(
                            "Weight",
                            listOf("Regular", "Medium", "Bold"),
                            selectedProfile.weight
                        ) { value -> updateSelected { it.copy(weight = value) } }
                    }
                    TextSizePreference(
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
                    AodChoiceRow("Font", selectedProfile.fontFamily) {
                        openChoice(
                            "Font",
                            listOf("noto", "spotify", "apple"),
                            selectedProfile.fontFamily
                        ) { value -> updateSelected { it.copy(fontFamily = value) } }
                    }
                }
            }
            item { SmallTitle(text = "Effects") }
            item {
                SettingsCard {
                    AodChoiceRow("Word animation", selectedProfile.animation) {
                        openChoice(
                            "Word animation",
                            listOf("Minimal", "Gradient"),
                            selectedProfile.animation
                        ) { value -> updateSelected { it.copy(animation = value) } }
                    }
                    AodChoiceRow("Glow", selectedProfile.glow) {
                        openChoice("Glow", listOf("Off", "On"), selectedProfile.glow) { value ->
                            updateSelected { it.copy(glow = value) }
                        }
                    }
                    AodChoiceRow("Line-level sweep", selectedProfile.lineSyncFillMode) {
                        openChoice(
                            "Line-level sweep",
                            listOf(
                                "None",
                                "Top to bottom",
                                "Left to right (main only)",
                                "Left to right (whole block)"
                            ),
                            selectedProfile.lineSyncFillMode
                        ) { value -> updateSelected { it.copy(lineSyncFillMode = value) } }
                    }
                    AodChoiceRow("Colors", palettePresetName(selectedProfile.palette)) {
                        openChoice(
                            "Colors",
                            listOf("default", "dimmed"),
                            palettePresetName(selectedProfile.palette)
                        ) { value -> updateSelected { it.copy(palette = palettePreset(value)) } }
                    }
                    AodChoiceRow(
                        "Transition duration",
                        selectedProfile.transition.durationMs.toString()
                    ) {
                        openChoice(
                            "Transition duration",
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
                item { SmallTitle(text = "Lock screen card") }
                item {
                    SettingsCard {
                        SwitchPreference(
                            selectedProfile.backgroundStyle != "none",
                            { enabled ->
                                updateSelected {
                                    it.copy(backgroundStyle = if (enabled) "card" else "none")
                                }
                            },
                            "Card background"
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
                            "Media progress"
                        )
                    }
                }
            }
            item { SmallTitle(text = "Profiles") }
            item {
                Card(modifier = Modifier.padding(12.dp).fillMaxWidth()) {
                    Column {
                        ArrowPreference(
                            title = "Import profile",
                            onClick = { importLauncher.launch(arrayOf("application/json", "text/plain")) }
                        )
                        ArrowPreference(
                            title = "Export profile",
                            onClick = { exportLauncher.launch("hyperglow-profile.json") }
                        )
                        ArrowPreference(
                            title = "Restore default",
                            onClick = { showResetDialog = true }
                        )
                    }
                }
            }
        }
    }

    activeChoice?.let { selected ->
        WindowDialog(
            title = selected.title,
            show = true,
            onDismissRequest = { activeChoice = null }
        ) {
            Column {
                selected.values.forEach { value ->
                    RadioButtonPreference(
                        choiceDisplayLabel(selected.title, value),
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
            title = "Restore default?",
            summary = "Restores built-in AOD and lock screen layout settings.",
            show = true,
            onDismissRequest = { showResetDialog = false }
        ) {
            androidx.compose.foundation.layout.Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    text = "Cancel",
                    modifier = Modifier.weight(1f),
                    onClick = { showResetDialog = false }
                )
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = "Restore",
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
                            if (reset) "Defaults restored" else "Restore failed",
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
    ) height * 0.10f else 0f
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
private fun AodChoiceRow(title: String, value: String, onClick: () -> Unit) {
    ArrowPreference(
        title = title,
        summary = choiceDisplayLabel(title, value),
        onClick = onClick
    )
}

@Composable
private fun TextSizePreference(
    percent: Int,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit
) {
    BasicComponent(
        title = "Text size",
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

private fun choiceDisplayLabel(title: String, value: String): String = when (title) {
    "Anchor" -> when (value) {
        "below_stock_clock" -> "Below stock clock"
        "screen_center" -> "Screen center"
        "screen_top_safe" -> "Top safe area"
        "screen_bottom_safe" -> "Bottom safe area"
        "custom_vertical_bias" -> "Custom vertical position"
        else -> value
    }
    "Width" -> value.toFloatOrNull()?.let { "${(it * 100).roundToInt()}%" } ?: value
    "Vertical position" -> when (value) {
        "0.25" -> "Upper"
        "0.5", "0.50" -> "Center"
        "0.75" -> "Lower"
        else -> value
    }
    "System content" -> when (value) {
        "avoid" -> "Place below system content"
        "behind_system" -> "Allow overlap"
        "hide_optional" -> "Hide optional lyric rows first"
        "hide_scene" -> "Hide lyrics when space is blocked"
        else -> value
    }
    "Alignment" -> when (value) {
        "auto" -> "Match lyric direction"
        "start" -> "Start"
        "center" -> "Center"
        "end" -> "End"
        else -> value
    }
    "Metadata position" -> if (value == "bottom") "Bottom" else "Top"
    "Font" -> when (value) {
        "noto" -> "Noto Sans"
        "spotify" -> "Spotify Mix"
        "apple" -> "SF Pro Display"
        else -> value
    }
    "Colors" -> if (value == "dimmed") "Dimmed" else "Default"
    "Transition duration" -> "$value ms"
    else -> value.replaceFirstChar { it.uppercase() }
}

private data class AodChoice(
    val title: String,
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

private fun updateCustomizationSurfaceMetadata(
    context: android.content.Context,
    surface: String,
    visible: Boolean
): Boolean {
    val document = CustomizationRepository.loadDocument(context)
    val profiles = document.profiles.toMutableMap()
    profiles[surface] = withMetadataVisible(
        profiles[surface] ?: SurfaceProfile(),
        visible
    )
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
    val runtime = AodRenderPreferences.read(context)
    AodStateBridge.publishConfiguration(
        RuntimeCustomization.compile(
            document,
            DiagnosticLoggingPreferences.read(context),
            lockscreenKeepAwake = runtime.lockscreenKeepAwake,
            raiseToAod = runtime.raiseToAod
        ),
        currentProcessUserId()
    )
}

private fun updateLockscreenKeepAwake(
    context: android.content.Context,
    enabled: Boolean,
    raiseToAod: Boolean
): Boolean {
    val saved = context.getSharedPreferences(AodRenderPreferences.PREFS, 0).edit()
        .putBoolean(AodRenderPreferences.LOCKSCREEN_KEEP_AWAKE, enabled)
        .commit()
    if (!saved) return false
    AodStateBridge.publishConfiguration(
        RuntimeCustomization.compile(
            CustomizationRepository.loadDocument(context),
            DiagnosticLoggingPreferences.read(context),
            lockscreenKeepAwake = enabled,
            raiseToAod = raiseToAod
        ),
        currentProcessUserId()
    )
    return true
}

private fun updateRaiseToAod(
    context: android.content.Context,
    enabled: Boolean,
    lockscreenKeepAwake: Boolean
): Boolean {
    val saved = context.getSharedPreferences(AodRenderPreferences.PREFS, 0).edit()
        .putBoolean(AodRenderPreferences.RAISE_TO_AOD, enabled)
        .commit()
    if (!saved) return false
    AodStateBridge.publishConfiguration(
        RuntimeCustomization.compile(
            CustomizationRepository.loadDocument(context),
            DiagnosticLoggingPreferences.read(context),
            lockscreenKeepAwake = lockscreenKeepAwake,
            raiseToAod = enabled
        ),
        currentProcessUserId()
    )
    return true
}

private fun updateDiagnosticLogging(
    context: android.content.Context,
    enabled: Boolean
): Boolean {
    if (!DiagnosticLoggingPreferences.write(context, enabled)) return false
    val effective = DiagnosticLoggingPreferences.read(context)
    DiagnosticLoggingRuntime.setEnabled(effective)
    AodStateBridge.publishConfiguration(
        RuntimeCustomization.loadCompiled(context),
        currentProcessUserId()
    )
    return true
}
