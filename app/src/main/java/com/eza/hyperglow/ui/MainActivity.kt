package com.eza.hyperglow.ui

import android.Manifest
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
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import com.eza.hyperglow.BuildConfig
import com.eza.hyperglow.R
import com.eza.hyperglow.AppLog
import com.eza.hyperglow.DiagnosticLoggingPreferences
import com.eza.hyperglow.root.utils.ShellUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.eza.hyperglow.aod.AOD_ROTATION_MODE_AUTO
import com.eza.hyperglow.aod.AOD_ROTATION_MODE_LANDSCAPE
import com.eza.hyperglow.aod.AOD_ROTATION_MODE_LANDSCAPE_REVERSE
import com.eza.hyperglow.aod.AOD_ROTATION_MODE_PORTRAIT
import com.eza.hyperglow.aod.AodLyricBridgeService
import com.eza.hyperglow.aod.AodRenderPreferences
import com.eza.hyperglow.aod.XiaomiCapabilityStore
import com.eza.hyperglow.aod.XiaomiRuntimeSupportState
import com.eza.hyperglow.aod.normalizeAodCanvasAnchor
import com.eza.hyperglow.aod.normalizeAodCanvasPaddingPercent
import com.eza.hyperglow.aod.normalizeAodLandscapeTextScale
import com.eza.hyperglow.aod.normalizeAodRotationMode
import com.eza.hyperglow.customization.CustomizationDocument
import com.eza.hyperglow.customization.CustomizationEditorState
import com.eza.hyperglow.customization.CustomizationRepository
import com.eza.hyperglow.customization.SceneCompiler
import com.eza.hyperglow.customization.SurfaceProfile
import com.eza.hyperglow.customization.MAX_LYRIC_TEXT_SIZE_PERCENT
import com.eza.hyperglow.root.aod.metadataWidgetHeightDp
import com.eza.hyperglow.root.capability.XiaomiCapability
import com.eza.hyperglow.root.projection.LyricRuby
import com.eza.hyperglow.root.projection.LyricSnapshot
import com.eza.hyperglow.root.surface.PlacementEngine
import com.eza.hyperglow.update.UpdateAvailability
import com.eza.hyperglow.update.UpdateChecker
import com.eza.hyperglow.root.surface.PlacementEnvironment
import com.eza.hyperglow.root.surface.PlacementRect
import com.eza.hyperglow.root.surface.ResolvedPlacement
import com.eza.hyperglow.root.surface.WidgetMeasurement
import kotlin.math.roundToInt
import java.util.Locale
import kotlinx.serialization.encodeToString
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar
import top.yukonga.miuix.kmp.basic.FloatingNavigationBarItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.window.WindowDialog

class MainActivity : ComponentActivity() {
    private lateinit var session: SettingsSession

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        enableEdgeToEdge()
        window.isNavigationBarContrastEnforced = false
        // Foreground retry for the bridge-service promotion: the application-level attempt usually
        // runs without a background-start window, while an activity in the foreground has one.
        runCatching {
            startForegroundService(Intent(this, AodLyricBridgeService::class.java))
        }.onFailure { error ->
            AppLog.w("MainActivity", "startForegroundService denied", error)
        }
        // One activity-owned state holder feeds both screens; it survives AnimatedContent swaps,
        // so no screen reads disk or capability stores during a transition.
        session = SettingsSession(
            initialConfig = AodRenderPreferences.read(this),
            initialDocument = CustomizationRepository.loadDocument(this),
            initialDiagnosticLogging = DiagnosticLoggingPreferences.read(this),
            initialCapabilityReport = XiaomiCapabilityStore.read(this),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1)),
            store = PreferenceSettingsStore(applicationContext),
            nowMs = { android.os.SystemClock.elapsedRealtime() }
        )
        setContent {
            val controller = remember { ThemeController(colorSchemeMode = ColorSchemeMode.System) }
            MiuixTheme(controller = controller) {
                // Startup component-enable and app-task reapplies land after the first frame,
                // off the UI thread; they are idempotent one-shots.
                LaunchedEffect(session) {
                    val config = session.config.value
                    withContext(Dispatchers.Default) {
                        applyHideLauncherIcon(applicationContext, config.hideLauncherIcon)
                        applyExcludeFromRecents(applicationContext, config.hideFromRecents)
                    }
                }
                // One collector above AnimatedContent: both screens exist briefly during a
                // transition and must not double-report the same failed flush.
                LaunchedEffect(session) {
                    session.persistFailures.collect { failure ->
                        Toast.makeText(
                            applicationContext,
                            getString(
                                when (failure) {
                                    SettingsPersistFailure.DOCUMENT ->
                                        R.string.toast_appearance_save_failed
                                    SettingsPersistFailure.CONFIG,
                                    SettingsPersistFailure.DIAGNOSTIC ->
                                        R.string.toast_setting_save_failed
                                }
                            ),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                var selectedTabName by rememberSaveable {
                    mutableStateOf(SettingsTab.OVERVIEW.name)
                }
                var showReport by rememberSaveable { mutableStateOf(false) }
                if (showReport) {
                    DiagnosticsScreen(onBack = { showReport = false })
                } else {
                    HomeScreen(
                        session = session,
                        showRestartResult = ::showRestartResult,
                        selectedTabName = selectedTabName,
                        onSelectTab = { selectedTabName = it },
                        onOpenDiagnostics = { showReport = true }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        // Pending debounced work must not publish after screen disposal.
        if (::session.isInitialized) session.dispose()
        super.onDestroy()
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

/** Inline surface appearance controls. They stay under the surface gate. */
@Composable
private fun SurfaceAppearanceSettings(session: SettingsSession, surface: String) {
    val context = LocalContext.current
    var activeChoice by remember { mutableStateOf<AodChoice?>(null) }
    var customColorKey by remember { mutableStateOf<String?>(null) }
    var customColorText by remember { mutableStateOf("#FFFFFF") }
    val document by session.document.collectAsState()
    val profile = document.profiles[surface] ?: SurfaceProfile()
    val renderConfig by session.config.collectAsState()
    val isAod = surface == SceneCompiler.SURFACE_AOD

    fun update(update: (SurfaceProfile) -> SurfaceProfile) {
        session.updateSelectedProfile(surface, update)
    }
    fun choose(kind: AodChoiceKind, values: List<String>, current: String, onSelect: (String) -> Unit) {
        activeChoice = AodChoice(kind, values, current, onSelect)
    }
    fun chooseColor(kind: AodChoiceKind, key: String, current: String) {
        choose(kind, if (key == "cardColor") CARD_COLOR_CHOICES else COLOR_CHOICES, current) { value ->
            if (value == "custom") {
                activeChoice = null
                customColorKey = key
                customColorText = if (key == "cardColor") {
                    profile.cardColor.takeIf { it.startsWith("#") } ?: "#151519"
                } else {
                    profile.palette[key]?.takeIf { it.startsWith("#") } ?: "#FFFFFF"
                }
            } else {
                update { currentProfile ->
                    if (key == "cardColor") currentProfile.copy(cardColor = value)
                    else currentProfile.withPaletteColor(key, value)
                }
            }
        }
    }

    SmallTitle(text = stringResource(R.string.section_appearance))
    if (!isAod || !renderConfig.suppressStockAodContent) {
    SettingsCard {
        AodChoiceRow(AodChoiceKind.POSITION, profile.anchor) {
            choose(AodChoiceKind.POSITION, listOf("below_stock_clock", "screen_center", "screen_top_safe", "screen_bottom_safe", "custom_vertical_bias"), profile.anchor) { value -> update { it.copy(anchor = value) } }
        }
        PercentSliderPreference(title = stringResource(R.string.choice_width), percent = (profile.widthFraction * 100).roundToInt(), range = 40..100, step = 1, onPercentChange = { value -> update { it.copy(widthFraction = value / 100f) } })
        if (profile.anchor == "custom_vertical_bias") {
            CanvasAnchorPreference(stringResource(R.string.choice_vertical_position), profile.verticalBias) { value -> update { it.copy(verticalBias = value) } }
        }
        PercentSliderPreference(title = stringResource(R.string.choice_height), percent = (profile.maxHeightFraction * 100).roundToInt(), range = 15..(if (isAod) 90 else 80), step = 1, onPercentChange = { value -> update { it.copy(maxHeightFraction = value / 100f) } })
        if (!isAod) AodChoiceRow(AodChoiceKind.OVERLAP, profile.collisionPolicy) {
            choose(AodChoiceKind.OVERLAP, listOf("avoid", "hide_scene"), profile.collisionPolicy) { value -> update { it.copy(collisionPolicy = value) } }
        }
    }
    }
    SmallTitle(text = stringResource(R.string.section_text_language))
    SettingsCard {
        AodChoiceRow(AodChoiceKind.TEXT_WEIGHT, profile.weight) {
            choose(AodChoiceKind.TEXT_WEIGHT, listOf("Regular", "Medium", "Bold"), profile.weight) { value -> update { it.copy(weight = value) } }
        }
        TextSizePreference(
            title = stringResource(R.string.setting_lyric_size),
            percent = effectiveTextSizePercent(profile),
            maxPercent = MAX_LYRIC_TEXT_SIZE_PERCENT,
            onDecrease = { update { it.copy(textSize = "custom", textSizeCustom = (effectiveTextSizePercent(it) - 5).coerceIn(50, MAX_LYRIC_TEXT_SIZE_PERCENT)) } },
            onIncrease = { update { it.copy(textSize = "custom", textSizeCustom = (effectiveTextSizePercent(it) + 5).coerceIn(50, MAX_LYRIC_TEXT_SIZE_PERCENT)) } }
        )
        AodChoiceRow(AodChoiceKind.FONT, profile.fontFamily) {
            choose(AodChoiceKind.FONT, listOf("noto", "spotify", "apple"), profile.fontFamily) { value -> update { it.copy(fontFamily = value) } }
        }
        AodChoiceRow(AodChoiceKind.ALIGNMENT, profile.alignment) {
            choose(AodChoiceKind.ALIGNMENT, listOf("auto", "start", "center", "end"), profile.alignment) { value -> update { it.copy(alignment = value) } }
        }
        AodChoiceRow(AodChoiceKind.SECONDARY_TEXT, profile.secondaryMode) {
            choose(AodChoiceKind.SECONDARY_TEXT, listOf("Main only", "Transliteration", "Translation", "Both"), profile.secondaryMode) { value -> update { it.copy(secondaryMode = value) } }
        }
        if (profile.secondaryMode != "Main only") {
            SwitchPreference(profile.secondaryTextBright, { value -> update { it.copy(secondaryTextBright = value) } }, stringResource(R.string.setting_bright_secondary_text))
        }
        if (isAod) SwitchPreference(profile.duetEnabled, { value -> update { it.copy(duetEnabled = value) } }, stringResource(R.string.setting_duet_display))
        SwitchPreference(profile.rubyVisible, { value -> update { it.copy(rubyVisible = value) } }, stringResource(R.string.setting_show_furigana))
        AodChoiceRow(AodChoiceKind.LONG_LINES, profile.overflow) {
            choose(AodChoiceKind.LONG_LINES, listOf("Wrap", "Clip"), profile.overflow) { value -> update { it.copy(overflow = value) } }
        }
        if (profile.overflow == "Wrap") {
            AodChoiceRow(AodChoiceKind.LYRIC_LINES, profile.lyricLineLimit.toString()) {
                choose(AodChoiceKind.LYRIC_LINES, listOf("1", "2", "3", "4", "5", "0"), profile.lyricLineLimit.toString()) { value -> update { it.copy(lyricLineLimit = value.toInt()) } }
            }
            SwitchPreference(profile.adaptiveSectioning, { value -> update { it.copy(adaptiveSectioning = value) } }, stringResource(R.string.setting_keep_phrases_together))
        }
    }
    SmallTitle(text = stringResource(R.string.section_song_information))
    SettingsCard {
        if (isAod) SwitchPreference(renderConfig.songChangeInfoEnabled, { value -> session.updateConfig { it.copy(songChangeInfoEnabled = value) } }, stringResource(R.string.setting_song_change_info))
        SwitchPreference(profile.metadataVisible, { value -> update { withMetadataVisible(it, value) } }, stringResource(R.string.setting_show_song_info))
        if (profile.metadataVisible) {
            AodChoiceRow(AodChoiceKind.SONG_INFO_POSITION, profile.metadataAnchor) {
                choose(AodChoiceKind.SONG_INFO_POSITION, listOf("top", "bottom"), profile.metadataAnchor) { value -> update { it.copy(metadataAnchor = value) } }
            }
            TextSizePreference(
                title = stringResource(R.string.setting_song_info_size),
                percent = profile.metadataSizePercent.coerceIn(50, 200),
                maxPercent = 200,
                onDecrease = { update { it.copy(metadataSizePercent = (it.metadataSizePercent - 5).coerceIn(50, 200)) } },
                onIncrease = { update { it.copy(metadataSizePercent = (it.metadataSizePercent + 5).coerceIn(50, 200)) } }
            )
        }

    }
    SmallTitle(text = stringResource(R.string.section_effects))
    SettingsCard {
        AodChoiceRow(AodChoiceKind.WORD_ANIMATION, profile.animation) {
            choose(AodChoiceKind.WORD_ANIMATION, listOf("Minimal", "Gradient"), profile.animation) { value -> update { it.copy(animation = value) } }
        }
        SwitchPreference(
            profile.glow == "On",
            { value -> update { it.copy(glow = if (value) "On" else "Off") } },
            stringResource(R.string.choice_glow)
        )
        AodChoiceRow(AodChoiceKind.LINE_PROGRESS, profile.lineSyncFillMode) {
            choose(AodChoiceKind.LINE_PROGRESS, listOf("None", "Top to bottom", "Left to right (main only)", "Left to right (whole block)"), profile.lineSyncFillMode) { value -> update { it.copy(lineSyncFillMode = value) } }
        }
        if (!isAod) IntSliderPreference(title = stringResource(R.string.choice_scene_transition_speed), value = profile.transition.durationMs, range = 150..600, step = 50, suffix = " ms", onValueChange = session::updateHandoffDuration)
    }
    SmallTitle(text = stringResource(R.string.section_colors))
    SettingsCard {
        AodChoiceRow(AodChoiceKind.LYRIC_COLOR, paletteChoice(profile.palette, "primaryText")) {
            chooseColor(AodChoiceKind.LYRIC_COLOR, "primaryText", paletteChoice(profile.palette, "primaryText"))
        }
        if (profile.metadataVisible) {
            AodChoiceRow(AodChoiceKind.METADATA_COLOR, paletteChoice(profile.palette, "metadataText")) {
                chooseColor(AodChoiceKind.METADATA_COLOR, "metadataText", paletteChoice(profile.palette, "metadataText"))
            }
        }
    }
    if (surface == SceneCompiler.SURFACE_LOCKSCREEN) {
        SmallTitle(text = stringResource(R.string.section_lockscreen_card))
        SettingsCard {
            val cardVisible = profile.backgroundStyle != "none"
            SwitchPreference(cardVisible, { value -> update { it.copy(backgroundStyle = if (value) "card" else "none") } }, stringResource(R.string.setting_show_lyric_card))
            if (cardVisible) {
                AodChoiceRow(AodChoiceKind.CARD_COLOR, profile.cardColor) {
                    chooseColor(AodChoiceKind.CARD_COLOR, "cardColor", profile.cardColor)
                }
                PercentSliderPreference(title = stringResource(R.string.setting_card_transparency), percent = ((1f - profile.cardAlpha) * 100).roundToInt().coerceIn(0, 100), range = 0..100, step = 5, onPercentChange = { value -> update { it.copy(cardAlpha = 1f - value / 100f) } })
            }
            val progressEnabled = profile.widgets.any { it.type == "media_progress" }
            SwitchPreference(progressEnabled, { value -> update { current ->
                val widgets = current.widgets.filterNot { it.type == "media_progress" }.toMutableList()
                if (value) widgets += com.eza.hyperglow.customization.WidgetSpec("media_progress", optional = true)
                current.copy(widgets = widgets)
            } }, stringResource(R.string.setting_show_playback_progress))
        }
    }
    customColorKey?.let { key ->
        WindowDialog(
            title = stringResource(R.string.setting_custom_color),
            show = true,
            onDismissRequest = { customColorKey = null }
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                ColorPicker(
                    initialHex = customColorText,
                    onColorChanged = { customColorText = it }
                )
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(32.dp).clip(CircleShape).background(customColorText.toComposeColorOrNull() ?: ComposeColor.Gray))
                    Spacer(Modifier.width(12.dp))
                    BasicTextField(
                        value = customColorText,
                        onValueChange = { customColorText = it.take(7) },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(color = MiuixTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        text = stringResource(R.string.action_apply),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                        enabled = customColorText.matches(Regex("#[0-9a-fA-F]{6}")),
                        onClick = {
                            update { currentProfile ->
                                if (key == "cardColor") currentProfile.copy(cardColor = customColorText.uppercase(Locale.ROOT))
                                else currentProfile.withPaletteColor(key, customColorText.uppercase(Locale.ROOT))
                            }
                            customColorKey = null
                        }
                    )
                }
            }
        }
    }
    activeChoice?.let { selected ->
        WindowDialog(title = stringResource(selected.kind.titleRes), show = true, onDismissRequest = { activeChoice = null }) {
            Column {
                selected.values.forEach { value ->
                    if (selected.kind == AodChoiceKind.LYRIC_COLOR || selected.kind == AodChoiceKind.METADATA_COLOR || selected.kind == AodChoiceKind.CARD_COLOR) {
                        ColorChoiceRow(value, selected.current, selected.kind, context) { selected.onSelect(value); activeChoice = null }
                    } else RadioButtonPreference(choiceDisplayLabel(context, selected.kind, value), selected.current == value, { selected.onSelect(value); activeChoice = null })
                }
            }
        }
    }
}

@Composable
private fun AodCanvasInlineSettings(session: SettingsSession) {
    val context = LocalContext.current
    val config by session.config.collectAsState()
    var activeChoice by remember { mutableStateOf<AodChoice?>(null) }
    SmallTitle(text = stringResource(R.string.setting_aod_canvas))
    SettingsCard {
        AodChoiceRow(AodChoiceKind.CANVAS_ORIENTATION, config.aodRotationMode) {
            activeChoice = AodChoice(
                AodChoiceKind.CANVAS_ORIENTATION,
                AOD_ROTATION_MODES,
                config.aodRotationMode
            ) { value -> session.updateConfig { it.copy(aodRotationMode = normalizeAodRotationMode(value), aodRotateWithDevice = value == AOD_ROTATION_MODE_AUTO) } }
        }
        if (config.aodRotationMode == AOD_ROTATION_MODE_AUTO) {
            AodChoiceRow(AodChoiceKind.ROTATION_SETTLE, config.aodRotationSettleMs.toString()) {
                activeChoice = AodChoice(AodChoiceKind.ROTATION_SETTLE, ROTATION_SETTLE_OPTIONS.map(Long::toString), config.aodRotationSettleMs.toString()) { value -> session.updateConfig { it.copy(aodRotationSettleMs = value.toLong()) } }
            }
        }
        if (config.aodRotationMode != AOD_ROTATION_MODE_PORTRAIT) {
            PercentSliderPreference(title = stringResource(R.string.setting_landscape_text_size), percent = (config.aodLandscapeTextScale * 100).roundToInt(), range = 50..200, step = 5, onPercentChange = { value -> session.updateConfig { it.copy(aodLandscapeTextScale = normalizeAodLandscapeTextScale(value / 100f)) } })
        }
        val document by session.document.collectAsState()
        val aodProfile = document.profiles[SceneCompiler.SURFACE_AOD] ?: SceneCompiler.safeAodProfile()
        SmallTitle(text = stringResource(R.string.section_canvas_size))
        PercentSliderPreference(title = stringResource(R.string.choice_width), percent = (aodProfile.widthFraction * 100).roundToInt(), range = 40..100, step = 1,
            onPercentChange = { value -> session.updateSelectedProfile(SceneCompiler.SURFACE_AOD) { it.copy(widthFraction = value / 100f) } })
        PercentSliderPreference(title = stringResource(R.string.choice_height), percent = (aodProfile.maxHeightFraction * 100).roundToInt(), range = 15..90, step = 1,
            onPercentChange = { value -> session.updateSelectedProfile(SceneCompiler.SURFACE_AOD) { it.copy(maxHeightFraction = value / 100f) } })
        SmallTitle(text = stringResource(R.string.section_canvas_position))
        CanvasAnchorPreference(stringResource(R.string.setting_canvas_anchor), config.aodCanvasAnchor) { value -> session.updateConfig { it.copy(aodCanvasAnchor = normalizeAodCanvasAnchor(value)) } }
        CanvasAnchorPreference(stringResource(R.string.setting_canvas_anchor_landscape), config.aodCanvasAnchorLandscape) { value -> session.updateConfig { it.copy(aodCanvasAnchorLandscape = normalizeAodCanvasAnchor(value)) } }
        SmallTitle(text = stringResource(R.string.section_canvas_padding))
        PercentSliderPreference(title = stringResource(R.string.setting_canvas_padding_portrait_x), percent = config.aodCanvasPaddingPortraitXPercent.roundToInt(), range = 0..20, step = 1, onPercentChange = { value -> session.updateConfig { it.copy(aodCanvasPaddingPortraitXPercent = normalizeAodCanvasPaddingPercent(value.toFloat())) } })
        PercentSliderPreference(title = stringResource(R.string.setting_canvas_padding_portrait_y), percent = config.aodCanvasPaddingPortraitYPercent.roundToInt(), range = 0..20, step = 1, onPercentChange = { value -> session.updateConfig { it.copy(aodCanvasPaddingPortraitYPercent = normalizeAodCanvasPaddingPercent(value.toFloat())) } })
        PercentSliderPreference(title = stringResource(R.string.setting_canvas_padding_landscape_x), percent = config.aodCanvasPaddingLandscapeXPercent.roundToInt(), range = 0..20, step = 1, onPercentChange = { value -> session.updateConfig { it.copy(aodCanvasPaddingLandscapeXPercent = normalizeAodCanvasPaddingPercent(value.toFloat())) } })
        PercentSliderPreference(title = stringResource(R.string.setting_canvas_padding_landscape_y), percent = config.aodCanvasPaddingLandscapeYPercent.roundToInt(), range = 0..20, step = 1, onPercentChange = { value -> session.updateConfig { it.copy(aodCanvasPaddingLandscapeYPercent = normalizeAodCanvasPaddingPercent(value.toFloat())) } })
    }
    activeChoice?.let { selected ->
        WindowDialog(title = stringResource(selected.kind.titleRes), show = true, onDismissRequest = { activeChoice = null }) {
            Column { selected.values.forEach { value -> RadioButtonPreference(aodCanvasChoiceLabel(context, selected.kind, value), selected.current == value, { selected.onSelect(value); activeChoice = null }) } }
        }
    }
}

@Composable
private fun HomeScreen(
    session: SettingsSession,
    showRestartResult: (Boolean) -> Unit,
    selectedTabName: String,
    onSelectTab: (String) -> Unit,
    onOpenDiagnostics: () -> Unit
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
    var updateAvailability by remember {
        mutableStateOf<UpdateAvailability>(UpdateAvailability.Unknown)
    }
    LaunchedEffect(Unit) {
        updateAvailability = UpdateChecker().refresh(context)
    }
    val config by session.config.collectAsState()
    val document by session.document.collectAsState()
    val capabilityReport by session.capabilityReport.collectAsState()
    val diagnosticLogging by session.diagnosticLogging.collectAsState()
    DisposableEffect(session) {
        val capabilityPrefs = context.getSharedPreferences(XiaomiCapabilityStore.PREFS, 0)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            session.updateCapabilityReport(XiaomiCapabilityStore.read(context))
        }
        capabilityPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { capabilityPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val aodEnabled = document.profiles[SceneCompiler.SURFACE_AOD]?.enabled
        ?: config.aodEnabled
    val lockscreenEnabled = document.profiles[SceneCompiler.SURFACE_LOCKSCREEN]?.enabled
        ?: config.lockscreenEnabled
    val keepAwake = config.keepAwake
    val keepAwakeUnsynced = config.keepAwakeUnsynced
    val keepAwakeDurationMs = config.keepAwakeDurationMs
    val lockscreenKeepAwake = config.lockscreenKeepAwake
    val raiseToAod = config.raiseToAod
    val suppressLockscreenEditorLongPress = config.suppressLockscreenEditorLongPress
    val positionFollowing = config.experimentalPositionFollowing
    val burnInPattern = config.burnInPattern
    val burnInIntervalMs = config.burnInIntervalMs
    val suppressStockAodContent = config.suppressStockAodContent
    val pauseLingerMs = config.pauseLingerMs
    val hideLauncherIcon = config.hideLauncherIcon
    val hideFromRecents = config.hideFromRecents
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

    val configExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // File I/O stays off the UI thread; the toast reports after the write settles.
        scope.launch {
            val written = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                        it.write(
                            ConfigBackupCodec.encode(
                                session.config.value,
                                session.document.value
                            )
                        )
                    } ?: error("Backup stream unavailable")
                }.isSuccess
            }
            Toast.makeText(
                context,
                context.getString(
                    if (written) R.string.toast_config_backup_exported
                    else R.string.toast_config_backup_write_failed
                ),
                Toast.LENGTH_LONG
            ).show()
        }
    }
    val configImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        input.readNBytes(ConfigBackupCodec.MAX_BYTES + 1)
                    }
                }.getOrNull()
            }
            val result = bytes?.let(ConfigBackupCodec::decode)
                ?: ConfigBackupDecodeResult.Rejected(ConfigBackupRejection.MALFORMED)
            when (result) {
                is ConfigBackupDecodeResult.Success -> {
                    // Adopt optimistically in memory, persist synchronously, and let the failure
                    // event roll memory back if the stores reject the write; its collector owns
                    // the failure toast, and the store applies component/task state from the
                    // persisted snapshot, so only success is reported here.
                    session.restore(result.preferences, result.customizationDocument)
                    val persisted = session.flushNow()
                    if (persisted) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.toast_config_backup_imported),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                is ConfigBackupDecodeResult.Rejected -> showInvalidBackupToast(context)
            }
        }
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
                    icon = LucideIcons.House,
                    label = stringResource(R.string.nav_overview)
                )
                FloatingNavigationBarItem(
                    selected = pagerState.currentPage == SettingsTab.LOCKSCREEN.ordinal,
                    onClick = {
                        scope.launch { pagerState.animateScrollToPage(SettingsTab.LOCKSCREEN.ordinal) }
                    },
                    icon = LucideIcons.Lock,
                    label = stringResource(R.string.nav_lockscreen)
                )
                FloatingNavigationBarItem(
                    selected = pagerState.currentPage == SettingsTab.AOD.ordinal,
                    onClick = {
                        scope.launch { pagerState.animateScrollToPage(SettingsTab.AOD.ordinal) }
                    },
                    icon = LucideIcons.MoonStar,
                    label = stringResource(R.string.nav_aod)
                )
                FloatingNavigationBarItem(
                    selected = pagerState.currentPage == SettingsTab.DIAGNOSTICS.ordinal,
                    onClick = {
                        scope.launch { pagerState.animateScrollToPage(SettingsTab.DIAGNOSTICS.ordinal) }
                    },
                    icon = LucideIcons.Info,
                    label = stringResource(R.string.nav_diagnostics)
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
                    item { SmallTitle(text = stringResource(R.string.section_runtime_status)) }
                    item { SettingsCard {
                        val source by com.eza.hyperglow.bridge.SpicyBridgeStore.state.collectAsState()
                        BasicComponent(title = stringResource(R.string.setting_source_connection),
                            summary = stringResource(if (source != null) R.string.status_source_connected else R.string.status_source_waiting))
                        BasicComponent(title = stringResource(R.string.setting_active_track),
                            summary = source?.let { "${it.title} · ${it.artist}" } ?: stringResource(R.string.status_no_track))
                        BasicComponent(title = stringResource(R.string.label_compatibility),
                            summary = supportStateLabel(context, supportState, capabilityReport.availableCapabilityCount, capabilityReport.totalCapabilityCount))
                    } }
                }

                SettingsTab.LOCKSCREEN -> {
                    item {
                        SurfaceSettingsPage(
                            session = session,
                            surface = SceneCompiler.SURFACE_LOCKSCREEN,
                            enabled = lockscreenEnabled,
                            supported = lockscreenSupported,
                            onEnabledChange = { enabled ->
                                if (lockscreenSupported) {
                                    session.updateSurfaceEnabled(SceneCompiler.SURFACE_LOCKSCREEN, enabled)
                                }
                            },
                            onRestoreDefaults = {
                                session.resetSurface(SceneCompiler.SURFACE_LOCKSCREEN)
                            },
                            behavior = {
                                SwitchPreference(
                                    lockscreenKeepAwake,
                                    { enabled ->
                                        session.updatePublishedConfig {
                                            it.copy(lockscreenKeepAwake = enabled)
                                        }
                                    },
                                    stringResource(R.string.setting_keep_lockscreen_awake),
                                    summary = stringResource(R.string.summary_keep_lockscreen_awake),
                                    enabled = lockscreenSupported
                                )
                                SwitchPreference(
                                    suppressLockscreenEditorLongPress,
                                    { enabled ->
                                        session.updatePublishedConfig {
                                            it.copy(suppressLockscreenEditorLongPress = enabled)
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
                        )
                    }
                }

                SettingsTab.AOD -> {
                    item {
                        SurfaceSettingsPage(
                            session = session,
                            surface = SceneCompiler.SURFACE_AOD,
                            enabled = aodEnabled,
                            supported = aodSupported,
                            onEnabledChange = { enabled ->
                                if (aodSupported) {
                                    session.updateSurfaceEnabled(SceneCompiler.SURFACE_AOD, enabled)
                                }
                            },
                            onRestoreDefaults = {
                                session.resetSurface(SceneCompiler.SURFACE_AOD)
                            },
                            behavior = {
                                SwitchPreference(
                                    keepAwake,
                                    { enabled -> session.updateConfig { it.copy(keepAwake = enabled) } },
                                    stringResource(R.string.setting_keep_aod_active),
                                    summary = if (aodSupported) {
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
                                    { enabled -> session.updateConfig { it.copy(keepAwakeUnsynced = enabled) } },
                                    stringResource(R.string.setting_keep_aod_unsynced),
                                    enabled = aodSupported && keepAwake
                                )
                                SwitchPreference(
                                    config.aodBrightnessOverride,
                                    { value -> session.updatePublishedConfig { it.copy(aodBrightnessOverride = value) } },
                                    stringResource(R.string.setting_aod_brightness_override),
                                    summary = stringResource(R.string.summary_aod_brightness_override),
                                    enabled = aodSupported
                                )
                                if (config.aodBrightnessOverride) {
                                    IntSliderPreference(title = stringResource(R.string.setting_aod_brightness_level),
                                        value = config.aodBrightnessLevel, range = 10..255, step = 1,
                                        onValueChange = { value -> session.updatePublishedConfig { it.copy(aodBrightnessLevel = value) } })
                                }
                                SwitchPreference(
                                    raiseToAod,
                                    { enabled ->
                                        session.updatePublishedConfig { it.copy(raiseToAod = enabled) }
                                    },
                                    stringResource(R.string.setting_raise_to_aod),
                                    enabled = raiseToAodSupported
                                )
                                SwitchPreference(
                                    suppressStockAodContent,
                                    { enabled -> session.updateConfig { it.copy(suppressStockAodContent = enabled) } },
                                    stringResource(R.string.setting_hide_stock_aod_content),
                                    enabled = aodSupported
                                )
                                if (!suppressStockAodContent) {
                                ArrowPreference(
                                    title = stringResource(R.string.setting_aod_clock_image),
                                    summary = if (positionFollowingSupported) {
                                        aodMovementLabel(context, positionFollowing, burnInPattern)
                                    } else {
                                        stringResource(R.string.summary_aod_placement_unsupported)
                                    },
                                    onClick = { if (positionFollowingSupported) showBurnInPatternDialog = true },
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
                        )
                    }
                }

                SettingsTab.DIAGNOSTICS -> {
                    (updateAvailability as? UpdateAvailability.UpdateAvailable)?.let { available ->
                        item { SettingsCard { ArrowPreference(
                            title = stringResource(R.string.update_available_title),
                            summary = stringResource(R.string.update_available_summary, available.latest.versionName, BuildConfig.VERSION_NAME),
                            onClick = { openExternalUrl(context, GITHUB_RELEASES_URL) }
                        ) } }
                    }

                    item { SmallTitle(text = stringResource(R.string.section_language)) }
                    item { SettingsCard {
                        ArrowPreference(
                            title = englishInterfaceLanguageLabel(context),
                            summary = uiLanguageLabel(context, currentUiLanguage(context)),
                            onClick = { showLanguageDialog = true },
                            startAction = { Icon(LucideIcons.Globe, contentDescription = null) }
                        )
                    } }
                    item { SmallTitle(text = stringResource(R.string.section_runtime_status)) }
                    item { SettingsCard {
                        SwitchPreference(
                            diagnosticLogging,
                            { enabled -> session.setDiagnosticLogging(enabled) },
                            stringResource(R.string.label_diagnostic_logging),
                            summary = if (BuildConfig.TRACE_LOGGING_AVAILABLE) stringResource(R.string.summary_diagnostic_logging_available) else stringResource(R.string.summary_diagnostic_logging_unavailable),
                            enabled = BuildConfig.TRACE_LOGGING_AVAILABLE
                        )
                        ArrowPreference(
                            title = stringResource(R.string.action_restart_systemui),
                            onClick = { showRestartDialog = true },
                            startAction = { Icon(LucideIcons.RefreshCw, contentDescription = null) }
                        )
                    } }
                    item { Spacer(Modifier.height(12.dp)) }
                    item { SettingsCard {
                        ArrowPreference(title = stringResource(R.string.action_report_problem), onClick = onOpenDiagnostics)
                        BasicComponent(
                            title = stringResource(R.string.label_systemui_aod),
                            summary = "${capabilityReport.systemUiVersion} / ${capabilityReport.aodVersion}",
                            startAction = { Icon(LucideIcons.Info, contentDescription = null) }
                        )
                    } }
                    item { SmallTitle(text = stringResource(R.string.section_playback_behavior)) }
                    item { SettingsCard {
                        ArrowPreference(
                            title = stringResource(R.string.setting_after_spotify_pauses),
                            summary = pauseLingerLabel(context, pauseLingerMs) + " · " + stringResource(R.string.summary_shared_surfaces),
                            onClick = { showPauseLingerDialog = true },
                            enabled = runtimeProfileAvailable && (aodSupported || lockscreenSupported),
                            startAction = { Icon(LucideIcons.Pause, contentDescription = null) }
                        )
                    } }
                    item { SmallTitle(text = stringResource(R.string.section_system_integration)) }
                    item { SettingsCard {
                        SwitchPreference(hideLauncherIcon, { value -> session.updateConfig { it.copy(hideLauncherIcon = value) } }, stringResource(R.string.setting_hide_launcher_icon))
                        SwitchPreference(hideFromRecents, { value -> session.updateConfig { it.copy(hideFromRecents = value) } }, stringResource(R.string.setting_hide_from_recents))
                        ArrowPreference(title = stringResource(R.string.action_export_config), onClick = { configExportLauncher.launch(CONFIG_BACKUP_FILE_NAME) })
                        ArrowPreference(title = stringResource(R.string.action_import_config), onClick = { configImportLauncher.launch(arrayOf("application/json")) })
                    } }
                    item { SmallTitle(text = stringResource(R.string.section_spotify_integration)) }
                    item { SettingsCard {
                        ArrowPreference(title = stringResource(R.string.action_download_spicy_ex), onClick = { openExternalUrl(context, SPICY_EX_GITHUB_URL) }, startAction = { Icon(LucideIcons.Download, contentDescription = null) })
                        ArrowPreference(title = stringResource(R.string.action_open_spotify), onClick = {
                            context.packageManager.getLaunchIntentForPackage("com.spotify.music")?.let(context::startActivity)
                                ?: Toast.makeText(context, context.getString(R.string.toast_spotify_not_installed), Toast.LENGTH_LONG).show()
                        })
                    } }
                    item { SmallTitle(text = stringResource(R.string.section_project)) }
                    item { SettingsCard {
                        BasicComponent(title = "HyperGlow", summary = "${BuildConfig.VERSION_NAME} · vC${BuildConfig.VERSION_CODE}")
                        ArrowPreference(title = stringResource(R.string.action_hyperglow_github), onClick = { openExternalUrl(context, GITHUB_URL) }, startAction = { Icon(LucideIcons.ExternalLink, contentDescription = null) })
                    } }
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
                        session.updateConfig {
                            it.copy(experimentalPositionFollowing = false)
                        }
                        showBurnInPatternDialog = false
                    }
                )
                BURN_IN_PATTERNS.forEach { value ->
                    RadioButtonPreference(
                        burnInPatternLabel(context, value),
                        positionFollowing && burnInPattern == value,
                        {
                            session.updateConfig {
                                it.copy(
                                    experimentalPositionFollowing = true,
                                    burnInPattern = value
                                )
                            }
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
                            session.updatePublishedConfig { it.copy(pauseLingerMs = value) }
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
                            session.updatePublishedConfig {
                                it.copy(keepAwakeDurationMs = value)
                            }
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
                            session.updateConfig { it.copy(burnInIntervalMs = value) }
                            showBurnInIntervalDialog = false
                        }
                    )
                }
            }
        }
    }

}

@Composable
private fun SurfaceSettingsPage(
    session: SettingsSession,
    surface: String,
    enabled: Boolean,
    supported: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onRestoreDefaults: () -> Unit,
    behavior: @Composable () -> Unit
) {
    val isAod = surface == SceneCompiler.SURFACE_AOD
    var confirmRestore by remember { mutableStateOf(false) }
    if (confirmRestore) {
        WindowDialog(title = stringResource(if (isAod) R.string.dialog_restore_aod_title else R.string.dialog_restore_lockscreen_title),
            show = true, onDismissRequest = { confirmRestore = false }) {
            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(text = stringResource(R.string.action_cancel), modifier = Modifier.weight(1f), onClick = { confirmRestore = false })
                Spacer(Modifier.width(12.dp))
                TextButton(text = stringResource(R.string.action_restore_surface_defaults), modifier = Modifier.weight(1f), colors = ButtonDefaults.textButtonColorsPrimary(), onClick = { onRestoreDefaults(); confirmRestore = false })
            }
        }
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        itemTitle(
            if (isAod) stringResource(R.string.surface_aod)
            else stringResource(R.string.surface_lockscreen)
        )
        SettingsCard {
            SwitchPreference(
                checked = enabled,
                onCheckedChange = onEnabledChange,
                title = if (isAod) {
                    stringResource(R.string.setting_show_aod)
                } else {
                    stringResource(R.string.setting_show_lockscreen)
                },
                summary = if (!supported) stringResource(if (isAod) R.string.summary_show_aod_unsupported else R.string.summary_show_lockscreen_unsupported) else null,
                enabled = supported,
                startAction = {
                    Icon(
                        if (isAod) LucideIcons.MoonStar else LucideIcons.Lock,
                        contentDescription = null
                    )
                }
            )
        }

        if (enabled) {
            SmallTitle(text = stringResource(if (isAod) R.string.section_aod_behavior else R.string.section_lockscreen_behavior))
            SettingsCard { behavior() }
            val surfaceConfig by session.config.collectAsState()
            if (isAod && surfaceConfig.suppressStockAodContent) AodCanvasInlineSettings(session)
            SurfaceAppearanceSettings(session = session, surface = surface)
            Spacer(Modifier.height(12.dp))
            SettingsCard {
                TextButton(
                    text = stringResource(R.string.action_restore_surface_defaults),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { confirmRestore = true }
                )
            }
        }
    }
}

@Composable
private fun itemTitle(text: String) {
    SmallTitle(text = text)
}

@Composable
private fun AodCanvasScreen(
    session: SettingsSession,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var showRotationModeDialog by remember { mutableStateOf(false) }
    var showRotationSettleDialog by remember { mutableStateOf(false) }
    val config by session.config.collectAsState()
    val aodRotationMode = config.aodRotationMode
    val aodCanvasAnchor = config.aodCanvasAnchor
    val aodRotationSettleMs = config.aodRotationSettleMs
    val aodCanvasAnchorLandscape = config.aodCanvasAnchorLandscape
    val aodLandscapeTextScale = config.aodLandscapeTextScale
    val aodCanvasPaddingPortraitXPercent = config.aodCanvasPaddingPortraitXPercent
    val aodCanvasPaddingPortraitYPercent = config.aodCanvasPaddingPortraitYPercent
    val aodCanvasPaddingLandscapeXPercent = config.aodCanvasPaddingLandscapeXPercent
    val aodCanvasPaddingLandscapeYPercent = config.aodCanvasPaddingLandscapeYPercent

    BackHandler(
        enabled = !showRotationModeDialog && !showRotationSettleDialog,
        onBack = onBack
    )
    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(R.string.setting_aod_canvas),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            LucideIcons.ChevronLeft,
                            contentDescription = stringResource(R.string.action_back)
                        )
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
            item { SmallTitle(text = stringResource(R.string.section_canvas_orientation)) }
            item {
                SettingsCard {
                    ArrowPreference(
                        title = stringResource(R.string.setting_aod_rotation_mode),
                        summary = aodRotationModeLabel(context, aodRotationMode),
                        onClick = { showRotationModeDialog = true }
                    )
                    if (aodRotationMode == AOD_ROTATION_MODE_AUTO) {
                        ArrowPreference(
                            title = stringResource(R.string.setting_rotation_settle),
                            summary = rotationSettleLabel(context, aodRotationSettleMs),
                            onClick = { showRotationSettleDialog = true }
                        )
                        PercentSliderPreference(
                            title = stringResource(
                                R.string.setting_landscape_text_size
                            ),
                            percent = (aodLandscapeTextScale * 100).roundToInt(),
                            range = 50..200,
                            onPercentChange = { percent ->
                                session.updateConfig {
                                    it.copy(
                                        aodLandscapeTextScale =
                                            normalizeAodLandscapeTextScale(
                                                percent / 100f
                                            )
                                    )
                                }
                            }
                        )
                    }
                }
            }
            item { SmallTitle(text = stringResource(R.string.section_canvas_position)) }
            item {
                SettingsCard {
                    CanvasAnchorPreference(
                        title = stringResource(R.string.setting_canvas_anchor),
                        anchor = aodCanvasAnchor,
                        onAnchorChange = { value ->
                            session.updateConfig {
                                it.copy(
                                    aodCanvasAnchor =
                                        normalizeAodCanvasAnchor(value)
                                )
                            }
                        }
                    )
                    CanvasAnchorPreference(
                        title = stringResource(
                            R.string.setting_canvas_anchor_landscape
                        ),
                        anchor = aodCanvasAnchorLandscape,
                        onAnchorChange = { value ->
                            session.updateConfig {
                                it.copy(
                                    aodCanvasAnchorLandscape =
                                        normalizeAodCanvasAnchor(value)
                                )
                            }
                        }
                    )
                }
            }
            item { SmallTitle(text = stringResource(R.string.section_canvas_padding)) }
            item {
                SettingsCard {
                    PercentSliderPreference(
                        title = stringResource(
                            R.string.setting_canvas_padding_portrait_x
                        ),
                        percent = aodCanvasPaddingPortraitXPercent.roundToInt(),
                        range = 0..20,
                        step = 1,
                        onPercentChange = { percent ->
                            session.updateConfig {
                                it.copy(
                                    aodCanvasPaddingPortraitXPercent =
                                        normalizeAodCanvasPaddingPercent(
                                            percent.toFloat()
                                        )
                                )
                            }
                        }
                    )
                    PercentSliderPreference(
                        title = stringResource(
                            R.string.setting_canvas_padding_portrait_y
                        ),
                        percent = aodCanvasPaddingPortraitYPercent.roundToInt(),
                        range = 0..20,
                        step = 1,
                        onPercentChange = { percent ->
                            session.updateConfig {
                                it.copy(
                                    aodCanvasPaddingPortraitYPercent =
                                        normalizeAodCanvasPaddingPercent(
                                            percent.toFloat()
                                        )
                                )
                            }
                        }
                    )
                    PercentSliderPreference(
                        title = stringResource(
                            R.string.setting_canvas_padding_landscape_x
                        ),
                        percent = aodCanvasPaddingLandscapeXPercent.roundToInt(),
                        range = 0..20,
                        step = 1,
                        onPercentChange = { percent ->
                            session.updateConfig {
                                it.copy(
                                    aodCanvasPaddingLandscapeXPercent =
                                        normalizeAodCanvasPaddingPercent(
                                            percent.toFloat()
                                        )
                                )
                            }
                        }
                    )
                    PercentSliderPreference(
                        title = stringResource(
                            R.string.setting_canvas_padding_landscape_y
                        ),
                        percent = aodCanvasPaddingLandscapeYPercent.roundToInt(),
                        range = 0..20,
                        step = 1,
                        onPercentChange = { percent ->
                            session.updateConfig {
                                it.copy(
                                    aodCanvasPaddingLandscapeYPercent =
                                        normalizeAodCanvasPaddingPercent(
                                            percent.toFloat()
                                        )
                                )
                            }
                        }
                    )
                }
            }
        }
    }

    if (showRotationModeDialog) {
        WindowDialog(
            title = stringResource(R.string.setting_aod_rotation_mode),
            summary = stringResource(R.string.summary_aod_rotation_mode),
            show = true,
            onDismissRequest = { showRotationModeDialog = false }
        ) {
            Column {
                AOD_ROTATION_MODES.forEach { value ->
                    RadioButtonPreference(
                        aodRotationModeLabel(context, value),
                        aodRotationMode == value,
                        {
                            session.updateConfig {
                                it.copy(
                                    aodRotationMode = normalizeAodRotationMode(value),
                                    aodRotateWithDevice = value == AOD_ROTATION_MODE_AUTO
                                )
                            }
                            showRotationModeDialog = false
                        }
                    )
                }
            }
        }
    }

    if (showRotationSettleDialog) {
        WindowDialog(
            title = stringResource(R.string.setting_rotation_settle),
            show = true,
            onDismissRequest = { showRotationSettleDialog = false }
        ) {
            Column {
                ROTATION_SETTLE_OPTIONS.forEach { value ->
                    RadioButtonPreference(
                        rotationSettleLabel(context, value),
                        aodRotationSettleMs == value,
                        {
                            session.updateConfig { it.copy(aodRotationSettleMs = value) }
                            showRotationSettleDialog = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
internal fun SettingsCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth()
    ) {
        // animateContentSize gives conditional rows inside the card a placement transition
        // instead of jumping the rest of the list under the finger.
        Column(modifier = Modifier.animateContentSize()) { content() }
    }
}

private fun String.isStaticClockPlacement(): Boolean =
    this == "static_top" || this == "static_bottom"

private enum class SettingsTab {
    OVERVIEW,
    LOCKSCREEN,
    AOD,
    DIAGNOSTICS
}

private const val DIAGNOSTICS_DESTINATION = "__diagnostics__"
private const val AOD_CANVAS_DESTINATION = "__aod_canvas__"
private const val GITHUB_URL = "https://github.com/amarinne/hyperglow"
private const val CONFIG_BACKUP_FILE_NAME = "hyperglow-config-backup.json"
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

private fun rotationSettleLabel(context: android.content.Context, value: Long): String =
    context.getString(
        when (value) {
            0L -> R.string.rotation_settle_instant
            500L -> R.string.rotation_settle_half_second
            2_000L -> R.string.rotation_settle_2_seconds
            5_000L -> R.string.duration_5_seconds
            10_000L -> R.string.duration_10_seconds
            else -> R.string.rotation_settle_1_second
        }
    )

private val ROTATION_SETTLE_OPTIONS = listOf(0L, 500L, 1_000L, 2_000L, 5_000L, 10_000L)

private fun aodRotationModeLabel(context: android.content.Context, value: String): String =
    context.getString(
        when (normalizeAodRotationMode(value)) {
            AOD_ROTATION_MODE_LANDSCAPE -> R.string.rotation_mode_landscape
            AOD_ROTATION_MODE_LANDSCAPE_REVERSE -> R.string.rotation_mode_landscape_reverse
            AOD_ROTATION_MODE_AUTO -> R.string.rotation_mode_auto
            else -> R.string.rotation_mode_portrait
        }
    )

private val AOD_ROTATION_MODES = listOf(
    AOD_ROTATION_MODE_PORTRAIT,
    AOD_ROTATION_MODE_LANDSCAPE,
    AOD_ROTATION_MODE_LANDSCAPE_REVERSE,
    AOD_ROTATION_MODE_AUTO
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
    session: SettingsSession,
    initialSurface: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var activeChoice by remember { mutableStateOf<AodChoice?>(null) }
    var showResetDialog by remember { mutableStateOf(false) }
    val document by session.document.collectAsState()
    val config by session.config.collectAsState()
    // The in-memory document is the source of truth; the editor derives its state from it and
    // mutates it directly. Persistence is the session's debounced background flush.
    val editorState = CustomizationEditorState(document, initialSurface)
    val songChangeInfo = config.songChangeInfoEnabled

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val raw = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val bytes = input.readNBytes(SceneCompiler.MAX_CONFIG_BYTES + 1)
                        if (bytes.size > SceneCompiler.MAX_CONFIG_BYTES) {
                            error("Appearance file too large")
                        }
                        bytes.toString(Charsets.UTF_8)
                    } ?: error("Appearance file unavailable")
                }.getOrNull()
            }
            val canonicalized = raw?.let { text ->
                withContext(Dispatchers.Default) {
                    SceneCompiler.decodeDocument(text)?.let(
                        CustomizationRepository::canonicalizeDocument
                    )
                }
            }
            if (canonicalized == null) {
                Toast.makeText(
                    context,
                    context.getString(R.string.toast_appearance_invalid),
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            session.restore(session.config.value, canonicalized)
            // The persistFailures collector owns the failure toast; only success reports here.
            val persisted = session.flushNow()
            if (persisted) {
                Toast.makeText(
                    context,
                    context.getString(R.string.toast_appearance_imported),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val written = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                        it.write(SceneCompiler.json.encodeToString(session.document.value))
                    } ?: error("Appearance file unavailable")
                }.isSuccess
            }
            Toast.makeText(
                context,
                context.getString(
                    if (written) R.string.toast_appearance_exported
                    else R.string.toast_appearance_export_failed
                ),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    BackHandler(enabled = activeChoice == null && !showResetDialog, onBack = onBack)

    fun updateSelected(updateProfile: (SurfaceProfile) -> SurfaceProfile) {
        // Memory-only; the debounced background flush persists and publishes.
        session.updateSelectedProfile(initialSurface, updateProfile)
    }

    LaunchedEffect(Unit) {
        session.disableLinkSurfaces()
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
                        Icon(
                            LucideIcons.ChevronLeft,
                            contentDescription = stringResource(R.string.action_back)
                        )
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
                    PercentSliderPreference(
                        title = stringResource(R.string.choice_width),
                        percent = (selectedProfile.widthFraction * 100).roundToInt(),
                        range = 40..100,
                        step = 1,
                        onPercentChange = { percent ->
                            updateSelected { it.copy(widthFraction = percent / 100f) }
                        }
                    )
                    if (selectedProfile.anchor == "custom_vertical_bias") {
                        CanvasAnchorPreference(
                            title = stringResource(R.string.choice_vertical_position),
                            anchor = selectedProfile.verticalBias,
                            onAnchorChange = { value -> updateSelected { it.copy(verticalBias = value) } }
                        )
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
                        selectedProfile.duetEnabled,
                        { enabled -> updateSelected { it.copy(duetEnabled = enabled) } },
                        stringResource(R.string.setting_duet_display)
                    )
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
                            session.updateConfig {
                                it.copy(songChangeInfoEnabled = enabled)
                            }
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
                            maxPercent = 200,
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
                            maxPercent = MAX_LYRIC_TEXT_SIZE_PERCENT,
                        onDecrease = {
                            updateSelected {
                                it.copy(
                                    textSize = "custom",
                                        textSizeCustom = (effectiveTextSizePercent(it) - 5).coerceIn(50, MAX_LYRIC_TEXT_SIZE_PERCENT)
                                )
                            }
                        },
                        onIncrease = {
                            updateSelected {
                                it.copy(
                                    textSize = "custom",
                                        textSizeCustom = (effectiveTextSizePercent(it) + 5).coerceIn(50, MAX_LYRIC_TEXT_SIZE_PERCENT)
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
                    SwitchPreference(
                        selectedProfile.glow == "On",
                        { enabled ->
                            updateSelected { it.copy(glow = if (enabled) "On" else "Off") }
                        },
                        stringResource(R.string.choice_glow)
                    )
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
                    IntSliderPreference(
                        title = stringResource(R.string.choice_scene_transition_speed),
                        value = selectedProfile.transition.durationMs,
                        range = 150..600,
                        step = 10,
                        suffix = " ms",
                        onValueChange = { value ->
                            updateSelected {
                                it.copy(transition = it.transition.copy(durationMs = value))
                            }
                        }
                    )
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
                            stringResource(R.string.setting_show_playback_progress)
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
                            title = stringResource(R.string.action_restore_surface_default),
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
            title = stringResource(
                if (initialSurface == SceneCompiler.SURFACE_AOD) {
                    R.string.dialog_restore_aod_title
                } else {
                    R.string.dialog_restore_lockscreen_title
                }
            ),
            summary =
                stringResource(R.string.dialog_restore_surface_summary),
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
                        // Optimistic in-memory reset; the failure event reports a rejected write
                        // and rolls the editor back to the persisted document.
                        session.resetSurface(initialSurface)
                        Toast.makeText(
                            context,
                            context.getString(R.string.toast_settings_restored),
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

private fun resolveColorSample(value: String): ComposeColor? = when (value) {
    "white" -> ComposeColor.White
    "lavender" -> ComposeColor(0xFFB9A7FF)
    "mint" -> ComposeColor(0xFF9DE7C2)
    "black" -> ComposeColor.Black
    "charcoal" -> ComposeColor(0xFF303035)
    "deep_purple" -> ComposeColor(0xFF4A315E)
    else -> value.toComposeColorOrNull()
}

@Composable
private fun ColorChoiceRow(
    value: String,
    current: String,
    kind: AodChoiceKind,
    context: android.content.Context,
    onClick: () -> Unit
) {
    val sample = if (value == "custom") resolveColorSample(current) else resolveColorSample(value)
    BasicComponent(
        title = choiceDisplayLabel(context, kind, value),
        summary = if (current == value) context.getString(R.string.option_selected) else null,
        endActions = {
            Box(Modifier.padding(end = 20.dp).size(24.dp).clip(CircleShape).background(sample ?: MiuixTheme.colorScheme.onSurface.copy(alpha = 0.45f)))
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
}

@Composable
private fun ColorPicker(initialHex: String, onColorChanged: (String) -> Unit) {
    val initial = initialHex.toComposeColorOrNull() ?: ComposeColor.White
    val hsv = remember(initialHex) { FloatArray(3).also { android.graphics.Color.colorToHSV(initial.toArgb(), it) } }
    var hue by remember(initialHex) { mutableStateOf(hsv[0]) }
    var saturation by remember(initialHex) { mutableStateOf(hsv[1]) }
    var value by remember(initialHex) { mutableStateOf(hsv[2]) }
    fun publish() {
        onColorChanged(String.format(Locale.ROOT, "#%06X", android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, value)) and 0xFFFFFF))
    }
    Column {
        Canvas(Modifier.fillMaxWidth().height(180.dp).pointerInput(Unit) {
            detectDragGestures(
                onDragStart = { point -> saturation = (point.x / size.width).coerceIn(0f, 1f); value = (1f - point.y / size.height).coerceIn(0f, 1f); publish() },
                onDrag = { change, _ -> change.consume(); saturation = (change.position.x / size.width).coerceIn(0f, 1f); value = (1f - change.position.y / size.height).coerceIn(0f, 1f); publish() }
            )
        }) {
            drawRect(Brush.horizontalGradient(listOf(ComposeColor.White, ComposeColor(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f))))))
            drawRect(Brush.verticalGradient(listOf(ComposeColor.Transparent, ComposeColor.Black)))
            val cursor = androidx.compose.ui.geometry.Offset(saturation * size.width, (1f - value) * size.height)
            drawCircle(ComposeColor(android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, value))), radius = 14.dp.toPx(), center = cursor)
            drawCircle(ComposeColor.White, radius = 14.dp.toPx(), center = cursor, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx()))
            drawCircle(ComposeColor.Black, radius = 17.dp.toPx(), center = cursor, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()))
        }
        Canvas(Modifier.fillMaxWidth().height(28.dp).padding(vertical = 6.dp).pointerInput(Unit) {
            detectDragGestures(
                onDragStart = { point -> hue = (point.x / size.width).coerceIn(0f, 1f) * 360f; publish() },
                onDrag = { change, _ -> change.consume(); hue = (change.position.x / size.width).coerceIn(0f, 1f) * 360f; publish() }
            )
        }) {
            drawRect(Brush.horizontalGradient((0..6).map { ComposeColor(android.graphics.Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f))) }))
            val x = (hue / 360f).coerceIn(0f, 1f) * size.width
            drawLine(ComposeColor.Black, androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Offset(x, size.height), strokeWidth = 2.dp.toPx())
            drawCircle(ComposeColor.White, radius = 7.dp.toPx(), center = androidx.compose.ui.geometry.Offset(x, size.height / 2f))
            drawCircle(ComposeColor.Black, radius = 7.dp.toPx(), center = androidx.compose.ui.geometry.Offset(x, size.height / 2f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()))
        }
    }
}

private fun String.toComposeColorOrNull(): ComposeColor? = runCatching {
    if (!matches(Regex("#[0-9a-fA-F]{6}"))) return null
    ComposeColor(android.graphics.Color.parseColor(this))
}.getOrNull()

@Composable
private fun AodChoiceRow(kind: AodChoiceKind, value: String, onClick: () -> Unit) {
    val context = LocalContext.current
    val colorSample = if (kind == AodChoiceKind.LYRIC_COLOR || kind == AodChoiceKind.METADATA_COLOR || kind == AodChoiceKind.CARD_COLOR) {
        resolveColorSample(value)
    } else null
    ArrowPreference(
        title = stringResource(kind.titleRes),
        summary = choiceDisplayLabel(context, kind, value),
        endActions = {
            if (colorSample != null) {
                Box(
                    Modifier
                        .padding(end = 8.dp)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(colorSample)
                )
            }
        },
        onClick = onClick
    )
}

@Composable
private fun TextSizePreference(
    title: String,
    percent: Int,
    maxPercent: Int = MAX_LYRIC_TEXT_SIZE_PERCENT,
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
                Icon(
                    LucideIcons.Minus,
                    contentDescription = stringResource(R.string.action_decrease)
                )
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
                enabled = percent < maxPercent,
                backgroundColor = MiuixTheme.colorScheme.surfaceContainerHighest,
                cornerRadius = 24.dp,
                minHeight = 48.dp,
                minWidth = 48.dp
            ) {
                Icon(
                    LucideIcons.Plus,
                    contentDescription = stringResource(R.string.action_increase)
                )
            }
        }
    )
}

@Composable
private fun IntSliderPreference(
    title: String,
    value: Int,
    range: IntRange,
    step: Int,
    suffix: String = "",
    onValueChange: (Int) -> Unit
) {
    BasicComponent(
        title = title,
        summary = "${value.coerceIn(range)}$suffix",
        endActions = {
            Slider(
                value = value.coerceIn(range).toFloat(),
                onValueChange = { onValueChange(it.roundToInt().coerceIn(range)) },
                modifier = Modifier.width(150.dp),
                valueRange = range.first.toFloat()..range.last.toFloat(),
                steps = ((range.last - range.first) / step - 1).coerceAtLeast(0)
            )
        }
    )
}

@Composable
private fun CanvasAnchorPreference(
    title: String,
    anchor: Float,
    onAnchorChange: (Float) -> Unit
) {
    BasicComponent(
        title = title,
        summary = "${(anchor * 100).roundToInt()}%",
        endActions = {
            Slider(
                value = anchor,
                onValueChange = onAnchorChange,
                modifier = Modifier.width(150.dp),
                valueRange = 0f..1f,
                steps = 19
            )
        }
    )
}

@Composable
private fun PercentSliderPreference(
    title: String,
    percent: Int,
    range: IntRange,
    onPercentChange: (Int) -> Unit,
    step: Int = 5
) {
    BasicComponent(
        title = title,
        summary = "$percent%",
        endActions = {
            Slider(
                value = percent.toFloat(),
                onValueChange = { onPercentChange(it.roundToInt()) },
                modifier = Modifier.width(150.dp),
                valueRange = range.first.toFloat()..range.last.toFloat(),
                steps = (range.last - range.first) / step - 1
            )
        }
    )
}

private fun effectiveTextSizePercent(profile: SurfaceProfile): Int = when (profile.textSize) {
    "small" -> 90
    "large" -> 120
    "xlarge" -> 150
    "custom" -> profile.textSizeCustom.coerceIn(50, MAX_LYRIC_TEXT_SIZE_PERCENT)
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
    AodChoiceKind.CANVAS_ORIENTATION,
    AodChoiceKind.ROTATION_SETTLE -> aodCanvasChoiceLabel(context, kind, value)
    AodChoiceKind.LYRIC_COLOR,
    AodChoiceKind.METADATA_COLOR,
    AodChoiceKind.CARD_COLOR -> if (value.startsWith("#")) value else context.getString(when (value) {
        "dimmed" -> R.string.option_dimmed
        "white" -> R.string.option_white
        "lavender" -> R.string.option_lavender
        "mint" -> R.string.option_mint
        "custom" -> R.string.option_custom
        "charcoal" -> R.string.option_charcoal
        "deep_purple" -> R.string.option_deep_purple
        "black" -> R.string.option_black
        else -> if (value.startsWith("#")) R.string.option_custom else R.string.option_default
    })
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
    LYRIC_COLOR(R.string.choice_lyric_color),
    METADATA_COLOR(R.string.choice_metadata_color),
    CARD_COLOR(R.string.choice_card_color),
    CANVAS_ORIENTATION(R.string.setting_aod_rotation_mode),
    ROTATION_SETTLE(R.string.setting_rotation_settle),
    TRANSITION_SPEED(R.string.choice_scene_transition_speed)
}

private fun aodCanvasChoiceLabel(context: android.content.Context, kind: AodChoiceKind, value: String): String = when (kind) {
    AodChoiceKind.CANVAS_ORIENTATION -> aodRotationModeLabel(context, value)
    AodChoiceKind.ROTATION_SETTLE -> rotationSettleLabel(context, value.toLongOrNull() ?: 1_000L)
    else -> value
}

private data class AodChoice(
    val kind: AodChoiceKind,
    val values: List<String>,
    val current: String,
    val onSelect: (String) -> Unit
)

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

private val COLOR_CHOICES = listOf("default", "white", "lavender", "mint", "custom")
private val CARD_COLOR_CHOICES = listOf("black", "charcoal", "deep_purple", "custom")

private fun paletteChoice(palette: Map<String, String>, key: String): String =
    palette[key]?.takeIf { it.startsWith("#") || it == "dimmed" || it in COLOR_CHOICES } ?: "default"

private fun SurfaceProfile.withPaletteColor(key: String, value: String): SurfaceProfile {
    val next = palette.toMutableMap()
    val keys = if (key == "primaryText") listOf("primaryText", "secondaryText", "sungText", "unsungText", "glow") else listOf(key)
    keys.forEach { if (value == "default") next.remove(it) else next[it] = value }
    return copy(palette = next)
}

internal fun palettePreset(name: String): Map<String, String> =
    if (name == "dimmed") SEMANTIC_PALETTE_KEYS.associateWith { "dimmed" } else emptyMap()

internal fun palettePresetName(palette: Map<String, String>): String =
    if (palette.isNotEmpty() && palette.values.all { it == "dimmed" }) "dimmed" else "default"

private fun showInvalidBackupToast(context: android.content.Context) {
    Toast.makeText(
        context,
        context.getString(R.string.toast_config_backup_invalid),
        Toast.LENGTH_LONG
    ).show()
}
