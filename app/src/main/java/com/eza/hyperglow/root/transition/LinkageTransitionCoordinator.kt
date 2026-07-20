package com.eza.hyperglow.root.transition

import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.animation.PathInterpolator
import com.eza.hyperglow.root.capability.XiaomiCapability
import com.eza.hyperglow.root.capability.XiaomiCapabilityResolver
import com.eza.hyperglow.root.HookLogger
import com.eza.hyperglow.root.projection.LyricSnapshot
import com.eza.hyperglow.root.projection.LyricSurfaceKind
import com.eza.hyperglow.root.projection.SystemUiLyricProjectionRuntime
import com.eza.hyperglow.customization.SceneCompiler
import java.util.EnumMap

internal interface LinkageSurface {
    val linkageSurfaceKind: LyricSurfaceKind

    fun transitionRectInWindow(): TransitionRect?

    fun presentationRectInWindow(): TransitionRect?

    fun setSceneRole(role: LinkageSceneRole)

    fun setHandoffActive(active: Boolean)

    fun animateFrom(
        source: TransitionRect?,
        fadeIn: Boolean,
        preserveAlpha: Boolean,
        durationMs: Long,
        token: Long,
        onComplete: (Long) -> Unit
    )

    fun fadeOut(durationMs: Long)

    fun resetTransition()

    fun hideForFailedTransition()

    fun applyTransitionSnapshot(snapshot: LyricSnapshot)
}

internal object LinkageTransitionCoordinator {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val surfaces = EnumMap<LyricSurfaceKind, LinkageSurface>(LyricSurfaceKind::class.java)
    private val stateMachine = LinkageStateMachine()
    private val freeze = HandoffSnapshotFreeze()
    private var sourceRect: TransitionRect? = null
    private var targetKind: LyricSurfaceKind? = null
    private var activeToken = -1L
    private var animationStartedToken = -1L
    private var timeoutToken = -1L
    private var activeDurationMs = DEFAULT_DURATION_MS
    private var targetWaitLoggedToken = -1L
    private val timeout = Runnable { onTimeout(timeoutToken) }

    fun registerSurface(surface: LinkageSurface) = onMain {
        surfaces[surface.linkageSurfaceKind] = surface
        surface.resetTransition()
        stateMachine.attach(surface.linkageSurfaceKind)
        publishAuthority()
        HookLogger.i(
            TAG,
            "Surface registered kind=${surface.linkageSurfaceKind} state=${stateMachine.state} " +
                "attached=${surfaces.keys}"
        )
        if (isTransitionActive() && targetKind == surface.linkageSurfaceKind) tryStartTarget()
        else if (!isTransitionActive()) {
            SystemUiLyricProjectionRuntime.projection.cachedSnapshot()
                ?.let(surface::applyTransitionSnapshot)
        }
    }

    fun unregisterSurface(surface: LinkageSurface) = onMain {
        if (surfaces[surface.linkageSurfaceKind] !== surface) return@onMain
        val wasTransitionActive = isTransitionActive()
        surfaces.remove(surface.linkageSurfaceKind)
        stateMachine.detach(surface.linkageSurfaceKind)
        surface.setSceneRole(LinkageSceneRole.INACTIVE)
        publishAuthority()
        surface.resetTransition()
        HookLogger.i(
            TAG,
            "Surface unregistered kind=${surface.linkageSurfaceKind} state=${stateMachine.state} " +
                "attached=${surfaces.keys}"
        )
        if (wasTransitionActive) {
            val target = targetKind
            if (surface.linkageSurfaceKind != target && target != null && surfaces[target] != null) {
                finishAuthoritativeDetach(activeToken)
            } else {
                finishWithoutTarget(activeToken)
            }
        }
    }

    fun onSurfaceReady(kind: LyricSurfaceKind) = onMain {
        HookLogger.i(
            TAG,
            "Surface ready kind=$kind target=$targetKind state=${stateMachine.state}"
        )
        if (targetKind == kind) {
            tryStartTarget()
        } else if (!isTransitionActive()) {
            val surface = surfaces[kind] ?: return@onMain
            if (!stateMachine.recoverTimedOutTarget(kind)) return@onMain
            surface.resetTransition()
            publishAuthority()
            SystemUiLyricProjectionRuntime.projection.cachedSnapshot()
                ?.let(surface::applyTransitionSnapshot)
            HookLogger.i(TAG, "Timed-out target recovered kind=$kind state=${stateMachine.state}")
        }
    }

    fun onLinkage(toLockscreen: Boolean) = onMain {
        val currentState = stateMachine.state
        if ((!toLockscreen && currentState == LinkageTransitionState.TO_AOD) ||
            (toLockscreen && currentState == LinkageTransitionState.TO_LOCKSCREEN)
        ) return@onMain
        val snapshot = SystemUiLyricProjectionRuntime.projection.cachedSnapshot()
        val customization = SystemUiLyricProjectionRuntime.projection.cachedCustomization()
        val aodProfile = customization?.profiles?.get(SceneCompiler.SURFACE_AOD)
        val lockscreenProfile = customization?.profiles?.get(SceneCompiler.SURFACE_LOCKSCREEN)
        val directionCapable = XiaomiCapabilityResolver.hasCapability(XiaomiCapability.LINKAGE_DIRECTION)
        val geometryCapable = XiaomiCapabilityResolver.hasCapability(XiaomiCapability.LINKAGE_GEOMETRY)
        val sourceKind = if (toLockscreen) LyricSurfaceKind.AOD else LyricSurfaceKind.LOCKSCREEN
        val nextTarget = if (toLockscreen) LyricSurfaceKind.LOCKSCREEN else LyricSurfaceKind.AOD
        val blockReason = linkageStartBlockReason(
            LinkageStartEligibility(
                directionCapable = directionCapable,
                geometryCapable = geometryCapable,
                snapshotVisible = snapshot?.visible == true,
                aodEnabled = snapshot?.aodEnabled == true,
                lockscreenEnabled = snapshot?.lockscreenEnabled == true,
                seamlessEnabled = snapshot?.seamlessTransitionEnabled == true,
                aodProfileEnabled = aodProfile?.enabled != false,
                lockscreenProfileEnabled = lockscreenProfile?.enabled != false,
                sourceAttached = surfaces[sourceKind] != null
            )
        )
        HookLogger.i(
            TAG,
            "Linkage request toLockscreen=$toLockscreen state=$currentState " +
                "snapshot=${snapshot?.revision}/${snapshot?.visible} " +
                "flags=aod:${snapshot?.aodEnabled},lock:${snapshot?.lockscreenEnabled}," +
                "seamless:${snapshot?.seamlessTransitionEnabled} " +
                "profiles=aod:${aodProfile?.enabled},lock:${lockscreenProfile?.enabled} " +
                "caps=direction:$directionCapable,geometry:$geometryCapable " +
                "surfaces=${surfaces.keys}"
        )
        if (blockReason != null) {
            HookLogger.i(TAG, "Linkage rejected reason=$blockReason")
            return@onMain
        }
        val activeSnapshot = snapshot
            ?: return@onMain
        sourceRect = surfaces[sourceKind]?.presentationRectInWindow()
        val token = stateMachine.linkage(toLockscreen)
        activeToken = token
        animationStartedToken = -1L
        targetWaitLoggedToken = -1L
        targetKind = nextTarget
        activeDurationMs = (if (nextTarget == LyricSurfaceKind.AOD) {
            aodProfile?.transition?.durationMs
        } else {
            lockscreenProfile?.transition?.durationMs
        } ?: DEFAULT_DURATION_MS.toInt()).toLong().coerceIn(150L, 600L)
        freeze.start(activeSnapshot, SystemClock.elapsedRealtime())
        publishAuthority()
        surfaces.values.forEach(LinkageSurface::resetTransition)
        surfaces.values.forEach { it.setHandoffActive(true) }
        surfaces.values.forEach { it.applyTransitionSnapshot(activeSnapshot) }
        HookLogger.i(
            TAG,
            "Linkage accepted token=$token source=$sourceKind target=$nextTarget " +
                "sourceRect=$sourceRect duration=$activeDurationMs authority=${stateMachine.authority}"
        )
        if (toLockscreen) surfaces[sourceKind]?.fadeOut(activeDurationMs)
        tryStartTarget()
        timeoutToken = token
        mainHandler.removeCallbacks(timeout)
        mainHandler.postDelayed(timeout, TARGET_TIMEOUT_MS)
    }

    fun resolveSnapshot(snapshot: LyricSnapshot): LyricSnapshot =
        if (isTransitionActive()) freeze.resolve(snapshot, SystemClock.elapsedRealtime()) else snapshot

    private fun tryStartTarget() {
        val token = activeToken
        if (token < 0L || animationStartedToken == token) return
        val kind = targetKind ?: return
        val target = surfaces[kind] ?: return
        val targetRect = target.transitionRectInWindow()
        if (targetRect == null) {
            if (targetWaitLoggedToken != token) {
                targetWaitLoggedToken = token
                HookLogger.i(TAG, "Target waiting token=$token kind=$kind rect=null")
            }
            return
        }
        animationStartedToken = token
        HookLogger.i(TAG, "Target animation start token=$token kind=$kind rect=$targetRect")
        target.animateFrom(
            sourceRect,
            fadeIn = kind == LyricSurfaceKind.AOD,
            preserveAlpha = kind == LyricSurfaceKind.LOCKSCREEN,
            durationMs = activeDurationMs,
            token = token,
            onComplete = ::onAnimationComplete
        )
    }

    private fun onAnimationComplete(token: Long) = onMain {
        HookLogger.i(TAG, "Target animation complete token=$token state=${stateMachine.state}")
        if (!stateMachine.targetReady(token)) return@onMain
        publishAuthority()
        finishSuccessful(token)
    }

    private fun onTimeout(token: Long) {
        HookLogger.i(TAG, "Target timeout token=$token state=${stateMachine.state} target=$targetKind")
        if (!stateMachine.timeout(token)) return
        publishAuthority()
        finishWithoutTarget(token)
    }

    private fun finishSuccessful(token: Long) {
        if (token != activeToken) return
        settleTransition()
    }

    private fun finishAuthoritativeDetach(token: Long) {
        if (token != activeToken) return
        settleTransition()
    }

    private fun settleTransition() {
        HookLogger.i(TAG, "Transition settle token=$activeToken state=${stateMachine.state}")
        mainHandler.removeCallbacks(timeout)
        val latest = freeze.settle(SystemUiLyricProjectionRuntime.projection.cachedSnapshot())
        latest?.let { snapshot -> surfaces.values.forEach { it.applyTransitionSnapshot(snapshot) } }
        surfaces.values.forEach { it.setHandoffActive(false) }
        activeToken = -1L
        animationStartedToken = -1L
        targetKind = null
        sourceRect = null
        activeDurationMs = DEFAULT_DURATION_MS
        targetWaitLoggedToken = -1L
    }

    private fun finishWithoutTarget(token: Long) {
        if (token != activeToken) return
        HookLogger.i(TAG, "Transition failed token=$token target=$targetKind state=${stateMachine.state}")
        mainHandler.removeCallbacks(timeout)
        val failedTarget = targetKind
        surfaces.forEach { (kind, surface) ->
            if (kind != failedTarget) surface.resetTransition()
        }
        failedTarget?.let { surfaces[it]?.hideForFailedTransition() }
        surfaces.values.forEach { it.setHandoffActive(false) }
        freeze.clear()
        activeToken = -1L
        animationStartedToken = -1L
        targetKind = null
        sourceRect = null
        activeDurationMs = DEFAULT_DURATION_MS
        targetWaitLoggedToken = -1L
    }

    private fun publishAuthority() {
        val authority = stateMachine.authority
        surfaces.forEach { (kind, surface) ->
            surface.setSceneRole(authority.roleOf(kind))
        }
    }

    private fun isTransitionActive(): Boolean = when (stateMachine.state) {
        LinkageTransitionState.TO_AOD,
        LinkageTransitionState.TO_LOCKSCREEN -> true
        else -> false
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private const val DEFAULT_DURATION_MS = 320L
    private const val TARGET_TIMEOUT_MS = 1_200L
    private const val TAG = "LinkageTransition"
}

internal fun transitionRectInWindow(view: View?): TransitionRect? {
    if (view == null || !view.isAttachedToWindow || view.visibility != View.VISIBLE ||
        view.width <= 0 || view.height <= 0
    ) return null
    val location = IntArray(2)
    view.getLocationInWindow(location)
    return TransitionRect(
        location[0].toFloat(),
        location[1].toFloat(),
        (location[0] + view.width).toFloat(),
        (location[1] + view.height).toFloat()
    )
}

internal fun presentationRectInWindow(view: View?): TransitionRect? {
    if (view == null || !view.isAttachedToWindow || view.visibility != View.VISIBLE ||
        view.width <= 0 || view.height <= 0
    ) return null
    val matrix = Matrix()
    view.transformMatrixToGlobal(matrix)
    val points = floatArrayOf(
        0f, 0f,
        view.width.toFloat(), 0f,
        view.width.toFloat(), view.height.toFloat(),
        0f, view.height.toFloat()
    )
    matrix.mapPoints(points)
    val root = view.rootView
    val screen = IntArray(2)
    val window = IntArray(2)
    root.getLocationOnScreen(screen)
    root.getLocationInWindow(window)
    val screenToWindowX = (screen[0] - window[0]).toFloat()
    val screenToWindowY = (screen[1] - window[1]).toFloat()
    return TransitionRect(
        left = minOf(points[0], points[2], points[4], points[6]) - screenToWindowX,
        top = minOf(points[1], points[3], points[5], points[7]) - screenToWindowY,
        right = maxOf(points[0], points[2], points[4], points[6]) - screenToWindowX,
        bottom = maxOf(points[1], points[3], points[5], points[7]) - screenToWindowY
    )
}

internal fun animateLinkageView(
    view: View?,
    source: TransitionRect?,
    fadeIn: Boolean,
    preserveAlpha: Boolean,
    durationMs: Long,
    token: Long,
    onComplete: (Long) -> Unit,
    additionalStartTranslationY: Float = 0f,
    initialAlpha: Float? = null
) {
    view ?: return onComplete(token)
    view.animate().cancel()
    val transform = source?.let { transitionTransformInTargetParent(it, view) }
        ?: TransitionTransform(1f, 1f, 0f, 0f)
    view.pivotX = view.width / 2f
    view.pivotY = view.height / 2f
    view.scaleX = initialLinkageScale(transform.scaleX, preserveAlpha)
    view.scaleY = initialLinkageScale(transform.scaleY, preserveAlpha)
    view.translationX = transform.translationX
    view.translationY = initialLinkageTranslationY(
        transform.translationY,
        additionalStartTranslationY
    )
    view.alpha = initialAlpha ?: if (preserveAlpha) 1f else if (fadeIn) 0f else view.alpha
    view.animate()
        .scaleX(1f)
        .scaleY(1f)
        .translationX(0f)
        .translationY(0f)
        .alpha(1f)
        .setDuration(durationMs.coerceIn(150L, 600L))
        .setInterpolator(LINKAGE_INTERPOLATOR)
        .withEndAction { onComplete(token) }
        .start()
}

internal fun initialLinkageScale(scale: Float, preserveAlpha: Boolean): Float =
    if (preserveAlpha) scale.coerceIn(0.88f, 1.12f) else scale

internal fun initialLinkageTranslationY(baseTranslationY: Float, entrySlideY: Float): Float =
    baseTranslationY + entrySlideY.coerceAtLeast(0f)

internal fun fadeInLinkageView(
    view: View?,
    durationMs: Long,
    startDelayMs: Long? = null,
    onComplete: () -> Unit = {}
) {
    if (view == null || view.visibility != View.VISIBLE) return
    val duration = durationMs.coerceIn(150L, 600L)
    val delay = startDelayMs?.coerceIn(0L, duration - 90L) ?: minOf(60L, duration / 4L)
    view.animate().cancel()
    view.alpha = 0f
    view.animate()
        .alpha(1f)
        .setStartDelay(delay)
        .setDuration((duration - delay).coerceAtLeast(90L))
        .setInterpolator(LINKAGE_INTERPOLATOR)
        .withEndAction(onComplete)
        .start()
}

internal fun transitionTransformInTargetParent(
    sourceInWindow: TransitionRect,
    targetView: View
): TransitionTransform? {
    if (targetView.width <= 0 || targetView.height <= 0) return null
    val parent = targetView.parent as? View ?: return transitionRectInWindow(targetView)?.let {
        transitionTransform(sourceInWindow, it)
    }
    val screenLocation = IntArray(2)
    val windowLocation = IntArray(2)
    parent.getLocationOnScreen(screenLocation)
    parent.getLocationInWindow(windowLocation)
    val windowToScreenX = (screenLocation[0] - windowLocation[0]).toFloat()
    val windowToScreenY = (screenLocation[1] - windowLocation[1]).toFloat()
    val parentToScreen = Matrix()
    parent.transformMatrixToGlobal(parentToScreen)
    val screenToParent = Matrix()
    if (!parentToScreen.invert(screenToParent)) return null
    val points = floatArrayOf(
        sourceInWindow.left + windowToScreenX,
        sourceInWindow.top + windowToScreenY,
        sourceInWindow.right + windowToScreenX,
        sourceInWindow.top + windowToScreenY,
        sourceInWindow.right + windowToScreenX,
        sourceInWindow.bottom + windowToScreenY,
        sourceInWindow.left + windowToScreenX,
        sourceInWindow.bottom + windowToScreenY
    )
    screenToParent.mapPoints(points)
    val sourceInParent = TransitionRect(
        left = minOf(points[0], points[2], points[4], points[6]),
        top = minOf(points[1], points[3], points[5], points[7]),
        right = maxOf(points[0], points[2], points[4], points[6]),
        bottom = maxOf(points[1], points[3], points[5], points[7])
    )
    val targetInParent = TransitionRect(
        targetView.left.toFloat(),
        targetView.top.toFloat(),
        targetView.right.toFloat(),
        targetView.bottom.toFloat()
    )
    return transitionTransform(sourceInParent, targetInParent)
}

internal fun fadeOutLinkageView(view: View?, durationMs: Long) {
    view?.animate()?.cancel()
    view?.animate()
        ?.alpha(0f)
        ?.setDuration(durationMs.coerceIn(150L, 600L))
        ?.setInterpolator(LINKAGE_INTERPOLATOR)
        ?.start()
}

internal fun resetLinkageView(view: View?) {
    view?.animate()?.cancel()
    view?.alpha = 1f
    view?.scaleX = 1f
    view?.scaleY = 1f
    view?.translationX = 0f
    view?.translationY = 0f
}

private val LINKAGE_INTERPOLATOR = PathInterpolator(0.4f, 0f, 0.2f, 1f)
