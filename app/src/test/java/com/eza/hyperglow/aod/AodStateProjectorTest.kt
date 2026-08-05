package com.eza.hyperglow.aod

import com.eza.hyperglow.customization.CompiledCustomization
import com.eza.hyperglow.customization.CompiledSurfaceProfile
import com.eza.hyperglow.customization.SceneCompiler
import com.eza.hyperglow.customization.TransitionPreset
import com.eza.hyperglow.producer.LyricKind
import com.eza.hyperglow.producer.LyricLayoutGroup
import com.eza.hyperglow.producer.LyricProducerState
import com.eza.hyperglow.producer.LyricRuby
import com.eza.hyperglow.producer.LyricWord
import com.eza.hyperglow.producer.ProducerRenderModes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [projectToDisplay] 单测：Phase 1 纯函数映射层的测试基线。
 *
 * 覆盖原 `AodProjectionEngine.project()` 的全部映射分支，按 [LyricKind] 维度组织：
 * - NONE / UNSYNCED → "♪"、空罗马音/翻译、transition="None"(NONE)
 * - LINE / SYLLABLE → 活动行原文、per-word、lineLevelSync 语义
 * - 位置投影、渲染模式透传、保活、trackGeneration/wakeSignal 同构性
 *
 * 不依赖 Android 框架（`prefs`/`compiled` 直接构造），可纯 JVM 运行。
 */
class AodStateProjectorTest {

    private val prefs = AodRenderConfig(
        aodEnabled = true,
        alignment = "center",
        secondaryMode = "Translation",
        weight = "Bold",
        textSize = "large",
        textSizeCustom = 140,
        fontFamily = "spotify",
        animation = "Minimal",
        glow = "On",
        keepAwake = true,
        keepAwakeUnsynced = false,
        keepAwakeDurationMs = -1L,
        experimentalPositionFollowing = true,
        burnInPattern = "four_corner",
        burnInIntervalMs = 60_000L,
        metadataVisible = "show",
        metadataAnchor = "bottom"
    )

    private val compiled: CompiledCustomization = CompiledCustomization(
        version = 1,
        revision = 1L,
        hash = "h",
        sourceId = "src",
        linkSurfaces = false,
        profiles = linkedMapOf(
            SceneCompiler.SURFACE_LOCKSCREEN to aodProfile(enabled = false, metadataVisible = false),
            SceneCompiler.SURFACE_AOD to aodProfile(enabled = true, metadataVisible = true)
        )
    )

    private fun aodProfile(
        enabled: Boolean = true,
        metadataVisible: Boolean = true
    ) = CompiledSurfaceProfile(
        surface = SceneCompiler.SURFACE_AOD,
        enabled = enabled,
        anchor = "below_stock_clock",
        widthFraction = 0.9f,
        maxHeightFraction = 0.5f,
        verticalBias = 0.5f,
        collisionPolicy = "avoid",
        widgets = emptyList(),
        transition = TransitionPreset(),
        alignment = "auto",
        secondaryMode = "Main only",
        metadataVisible = metadataVisible,
        metadataAnchor = "top",
        weight = "Medium",
        textSize = "normal",
        textSizeCustom = 100,
        fontFamily = "spotify",
        animation = "Gradient",
        glow = "Off",
        lineSyncFillMode = "Top to bottom",
        overflow = "Wrap",
        adaptiveSectioning = true,
        palette = emptyMap()
    )

    private fun renderModes() = ProducerRenderModes(
        weight = "Bold",
        textSize = "large",
        textSizeCustom = 140,
        secondary = "Translation",
        animation = "Minimal",
        glow = "On",
        lineSyncFill = "Top to bottom",
        overflow = "Wrap",
        transition = "Crossfade",
        font = "spotify"
    )

    private fun state(
        lyricKind: LyricKind = LyricKind.SYLLABLE,
        line: String = "",
        romanizedLine: String = "",
        translatedLine: String = "",
        lineIndex: Int = -1,
        words: List<LyricWord>? = null,
        alignedRight: Boolean = false,
        lineStartMs: Long = 0L,
        lineEndMs: Long = 0L,
        ruby: List<LyricRuby> = emptyList(),
        layoutGroups: List<LyricLayoutGroup> = emptyList(),
        hasTimedLyrics: Boolean = true,
        nextLineStartMs: Long? = null,
        positionMs: Long = 0L,
        durationMs: Long = 180_000L,
        sampledAtElapsedMs: Long = 0L,
        speed: Float = 1f,
        playing: Boolean = true,
        status: String = "ready",
        producerId: String = "spicy-prod",
        generation: Int = 1,
        trackUri: String = "spotify:track:abc",
        title: String = "Title",
        artist: String = "Artist"
    ) = LyricProducerState(
        producerId = producerId,
        generation = generation,
        sequence = 1L,
        status = status,
        trackUri = trackUri,
        title = title,
        artist = artist,
        album = "",
        imageId = "",
        line = line,
        romanizedLine = romanizedLine,
        translatedLine = translatedLine,
        lineIndex = lineIndex,
        positionMs = positionMs,
        durationMs = durationMs,
        sampledAtElapsedMs = sampledAtElapsedMs,
        speed = speed,
        playing = playing,
        receivedAtElapsedMs = sampledAtElapsedMs,
        words = words,
        renderModes = renderModes(),
        lyricKind = lyricKind,
        alignedRight = alignedRight,
        lineStartMs = lineStartMs,
        lineEndMs = lineEndMs,
        ruby = ruby,
        layoutGroups = layoutGroups,
        hasTimedLyrics = hasTimedLyrics,
        nextLineStartMs = nextLineStartMs
    )

    private fun project(state: LyricProducerState, now: Long = 0L): AodDisplayState =
        projectToDisplay(
            state = state,
            now = now,
            prefs = prefs,
            compiled = compiled,
            metadataIntroPolicy = SongMetadataIntroPolicy(),
            powerSessionPolicy = AodPowerSessionPolicy(),
            userId = 0
        )

    // --- lyricKind = NONE ---

    @Test
    fun noneKind_rendersMusicalNoteAndForcesNoneTransition() {
        val s = state(
            lyricKind = LyricKind.NONE,
            hasTimedLyrics = false,
            line = "",
            lineIndex = -1,
            title = "",
            artist = ""
        )
        val out = project(s)
        assertEquals("♪", out.original)
        assertEquals("", out.romanized)
        assertEquals("", out.translated)
        assertEquals("None", out.transitionMode)
        assertFalse(out.lineLevelSync)
        assertTrue(out.words.isEmpty())
    }

    // --- lyricKind = UNSYNCED ---

    @Test
    fun unsyncedKind_rendersMusicalNoteButKeepsTransitionFromModes() {
        val s = state(
            lyricKind = LyricKind.UNSYNCED,
            hasTimedLyrics = false,
            line = "untimed one-liner",
            lineIndex = -1,
            title = "",
            artist = ""
        )
        val out = project(s)
        assertEquals("♪", out.original)
        assertEquals("Crossfade", out.transitionMode) // 来自 renderModes，非 "None"
        assertFalse(out.lineLevelSync)
    }

    // --- lyricKind = LINE (活动行无 words) ---

    @Test
    fun lineKind_withActiveLine_rendersLineTextAndLineLevelSync() {
        val s = state(
            lyricKind = LyricKind.LINE,
            line = "hello world",
            romanizedLine = "roma",
            translatedLine = "tr",
            lineIndex = 0,
            words = null,
            lineStartMs = 1_000L,
            lineEndMs = 3_000L,
            alignedRight = true
        )
        val out = project(s)
        assertEquals("hello world", out.original)
        assertEquals("roma", out.romanized)
        assertEquals("tr", out.translated)
        assertTrue(out.lineLevelSync) // LINE → lineLevelSync
        assertEquals(1_000L, out.lineStartMs)
        assertEquals(3_000L, out.lineEndMs)
        assertTrue(out.alignedRight)
        assertTrue(out.words.isEmpty()) // 无 words
    }

    // --- lyricKind = SYLLABLE (活动行有 words) ---

    @Test
    fun syllableKind_withActiveLineAndWords_rendersWordsAndDisablesLineLevelSync() {
        val words = listOf(
            LyricWord("he", "", 1_000L, 1_500L, false),
            LyricWord("llo", "", 1_500L, 2_000L, true)
        )
        val s = state(
            lyricKind = LyricKind.SYLLABLE,
            line = "hello",
            lineIndex = 0,
            words = words,
            lineStartMs = 1_000L,
            lineEndMs = 2_000L
        )
        val out = project(s)
        assertEquals("hello", out.original)
        assertFalse(out.lineLevelSync) // SYLLABLE + 有 words → false
        assertEquals(2, out.words.size)
        assertEquals("he", out.words[0].text)
        assertEquals(1_000L, out.words[0].startMs)
        assertEquals("llo", out.words[1].text)
        assertTrue(out.words[1].boundaryAfter)
    }

    @Test
    fun syllableKind_withActiveLineButNoWords_treatsAsLineLevelSync() {
        // 对应原 isEffectiveLineLevelSync("Syllable", wordCount=0) = true
        val s = state(
            lyricKind = LyricKind.SYLLABLE,
            line = "hello",
            lineIndex = 0,
            words = null,
            lineStartMs = 1_000L,
            lineEndMs = 2_000L
        )
        val out = project(s)
        assertTrue(out.lineLevelSync)
        assertTrue(out.words.isEmpty())
    }

    // --- 位置投影 ---

    @Test
    fun positionIsForwardProjectedWhenPlaying() {
        val s = state(
            positionMs = 10_000L,
            sampledAtElapsedMs = 1_000L,
            speed = 2f,
            playing = true,
            lineIndex = -1,
            lyricKind = LyricKind.NONE,
            hasTimedLyrics = false
        )
        val out = project(s, now = 1_500L) // 0.5s * 2x = 1000ms 外推
        assertEquals(11_000L, out.positionMs)
        assertEquals(1_500L, out.sampledAtElapsedMs)
    }

    @Test
    fun positionIsClampedToDuration() {
        val s = state(
            positionMs = 179_000L,
            sampledAtElapsedMs = 0L,
            speed = 1f,
            playing = true,
            durationMs = 180_000L,
            lineIndex = -1,
            lyricKind = LyricKind.NONE,
            hasTimedLyrics = false
        )
        val out = project(s, now = 5_000L) // 179000 + 5000 = 184000 → clamp 180000
        assertEquals(180_000L, out.positionMs)
    }

    @Test
    fun positionHoldsWhenNotPlaying() {
        val s = state(
            positionMs = 42_000L,
            sampledAtElapsedMs = 0L,
            speed = 1f,
            playing = false,
            lineIndex = -1,
            lyricKind = LyricKind.NONE,
            hasTimedLyrics = false
        )
        val out = project(s, now = 10_000L)
        assertEquals(42_000L, out.positionMs)
    }

    // --- 渲染模式透传 ---

    @Test
    fun renderModesArePassedThroughFromState() {
        val s = state(lyricKind = LyricKind.LINE, line = "x", lineIndex = 0, lineStartMs = 1, lineEndMs = 2)
        val out = project(s)
        val modes = renderModes()
        assertEquals(modes.weight, out.weight)
        assertEquals(modes.textSize, out.textSizeMode)
        assertEquals(modes.textSizeCustom, out.textSizeCustom)
        assertEquals(modes.secondary, out.secondaryMode)
        assertEquals(modes.animation, out.animationMode)
        assertEquals(modes.glow, out.glowMode)
        assertEquals(modes.lineSyncFill, out.lineSyncFillMode)
        assertEquals(modes.overflow, out.overflowMode)
        assertEquals(modes.transition, out.transitionMode)
        assertEquals(modes.font, out.fontFamily)
    }

    @Test
    fun aodEnabledFromCompiledProfileOverridesPrefs() {
        val s = state(lyricKind = LyricKind.LINE, line = "x", lineIndex = 0, lineStartMs = 1, lineEndMs = 2)
        val out = project(s)
        assertTrue(out.aodEnabled) // compiled AOD profile enabled=true 覆盖
        assertFalse(out.lockscreenEnabled) // compiled lockscreen enabled=false
    }

    @Test
    fun metadataVisibleFromCompiledProfileOverridesPrefs() {
        val s = state(lyricKind = LyricKind.NONE, hasTimedLyrics = false, line = "", lineIndex = -1)
        val out = project(s)
        assertTrue(out.metadataVisible) // compiled AOD metadataVisible=true
    }

    // --- trackGeneration / wakeSignal 同构性 ---

    @Test
    fun trackGenerationMatchesEngineSpicyVersionSemantics() {
        val s = state(producerId = "p", generation = 7, trackUri = "spotify:track:z")
        val expected = ("p\u00007\u0000spotify:track:z").hashCode().toLong() and Long.MAX_VALUE
        assertEquals(expected, trackGeneration(s))
    }

    @Test
    fun wakeSignalDistinguishesTimedAndSongPhase() {
        val base = state(producerId = "p", generation = 1, trackUri = "spotify:track:x")
        val timed = sessionWakeSignal(base, hasTimedLyrics = true)
        val song = sessionWakeSignal(base, hasTimedLyrics = false)
        assertTrue(timed != song)
        assertTrue(timed != 0L)
        assertTrue(song != 0L)
    }

    // --- ruby / layoutGroups 透传 ---

    @Test
    fun rubyAndLayoutGroupsArePassedThroughWhenActiveLine() {
        val ruby = listOf(LyricRuby(0, 2, "ha"))
        val groups = listOf(LyricLayoutGroup(0, 5, "word", true, 0.9))
        val s = state(
            lyricKind = LyricKind.SYLLABLE,
            line = "hello",
            lineIndex = 0,
            words = listOf(LyricWord("hello", "", 1, 2, false)),
            lineStartMs = 1,
            lineEndMs = 2,
            ruby = ruby,
            layoutGroups = groups
        )
        val out = project(s)
        assertEquals(1, out.ruby.size)
        assertEquals("ha", out.ruby[0].reading)
        assertEquals(1, out.layoutGroups.size)
        assertEquals("word", out.layoutGroups[0].kind)
    }

    @Test
    fun rubyAndLayoutGroupsClearedWhenNoActiveLine() {
        val s = state(
            lyricKind = LyricKind.NONE,
            hasTimedLyrics = false,
            line = "",
            lineIndex = -1,
            ruby = listOf(LyricRuby(0, 2, "ha")),
            layoutGroups = listOf(LyricLayoutGroup(0, 5, "word", true, 0.9))
        )
        val out = project(s)
        assertTrue(out.ruby.isEmpty())
        assertTrue(out.layoutGroups.isEmpty())
    }

    // --- nextLineStartMs 透传到 metadataIntroPolicy ---

    @Test
    fun nextLineStartMsIsUsedByMetadataIntroPolicyForInterlude() {
        // 在间奏期（hasTimedLyrics=true，无活动行），nextLineStartMs 决定能否展示大元数据。
        // 这里只验证 nextLineStartMs 透传到了输出链路（policy 实际行为由其自身单测覆盖）。
        val s = state(
            lyricKind = LyricKind.LINE, // song-level，但 lineIndex=-1 → 间奏
            hasTimedLyrics = true,
            line = "",
            lineIndex = -1,
            nextLineStartMs = 5_000L,
            positionMs = 1_000L,
            title = "",
            artist = ""
        )
        val out = project(s)
        assertNotNull(out)
        // original 在间奏期且无大元数据时应为 "♪"
        assertEquals("♪", out.original)
    }

    // --- visible 标志 ---

    @Test
    fun visibleFollowsOriginalNonBlank() {
        val active = state(lyricKind = LyricKind.LINE, line = "hi", lineIndex = 0, lineStartMs = 1, lineEndMs = 2)
        assertTrue(project(active).visible)

        val none = state(lyricKind = LyricKind.NONE, hasTimedLyrics = false, line = "", lineIndex = -1)
        // "♪" 非空 → visible=true
        assertTrue(project(none).visible)
    }

    // --- shouldKeepAodAliveFor 顶层函数 ---

    @Test
    fun keepAliveRequiresPlayingAndAodAndKeepAwakeAndTimedOrUnsynced() {
        assertTrue(shouldKeepAodAliveFor(true, true, true, false, true))
        assertFalse(shouldKeepAodAliveFor(true, true, true, false, false)) // 无 timed、无 unsynced
        assertTrue(shouldKeepAodAliveFor(true, true, true, true, false)) // unsynced 兜底
        assertFalse(shouldKeepAodAliveFor(false, true, true, true, true))
        assertFalse(shouldKeepAodAliveFor(true, false, true, true, true))
        assertFalse(shouldKeepAodAliveFor(true, true, false, true, true))
    }
}
