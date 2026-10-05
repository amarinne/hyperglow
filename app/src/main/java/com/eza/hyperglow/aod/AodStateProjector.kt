package com.eza.hyperglow.aod

import com.eza.hyperglow.bridge.SpicyBridgeDocument
import com.eza.hyperglow.bridge.SpicyBridgeState

/** Row role the producer assigns to a synthesized instrumental-gap row. */
internal const val ROW_ROLE_INTERLUDE = "INTERLUDE"

/**
 * Everything the projection needs that does not come from the producer: the resolved preferences,
 * the compiled surface flags, the clock, and the user the projection is published for.
 *
 * `userId` is carried here rather than read inside the projection because `currentProcessUserId()`
 * resolves through `android.os.UserHandle`, which is a stub on the JVM and throws on call. Passing
 * it in is what lets the projection be exercised in unit tests at all.
 */
internal data class AodProjectionContext(
    val userId: Int,
    val nowElapsedMs: Long,
    val positionMs: Long,
    val prefs: AodRenderConfig,
    val aodEnabled: Boolean,
    val lockscreenEnabled: Boolean,
    val metadataVisible: Boolean,
    val duetEnabled: Boolean = true
)

/**
 * Maps one producer state plus its matching document into the display state published to both
 * surfaces. Content capability, presentation, and the keepalive decision are resolved here; session
 * lifetime, publication ordering, and transport classification stay with [AodProjectionEngine].
 *
 * The two policies are passed in because both carry per-session state that must survive across
 * projections — the metadata intro is consumed at most once per song, and the power session anchors
 * a finite keepalive duration to the first activation in a playing streak.
 */
internal fun projectToDisplay(
    state: SpicyBridgeState,
    document: SpicyBridgeDocument?,
    context: AodProjectionContext,
    metadataIntroPolicy: SongMetadataIntroPolicy,
    powerSessionPolicy: AodPowerSessionPolicy,
    responseCreditOutroPolicy: ResponseCreditOutroPolicy = ResponseCreditOutroPolicy()
): AodDisplayState {
    val position = context.positionMs
    val prefs = context.prefs
    val timedDocument = document?.takeIf { AodProjectionEngine.isTimedDocumentType(it.type) }
    val unsynced = document != null && timedDocument == null
    val noLyrics = state.status == "no_lyrics"
    val hasTimedLyrics = !noLyrics &&
        timedDocument?.let(AodProjectionEngine::hasActualLyricTiming) == true
    val row = timedDocument?.primaryRowAt(position).takeUnless { noLyrics }
    val metadata = if (prefs.metadataLayout == "single") {
        listOf(state.title, state.artist)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
    } else {
        listOf(state.title, state.artist)
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .replace('·', '\n')
    }
    val fallbackLine = state.line.takeIf {
        !unsynced && !noLyrics && document == null && state.status == "ready" && it.isNotBlank()
    }
    // The producer represents an instrumental gap of three seconds or more as its own row rather
    // than as an absence of rows, so a row covering the playhead is not proof of singing. Reading a
    // dot row as an active lyric made every intro look like a song that opens on vocals.
    val interludeRow = row?.takeIf { it.role == ROW_ROLE_INTERLUDE }
    val lyricState = when {
        unsynced || noLyrics -> SongIntroLyricState.NONE
        interludeRow != null -> SongIntroLyricState.INTERLUDE
        row != null || fallbackLine != null -> SongIntroLyricState.ACTIVE
        timedDocument != null -> SongIntroLyricState.INTERLUDE
        else -> SongIntroLyricState.UNKNOWN
    }
    // How long the interlude lasts is the distance to the next sung row. Dot rows are part of the
    // gap, so counting them here would cut every interlude short at its own boundary.
    val nextLyricStartMs = timedDocument?.rows?.asSequence()
        ?.filter { it.role != ROW_ROLE_INTERLUDE }
        ?.map { it.startMs }
        ?.filter { it > position }
        ?.minOrNull()
    val showLargeMetadata = prefs.songChangeInfoEnabled && metadataIntroPolicy.shouldShowLargeMetadata(
        SongMetadataIntroInput(
            session = ProjectionSessionIdentity.from(state),
            metadataAvailable = metadata.isNotBlank(),
            lyricState = lyricState,
            positionMs = position,
            nextLyricStartMs = nextLyricStartMs,
            speed = state.speed,
            nowElapsedMs = context.nowElapsedMs,
            openingResolved = document != null
        )
    )
    val showResponseCredit = responseCreditOutroPolicy.shouldShow(
        session = ProjectionSessionIdentity.from(state),
        document = document,
        positionMs = position,
        nowElapsedMs = context.nowElapsedMs,
        eligible = hasTimedLyrics && state.playing && (context.aodEnabled || context.lockscreenEnabled)
    )
    val presentedRow = row.takeUnless { showLargeMetadata || showResponseCredit }
    // One overlapping sung line stays on screen next to the primary so duet
    // and layered singing render together instead of cutting each other off.
    // The duet toggle withdraws the concurrent row at the source: snapshots
    // then never carry a second line, so both canvas surfaces present the
    // previous solo-only behavior with no per-build duet state.
    val concurrentRow = if (context.duetEnabled && presentedRow != null && timedDocument != null) {
        timedDocument.concurrentRowsAt(position, presentedRow).firstOrNull()
    } else {
        null
    }
    val rejectJapaneseReading = presentedRow?.let {
        hasLanguageInconsistentKanaRuby(document, it.ruby.map { ruby -> ruby.reading })
    } == true
    val rejectConcurrentJapaneseReading = concurrentRow?.let {
        hasLanguageInconsistentKanaRuby(document, it.ruby.map { ruby -> ruby.reading })
    } == true
    val original = when {
        showResponseCredit -> document?.responseCredit.orEmpty()
        showLargeMetadata -> metadata
        unsynced || noLyrics -> "♪"
        presentedRow != null -> presentedRow.text
        timedDocument != null || state.status == "loading" -> "♪"
        fallbackLine != null -> fallbackLine
        else -> "♪"
    }
    val romanized = if (showResponseCredit || showLargeMetadata || unsynced || noLyrics || rejectJapaneseReading) "" else
        presentedRow?.romanized.orEmpty()
    val translated = if (showResponseCredit || showLargeMetadata || unsynced || noLyrics) "" else
        presentedRow?.translated.orEmpty()
    val persistentKeepAlive = AodProjectionEngine.shouldKeepAodAlive(
        playing = state.playing,
        aodEnabled = context.aodEnabled,
        keepAwake = prefs.keepAwake,
        keepAwakeUnsynced = prefs.keepAwakeUnsynced,
        hasTimedLyrics = hasTimedLyrics
    )
    val powerDecision = powerSessionPolicy.resolve(
        state = SpicyPowerSessionState(
            session = ProjectionSessionIdentity.from(state),
            playing = state.playing,
            aodEnabled = context.aodEnabled,
            keepAwake = prefs.keepAwake,
            keepAliveDurationMs = prefs.keepAwakeDurationMs
        ),
        nowElapsedMs = context.nowElapsedMs,
        persistentKeepAlive = persistentKeepAlive
    )
    return AodDisplayState(
        visible = original.isNotBlank(),
        playbackActive = state.playing,
        userId = context.userId,
        trackGeneration = AodProjectionEngine.trackGeneration(state),
        aodEnabled = context.aodEnabled,
        lockscreenEnabled = context.lockscreenEnabled,
        seamlessTransitionEnabled = prefs.seamlessTransitionEnabled,
        keepAlive = powerDecision.keepAlive,
        positionFollowingEnabled = prefs.experimentalPositionFollowing,
        burnInPattern = prefs.burnInPattern,
        burnInIntervalMs = prefs.burnInIntervalMs,
        suppressStockAodContent = prefs.suppressStockAodContent,
        aodRotateWithDevice = prefs.aodRotateWithDevice,
        aodRotationMode = prefs.aodRotationMode,
        aodCanvasAnchor = prefs.aodCanvasAnchor,
        aodRotationSettleMs = prefs.aodRotationSettleMs,
        aodCanvasAnchorLandscape = prefs.aodCanvasAnchorLandscape,
        aodLandscapeTextScale = prefs.aodLandscapeTextScale,
        aodCanvasPaddingPortraitXPercent = prefs.aodCanvasPaddingPortraitXPercent,
        aodCanvasPaddingPortraitYPercent = prefs.aodCanvasPaddingPortraitYPercent,
        aodCanvasPaddingLandscapeXPercent = prefs.aodCanvasPaddingLandscapeXPercent,
        aodCanvasPaddingLandscapeYPercent = prefs.aodCanvasPaddingLandscapeYPercent,
        wakeSignal = AodProjectionEngine.sessionWakeSignal(state, hasTimedLyrics),
        original = original,
        romanized = romanized,
        translated = translated,
        metadata = metadata,
        alignedRight = presentedRow?.alignedRight == true,
        lineLevelSync = document != null && presentedRow != null &&
            AodProjectionEngine.isEffectiveLineLevelSync(document.type, presentedRow.words.size),
        sourceRowOrdinal = timedDocument?.rows?.indexOfFirst { it === presentedRow } ?: -1,
        lineStartMs = presentedRow?.startMs ?: 0L,
        lineEndMs = presentedRow?.let { minOf(it.fillEndMs, it.endMs) } ?: 0L,
        durationMs = state.durationMs,
        positionMs = position,
        sampledAtElapsedMs = context.nowElapsedMs,
        speed = state.speed,
        words = presentedRow?.words.orEmpty().map {
            AodDisplayWord(
                it.text,
                if (rejectJapaneseReading) "" else it.romanized,
                it.startMs,
                it.endMs,
                it.boundaryAfter,
                it.sourceStart,
                it.sourceEnd
            )
        },
        ruby = presentedRow?.ruby.orEmpty()
            .takeUnless { rejectJapaneseReading }
            .orEmpty()
            .map { AodDisplayRuby(it.start, it.end, it.reading) },
        secondLine = concurrentRow?.let { concurrent ->
            val concurrentWords = if (rejectConcurrentJapaneseReading) {
                concurrent.words.map { it.copy(romanized = "") }
            } else {
                concurrent.words
            }
            AodDisplaySecondLine(
                sourceRowOrdinal = timedDocument?.rows?.indexOfFirst { it === concurrent } ?: -1,
                text = concurrent.text,
                romanized = if (rejectConcurrentJapaneseReading) "" else concurrent.romanized,
                translated = concurrent.translated,
                alignedRight = concurrent.alignedRight,
                lineStartMs = concurrent.startMs,
                lineEndMs = minOf(concurrent.fillEndMs, concurrent.endMs),
                words = concurrentWords.map {
                    AodDisplayWord(
                        it.text,
                        it.romanized,
                        it.startMs,
                        it.endMs,
                        it.boundaryAfter,
                        it.sourceStart,
                        it.sourceEnd
                    )
                },
                ruby = concurrent.ruby
                    .takeUnless { rejectConcurrentJapaneseReading }
                    .orEmpty()
                    .map { AodDisplayRuby(it.start, it.end, it.reading) },
                layoutGroups = concurrent.layoutGroups.map {
                    AodDisplayLayoutGroup(
                        it.start, it.end, it.kind, it.keepTogether, it.confidence
                    )
                }
            )
        },
        layoutGroups = presentedRow?.layoutGroups.orEmpty().map {
            AodDisplayLayoutGroup(it.start, it.end, it.kind, it.keepTogether, it.confidence)
        },
        weight = prefs.weight,
        textSizeMode = if (showResponseCredit) "credit" else prefs.textSize,
        textSizeCustom = prefs.textSizeCustom,
        secondaryMode = prefs.secondaryMode,
        animationMode = if (showResponseCredit) "Minimal" else prefs.animation,
        glowMode = if (showResponseCredit) "Off" else prefs.glow,
        lineSyncFillMode = state.liveCardLineSyncFill,
        overflowMode = if (showResponseCredit) "Wrap" else prefs.overflowMode,
        transitionMode = if (noLyrics) "None" else state.liveCardTransition,
        fontFamily = prefs.fontFamily,
        alignmentMode = prefs.alignment,
        metadataVisible = context.metadataVisible && !showResponseCredit,
        metadataAnchor = prefs.metadataAnchor,
        metadataLayout = prefs.metadataLayout,
        adaptiveSectioning = prefs.adaptiveSectioning
    )
}

internal fun hasLanguageInconsistentKanaRuby(
    document: SpicyBridgeDocument?,
    rubyReadings: List<String>
): Boolean {
    val language = document?.language
        ?.substringBefore('-')
        ?.substringBefore('_')
    if (!language.equals("zh", ignoreCase = true)) return false
    return rubyReadings.any { reading -> reading.any(::isKana) }
}

private fun isKana(character: Char): Boolean =
    character in '\u3040'..'\u30ff' || character in '\uff66'..'\uff9f'
