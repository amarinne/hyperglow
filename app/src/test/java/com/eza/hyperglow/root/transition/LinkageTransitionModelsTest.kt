package com.eza.hyperglow.root.transition

import com.eza.hyperglow.root.projection.LyricSnapshot
import com.eza.hyperglow.root.projection.LyricSurfaceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkageTransitionModelsTest {
    @Test
    fun linkageStartEligibilityFailsClosedByExactReason() {
        val eligible = LinkageStartEligibility(
            directionCapable = true,
            geometryCapable = true,
            snapshotVisible = true,
            aodEnabled = true,
            lockscreenEnabled = true,
            seamlessEnabled = true,
            aodProfileEnabled = true,
            lockscreenProfileEnabled = true,
            sourceAttached = true
        )

        assertEquals(null, linkageStartBlockReason(eligible))
        assertEquals(
            LinkageStartBlockReason.MISSING_CAPABILITY,
            linkageStartBlockReason(eligible.copy(geometryCapable = false))
        )
        assertEquals(
            LinkageStartBlockReason.SNAPSHOT_NOT_VISIBLE,
            linkageStartBlockReason(eligible.copy(snapshotVisible = false))
        )
        assertEquals(
            LinkageStartBlockReason.SURFACE_DISABLED,
            linkageStartBlockReason(eligible.copy(lockscreenEnabled = false))
        )
        assertEquals(
            LinkageStartBlockReason.SEAMLESS_DISABLED,
            linkageStartBlockReason(eligible.copy(seamlessEnabled = false))
        )
        assertEquals(
            LinkageStartBlockReason.PROFILE_DISABLED,
            linkageStartBlockReason(eligible.copy(aodProfileEnabled = false))
        )
        assertEquals(
            LinkageStartBlockReason.MISSING_SOURCE,
            linkageStartBlockReason(eligible.copy(sourceAttached = false))
        )
    }

    @Test
    fun stateMachineHandlesForwardReverseAndStaleTokens() {
        val machine = LinkageStateMachine()
        machine.attach(LyricSurfaceKind.LOCKSCREEN)
        assertEquals(LinkageTransitionState.LOCKSCREEN, machine.state)

        val toAod = machine.linkage(toLockscreen = false)
        assertEquals(LinkageTransitionState.TO_AOD, machine.state)
        machine.attach(LyricSurfaceKind.AOD)
        val toLock = machine.linkage(toLockscreen = true)
        assertEquals(LinkageTransitionState.TO_LOCKSCREEN, machine.state)
        assertFalse(machine.targetReady(toAod))
        assertEquals(LyricSurfaceKind.AOD, machine.authority.transitionSource)
        assertEquals(LyricSurfaceKind.LOCKSCREEN, machine.authority.transitionTarget)
        assertTrue(machine.targetReady(toLock))
        assertEquals(LinkageTransitionState.LOCKSCREEN, machine.state)
    }

    @Test
    fun authorityTracksStableSurfaceAndActiveTransitionRoles() {
        val machine = LinkageStateMachine()
        machine.attach(LyricSurfaceKind.LOCKSCREEN)
        machine.attach(LyricSurfaceKind.AOD)
        assertEquals(LyricSurfaceKind.AOD, machine.authority.stableSurface)

        val forward = machine.linkage(toLockscreen = false)
        assertEquals(LyricSurfaceKind.LOCKSCREEN, machine.authority.stableSurface)
        assertEquals(LyricSurfaceKind.LOCKSCREEN, machine.authority.transitionSource)
        assertEquals(LyricSurfaceKind.AOD, machine.authority.transitionTarget)
        assertEquals(LinkageSceneRole.TRANSITION_SOURCE, machine.authority.roleOf(LyricSurfaceKind.LOCKSCREEN))
        assertEquals(LinkageSceneRole.TRANSITION_TARGET, machine.authority.roleOf(LyricSurfaceKind.AOD))

        assertFalse(machine.targetReady(forward - 1L))
        assertEquals(LyricSurfaceKind.LOCKSCREEN, machine.authority.stableSurface)
        assertTrue(machine.targetReady(forward))
        assertEquals(LyricSurfaceKind.AOD, machine.authority.stableSurface)
        assertEquals(LinkageSceneRole.AUTHORITATIVE, machine.authority.roleOf(LyricSurfaceKind.AOD))
        assertEquals(LinkageSceneRole.INACTIVE, machine.authority.roleOf(LyricSurfaceKind.LOCKSCREEN))
    }

    @Test
    fun aodAttachRecoversAuthorityWhenDirectionCallbackWasMissed() {
        val machine = LinkageStateMachine()
        machine.attach(LyricSurfaceKind.LOCKSCREEN)

        machine.attach(LyricSurfaceKind.AOD)

        assertEquals(LinkageTransitionState.AOD, machine.state)
        assertEquals(LinkageSceneRole.AUTHORITATIVE, machine.authority.roleOf(LyricSurfaceKind.AOD))
        assertEquals(LinkageSceneRole.INACTIVE, machine.authority.roleOf(LyricSurfaceKind.LOCKSCREEN))
    }

    @Test
    fun reverseSettleRetiresAodAuthorityAndNativeFailureOwnsNoModuleScene() {
        val machine = LinkageStateMachine()
        machine.attach(LyricSurfaceKind.AOD)
        machine.attach(LyricSurfaceKind.LOCKSCREEN)
        val reverse = machine.linkage(toLockscreen = true)

        assertEquals(LinkageSceneRole.TRANSITION_SOURCE, machine.authority.roleOf(LyricSurfaceKind.AOD))
        assertTrue(machine.targetReady(reverse))
        assertEquals(LinkageSceneRole.INACTIVE, machine.authority.roleOf(LyricSurfaceKind.AOD))
        assertEquals(LinkageSceneRole.AUTHORITATIVE, machine.authority.roleOf(LyricSurfaceKind.LOCKSCREEN))

        val forward = machine.linkage(toLockscreen = false)
        assertTrue(machine.timeout(forward))
        assertEquals(LinkageTransitionState.AOD_NO_CUSTOM_SURFACE, machine.state)
        assertEquals(null, machine.authority.stableSurface)
        assertFalse(machine.authority.isSceneActive(LyricSurfaceKind.AOD))
    }

    @Test
    fun timeoutFailsToTargetSpecificNoSurfaceState() {
        val machine = LinkageStateMachine()
        machine.attach(LyricSurfaceKind.LOCKSCREEN)
        val token = machine.linkage(toLockscreen = false)

        assertTrue(machine.timeout(token))
        assertEquals(LinkageTransitionState.AOD_NO_CUSTOM_SURFACE, machine.state)
    }

    @Test
    fun timedOutAttachedTargetRecoversWhenItBecomesReady() {
        val machine = LinkageStateMachine()
        machine.attach(LyricSurfaceKind.LOCKSCREEN)
        machine.attach(LyricSurfaceKind.AOD)
        val token = machine.linkage(toLockscreen = false)
        assertTrue(machine.timeout(token))

        assertFalse(machine.recoverTimedOutTarget(LyricSurfaceKind.LOCKSCREEN))
        assertTrue(machine.recoverTimedOutTarget(LyricSurfaceKind.AOD))
        assertEquals(LinkageTransitionState.AOD, machine.state)
        assertEquals(
            LinkageSceneRole.AUTHORITATIVE,
            machine.authority.roleOf(LyricSurfaceKind.AOD)
        )
    }

    @Test
    fun sourceDetachSettlesToReadyTargetAndTargetDetachFailsClosed() {
        val reverse = LinkageStateMachine()
        reverse.attach(LyricSurfaceKind.LOCKSCREEN)
        reverse.attach(LyricSurfaceKind.AOD)
        reverse.linkage(toLockscreen = true)
        reverse.detach(LyricSurfaceKind.AOD)
        assertEquals(LinkageTransitionState.LOCKSCREEN, reverse.state)

        val forward = LinkageStateMachine()
        forward.attach(LyricSurfaceKind.LOCKSCREEN)
        forward.attach(LyricSurfaceKind.AOD)
        forward.linkage(toLockscreen = false)
        forward.detach(LyricSurfaceKind.AOD)
        assertEquals(LinkageTransitionState.AOD_NO_CUSTOM_SURFACE, forward.state)
    }

    @Test
    fun freezeKeepsRowButNotTrackAndQueuesLatestRow() {
        val freeze = HandoffSnapshotFreeze()
        val first = snapshot(1, "row one")
        val second = snapshot(1, "row two").copy(updatedAtElapsedMs = 20)
        freeze.start(first, 0)

        assertEquals("row one", freeze.resolve(second, 200).original)
        assertEquals("row two", freeze.settle(second)?.original)

        freeze.start(first, 0)
        assertEquals("new track", freeze.resolve(snapshot(2, "new track"), 100).original)
    }

    @Test
    fun freezeExpiresAtSixHundredMsAndClearAbortsImmediately() {
        val freeze = HandoffSnapshotFreeze()
        val first = snapshot(1, "row one")
        freeze.start(first, 0)

        assertEquals("row two", freeze.resolve(snapshot(1, "row two"), 600).original)
        freeze.start(first, 0)
        assertFalse(freeze.resolve(first.copy(visible = false, original = ""), 50).visible)
    }

    @Test
    fun windowRectTransformMapsSourceIntoTargetParentSpace() {
        assertEquals(
            TransitionTransform(0.5f, 0.5f, -200f, -300f),
            transitionTransform(
                TransitionRect(100f, 200f, 300f, 400f),
                TransitionRect(200f, 400f, 600f, 800f)
            )
        )
    }

    @Test
    fun missingGeometryFallsBackToIdentityTransform() {
        assertEquals(
            TransitionTransform(1f, 1f, 0f, 0f),
            transitionTransform(
                TransitionRect(0f, 0f, 0f, 0f),
                TransitionRect(10f, 10f, 100f, 100f)
            )
        )
    }

    @Test
    fun reverseHandoffBoundsScaleWhileForwardHandoffKeepsGeometry() {
        assertEquals(1.12f, initialLinkageScale(1.8f, preserveAlpha = true), 0.0001f)
        assertEquals(0.88f, initialLinkageScale(0.6f, preserveAlpha = true), 0.0001f)
        assertEquals(1.8f, initialLinkageScale(1.8f, preserveAlpha = false), 0.0001f)
        assertEquals(44f, initialLinkageTranslationY(24f, 20f), 0.0001f)
    }

    @Test
    fun fallbackDirectionDebounceRejectsSameDirectionBurst() {
        val debouncer = LinkageDirectionDebouncer(700)

        assertTrue(debouncer.accept(toLockscreen = false, nowElapsedMs = 1_000))
        assertFalse(debouncer.accept(toLockscreen = false, nowElapsedMs = 1_500))
        assertTrue(debouncer.accept(toLockscreen = true, nowElapsedMs = 1_501))
        assertTrue(debouncer.accept(toLockscreen = true, nowElapsedMs = 2_201))
    }

    private fun snapshot(generation: Long, text: String) = LyricSnapshot(
        revision = generation,
        trackGeneration = generation,
        updatedAtElapsedMs = 10,
        visible = true,
        original = text
    )
}
