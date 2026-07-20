package com.eza.hyperglow.root.transition

import com.eza.hyperglow.root.projection.LyricSnapshot
import com.eza.hyperglow.root.projection.LyricSurfaceKind

internal enum class LinkageTransitionState {
    DETACHED,
    LOCKSCREEN,
    TO_AOD,
    AOD,
    TO_LOCKSCREEN,
    AOD_NO_CUSTOM_SURFACE,
    LOCKSCREEN_NO_CUSTOM_SURFACE
}

internal enum class LinkageSceneRole {
    INACTIVE,
    AUTHORITATIVE,
    TRANSITION_SOURCE,
    TRANSITION_TARGET
}

internal enum class LinkageStartBlockReason {
    MISSING_CAPABILITY,
    SNAPSHOT_NOT_VISIBLE,
    SURFACE_DISABLED,
    SEAMLESS_DISABLED,
    PROFILE_DISABLED,
    MISSING_SOURCE
}

internal data class LinkageStartEligibility(
    val directionCapable: Boolean,
    val geometryCapable: Boolean,
    val snapshotVisible: Boolean,
    val aodEnabled: Boolean,
    val lockscreenEnabled: Boolean,
    val seamlessEnabled: Boolean,
    val aodProfileEnabled: Boolean,
    val lockscreenProfileEnabled: Boolean,
    val sourceAttached: Boolean
)

internal fun linkageStartBlockReason(
    eligibility: LinkageStartEligibility
): LinkageStartBlockReason? = when {
    !eligibility.directionCapable || !eligibility.geometryCapable ->
        LinkageStartBlockReason.MISSING_CAPABILITY
    !eligibility.snapshotVisible -> LinkageStartBlockReason.SNAPSHOT_NOT_VISIBLE
    !eligibility.aodEnabled || !eligibility.lockscreenEnabled ->
        LinkageStartBlockReason.SURFACE_DISABLED
    !eligibility.seamlessEnabled -> LinkageStartBlockReason.SEAMLESS_DISABLED
    !eligibility.aodProfileEnabled || !eligibility.lockscreenProfileEnabled ->
        LinkageStartBlockReason.PROFILE_DISABLED
    !eligibility.sourceAttached -> LinkageStartBlockReason.MISSING_SOURCE
    else -> null
}

internal data class LinkageSceneAuthority(
    val stableSurface: LyricSurfaceKind? = null,
    val transitionSource: LyricSurfaceKind? = null,
    val transitionTarget: LyricSurfaceKind? = null,
    val token: Long? = null
) {
    fun roleOf(kind: LyricSurfaceKind): LinkageSceneRole = when (kind) {
        transitionSource -> LinkageSceneRole.TRANSITION_SOURCE
        transitionTarget -> LinkageSceneRole.TRANSITION_TARGET
        stableSurface -> LinkageSceneRole.AUTHORITATIVE
        else -> LinkageSceneRole.INACTIVE
    }

    fun isSceneActive(kind: LyricSurfaceKind): Boolean = roleOf(kind) != LinkageSceneRole.INACTIVE
}

internal data class TransitionRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
}

internal data class TransitionTransform(
    val scaleX: Float,
    val scaleY: Float,
    val translationX: Float,
    val translationY: Float
)

internal fun transitionTransform(source: TransitionRect, target: TransitionRect): TransitionTransform {
    if (source.width <= 0f || source.height <= 0f || target.width <= 0f || target.height <= 0f) {
        return TransitionTransform(1f, 1f, 0f, 0f)
    }
    return TransitionTransform(
        scaleX = (source.width / target.width).coerceIn(0.5f, 2f),
        scaleY = (source.height / target.height).coerceIn(0.5f, 2f),
        translationX = source.centerX - target.centerX,
        translationY = source.centerY - target.centerY
    )
}

internal class LinkageStateMachine {
    var state = LinkageTransitionState.DETACHED
        private set
    var authority = LinkageSceneAuthority()
        private set
    var token = 0L
        private set
    private var lockscreenAttached = false
    private var aodAttached = false

    fun attach(kind: LyricSurfaceKind): LinkageTransitionState {
        if (kind == LyricSurfaceKind.LOCKSCREEN) lockscreenAttached = true else aodAttached = true
        state = when (state) {
            LinkageTransitionState.DETACHED -> stableState()
            LinkageTransitionState.LOCKSCREEN ->
                if (kind == LyricSurfaceKind.AOD) LinkageTransitionState.AOD else state
            LinkageTransitionState.AOD_NO_CUSTOM_SURFACE ->
                if (kind == LyricSurfaceKind.AOD) LinkageTransitionState.AOD else state
            LinkageTransitionState.LOCKSCREEN_NO_CUSTOM_SURFACE ->
                if (kind == LyricSurfaceKind.LOCKSCREEN) LinkageTransitionState.LOCKSCREEN else state
            else -> state
        }
        syncStableAuthority()
        return state
    }

    fun detach(kind: LyricSurfaceKind): LinkageTransitionState {
        if (kind == LyricSurfaceKind.LOCKSCREEN) lockscreenAttached = false else aodAttached = false
        state = when (state) {
            LinkageTransitionState.TO_AOD -> when (kind) {
                LyricSurfaceKind.AOD -> LinkageTransitionState.AOD_NO_CUSTOM_SURFACE
                LyricSurfaceKind.LOCKSCREEN -> if (aodAttached) LinkageTransitionState.AOD else stableState()
            }
            LinkageTransitionState.TO_LOCKSCREEN -> when (kind) {
                LyricSurfaceKind.LOCKSCREEN -> LinkageTransitionState.LOCKSCREEN_NO_CUSTOM_SURFACE
                LyricSurfaceKind.AOD -> if (lockscreenAttached) LinkageTransitionState.LOCKSCREEN else stableState()
            }
            LinkageTransitionState.AOD -> if (kind == LyricSurfaceKind.AOD) stableState() else state
            LinkageTransitionState.LOCKSCREEN ->
                if (kind == LyricSurfaceKind.LOCKSCREEN) stableState() else state
            else -> stableState()
        }
        syncStableAuthority()
        return state
    }

    fun linkage(toLockscreen: Boolean): Long {
        token++
        val source = if (toLockscreen) LyricSurfaceKind.AOD else LyricSurfaceKind.LOCKSCREEN
        val target = if (toLockscreen) LyricSurfaceKind.LOCKSCREEN else LyricSurfaceKind.AOD
        state = if (toLockscreen) {
            LinkageTransitionState.TO_LOCKSCREEN
        } else {
            LinkageTransitionState.TO_AOD
        }
        authority = LinkageSceneAuthority(
            stableSurface = source,
            transitionSource = source,
            transitionTarget = target,
            token = token
        )
        return token
    }

    fun targetReady(expectedToken: Long): Boolean {
        if (expectedToken != token) return false
        state = when (state) {
            LinkageTransitionState.TO_AOD -> LinkageTransitionState.AOD
            LinkageTransitionState.TO_LOCKSCREEN -> LinkageTransitionState.LOCKSCREEN
            else -> return false
        }
        syncStableAuthority()
        return true
    }

    fun timeout(expectedToken: Long): Boolean {
        if (expectedToken != token) return false
        state = when (state) {
            LinkageTransitionState.TO_AOD -> LinkageTransitionState.AOD_NO_CUSTOM_SURFACE
            LinkageTransitionState.TO_LOCKSCREEN ->
                LinkageTransitionState.LOCKSCREEN_NO_CUSTOM_SURFACE
            else -> return false
        }
        syncStableAuthority()
        return true
    }

    fun recoverTimedOutTarget(kind: LyricSurfaceKind): Boolean {
        state = when (state) {
            LinkageTransitionState.AOD_NO_CUSTOM_SURFACE ->
                if (kind == LyricSurfaceKind.AOD && aodAttached) {
                    LinkageTransitionState.AOD
                } else {
                    return false
                }
            LinkageTransitionState.LOCKSCREEN_NO_CUSTOM_SURFACE ->
                if (kind == LyricSurfaceKind.LOCKSCREEN && lockscreenAttached) {
                    LinkageTransitionState.LOCKSCREEN
                } else {
                    return false
                }
            else -> return false
        }
        syncStableAuthority()
        return true
    }

    private fun syncStableAuthority() {
        if (state == LinkageTransitionState.TO_AOD ||
            state == LinkageTransitionState.TO_LOCKSCREEN
        ) return
        authority = LinkageSceneAuthority(
            stableSurface = when (state) {
                LinkageTransitionState.AOD -> LyricSurfaceKind.AOD
                LinkageTransitionState.LOCKSCREEN -> LyricSurfaceKind.LOCKSCREEN
                else -> null
            }
        )
    }

    private fun stableState(): LinkageTransitionState = when {
        aodAttached -> LinkageTransitionState.AOD
        lockscreenAttached -> LinkageTransitionState.LOCKSCREEN
        else -> LinkageTransitionState.DETACHED
    }
}

internal class HandoffSnapshotFreeze(
    private val maximumDurationMs: Long = MAXIMUM_FREEZE_MS
) {
    private var frozen: LyricSnapshot? = null
    private var queuedLatest: LyricSnapshot? = null
    private var startedAt = 0L

    fun start(snapshot: LyricSnapshot?, now: Long) {
        frozen = snapshot?.takeIf { it.visible }
        queuedLatest = null
        startedAt = now
    }

    fun resolve(incoming: LyricSnapshot, now: Long): LyricSnapshot {
        val current = frozen ?: return incoming
        if (!incoming.visible || incoming.trackGeneration != current.trackGeneration ||
            now - startedAt >= maximumDurationMs
        ) {
            clear()
            return incoming
        }
        queuedLatest = incoming
        return current.copy(
            updatedAtElapsedMs = incoming.updatedAtElapsedMs,
            keepAlive = incoming.keepAlive,
            wakeSignal = incoming.wakeSignal
        )
    }

    fun settle(latest: LyricSnapshot?): LyricSnapshot? {
        val result = queuedLatest ?: latest
        clear()
        return result
    }

    fun clear() {
        frozen = null
        queuedLatest = null
        startedAt = 0L
    }

    companion object {
        const val MAXIMUM_FREEZE_MS = 600L
    }
}

internal class LinkageDirectionDebouncer(
    private val debounceMs: Long = 700L
) {
    private var lastDirection: Boolean? = null
    private var lastAcceptedAt = Long.MIN_VALUE

    fun accept(toLockscreen: Boolean, nowElapsedMs: Long): Boolean {
        if (lastDirection == toLockscreen && nowElapsedMs - lastAcceptedAt < debounceMs) return false
        lastDirection = toLockscreen
        lastAcceptedAt = nowElapsedMs
        return true
    }
}
