package com.eza.hyperglow.root.aod

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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
import com.eza.hyperglow.root.projection.shouldActivateAodLifetime
import com.eza.hyperglow.root.projection.shouldRequestAodWake
import com.eza.hyperglow.root.surface.SurfaceEnvironment
import com.eza.hyperglow.root.surface.PlacementEngine
import com.eza.hyperglow.root.surface.PlacementEnvironment
import com.eza.hyperglow.root.surface.PlacementRect
import com.eza.hyperglow.root.surface.WidgetMeasurement
import com.eza.hyperglow.root.transition.LinkageSceneRole
import com.eza.hyperglow.root.transition.LinkageSurface
import com.eza.hyperglow.root.transition.LinkageTransitionCoordinator
import com.eza.hyperglow.root.transition.TransitionRect
import com.eza.hyperglow.root.transition.animateLinkageView
import com.eza.hyperglow.root.transition.fadeOutLinkageView
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
    transitionFailed: Boolean
): Boolean = sceneActive && snapshotVisible && featureEnabled && profileEnabled && !transitionFailed

internal fun retainedAodSnapshotAfterUpdate(
    incoming: LyricSnapshot,
    lastVisible: LyricSnapshot?,
    retained: LyricSnapshot?,
    mediaPlayerPresent: Boolean,
    nowElapsedMs: Long
): LyricSnapshot? = when {
    incoming.visible -> null
    !mediaPlayerPresent -> null
    retained != null -> retained
    lastVisible != null -> lastVisible.freezeAt(
        nowElapsedMs,
        keepAliveWhileFrozen = lastVisible.keepAlive
    )
    else -> null
}

internal fun smoothAodRevealProgress(progress: Float): Float {
    val value = progress.coerceIn(0f, 1f)
    return value * value * (3f - 2f * value)
}

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
    @Volatile private var stockWidgetControlActive = false
    @Volatile private var burnInPattern = "static_bottom"
    private var burnInIntervalMs = 60_000L
    private var sceneZone = AodSceneZone.STOCK
    private var controlledClockTop: Int? = null
    private var controlledClockBottom: Int? = null
    private var controlledLyricTopSafe: Int? = null
    private var pendingStockMotionUpdate: AodPositionUpdate? = null
    private var drawWakeRenewalActive = false
    private var initialRevealPending = true
    private var initialRevealActive = false
    private var initialRevealStartedAt = 0L
    private var initialRevealDurationMs = 0L
    private val layoutChangeListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        requestGeometryUpdate()
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
    private val stockMotionSettle = Runnable {
        val update = pendingStockMotionUpdate ?: return@Runnable
        pendingStockMotionUpdate = null
        if (update.generation != attachmentGeneration) return@Runnable
        enqueueGeometryUpdate(update)
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
                if (managedAodPatternRepeats(burnInPattern)) {
                    mainHandler.postDelayed(managedBurnInAdvance, burnInIntervalMs)
                }
            } else {
                mainHandler.postDelayed(this, MANAGED_BURN_IN_RETRY_MS)
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
                val directSurface = buildSurface(root)
                surface = directSurface
                root.overlay.add(directSurface)
                root.addOnLayoutChangeListener(layoutChangeListener)
                burnInContainer.addOnLayoutChangeListener(layoutChangeListener)
                LinkageTransitionCoordinator.registerSurface(this)
                val generation = attachmentGeneration
                root.post {
                    if (generation == attachmentGeneration && rootRef.get() === root) {
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
                mainHandler.removeCallbacks(stockMotionSettle)
                hideForStockMotion()
                mainHandler.postDelayed(stockMotionSettle, STOCK_MOTION_SETTLE_MS)
            } else if (pendingStockMotionUpdate != null && animated) {
                pendingStockMotionUpdate = update
            } else {
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
            SystemClock.elapsedRealtime()
        )
        val resolvedSnapshot = if (incomingSnapshot.visible) {
            incomingSnapshot
        } else {
            retainedMediaSnapshot ?: incomingSnapshot
        }
        val wasFollowingPosition = latestSnapshot?.positionFollowingEnabled == true
        latestSnapshot = resolvedSnapshot
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
        val wakeRequired = resolvedSnapshot.wakeSignal != lastWakeSignal
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
        latestSnapshot = latestSnapshot?.copy(
            updatedAtElapsedMs = signal.updatedAtElapsedMs,
            keepAlive = retainedMediaSnapshot?.keepAlive ?: signal.keepAlive,
            wakeSignal = signal.wakeSignal
        )
        updateLifetimeGuard()
        if (!signal.keepAlive) return
        val directSurface = surface ?: return
        val root = rootRef.get() ?: return
        val wakeRequired = signal.wakeSignal != lastWakeSignal
        lastWakeSignal = signal.wakeSignal
        requestWakeIfAllowed(root, directSurface, wakeRequired)
    }

    override fun onLyricProjectionDisconnected() {
        latestSnapshot = null
        lastVisibleSnapshot = null
        retainedMediaSnapshot = null
        customization = null
        runtimeProfile = null
        setStockWidgetControlActive(false)
        hideSurfaceOnly(pulse = false)
    }

    override fun onLyricProjectionStale() {
        latestSnapshot = null
        lastVisibleSnapshot = null
        retainedMediaSnapshot = null
        setStockWidgetControlActive(false)
        hideSurfaceOnly(pulse = false)
    }

    override fun onCustomization(configuration: CompiledCustomization) {
        val retained = retainedMediaSnapshot
        customization = configuration
        runtimeProfile = null
        lastRenderContent = null
        latestSnapshot?.let(::onLyricSnapshot)
        if (retained != null) {
            retainedMediaSnapshot = retained
            latestSnapshot = retained
        }
    }

    private fun hideSurfaceOnly(pulse: Boolean = true) {
        if (latestSnapshot?.let(::canRenderAod) != true) {
            setStockWidgetControlActive(false)
        }
        finishInitialReveal()
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
        mainHandler.removeCallbacks(stockMotionSettle)
        mainHandler.removeCallbacks(managedBurnInStart)
        mainHandler.removeCallbacks(managedBurnInAdvance)
        pendingStockMotionUpdate = null
        positionUpdates.clear()
        stockWidgetControlActive = false
        AodPositionHook.restoreStockTranslation()
        AodPositionHook.abandonManagedSession()
        setDrawWakeRenewalActive(false)
        finishInitialReveal()
        LinkageTransitionCoordinator.unregisterSurface(this)
        SystemUiLyricProjectionRuntime.projection.detach(this)
        AodLifetimeController.setLyricActive(false)
        rootRef.get()?.removeOnLayoutChangeListener(layoutChangeListener)
        burnInContainerRef.get()?.removeOnLayoutChangeListener(layoutChangeListener)
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
        sceneZone = AodSceneZone.STOCK
        controlledClockTop = null
        controlledClockBottom = null
        controlledLyricTopSafe = null
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
            mainHandler.removeCallbacks(managedBurnInStart)
            mainHandler.removeCallbacks(managedBurnInAdvance)
            AodPositionHook.restoreStockTranslation()
        }
    }

    private fun startManagedBurnInSchedule() {
        mainHandler.removeCallbacks(managedBurnInStart)
        mainHandler.removeCallbacks(managedBurnInAdvance)
        AodPositionHook.restartManagedPattern()
        mainHandler.post(managedBurnInStart)
    }

    private fun hideForStockMotion() {
        surface?.visibility = View.INVISIBLE
        lyricCanvas?.stop()
        lyricCanvas?.visibility = View.GONE
        lastRenderContent = null
        updateLifetimeGuard()
    }

    private fun updateLifetimeGuard() {
        val snapshot = latestSnapshot
        val active = XiaomiCapabilityResolver.hasCapability(
            XiaomiCapability.AOD_LIFETIME_GUARD
        ) && shouldActivateAodLifetime(
            surfaceKind = surfaceKind,
            attached = rootRef.get() != null,
            sceneActive = isSceneActive(),
            effectivelyVisible = isSurfaceRenderActive() &&
                snapshot != null && canRenderAod(snapshot),
            pendingStockMotion = pendingStockMotionUpdate != null,
            keepAlive = snapshot?.keepAlive == true
        )
        setDrawWakeRenewalActive(active)
        AodLifetimeController.setLyricActive(active)
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
        if (stockWidgetControlActive && !AodPositionHook.hasManagedPosition()) {
            directSurface.visibility = View.INVISIBLE
            traceLayoutBlock("managed-position-pending")
            return false
        }
        if (pendingStockMotionUpdate != null) {
            directSurface.visibility = View.INVISIBLE
            traceLayoutBlock("stock-motion-pending")
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
        val effectiveClockTop = controlledClockTop ?: stockTop
        val effectiveClockBottom = controlledClockBottom ?: stockBottom
        val density = root.resources.displayMetrics.density
        val margin = (SURFACE_MARGIN_DP * density).roundToInt()
        val profile = currentAodProfile()
        val metadataHeight = if (profile.metadataVisible &&
            profile.widgets.any { it.type == "metadata" }
        ) {
            36f * density
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
            controlledLyricTopSafe ?: margin,
            margin,
            sceneZone
        )
        val placement = PlacementEngine.resolve(
            profile.copy(
                maxHeightFraction = aodPlacementMaxHeightFraction(
                    profile.maxHeightFraction,
                    sceneZone
                )
            ),
            PlacementEnvironment(
                safeCanvas = safeCanvas,
                stockClockBottom = if (sceneZone == AodSceneZone.CLOCK_BOTTOM) {
                    safeCanvas.top
                } else {
                    (effectiveClockBottom + margin).toFloat()
                },
                bottomReserveTop = if (sceneZone == AodSceneZone.CLOCK_BOTTOM) {
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
                    "zone=$sceneZone placed=$placed"
            )
            return false
        }
        if (lastLayoutBlockTrace != null) {
            HookLogger.i(TAG, "Layout ready after=$lastLayoutBlockTrace rect=$rect zone=$sceneZone")
            lastLayoutBlockTrace = null
        }
        directSurface.measure(
            View.MeasureSpec.makeMeasureSpec(rect.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(rect.height, View.MeasureSpec.EXACTLY)
        )
        directSurface.layout(rect.left, rect.top, rect.right, rect.bottom)
        if (!handoffActive && !initialRevealActive) directSurface.alpha = 1f
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

    override fun transitionRectInWindow(): TransitionRect? =
        transitionRectInWindow(surface)

    override fun presentationRectInWindow(): TransitionRect? =
        presentationRectInWindow(surface)

    override fun setSceneRole(role: LinkageSceneRole) {
        if (sceneRole == role) return
        sceneRole = role
        lyricCanvas?.setSceneActive(isSceneActive())
        if (!isSceneActive()) {
            pendingStockMotionUpdate = null
            mainHandler.removeCallbacks(stockMotionSettle)
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
        animateLinkageView(
            surface,
            source,
            fadeIn,
            preserveAlpha,
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
    private const val STOCK_MOTION_SETTLE_MS = 850L
    private const val MANAGED_BURN_IN_RETRY_MS = 1_000L
    private val DEFAULT_AOD_PROFILE = SceneCompiler.compile(SceneCompiler.safeDefaultDocument())
        .profiles.getValue(SceneCompiler.SURFACE_AOD)
}
