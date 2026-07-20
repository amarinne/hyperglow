package com.eza.hyperglow.root.lockscreen

import com.eza.hyperglow.root.aod.CadenceChange
import com.eza.hyperglow.root.aod.EffectiveCadenceGate
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaProgressViewTest {
    @Test
    fun projectedProgressUsesSharedElapsedTimeAnchorAndClamps() {
        assertEquals(0.25f, projectedMediaProgress(4_000, 500, 1_000, 1f, 1_500), 0.0001f)
        assertEquals(1f, projectedMediaProgress(4_000, 3_500, 1_000, 2f, 2_000), 0.0001f)
        assertEquals(0f, projectedMediaProgress(0, 500, 1_000, 1f, 1_500), 0.0001f)
    }

    @Test
    fun progressCadenceRestartsAfterEffectiveVisibilityReturns() {
        val gate = EffectiveCadenceGate()

        assertEquals(CadenceChange.START, gate.update(true))
        assertEquals(CadenceChange.STOP, gate.update(false))
        assertEquals(CadenceChange.START, gate.update(true))
    }
}
