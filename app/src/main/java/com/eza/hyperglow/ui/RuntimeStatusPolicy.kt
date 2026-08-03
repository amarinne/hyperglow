package com.eza.hyperglow.ui

internal enum class RuntimeSurfaceState {
    ENABLED,
    DISABLED,
    CONFIGURED_UNAVAILABLE,
    UNAVAILABLE
}

internal fun resolveRuntimeSurfaceState(
    configured: Boolean,
    supported: Boolean
): RuntimeSurfaceState = when {
    supported && configured -> RuntimeSurfaceState.ENABLED
    supported -> RuntimeSurfaceState.DISABLED
    configured -> RuntimeSurfaceState.CONFIGURED_UNAVAILABLE
    else -> RuntimeSurfaceState.UNAVAILABLE
}
