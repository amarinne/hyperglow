package com.eza.hyperglow.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeStatusPolicyTest {
    @Test
    fun configuredUnsupportedSurfaceIsNeverPresentedAsEnabled() {
        val state = resolveRuntimeSurfaceState(configured = true, supported = false)

        assertEquals(RuntimeSurfaceState.CONFIGURED_UNAVAILABLE, state)
    }

    @Test
    fun supportedSurfaceReflectsConfiguredState() {
        assertEquals(
            RuntimeSurfaceState.ENABLED,
            resolveRuntimeSurfaceState(configured = true, supported = true)
        )
        assertEquals(
            RuntimeSurfaceState.DISABLED,
            resolveRuntimeSurfaceState(configured = false, supported = true)
        )
    }
}
