package com.eza.hyperglow.diagnostics

internal data class HyperGlowSetupInput(
    val rootAccessStatus: String,
    val capabilityReportPresent: Boolean,
    val systemUiCallbackPresent: Boolean,
    val profileState: String,
    val spotifyProducerBridgePresent: Boolean,
    val systemUiPackagePresent: Boolean,
    val xiaomiAodPackagePresent: Boolean,
    val spotifyPackagePresent: Boolean
)

internal fun resolveHyperGlowSetupChecks(input: HyperGlowSetupInput): HyperGlowSetupChecks {
    val failures = mutableListOf<String>()
    var hardFailure = false

    when (input.rootAccessStatus) {
        "granted" -> Unit
        "not_checked" -> failures += "root_access"
        else -> {
            failures += "root_access"
            hardFailure = true
        }
    }
    if (!input.capabilityReportPresent) {
        failures += "capability_report"
        hardFailure = true
    }
    if (!input.systemUiCallbackPresent) {
        failures += "systemui_hook"
        hardFailure = true
    }
    val profileSupported = input.profileState == "verified_profile"
    if (input.capabilityReportPresent && !profileSupported) {
        failures += "unsupported_profile"
        hardFailure = true
    }
    if (!input.systemUiPackagePresent) {
        failures += "systemui_package"
        hardFailure = true
    }
    if (!input.xiaomiAodPackagePresent) {
        failures += "xiaomi_aod_package"
        hardFailure = true
    }
    if (!input.spotifyPackagePresent) {
        failures += "spotify_package"
        hardFailure = true
    }
    if (!input.spotifyProducerBridgePresent) failures += "spotify_bridge"

    return HyperGlowSetupChecks(
        setupState = when {
            hardFailure -> "failed"
            failures.isNotEmpty() -> "warning"
            else -> "ready"
        },
        setupFailures = failures,
        rootAccessStatus = input.rootAccessStatus,
        capabilityReportPresent = input.capabilityReportPresent,
        systemUiHookActive = input.systemUiCallbackPresent,
        profileSupported = profileSupported,
        spotifyProducerBridgePresent = input.spotifyProducerBridgePresent,
        requiredPackagesPresent = input.systemUiPackagePresent &&
            input.xiaomiAodPackagePresent && input.spotifyPackagePresent
    )
}
