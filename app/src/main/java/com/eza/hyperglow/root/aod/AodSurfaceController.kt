package com.eza.hyperglow.root.aod

import android.content.Context
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.eza.hyperglow.root.HookLogger
import com.eza.hyperglow.customization.CompiledCustomization
import com.eza.hyperglow.customization.CompiledSurfaceProfile
import com.eza.hyperglow.customization.SceneCompiler
import com.eza.hyperglow.root.capability.XiaomiCapability
import com.eza.hyperglow.root.capability.XiaomiCapabilityResolver
import com.eza.hyperglow.root.projection.LyricKeepAliveSignal
import com.eza.hyperglow.root.projection.LyricRenderContent
import com.eza.hyperglow.root.projection.LyricSnapshot
import com.eza.hyperglow.root.projection.LyricSurfaceKind
import com.eza.hyperglow.root.projection.SystemUiLyricProjectionRuntime
import com.eza.hyperglow.root.projection.SystemUiLyricSubscriber
import com.eza.hyperglow.root.projection.freezeAt
import com.eza.hyperglow.root.projection.isAuthorizedForPresentation
import com.eza.hyperglow.root.projection.pauseLingerRemainingMs
import com.eza.hyperglow.root.projection.shouldRenewAodDraw
import com.eza.hyperglow.root.projection.shouldRequestAodWake
import com.eza.hyperglow.root.surface.SurfaceEnvironment
import com.eza.hyperglow.root.surface.PlacementEngine
import com.eza.hyperglow.root.surface.PlacementEnvironment
import com.eza.hyperglow.root.surface.PlacementRect
import com.eza.hyperglow.root.surface.WidgetMeasurement
import com.eza.hyperglow.root.transition.LinkageSceneRole
import com.eza.hyperglow.root.transition.LinkageSurface
import com.eza.hyperglow.root.transition.LinkageTransitionCoordinator
import com.eza.hyperglow.root.transition.SystemUiClockMorphHook
import com.eza.hyperglow.root.transition.TransitionRect
import com.eza.hyperglow.root.transition.animateLinkageView
import com.eza.hyperglow.root.transition.fadeOutLinkageView
import com.eza.hyperglow.root.transition.isDimmedAodDisplayState
import com.eza.hyperglow.root.transition.presentationRectInWindow
import com.eza.hyperglow.root.transition.resetLinkageView
import com.eza.hyperglow.root.transition.transitionRectInWindow
import java.lang.ref.WeakReference
import kotlin.math.roundToInt

internal data class AodSurfaceRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

internal data class AodRenderedClockBounds(
    val top: Int,
    val bottom: Int
) {
    val height: Int get() = bottom - top
}

private const val BRIGHT_LINKAGE_CLOCK_RESERVE_FRACTION = 0.35f

internal fun resolveRenderedAodSceneZone(
    managedZone: AodSceneZone,
    renderedBounds: AodRenderedClockBounds?,
    rootHeight: Int,
    margin: Int
): AodSceneZone {
    if (managedZone == AodSceneZone.STOCK || renderedBounds == null ||
        renderedBounds.height <= 0 || rootHeight <= 0
    ) return managedZone
    val freeAbove = (renderedBounds.top - margin).coerceAtLeast(0)
    val freeBelow = (rootHeight - renderedBounds.bottom - margin).coerceAtLeast(0)
    return when {
        freeAbove > freeBelow -> AodSceneZone.CLOCK_BOTTOM
        freeBelow > freeAbove -> AodSceneZone.CLOCK_TOP
        else -> managedZone
    }
}

/**
 * @param rememberedPhysicalBounds the last physical measurement taken on a root of the same height.
 *   The physical clock cannot be measured while the panel is dark, so every re-attach in that state
 *   falls through to the managed position — which is where the clock was asked to go, not where the
 *   stock AOD clock actually is. On this device those differ by hundreds of pixels, so the lyrics
 *   appeared far from their configured place until the panel lit and the real bounds resolved. A
 *   measurement already taken is better evidence than a position we merely requested.
 */
internal fun resolvedAodClockBounds(
    renderedBounds: AodRenderedClockBounds?,
    controlledTop: Int?,
    controlledBottom: Int?,
    measuredTop: Int,
    measuredBottom: Int,
    exactPhysicalBounds: AodRenderedClockBounds? = null,
    rememberedPhysicalBounds: AodRenderedClockBounds? = null
): AodRenderedClockBounds {
    val controlled = if (controlledTop != null && controlledBottom != null) {
        AodRenderedClockBounds(controlledTop, controlledBottom)
    } else {
        null
    }
    val validRendered = renderedBounds?.takeIf { it.height > 0 }
    val validControlled = controlled?.takeIf { it.height > 0 }
    val validPhysical = exactPhysicalBounds?.takeIf { it.height > 0 }
    val validRemembered = rememberedPhysicalBounds?.takeIf { it.height > 0 }
    return when {
        validPhysical != null -> validPhysical
        validRemembered != null -> validRemembered
        validControlled != null -> validControlled
        validRendered != null -> validRendered
        else -> AodRenderedClockBounds(measuredTop, measuredBottom)
    }
}

internal fun selectPhysicalAodClockBounds(
    systemUiBounds: AodRenderedClockBounds?,
    aodControllerBounds: AodRenderedClockBounds?
): AodRenderedClockBounds? = systemUiBounds ?: aodControllerBounds

internal fun brightLinkageClockBounds(rootHeight: Int): AodRenderedClockBounds =
    AodRenderedClockBounds(0, (rootHeight * BRIGHT_LINKAGE_CLOCK_RESERVE_FRACTION).roundToInt())

internal fun shouldUseBrightClockMorphGeometry(
    linkageMode: Boolean,
    morphingToAod: Boolean,
    linkageAwaitingDim: Boolean,
    displayState: Int
): Boolean = linkageMode && !isDimmedAodDisplayState(displayState) &&
    (morphingToAod || linkageAwaitingDim)

internal fun calculateAodSurfaceRect(
    rootWidth: Int,
    rootHeight: Int,
    stockBottom: Int,
    margin: Int,
    desiredWidth: Int,
    desiredHeight: Int,
    translationX: Int = 0,
    safeBottom: Int? = null,
    anchor: String = "below_stock_clock",
    verticalBias: Float = 0.5f
): AodSurfaceRect {
    val boundedWidth = desiredWidth.coerceIn(0, rootWidth.coerceAtLeast(0))
    val maxLeft = (rootWidth - boundedWidth).coerceAtLeast(0)
    val left = ((rootWidth - boundedWidth) / 2 + translationX).coerceIn(0, maxLeft)
    val visibleBottom = (minOf(rootHeight, safeBottom ?: rootHeight) - margin).coerceAtLeast(0)
    val safeTop = (stockBottom + margin).coerceIn(0, visibleBottom)
    val height = desiredHeight.coerceIn(0, visibleBottom - safeTop)
    val top = when (anchor) {
        "screen_center" -> safeTop + (visibleBottom - safeTop - height) / 2
        "screen_bottom_safe" -> visibleBottom - height
        "custom_vertical_bias" -> safeTop +
            ((visibleBottom - safeTop - height) * verticalBias.coerceIn(0f, 1f)).roundToInt()
        else -> safeTop
    }
    return AodSurfaceRect(left, top, left + boundedWidth, top + height)
}

internal fun stockBottomInRoot(rootWindowY: Int, childWindowY: Int, childHeight: Int): Int =
    childWindowY - rootWindowY + childHeight

internal fun hasUsableAodRootSize(width: Int, height: Int): Boolean = width > 0 && height > 0

internal fun aodSceneSafeCanvas(
    rootWidth: Int,
    rootHeight: Int,
    clockTop: Int,
    lyricTopSafe: Int,
    margin: Int,
    zone: AodSceneZone
): PlacementRect = if (zone == AodSceneZone.CLOCK_BOTTOM) {
    val top = lyricTopSafe.coerceIn(0, rootHeight)
    val bottom = (clockTop - margin).coerceIn(top, rootHeight)
    PlacementRect(0f, top.toFloat(), rootWidth.toFloat(), bottom.toFloat())
} else {
    PlacementRect(0f, 0f, rootWidth.toFloat(), rootHeight.toFloat())
}

internal fun aodPlacementMaxHeightFraction(
    configuredFraction: Float,
    zone: AodSceneZone
): Float = if (zone == AodSceneZone.CLOCK_BOTTOM) 1f else configuredFraction

internal fun shouldRenderAodSnapshot(
    sceneActive: Boolean,
    snapshotVisible: Boolean,
    featureEnabled: Boolean,
    profileEnabled: Boolean,
    transitionFailed: Boolean,
    spotifyAuthorized: Boolean = true
): Boolean = sceneActive && snapshotVisible && spotifyAuthorized && featureEnabled &&
    profileEnabled && !transitionFailed

internal fun isNewAodWakeSignal(previous: Long, incoming: Long): Boolean =
    incoming != 0L && incoming != previous

internal fun retainedAodSnapshotAfterUpdate(
    incoming: LyricSnapshot,
    lastVisible: LyricSnapshot?,
    retained: LyricSnapshot?,
    mediaPlayerPresent: Boolean,
    nowElapsedMs: Long,
    pauseLingerMs: Long = 5_000L
): LyricSnapshot? = when {
    incoming.visible -> null
    !mediaPlayerPresent -> null
    incoming.pauseRetentionEligible -> {
        val pauseAtElapsedMs = incoming.updatedAtElapsedMs.coerceIn(0L, nowElapsedMs)
        val candidate = retained?.takeIf { it.pauseRetentionEligible } ?: lastVisible?.freezeAt(
            pauseAtElapsedMs,
            keepAliveWhileFrozen = false
        )?.copy(playbackActive = false, pauseRetentionEligible = true)
        candidate?.takeIf {
            pauseLingerRemainingMs(it.sampledAtElapsedMs, pauseLingerMs, nowElapsedMs) != null
        }
    }
    incoming.playbackActive -> {
        val candidate = retained?.takeIf { it.playbackActive } ?: lastVisible?.freezeAt(
            nowElapsedMs,
            keepAliveWhileFrozen = lastVisible.keepAlive
        )?.copy(playbackActive = true, pauseRetentionEligible = false)
        candidate?.let { expirePausedAodKeepAlive(it, nowElapsedMs) }
    }
    else -> null
}

internal fun expirePausedAodKeepAlive(
    retained: LyricSnapshot,
    nowElapsedMs: Long
): LyricSnapshot {
    if (!retained.keepAlive) return retained
    val pausedForMs = (nowElapsedMs - retained.sampledAtElapsedMs).coerceAtLeast(0L)
    return if (pausedForMs >= PAUSED_AOD_KEEP_ALIVE_MS) {
        retained.copy(keepAlive = false)
    } else {
        retained
    }
}

internal const val PAUSED_AOD_KEEP_ALIVE_MS = 30_000L

internal fun smoothAodRevealProgress(progress: Float): Float {
    val value = progress.coerceIn(0f, 1f)
    return value * value * (3f - 2f * value)
}

internal fun shouldRetryManagedAodPosition(attempts: Int, maximumAttempts: Int): Boolean =
    attempts < maximumAttempts

internal object AodSurfaceController : SystemUiLyricSubscriber, LinkageSurface {
    private const val TAG = "AodSurfaceController"
    private const val SURFACE_TAG = "hyper_aod_lyrics_surface"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val positionUpdates = AodPositionUpdateCoalescer()
    private var attachmentGeneration = 0L
    private var environment = SurfaceEnvironment(LyricSurfaceKind.AOD, 0L)
    private var rootRef = WeakReference<ViewGroup>(null)
    private var burnInContainerRef = WeakReference<FrameLayout>(null)
    private var surface: LinearLayout? = null
    private var lyricCanvas: AodLyricCanvasView? = null
    private var spicyAnimationView: AodSpicyAnimationView? = null
    private var latestSnapshot: LyricSnapshot? = null
    private var lastVisibleSnapshot: LyricSnapshot? = null
    private var retainedMediaSnapshot: LyricSnapshot? = null
    private var stockMediaPlayerPresent = false
    private var customization: CompiledCustomization? = null
    private var runtimeProfile: CompiledSurfaceProfile? = null
    private var lastRenderContent: LyricRenderContent? = null
    private var lastWakeSignal = Long.MIN_VALUE
    private var sceneRole = LinkageSceneRole.INACTIVE
    private var handoffActive = false
    private var transitionFailedHidden = false
    private var lastLayoutBlockTrace: String? = null
    private var lastSnapshotTrace: String? = null
    private var lastBrightClockMorphPhase: Boolean? = null
    private var lastClockGeometryAuthority: String? = null
    private var rememberedPhysicalClockBounds: AodRenderedClockBounds? = null
    private var rememberedPhysicalClockRootHeight = 0
    private var rememberedPhysicalClockTranslationY = 0f
    @Volatile private var stockWidgetControlActive = false
    @Volatile private var burnInPattern = "static_bottom"
    private var burnInIntervalMs = 60_000L
    private var sceneZone = AodSceneZone.STOCK
    private var controlledClockTop: Int? = null
    private var controlledClockBottom: Int? = null
    private var controlledLyricTopSafe: Int? = null
    private var systemUiClockBounds: AodRenderedClockBounds? = null
    private var aodControllerClockBounds: AodRenderedClockBounds? = null
    private var renderedClockBounds: AodRenderedClockBounds? = null
    private val renderedClockRootLocation = IntArray(2)
    private val renderedClockUnion = Rect()
    private val renderedClockScratch = Rect()
    private var displayManager: DisplayManager? = null
    private var observedDisplayId = -1
    private var lastObservedDisplayState = -1
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit

        override fun onDisplayRemoved(displayId: Int) = Unit

        override fun onDisplayChanged(displayId: Int) {
            if (displayId != observedDisplayId) return
            val state = rootRef.get()?.display?.state ?: return
            val changed = state != lastObservedDisplayState
            if (changed) {
                lastObservedDisplayState = state
                HookLogger.i(TAG, "AOD root display state=$state displayId=$displayId")
            }
            LinkageTransitionCoordinator.onAodDisplayState(state)
            AodPowerCoordinator.onAodDisplayState(state)
            if (changed) requestGeometryUpdate()
        }
    }
    private var pendingStockMotionUpdate: AodPositionUpdate? = null
    private var stockMotionRevealPending = false
    private var stockMotionTransitionActive = false
    private var stockMotionAlphaAnimationActive = false
    private var stockMotionAlphaCompletesTransition = false
    private var stockMotionAlphaStartedAt = 0L
    private var stockMotionAlphaDurationMs = 0L
    private var stockMotionAlphaFrom = 1f
    private var stockMotionAlphaTo = 1f
    private var drawWakeRenewalActive = false
    private var managedPositionRetryCount = 0
    private var initialRevealPending = true
    private var initialRevealActive = false
    private var initialRevealStartedAt = 0L
    private var initialRevealDurationMs = 0L
    private val pausedKeepAliveExpiry = Runnable {
        val retained = retainedMediaSnapshot ?: return@Runnable
        val expired = expirePausedAodKeepAlive(retained, SystemClock.elapsedRealtime())
        if (expired.keepAlive == retained.keepAlive) return@Runnable
        retainedMediaSnapshot = expired
        latestSnapshot = latestSnapshot?.copy(keepAlive = expired.keepAlive)
        HookLogger.i(TAG, "Paused AOD keepalive grace expired")
        updateLifetimeGuard()
    }
    private val pauseLingerExpiry = object : Runnable {
        override fun run() {
            val retained = retainedMediaSnapshot?.takeIf { it.pauseRetentionEligible } ?: return
            val remaining = pauseLingerRemainingMs(
                retained.sampledAtElapsedMs,
                customization?.pauseLingerMs ?: 5_000L,
                SystemClock.elapsedRealtime()
            )
            if (remaining != null) {
                if (remaining != Long.MAX_VALUE) mainHandler.postDelayed(this, remaining)
                return
            }
            retainedMediaSnapshot = null
            lastVisibleSnapshot = null
            latestSnapshot = latestSnapshot?.takeUnless { it === retained }
            setStockWidgetControlActive(false)
            hideSurfaceOnly(pulse = false)
        }
    }
    private val layoutChangeListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        requestGeometryUpdate()
    }
    private val clockGeometryPreDrawListener = ViewTreeObserver.OnPreDrawListener {
        val root = rootRef.get()
        val burnInContainer = burnInContainerRef.get()
        root?.display?.state?.let(LinkageTransitionCoordinator::onAodDisplayState)
        val snapshot = latestSnapshot
        val brightClockMorph = root?.let(::isBrightClockMorphPhase) == true
        val exactSystemUiBounds = root?.let(SystemUiClockMorphHook::renderedBoundsInRoot)
        val exactAodControllerBounds = root?.let(AodPositionHook::renderedTargetBoundsInRoot)
        val exactBoundsChanged = exactSystemUiBounds != systemUiClockBounds ||
            exactAodControllerBounds != aodControllerClockBounds
        if (exactBoundsChanged) {
            systemUiClockBounds = exactSystemUiBounds
            aodControllerClockBounds = exactAodControllerBounds
            requestGeometryUpdate()
        } else if (!brightClockMorph &&
            !stockWidgetControlActive && root != null && burnInContainer != null &&
            snapshot != null && canRenderAod(snapshot)
        ) {
            val nextBounds = renderedStockClockBounds(
                root,
                burnInContainer,
                renderedClockBounds
            )
            if (nextBounds != null && nextBounds != renderedClockBounds) {
                renderedClockBounds = nextBounds
                requestGeometryUpdate()
            }
        }
        true
    }
    private val geometryUpdate = Runnable {
        val update = positionUpdates.drain(attachmentGeneration) ?: return@Runnable
        environment = environment.copy(
            burnInTranslationX = update.translationX,
            burnInTranslationY = update.translationY,
            safeBottom = update.safeBottom
        )
        sceneZone = update.zone
        controlledClockTop = update.clockTop
        controlledClockBottom = update.clockBottom
        controlledLyricTopSafe = update.lyricTopSafe
        val root = rootRef.get() ?: return@Runnable
        val burnInContainer = burnInContainerRef.get() ?: return@Runnable
        val directSurface = surface ?: return@Runnable
        layoutSurface(root, burnInContainer, directSurface)
    }
    private val stockMotionSettleTimeout = Runnable {
        settleStockMotion("timeout")
    }

    private fun settleStockMotion(source: String) {
        val update = pendingStockMotionUpdate ?: return
        pendingStockMotionUpdate = null
        if (update.generation != attachmentGeneration) return
        stockMotionRevealPending = true
        HookLogger.i(TAG, "Stock scene motion settled source=$source zone=${update.zone}")
        enqueueGeometryUpdate(update)
    }
    private val stockMotionAlphaFrame = object : Runnable {
        override fun run() {
            if (!stockMotionAlphaAnimationActive) return
            val directSurface = surface ?: return cancelStockMotionTransition(resetAlpha = false)
            val elapsed = (SystemClock.elapsedRealtime() - stockMotionAlphaStartedAt)
                .coerceAtLeast(0L)
            val linear = (elapsed / stockMotionAlphaDurationMs.coerceAtLeast(1L).toFloat())
                .coerceIn(0f, 1f)
            val progress = smoothAodRevealProgress(linear)
            directSurface.alpha = stockMotionAlphaFrom +
                (stockMotionAlphaTo - stockMotionAlphaFrom) * progress
            directSurface.invalidate()
            rootRef.get()?.invalidate()
            if (linear < 1f) {
                mainHandler.postDelayed(this, AOD_ANIMATION_FRAME_MS)
            } else {
                stockMotionAlphaAnimationActive = false
                directSurface.alpha = stockMotionAlphaTo
                if (stockMotionAlphaCompletesTransition) {
                    stockMotionTransitionActive = false
                    stockMotionAlphaCompletesTransition = false
                }
            }
        }
    }
    private val drawWakeRenewal = object : Runnable {
        override fun run() {
            if (!drawWakeRenewalActive) return
            val root = rootRef.get()
            if (root == null || !isSceneActive()) {
                setDrawWakeRenewalActive(false)
                return
            }
            pulseDrawWakeLock(root)
            mainHandler.postDelayed(this, DRAW_WAKE_RENEW_INTERVAL_MS)
        }
    }
    private val initialRevealFrame = object : Runnable {
        override fun run() {
            if (!initialRevealActive) return
            val directSurface = surface ?: return finishInitialReveal()
            val elapsed = (SystemClock.elapsedRealtime() - initialRevealStartedAt).coerceAtLeast(0L)
            val linear = (elapsed / initialRevealDurationMs.coerceAtLeast(1L).toFloat())
                .coerceIn(0f, 1f)
            directSurface.alpha = smoothAodRevealProgress(linear)
            directSurface.invalidate()
            rootRef.get()?.invalidate()
            if (linear < 1f) mainHandler.postDelayed(this, AOD_ANIMATION_FRAME_MS)
            else finishInitialReveal()
        }
    }
    private val managedBurnInStart = object : Runnable {
        override fun run() {
            if (!stockWidgetControlActive) return
            if (AodPositionHook.hasManagedPosition() ||
                AodPositionHook.advanceManagedPosition(burnInPattern, animated = false)
            ) {
                managedPositionRetryCount = 0
                if (managedAodPatternRepeats(burnInPattern)) {
                    mainHandler.postDelayed(managedBurnInAdvance, burnInIntervalMs)
                }
            } else {
                managedPositionRetryCount++
                if (shouldRetryManagedAodPosition(
                        managedPositionRetryCount,
                        MAX_MANAGED_POSITION_RETRIES
                    )
                ) {
                    mainHandler.postDelayed(this, MANAGED_BURN_IN_RETRY_MS)
                } else {
                    HookLogger.i(TAG, "Managed AOD position unavailable; using stock geometry")
                }
            }
        }
    }
    private val managedBurnInAdvance = object : Runnable {
        override fun run() {
            if (!stockWidgetControlActive) return
            if (!managedAodPatternRepeats(burnInPattern)) return
            val moved = AodPositionHook.advanceManagedPosition(burnInPattern)
            mainHandler.postDelayed(
                this,
                if (moved) burnInIntervalMs else MANAGED_BURN_IN_RETRY_MS
            )
        }
    }

    override val surfaceKind = LyricSurfaceKind.AOD
    override val linkageSurfaceKind = LyricSurfaceKind.AOD

    fun attach(root: ViewGroup) {
        mainHandler.post {
            runCatching {
                XiaomiCapabilityResolver.observeContext(root.context)
                SystemUiLyricProjectionRuntime.projection.reportCapabilities()
                if (!XiaomiCapabilityResolver.hasCapability(XiaomiCapability.AOD_SURFACE)) {
                    if (rootRef.get() != null || surface != null) detachCurrent()
                    HookLogger.w(TAG, "AOD surface capability unavailable; surface disabled")
                    return@runCatching
                }

                val burnInContainer = findBurnInContainer(root) ?: run {
                    HookLogger.w(TAG, "mTableModeContainer unavailable; surface disabled")
                    return@runCatching
                }
                if (rootRef.get() === root && surface != null) return@runCatching
                detachCurrent()
                attachmentGeneration++
                environment = SurfaceEnvironment(
                    LyricSurfaceKind.AOD,
                    attachmentGeneration,
                    fullAodSupported = XiaomiCapabilityResolver.hasCapability(
                        XiaomiCapability.FULL_AOD
                    ),
                    videoDepthSupported = XiaomiCapabilityResolver.hasCapability(
                        XiaomiCapability.VIDEO_DEPTH
                    )
                )
                rootRef = WeakReference(root)
                burnInContainerRef = WeakReference(burnInContainer)
                observeDisplayState(root)
                AodPositionHook.observeAodRoot(root)
                val directSurface = buildSurface(root)
                surface = directSurface
                root.overlay.add(directSurface)
                root.addOnLayoutChangeListener(layoutChangeListener)
                burnInContainer.addOnLayoutChangeListener(layoutChangeListener)
                burnInContainer.viewTreeObserver.addOnPreDrawListener(
                    clockGeometryPreDrawListener
                )
                LinkageTransitionCoordinator.registerSurface(this)
                AodPowerCoordinator.onSurfaceAttached()
                LinkageTransitionCoordinator.onAodSurfaceMode(AodPositionHook.isLinkageMode())
                val generation = attachmentGeneration
                root.post {
                    if (generation == attachmentGeneration && rootRef.get() === root) {
                        root.display?.state?.let(LinkageTransitionCoordinator::onAodDisplayState)
                        val laidOut = layoutSurface(root, burnInContainer, directSurface)
                        HookLogger.i(
                            TAG,
                            "Attach layout replay result=$laidOut root=${root.width}x${root.height} " +
                                "snapshot=${latestSnapshot?.revision}"
                        )
                    }
                }
                SystemUiLyricProjectionRuntime.projection.attach(this, root.context)
                HookLogger.i(TAG, "Surface attached")
            }.onFailure {
                runCatching { detachCurrent() }
                HookLogger.e(TAG, "Attach failed", it)
            }
        }
    }

    fun detach(root: ViewGroup) {
        mainHandler.post {
            if (rootRef.get() !== root) return@post
            runCatching { detachCurrent() }
        }
    }

    fun onStockPositionUpdated(
        translationX: Float,
        translationY: Float,
        safeBottom: Int?,
        clockTop: Int?,
        clockBottom: Int?,
        lyricTopSafe: Int?,
        zone: AodSceneZone,
        zoneChanged: Boolean,
        animated: Boolean
    ) {
        val dispatch = dispatch@{
            val positionEnabled = latestSnapshot?.positionFollowingEnabled == true &&
                XiaomiCapabilityResolver.hasCapability(XiaomiCapability.AOD_POSITION_UPDATES)
            if (!positionEnabled && sceneZone == AodSceneZone.STOCK &&
                zone == AodSceneZone.STOCK && !zoneChanged &&
                pendingStockMotionUpdate == null
            ) return@dispatch
            val update = AodPositionUpdate(
                attachmentGeneration,
                translationX,
                translationY,
                safeBottom,
                clockTop,
                clockBottom,
                lyricTopSafe,
                zone
            )
            if (zoneChanged) {
                HookLogger.i(
                    TAG,
                    "Stock scene zone=$zone translation=($translationX,$translationY) " +
                        "clock=${clockTop ?: "?"}..${clockBottom ?: "?"}"
                )
            }
            if (zoneChanged && animated) {
                pendingStockMotionUpdate = update
                mainHandler.removeCallbacks(stockMotionSettleTimeout)
                fadeForStockMotion()
                mainHandler.postDelayed(stockMotionSettleTimeout, STOCK_MOTION_SETTLE_TIMEOUT_MS)
            } else if (pendingStockMotionUpdate != null && animated) {
                pendingStockMotionUpdate = update
            } else {
                if (!animated) {
                    pendingStockMotionUpdate = null
                    mainHandler.removeCallbacks(stockMotionSettleTimeout)
                    cancelStockMotionTransition(resetAlpha = true)
                }
                enqueueGeometryUpdate(update)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            dispatch()
        } else {
            mainHandler.post { dispatch() }
        }
    }

    fun isStockWidgetControlActive(): Boolean = stockWidgetControlActive

    fun onStockPositionSettled() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            mainHandler.removeCallbacks(stockMotionSettleTimeout)
            settleStockMotion("callback")
        } else {
            mainHandler.post {
                mainHandler.removeCallbacks(stockMotionSettleTimeout)
                settleStockMotion("callback")
            }
        }
    }

    fun managedBurnInPattern(): String = burnInPattern

    fun onStockMediaPlayerPresenceChanged(present: Boolean) {
        val update = update@{
            if (stockMediaPlayerPresent == present) return@update
            stockMediaPlayerPresent = present
            if (present) {
                latestSnapshot?.takeUnless { it.visible }?.let(::onLyricSnapshot)
                return@update
            }
            if (retainedMediaSnapshot == null) return@update
            cancelPausedKeepAliveExpiry()
            cancelPauseLingerExpiry()
            retainedMediaSnapshot = null
            lastVisibleSnapshot = null
            latestSnapshot = null
            setStockWidgetControlActive(false)
            hideSurfaceOnly(pulse = false)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            update()
        } else {
            mainHandler.post(update)
        }
    }

    override fun onLyricSnapshot(snapshot: LyricSnapshot) {
        val incomingSnapshot = LinkageTransitionCoordinator.resolveSnapshot(snapshot)
        if (incomingSnapshot.visible) lastVisibleSnapshot = incomingSnapshot
        else if (lastVisibleSnapshot == null) {
            lastVisibleSnapshot = SystemUiLyricProjectionRuntime.projection.cachedVisibleSnapshot()
        }
        retainedMediaSnapshot = retainedAodSnapshotAfterUpdate(
            incomingSnapshot,
            lastVisibleSnapshot,
            retainedMediaSnapshot,
            stockMediaPlayerPresent,
            SystemClock.elapsedRealtime(),
            customization?.pauseLingerMs ?: 5_000L
        )
        schedulePausedKeepAliveExpiry(retainedMediaSnapshot)
        schedulePauseLingerExpiry(retainedMediaSnapshot)
        val resolvedSnapshot = if (incomingSnapshot.visible) {
            incomingSnapshot
        } else {
            retainedMediaSnapshot ?: incomingSnapshot
        }
        val wasFollowingPosition = latestSnapshot?.positionFollowingEnabled == true
        latestSnapshot = resolvedSnapshot
        if (!incomingSnapshot.visible && retainedMediaSnapshot == null &&
            !incomingSnapshot.playbackActive
        ) {
            lastVisibleSnapshot = null
        }
        if (HookLogger.traceEnabled) {
            val snapshotTrace =
                "Snapshot revision=${resolvedSnapshot.revision} visible=${resolvedSnapshot.visible} " +
                    "retained=${retainedMediaSnapshot != null && !incomingSnapshot.visible} " +
                    "lineLevel=${resolvedSnapshot.lineLevelSync} " +
                    "keepAlive=${resolvedSnapshot.keepAlive} " +
                    "render=${canRenderAod(resolvedSnapshot)} " +
                    "surface=${surface != null}/${surface?.visibility} " +
                    "root=${rootRef.get()?.width}x${rootRef.get()?.height} " +
                    "failed=$transitionFailedHidden"
            if (snapshotTrace != lastSnapshotTrace) {
                lastSnapshotTrace = snapshotTrace
                HookLogger.i(TAG, snapshotTrace)
            }
        }
        val burnInScheduleChanged = burnInPattern != resolvedSnapshot.burnInPattern ||
            managedAodPatternRepeats(resolvedSnapshot.burnInPattern) &&
            burnInIntervalMs != resolvedSnapshot.burnInIntervalMs
        burnInPattern = resolvedSnapshot.burnInPattern
        burnInIntervalMs = resolvedSnapshot.burnInIntervalMs
        setStockWidgetControlActive(
            resolvedSnapshot.positionFollowingEnabled &&
                canRenderAod(resolvedSnapshot) &&
                XiaomiCapabilityResolver.hasCapability(XiaomiCapability.AOD_POSITION_UPDATES),
            restartSchedule = burnInScheduleChanged
        )
        if (!resolvedSnapshot.positionFollowingEnabled && wasFollowingPosition) {
            environment = environment.copy(
                burnInTranslationX = 0f,
                burnInTranslationY = 0f,
                safeBottom = null
            )
            requestGeometryUpdate()
        } else if (resolvedSnapshot.positionFollowingEnabled && !wasFollowingPosition &&
            XiaomiCapabilityResolver.hasCapability(XiaomiCapability.AOD_POSITION_UPDATES)
        ) {
            val burnInContainer = burnInContainerRef.get()
            enqueueGeometryUpdate(
                burnInContainer?.translationX ?: 0f,
                burnInContainer?.translationY ?: 0f,
                null
            )
        }
        if (!canRenderAod(resolvedSnapshot)) {
            hideSurfaceOnly()
            return
        }
        val demo = resolvedSnapshot.metadata.startsWith("AOD DEMO")
        val renderContent = resolvedSnapshot.renderContent()
        val directSurface = surface ?: return
        val root = rootRef.get() ?: return
        val burnInContainer = burnInContainerRef.get() ?: return
        val wakeRequired = isNewAodWakeSignal(lastWakeSignal, resolvedSnapshot.wakeSignal)
        lastWakeSignal = resolvedSnapshot.wakeSignal
        if (renderContent == lastRenderContent && directSurface.visibility == View.VISIBLE) {
            updateLifetimeGuard()
            requestWakeIfAllowed(root, directSurface, wakeRequired)
            return
        }
        if (!layoutSurface(root, burnInContainer, directSurface)) return
        lyricCanvas?.setContent(resolvedSnapshot.toAodCanvasContent(effectiveAodProfile()))
        lastRenderContent = renderContent
        lyricCanvas?.visibility = if (demo) View.GONE else View.VISIBLE
        spicyAnimationView?.visibility = if (demo) View.VISIBLE else View.GONE
        if (demo) spicyAnimationView?.start() else spicyAnimationView?.stop()
        directSurface.visibility = View.VISIBLE
        updateLifetimeGuard()
        LinkageTransitionCoordinator.onSurfaceReady(LyricSurfaceKind.AOD)
        requestWakeIfAllowed(root, directSurface, wakeRequired)
    }

    override fun onLyricKeepAlive(signal: LyricKeepAliveSignal) {
        val retained = retainedMediaSnapshot?.let {
            expirePausedAodKeepAlive(it, SystemClock.elapsedRealtime())
        }
        if (retained != retainedMediaSnapshot) retainedMediaSnapshot = retained
        schedulePausedKeepAliveExpiry(retained)
        val effectiveKeepAlive = retained?.keepAlive ?: signal.keepAlive
        latestSnapshot = latestSnapshot?.copy(
            updatedAtElapsedMs = signal.updatedAtElapsedMs,
            keepAlive = effectiveKeepAlive,
            wakeSignal = signal.wakeSignal,
            playbackActive = signal.playbackActive,
            pauseRetentionEligible = signal.pauseRetentionEligible
        )
        updateLifetimeGuard()
        val wakeRequired = isNewAodWakeSignal(lastWakeSignal, signal.wakeSignal)
        lastWakeSignal = signal.wakeSignal
        if (!effectiveKeepAlive && !wakeRequired) return
        val directSurface = surface ?: return
        val root = rootRef.get() ?: return
        requestWakeIfAllowed(root, directSurface, wakeRequired)
    }

    override fun onLyricProjectionDisconnected() {
        cancelPausedKeepAliveExpiry()
        cancelPauseLingerExpiry()
        latestSnapshot = null
        lastVisibleSnapshot = null
        retainedMediaSnapshot = null
        customization = null
        runtimeProfile = null
        setStockWidgetControlActive(false)
        hideSurfaceOnly(pulse = false)
    }

    override fun onLyricProjectionStale() {
        cancelPausedKeepAliveExpiry()
        cancelPauseLingerExpiry()
        latestSnapshot = null
        lastVisibleSnapshot = null
        retainedMediaSnapshot = null
        setStockWidgetControlActive(false)
        hideSurfaceOnly(pulse = false)
    }

    override fun onCustomization(configuration: CompiledCustomization) {
        customization = configuration
        val retained = retainedMediaSnapshot?.takeIf { snapshot ->
            !snapshot.pauseRetentionEligible || pauseLingerRemainingMs(
                snapshot.sampledAtElapsedMs,
                configuration.pauseLingerMs,
                SystemClock.elapsedRealtime()
            ) != null
        }
        if (retainedMediaSnapshot != null && retained == null) {
            retainedMediaSnapshot = null
            lastVisibleSnapshot = null
            latestSnapshot = null
            cancelPauseLingerExpiry()
            setStockWidgetControlActive(false)
            hideSurfaceOnly(pulse = false)
            return
        }
        runtimeProfile = null
        lastRenderContent = null
        latestSnapshot?.let(::onLyricSnapshot)
        if (retained != null) {
            retainedMediaSnapshot = retained
            latestSnapshot = retained
            schedulePausedKeepAliveExpiry(retained)
            schedulePauseLingerExpiry(retained)
        }
    }

    private fun schedulePausedKeepAliveExpiry(retained: LyricSnapshot?) {
        mainHandler.removeCallbacks(pausedKeepAliveExpiry)
        if (retained?.keepAlive != true) return
        val pausedForMs = (SystemClock.elapsedRealtime() - retained.sampledAtElapsedMs)
            .coerceAtLeast(0L)
        val delayMs = (PAUSED_AOD_KEEP_ALIVE_MS - pausedForMs).coerceAtLeast(0L)
        mainHandler.postDelayed(pausedKeepAliveExpiry, delayMs)
    }

    private fun cancelPausedKeepAliveExpiry() {
        mainHandler.removeCallbacks(pausedKeepAliveExpiry)
    }

    private fun schedulePauseLingerExpiry(retained: LyricSnapshot?) {
        cancelPauseLingerExpiry()
        retained?.takeIf { it.pauseRetentionEligible } ?: return
        val remaining = pauseLingerRemainingMs(
            retained.sampledAtElapsedMs,
            customization?.pauseLingerMs ?: 5_000L,
            SystemClock.elapsedRealtime()
        ) ?: run {
            retainedMediaSnapshot = null
            lastVisibleSnapshot = null
            latestSnapshot = latestSnapshot?.takeUnless { it === retained }
            setStockWidgetControlActive(false)
            hideSurfaceOnly(pulse = false)
            return
        }
        if (remaining != Long.MAX_VALUE) mainHandler.postDelayed(pauseLingerExpiry, remaining)
    }

    private fun cancelPauseLingerExpiry() {
        mainHandler.removeCallbacks(pauseLingerExpiry)
    }

    private fun hideSurfaceOnly(pulse: Boolean = true) {
        if (latestSnapshot?.let(::canRenderAod) != true) {
            setStockWidgetControlActive(false)
        }
        finishInitialReveal()
        surface?.animate()?.cancel()
        surface?.alpha = 1f
        val wasVisible = surface?.visibility == View.VISIBLE
        surface?.visibility = View.GONE
        lyricCanvas?.stop()
        lyricCanvas?.visibility = View.GONE
        spicyAnimationView?.stop()
        spicyAnimationView?.visibility = View.GONE
        lastRenderContent = null
        updateLifetimeGuard()
        if (pulse && wasVisible && isSceneActive()) rootRef.get()?.let(::pulseDrawWakeLock)
    }

    private fun detachCurrent() {
        attachmentGeneration++
        mainHandler.removeCallbacks(geometryUpdate)
        mainHandler.removeCallbacks(stockMotionSettleTimeout)
        mainHandler.removeCallbacks(managedBurnInStart)
        mainHandler.removeCallbacks(managedBurnInAdvance)
        cancelPausedKeepAliveExpiry()
        cancelPauseLingerExpiry()
        pendingStockMotionUpdate = null
        stockMotionRevealPending = false
        cancelStockMotionTransition(resetAlpha = false)
        positionUpdates.clear()
        stockWidgetControlActive = false
        AodPositionHook.restoreStockTranslation()
        AodPositionHook.abandonManagedSession()
        setDrawWakeRenewalActive(false)
        finishInitialReveal()
        AodPowerCoordinator.onSurfaceDetached()
        LinkageTransitionCoordinator.unregisterSurface(this)
        SystemUiLyricProjectionRuntime.projection.detach(this)
        rootRef.get()?.removeOnLayoutChangeListener(layoutChangeListener)
        burnInContainerRef.get()?.removeOnLayoutChangeListener(layoutChangeListener)
        burnInContainerRef.get()?.viewTreeObserver?.takeIf { it.isAlive }
            ?.removeOnPreDrawListener(clockGeometryPreDrawListener)
        displayManager?.unregisterDisplayListener(displayListener)
        displayManager = null
        observedDisplayId = -1
        lastObservedDisplayState = -1
        surface?.let { directSurface ->
            rootRef.get()?.overlay?.remove(directSurface)
            (directSurface.parent as? ViewGroup)?.removeView(directSurface)
        }
        surface = null
        lyricCanvas = null
        spicyAnimationView = null
        rootRef.clear()
        burnInContainerRef.clear()
        latestSnapshot = null
        lastVisibleSnapshot = null
        retainedMediaSnapshot = null
        stockMediaPlayerPresent = false
        customization = null
        runtimeProfile = null
        lastRenderContent = null
        lastWakeSignal = Long.MIN_VALUE
        sceneRole = LinkageSceneRole.INACTIVE
        handoffActive = false
        initialRevealPending = true
        initialRevealActive = false
        initialRevealStartedAt = 0L
        initialRevealDurationMs = 0L
        transitionFailedHidden = false
        lastLayoutBlockTrace = null
        lastSnapshotTrace = null
        lastBrightClockMorphPhase = null
        lastClockGeometryAuthority = null
        sceneZone = AodSceneZone.STOCK
        controlledClockTop = null
        controlledClockBottom = null
        controlledLyricTopSafe = null
        systemUiClockBounds = null
        aodControllerClockBounds = null
        renderedClockBounds = null
        environment = SurfaceEnvironment(LyricSurfaceKind.AOD, attachmentGeneration)
    }

    private fun setStockWidgetControlActive(active: Boolean, restartSchedule: Boolean = false) {
        if (stockWidgetControlActive == active) {
            if (active && restartSchedule) startManagedBurnInSchedule()
            return
        }
        stockWidgetControlActive = active
        if (active) {
            startManagedBurnInSchedule()
        } else {
            pendingStockMotionUpdate = null
            stockMotionRevealPending = false
            mainHandler.removeCallbacks(stockMotionSettleTimeout)
            cancelStockMotionTransition(resetAlpha = true)
            managedPositionRetryCount = 0
            mainHandler.removeCallbacks(managedBurnInStart)
            mainHandler.removeCallbacks(managedBurnInAdvance)
            AodPositionHook.restoreStockTranslation()
        }
    }

    private fun startManagedBurnInSchedule() {
        mainHandler.removeCallbacks(managedBurnInStart)
        mainHandler.removeCallbacks(managedBurnInAdvance)
        AodPositionHook.restartManagedPattern()
        managedPositionRetryCount = 0
        mainHandler.post(managedBurnInStart)
    }

    private fun updateLifetimeGuard() {
        val snapshot = latestSnapshot
        val active = XiaomiCapabilityResolver.hasCapability(
            XiaomiCapability.AOD_LIFETIME_GUARD
        ) && shouldRenewAodDraw(
            surfaceKind = surfaceKind,
            attached = rootRef.get() != null,
            sceneActive = isSceneActive(),
            effectivelyVisible = isSurfaceRenderActive() &&
                snapshot != null && canRenderAod(snapshot),
            pendingStockMotion = pendingStockMotionUpdate != null,
            keepAlive = snapshot?.keepAlive == true
        )
        setDrawWakeRenewalActive(active)
    }

    private fun setDrawWakeRenewalActive(active: Boolean) {
        if (drawWakeRenewalActive == active) return
        drawWakeRenewalActive = active
        mainHandler.removeCallbacks(drawWakeRenewal)
        HookLogger.i(TAG, "Draw wake renewal active=$active")
        if (!active) return
        rootRef.get()?.let(::pulseDrawWakeLock)
        mainHandler.postDelayed(drawWakeRenewal, DRAW_WAKE_RENEW_INTERVAL_MS)
    }

    private fun startInitialReveal(directSurface: View, durationMs: Long) {
        initialRevealPending = false
        initialRevealActive = true
        initialRevealStartedAt = SystemClock.elapsedRealtime()
        initialRevealDurationMs = durationMs.coerceIn(150L, 600L)
        directSurface.animate().cancel()
        directSurface.alpha = 0f
        mainHandler.removeCallbacks(initialRevealFrame)
        mainHandler.post(initialRevealFrame)
    }

    private fun finishInitialReveal() {
        mainHandler.removeCallbacks(initialRevealFrame)
        initialRevealActive = false
        initialRevealStartedAt = 0L
        initialRevealDurationMs = 0L
        surface?.alpha = 1f
    }

    private fun buildSurface(root: ViewGroup): LinearLayout {
        val density = root.resources.displayMetrics.density
        return LinearLayout(root.context).apply {
            tag = SURFACE_TAG
            orientation = LinearLayout.VERTICAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            isClickable = false
            isFocusable = false
            visibility = View.GONE
            setPadding((8f * density).roundToInt(), 0, (8f * density).roundToInt(), 0)
            val lyricContent = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                lyricCanvas = AodLyricCanvasView(context, useDozeHandlerCadence = true).also {
                    it.layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    addView(it)
                }
                spicyAnimationView = AodSpicyAnimationView(context).apply {
                    visibility = View.GONE
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    addView(this)
                }
            }
            addView(lyricContent)
        }
    }

    private fun layoutSurface(
        root: ViewGroup,
        burnInContainer: FrameLayout,
        directSurface: View
    ): Boolean {
        if (!hasUsableAodRootSize(root.width, root.height)) {
            failClosedLayout(directSurface, "root=${root.width}x${root.height}")
            return false
        }
        val rootLocation = IntArray(2)
        root.getLocationInWindow(rootLocation)
        var stockTop = root.height
        var stockBottom = 0
        var foundStockContent = false
        val childLocation = IntArray(2)
        for (index in 0 until burnInContainer.childCount) {
            val child = burnInContainer.getChildAt(index)
            if (child.visibility == View.GONE) continue
            child.getLocationInWindow(childLocation)
            foundStockContent = true
            stockTop = minOf(stockTop, childLocation[1] - rootLocation[1])
            stockBottom = maxOf(
                stockBottom,
                stockBottomInRoot(rootLocation[1], childLocation[1], child.height)
            )
        }
        if (!foundStockContent) stockTop = 0
        val density = root.resources.displayMetrics.density
        val margin = (SURFACE_MARGIN_DP * density).roundToInt()
        val brightLinkage = isBrightClockMorphPhase(root)
        val physicalClockBounds = selectPhysicalAodClockBounds(
            systemUiClockBounds,
            aodControllerClockBounds
        )
        if (physicalClockBounds != null && root.height > 0) {
            rememberedPhysicalClockBounds = physicalClockBounds
            rememberedPhysicalClockRootHeight = root.height
            rememberedPhysicalClockTranslationY = environment.burnInTranslationY
        }
        // A remembered measurement only describes this layout. A different root height means a
        // different display or configuration, and the old bounds say nothing about it.
        //
        // The bounds are used as measured. Carrying them by our own burn-in delta was tried and
        // reverted: the container keeps its translation across a re-attach while `environment`
        // resets to zero, so the delta is a phantom exactly when the remembered bounds are needed.
        // It moved the lyrics 333 px off a position that was already correct. The two offsets stay
        // in the log because they are what showed that.
        val rememberedPhysicalClockBounds = rememberedPhysicalClockBounds
            ?.takeIf { rememberedPhysicalClockRootHeight == root.height }
        val effectiveClockBounds = if (brightLinkage && physicalClockBounds == null) {
            brightLinkageClockBounds(root.height)
        } else {
            resolvedAodClockBounds(
                renderedClockBounds,
                controlledClockTop,
                controlledClockBottom,
                stockTop,
                stockBottom,
                physicalClockBounds,
                rememberedPhysicalClockBounds
            )
        }
        val effectiveClockTop = effectiveClockBounds.top
        val effectiveClockBottom = effectiveClockBounds.bottom
        val clockGeometryAuthority = when {
            systemUiClockBounds != null -> "physical-systemui"
            aodControllerClockBounds != null -> "physical-aod"
            brightLinkage -> "bright-fallback"
            rememberedPhysicalClockBounds != null -> "physical-remembered"
            controlledClockTop != null && controlledClockBottom != null -> "managed"
            renderedClockBounds != null -> "rendered-fallback"
            else -> "measured-fallback"
        }
        if (clockGeometryAuthority != lastClockGeometryAuthority) {
            lastClockGeometryAuthority = clockGeometryAuthority
            HookLogger.i(
                TAG,
                "Clock geometry authority=$clockGeometryAuthority bounds=" +
                    "$effectiveClockTop..$effectiveClockBottom managed=" +
                    "${controlledClockTop ?: "?"}..${controlledClockBottom ?: "?"} burnInY=" +
                    "${environment.burnInTranslationY.roundToInt()} rememberedAtY=" +
                    "${rememberedPhysicalClockTranslationY.roundToInt()}"
            )
        }
        val layoutZone = if (brightLinkage || physicalClockBounds != null) {
            resolveRenderedAodSceneZone(
                AodSceneZone.CLOCK_TOP,
                effectiveClockBounds,
                root.height,
                margin
            )
        } else if (controlledClockTop != null && controlledClockBottom != null &&
            sceneZone != AodSceneZone.STOCK
        ) {
            sceneZone
        } else {
            resolveRenderedAodSceneZone(
                sceneZone,
                effectiveClockBounds,
                root.height,
                margin
            )
        }
        val profile = currentAodProfile()
        val metadataHeight = if (profile.metadataVisible &&
            profile.widgets.any { it.type == "metadata" }
        ) {
            metadataWidgetHeightDp(profile.metadataSizePercent) * density
        } else {
            0f
        }
        val desiredHeight = root.height * profile.maxHeightFraction
        val measurements = profile.widgets.mapNotNull { widget ->
            when (widget.type) {
                "lyrics" -> WidgetMeasurement(
                    widget,
                    (desiredHeight - metadataHeight).coerceAtLeast(MIN_LYRIC_HEIGHT_DP * density)
                )
                "metadata" -> WidgetMeasurement(widget, metadataHeight)
                else -> null
            }
        }
        val safeCanvas = aodSceneSafeCanvas(
            root.width,
            root.height,
            effectiveClockTop,
            minOf(controlledLyricTopSafe ?: margin, effectiveClockTop).coerceAtLeast(0),
            margin,
            layoutZone
        )
        val placement = PlacementEngine.resolve(
            profile.copy(
                maxHeightFraction = aodPlacementMaxHeightFraction(
                    profile.maxHeightFraction,
                    layoutZone
                )
            ),
            PlacementEnvironment(
                safeCanvas = safeCanvas,
                stockClockBottom = if (layoutZone == AodSceneZone.CLOCK_BOTTOM) {
                    safeCanvas.top
                } else {
                    (effectiveClockBottom + margin).toFloat()
                },
                bottomReserveTop = if (layoutZone == AodSceneZone.CLOCK_BOTTOM) {
                    safeCanvas.bottom
                } else {
                    ((environment.safeBottom ?: root.height) - margin)
                        .coerceAtLeast(0).toFloat()
                }
            ),
            measurements,
            minimumLyricHeight = MIN_LYRIC_HEIGHT_DP * density
        )
        val placed = placement.contentRect
        if (placed != null) {
            val visibleTypes = placement.visibleWidgets.mapTo(HashSet()) { it.type }
            val nextRuntimeProfile = profile.copy(
                widgets = profile.widgets.filter { it.type in visibleTypes },
                metadataVisible = profile.metadataVisible && "metadata" in visibleTypes
            )
            if (runtimeProfile != nextRuntimeProfile) {
                runtimeProfile = nextRuntimeProfile
                lastRenderContent = null
                latestSnapshot?.takeIf {
                    !it.metadata.startsWith("AOD DEMO")
                }?.let {
                    lyricCanvas?.setContent(it.toAodCanvasContent(nextRuntimeProfile))
                    lastRenderContent = it.renderContent()
                }
            }
        }
        val horizontalShift = environment.burnInTranslationX.roundToInt()
        val placedWidth = placed?.width?.roundToInt() ?: 0
        val maxLeft = (root.width - placedWidth).coerceAtLeast(0)
        val shiftedLeft = ((placed?.left?.roundToInt() ?: 0) + horizontalShift).coerceIn(0, maxLeft)
        val rect = AodSurfaceRect(
            shiftedLeft,
            placed?.top?.roundToInt() ?: 0,
            shiftedLeft + placedWidth,
            placed?.bottom?.roundToInt() ?: 0
        )
        if (rect.width <= 0 || rect.height <= 0) {
            failClosedLayout(
                directSurface,
                "invalid-rect=$rect stock=$effectiveClockTop..$effectiveClockBottom " +
                    "zone=$layoutZone managed=$sceneZone placed=$placed"
            )
            return false
        }
        if (lastLayoutBlockTrace != null) {
            HookLogger.i(
                TAG,
                "Layout ready after=$lastLayoutBlockTrace rect=$rect zone=$layoutZone"
            )
            lastLayoutBlockTrace = null
        }
        directSurface.measure(
            View.MeasureSpec.makeMeasureSpec(rect.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(rect.height, View.MeasureSpec.EXACTLY)
        )
        directSurface.layout(rect.left, rect.top, rect.right, rect.bottom)
        if (stockMotionRevealPending) {
            stockMotionRevealPending = false
            directSurface.alpha = 0f
            startStockMotionAlphaAnimation(
                targetAlpha = 1f,
                durationMs = STOCK_MOTION_FADE_IN_MS,
                completesTransition = true
            )
        }
        if (!handoffActive && !initialRevealActive && !stockMotionTransitionActive) {
            directSurface.alpha = 1f
        }
        val snapshot = latestSnapshot
        val visible = snapshot != null && canRenderAod(snapshot)
        directSurface.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible && !snapshot.metadata.startsWith("AOD DEMO") &&
            lyricCanvas?.visibility != View.VISIBLE
        ) {
            lyricCanvas?.setContent(snapshot.toAodCanvasContent(effectiveAodProfile()))
            lyricCanvas?.visibility = View.VISIBLE
        }
        if (visible && initialRevealPending && !handoffActive) {
            startInitialReveal(
                directSurface,
                effectiveAodProfile().transition.durationMs.toLong()
            )
        }
        environment = environment.copy(
            rootWidth = root.width,
            rootHeight = root.height,
            stockBottom = effectiveClockBottom
        )
        updateLifetimeGuard()
        if (visible) LinkageTransitionCoordinator.onSurfaceReady(LyricSurfaceKind.AOD)
        return true
    }

    private fun renderedStockClockBounds(
        root: ViewGroup,
        burnInContainer: ViewGroup,
        previous: AodRenderedClockBounds?
    ): AodRenderedClockBounds? {
        root.getLocationInWindow(renderedClockRootLocation)
        renderedClockUnion.setEmpty()
        var found = false
        for (index in 0 until burnInContainer.childCount) {
            found = collectRenderedClockBounds(
                burnInContainer.getChildAt(index),
                burnInContainer.alpha,
                root
            ) || found
        }
        if (!found || renderedClockUnion.bottom <= renderedClockUnion.top) return null
        return previous?.takeIf {
            it.top == renderedClockUnion.top && it.bottom == renderedClockUnion.bottom
        } ?: AodRenderedClockBounds(renderedClockUnion.top, renderedClockUnion.bottom)
    }

    private fun collectRenderedClockBounds(
        view: View,
        inheritedAlpha: Float,
        root: ViewGroup
    ): Boolean {
        if (view.visibility != View.VISIBLE || view.width <= 0 || view.height <= 0) return false
        val effectiveAlpha = inheritedAlpha * view.alpha
        if (effectiveAlpha <= MIN_RENDERED_CLOCK_ALPHA) return false
        if (view is ViewGroup && view.childCount > 0) {
            var childRendered = false
            for (index in 0 until view.childCount) {
                childRendered = collectRenderedClockBounds(
                    view.getChildAt(index),
                    effectiveAlpha,
                    root
                ) || childRendered
            }
            if (childRendered) return true
            if (view.background == null && view.willNotDraw()) return false
        }
        if (!view.getGlobalVisibleRect(renderedClockScratch) ||
            renderedClockScratch.height() <= 0
        ) return false
        renderedClockScratch.offset(
            -renderedClockRootLocation[0],
            -renderedClockRootLocation[1]
        )
        if (!renderedClockScratch.intersect(0, 0, root.width, root.height)) return false
        if (renderedClockUnion.isEmpty) {
            renderedClockUnion.set(renderedClockScratch)
        } else {
            renderedClockUnion.union(renderedClockScratch)
        }
        return true
    }

    private fun traceLayoutBlock(reason: String) {
        if (!HookLogger.traceEnabled) return
        if (lastLayoutBlockTrace == reason) return
        lastLayoutBlockTrace = reason
        HookLogger.i(TAG, "Layout blocked reason=$reason snapshot=${latestSnapshot?.revision}")
    }

    private fun failClosedLayout(directSurface: View, reason: String) {
        traceLayoutBlock(reason)
        directSurface.visibility = View.GONE
        lyricCanvas?.stop()
        lyricCanvas?.visibility = View.GONE
        spicyAnimationView?.stop()
        spicyAnimationView?.visibility = View.GONE
        lastRenderContent = null
        updateLifetimeGuard()
    }

    private fun fadeForStockMotion() {
        if (surface == null) return
        stockMotionTransitionActive = true
        startStockMotionAlphaAnimation(
            targetAlpha = 0f,
            durationMs = STOCK_MOTION_FADE_OUT_MS,
            completesTransition = false
        )
    }

    private fun startStockMotionAlphaAnimation(
        targetAlpha: Float,
        durationMs: Long,
        completesTransition: Boolean
    ) {
        val directSurface = surface ?: return
        mainHandler.removeCallbacks(stockMotionAlphaFrame)
        stockMotionAlphaFrom = directSurface.alpha
        stockMotionAlphaTo = targetAlpha.coerceIn(0f, 1f)
        stockMotionAlphaStartedAt = SystemClock.elapsedRealtime()
        stockMotionAlphaDurationMs = durationMs.coerceAtLeast(1L)
        stockMotionAlphaCompletesTransition = completesTransition
        stockMotionAlphaAnimationActive = true
        mainHandler.post(stockMotionAlphaFrame)
    }

    private fun cancelStockMotionTransition(resetAlpha: Boolean) {
        mainHandler.removeCallbacks(stockMotionAlphaFrame)
        stockMotionTransitionActive = false
        stockMotionAlphaAnimationActive = false
        stockMotionAlphaCompletesTransition = false
        stockMotionAlphaStartedAt = 0L
        stockMotionAlphaDurationMs = 0L
        if (resetAlpha) surface?.alpha = 1f
    }

    override fun transitionRectInWindow(): TransitionRect? =
        if (awaitingInitialManagedLinkageGeometry()) null else transitionRectInWindow(surface)

    override fun presentationRectInWindow(): TransitionRect? =
        presentationRectInWindow(surface)

    override fun setSceneRole(role: LinkageSceneRole) {
        if (sceneRole == role) return
        sceneRole = role
        lyricCanvas?.setSceneActive(isSceneActive())
        if (!isSceneActive()) {
            pendingStockMotionUpdate = null
            stockMotionRevealPending = false
            mainHandler.removeCallbacks(stockMotionSettleTimeout)
            setStockWidgetControlActive(false)
            resetLinkageView(surface)
            hideSurfaceOnly(pulse = false)
        }
        updateLifetimeGuard()
    }

    override fun setHandoffActive(active: Boolean) {
        handoffActive = active
        if (active) {
            initialRevealPending = false
            finishInitialReveal()
        }
        lyricCanvas?.setHandoffActive(active)
    }

    override fun animateFrom(
        source: TransitionRect?,
        fadeIn: Boolean,
        preserveAlpha: Boolean,
        durationMs: Long,
        token: Long,
        onComplete: (Long) -> Unit
    ) {
        val preserveStockLinkageAlpha = AodPositionHook.isLinkageMode()
        animateLinkageView(
            surface,
            source,
            fadeIn,
            preserveAlpha || preserveStockLinkageAlpha,
            durationMs,
            token,
            onComplete
        )
    }

    override fun fadeOut(durationMs: Long) {
        fadeOutLinkageView(surface, durationMs)
    }

    override fun resetTransition() {
        transitionFailedHidden = false
        resetLinkageView(surface)
    }

    override fun hideForFailedTransition() {
        transitionFailedHidden = true
        hideSurfaceOnly()
    }

    override fun applyTransitionSnapshot(snapshot: LyricSnapshot) {
        onLyricSnapshot(snapshot)
    }

    private fun aodProfile() = customization?.profiles?.get(SceneCompiler.SURFACE_AOD)

    private fun currentAodProfile(): CompiledSurfaceProfile =
        aodProfile() ?: DEFAULT_AOD_PROFILE

    private fun effectiveAodProfile(): CompiledSurfaceProfile =
        runtimeProfile ?: currentAodProfile()

    private fun canRenderAod(snapshot: LyricSnapshot): Boolean =
        XiaomiCapabilityResolver.hasCapability(XiaomiCapability.AOD_SURFACE) &&
            shouldRenderAodSnapshot(
                sceneActive = isSceneActive(),
                snapshotVisible = snapshot.visible,
                spotifyAuthorized = snapshot.isAuthorizedForPresentation(),
                featureEnabled = snapshot.aodEnabled,
                profileEnabled = aodProfile()?.enabled != false,
                transitionFailed = transitionFailedHidden
            )

    private fun requestGeometryUpdate() {
        enqueueGeometryUpdate(
            AodPositionUpdate(
                attachmentGeneration,
                environment.burnInTranslationX,
                environment.burnInTranslationY,
                environment.safeBottom,
                controlledClockTop,
                controlledClockBottom,
                controlledLyricTopSafe,
                sceneZone
            )
        )
    }

    private fun observeDisplayState(root: ViewGroup) {
        displayManager?.unregisterDisplayListener(displayListener)
        displayManager = root.context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        observedDisplayId = root.display?.displayId ?: -1
        lastObservedDisplayState = root.display?.state ?: -1
        displayManager?.registerDisplayListener(displayListener, mainHandler)
        HookLogger.i(
            TAG,
            "AOD root display observer registered displayId=$observedDisplayId " +
                "state=$lastObservedDisplayState"
        )
        LinkageTransitionCoordinator.onAodDisplayState(lastObservedDisplayState)
        AodPowerCoordinator.onAodDisplayState(lastObservedDisplayState)
    }

    private fun enqueueGeometryUpdate(
        translationX: Float,
        translationY: Float,
        safeBottom: Int?
    ) = enqueueGeometryUpdate(
        AodPositionUpdate(
            attachmentGeneration,
            translationX,
            translationY,
            safeBottom,
            controlledClockTop,
            controlledClockBottom,
            controlledLyricTopSafe,
            sceneZone
        )
    )

    private fun enqueueGeometryUpdate(update: AodPositionUpdate) {
        if (surface == null || rootRef.get() == null) return
        val shouldSchedule = positionUpdates.offer(update)
        if (shouldSchedule) mainHandler.post(geometryUpdate)
    }

    private fun findBurnInContainer(root: ViewGroup): FrameLayout? = runCatching {
        root.javaClass.getDeclaredField("mTableModeContainer").apply { isAccessible = true }
            .get(root) as? FrameLayout
    }.getOrNull()

    private fun isSceneActive(): Boolean = sceneRole != LinkageSceneRole.INACTIVE

    private fun isBrightClockMorphPhase(root: ViewGroup): Boolean {
        val active = shouldUseBrightClockMorphGeometry(
            linkageMode = AodPositionHook.isLinkageMode(),
            morphingToAod = SystemUiClockMorphHook.isMorphingToAod(),
            linkageAwaitingDim = LinkageTransitionCoordinator.isAwaitingAodDimOwnership(),
            displayState = root.display?.state ?: -1
        )
        if (active != lastBrightClockMorphPhase) {
            lastBrightClockMorphPhase = active
            HookLogger.i(
                TAG,
                "Bright clock morph phase active=$active display=${root.display?.state ?: -1} " +
                    "physical=${SystemUiClockMorphHook.isMorphingToAod()} " +
                    "linkage=${LinkageTransitionCoordinator.isAwaitingAodDimOwnership()}"
            )
        }
        return active
    }

    private fun awaitingInitialManagedLinkageGeometry(): Boolean =
        stockWidgetControlActive &&
            AodPositionHook.isLinkageMode() &&
            (sceneZone == AodSceneZone.STOCK ||
                controlledClockTop == null || controlledClockBottom == null)

    private fun isSurfaceRenderActive(): Boolean {
        val directSurface = surface ?: return false
        if (!directSurface.isAttachedToWindow || directSurface.visibility != View.VISIBLE) return false
        return lyricCanvas?.visibility == View.VISIBLE || spicyAnimationView?.visibility == View.VISIBLE
    }

    private fun requestWakeIfAllowed(root: ViewGroup, directSurface: View, wakeRequired: Boolean) {
        if (!shouldRequestAodWake(
                attached = rootRef.get() === root,
                sceneActive = isSceneActive(),
                effectivelyVisible = isSurfaceRenderActive()
            )
        ) return
        if (wakeRequired) wakeAodSurface(root, directSurface) else pulseDrawWakeLock(root)
    }

    private fun pulseDrawWakeLock(root: ViewGroup) {
        runCatching {
            val wakeLock = root.javaClass.getDeclaredField("mWakeLock").apply { isAccessible = true }
                .get(root) ?: return
            wakeLock.javaClass.getMethod("setMaxAcquireTime", Long::class.javaPrimitiveType)
                .invoke(wakeLock, DRAW_WAKE_LOCK_MS)
            wakeLock.javaClass.getMethod("acquire", String::class.java)
                .invoke(wakeLock, "HyperGlowUpdate")
        }.onFailure { HookLogger.w(TAG, "Draw pulse failed", it) }
    }

    private fun wakeAodSurface(root: ViewGroup, directSurface: View) {
        if (!isSceneActive() || surface !== directSurface) return
        directSurface.visibility = View.VISIBLE
        if (!handoffActive && !initialRevealActive) directSurface.alpha = 1f
        directSurface.invalidate()
        root.invalidate()
        pulseDrawWakeLock(root)
        HookLogger.i(TAG, "AOD wake signal applied")
    }

    private const val DRAW_WAKE_LOCK_MS = 5_500L
    private const val DRAW_WAKE_RENEW_INTERVAL_MS = DRAW_WAKE_LOCK_MS / 2L
    private const val AOD_ANIMATION_FRAME_MS = 16L
    private const val SURFACE_MARGIN_DP = 12f
    private const val MIN_LYRIC_HEIGHT_DP = 96f
    private const val STOCK_MOTION_SETTLE_TIMEOUT_MS = 1_500L
    private const val STOCK_MOTION_FADE_OUT_MS = 150L
    private const val STOCK_MOTION_FADE_IN_MS = 180L
    private const val MIN_RENDERED_CLOCK_ALPHA = 0.02f
    private const val MANAGED_BURN_IN_RETRY_MS = 1_000L
    private const val MAX_MANAGED_POSITION_RETRIES = 5
    private val DEFAULT_AOD_PROFILE = SceneCompiler.compile(SceneCompiler.safeDefaultDocument())
        .profiles.getValue(SceneCompiler.SURFACE_AOD)
}
