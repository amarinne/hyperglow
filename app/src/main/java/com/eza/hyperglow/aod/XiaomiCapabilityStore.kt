package com.eza.hyperglow.aod

import android.content.Context
import android.os.Bundle
import com.eza.hyperglow.root.capability.XiaomiCapability
import com.eza.hyperglow.root.capability.XiaomiCapabilityReport
import com.eza.hyperglow.root.capability.XiaomiProfileState
import com.eza.hyperglow.root.capability.XiaomiSymbolProbe

internal enum class XiaomiRuntimeSupportState(val displayName: String) {
    NO_SYSTEM_UI_REPORT("No SystemUI report"),
    VERIFIED_PROFILE("Verified profile"),
    VERIFIED_PROFILE_MISSING_SYMBOLS("Verified profile missing symbols"),
    UNSUPPORTED_PROFILE("Unsupported profile"),
    EXPERIMENTAL_ELIGIBLE("Experimental eligible"),
    EXPERIMENTAL_ACTIVE("Experimental active")
}

internal data class StoredXiaomiCapabilityReport(
    val protocolVersion: Int = 0,
    val reportedAtUtcMillis: Long = 0L,
    val systemUiVersion: String = "unknown",
    val aodVersion: String = "unknown",
    val verifiedRuntimeProfile: Boolean = false,
    val capabilities: Set<String> = emptySet(),
    val profileState: XiaomiProfileState = XiaomiProfileState.UNSUPPORTED_PROFILE,
    val experimentalModeActive: Boolean = false,
    val rawProbes: Map<String, Boolean> = emptyMap(),
    val summary: String = "No SystemUI capability report yet",
    /**
     * 用户在 app 端开启的实验模式开关(来自 [AodRenderPreferences.EXPERIMENTAL_MODE])。
     * 仅用于 app 端本地覆写:当 hook 端上报 `EXPERIMENTAL_ELIGIBLE` 且此项为 true 时,
     * [supportState] 返回 `EXPERIMENTAL_ACTIVE`、[has] 基于 rawProbes 推导 surface
     * capability,从而放开 UI 配置。不改变 hook 端上报的原始数据。
     */
    val experimentalModeEnabled: Boolean = false
) {
    val hasReport: Boolean
        get() = protocolVersion in 1..XiaomiCapabilityBundleCodec.CURRENT_PROTOCOL

    fun has(capability: XiaomiCapability): Boolean {
        if (!hasReport) return false
        if (capability.name in capabilities) return true
        // 实验模式:hook 端未上报 capability(因 verifiedRuntimeProfile=false 全砍),
        // 但 rawProbes 显示对应符号已探测到 —— 本地推导认为可用。
        return experimentalModeEnabled && capability in experimentalCapabilitiesFromProbes()
    }

    fun supportState(): XiaomiRuntimeSupportState = when {
        !hasReport -> XiaomiRuntimeSupportState.NO_SYSTEM_UI_REPORT
        profileState == XiaomiProfileState.VERIFIED_PROFILE ->
            XiaomiRuntimeSupportState.VERIFIED_PROFILE
        profileState == XiaomiProfileState.VERIFIED_PROFILE_MISSING_SYMBOLS ->
            XiaomiRuntimeSupportState.VERIFIED_PROFILE_MISSING_SYMBOLS
        profileState == XiaomiProfileState.EXPERIMENTAL_ELIGIBLE ->
            if (experimentalModeEnabled) XiaomiRuntimeSupportState.EXPERIMENTAL_ACTIVE
            else XiaomiRuntimeSupportState.EXPERIMENTAL_ELIGIBLE
        profileState == XiaomiProfileState.EXPERIMENTAL_ACTIVE ->
            XiaomiRuntimeSupportState.EXPERIMENTAL_ACTIVE
        else -> XiaomiRuntimeSupportState.UNSUPPORTED_PROFILE
    }

    /**
     * 基于 rawProbes 推导实验模式下可用的 capability。镜像 [XiaomiCapabilityResolver]
     * 的 probe→capability 逻辑,但只覆盖 surface 相关(够 UI 放开配置用)。
     */
    private fun experimentalCapabilitiesFromProbes(): Set<XiaomiCapability> {
        if (!experimentalModeEnabled) return emptySet()
        val caps = mutableSetOf<XiaomiCapability>()
        val aodSurfaceEligible = rawProbes["AOD_SURFACE_LIFECYCLE"] == true &&
            rawProbes["AOD_HOST_CONTAINER"] == true
        if (aodSurfaceEligible) {
            caps += XiaomiCapability.AOD_SURFACE
            if (rawProbes["AOD_POSITION_UPDATE"] == true) caps += XiaomiCapability.AOD_POSITION_UPDATES
            if (rawProbes["AOD_LIFETIME_POLICY"] == true) caps += XiaomiCapability.AOD_LIFETIME_GUARD
            if (rawProbes["AOD_WAKE_SEAM"] == true) caps += XiaomiCapability.AOD_WAKE_BROKER
            if (rawProbes["FULL_AOD"] == true) caps += XiaomiCapability.FULL_AOD
        }
        val lockscreenHostEligible = rawProbes["LOCKSCREEN_SECTION_LIFECYCLE"] == true &&
            rawProbes["LOCKSCREEN_CONTROLLER"] == true &&
            rawProbes["LOCKSCREEN_HOST_CONTAINER"] == true
        if (lockscreenHostEligible) {
            caps += XiaomiCapability.LOCKSCREEN_HOST
            if (rawProbes["LOCKSCREEN_CLOCK_GEOMETRY"] == true) caps += XiaomiCapability.LOCKSCREEN_GEOMETRY
            if (rawProbes["LINKAGE_DIRECTION"] == true) caps += XiaomiCapability.LINKAGE_DIRECTION
            if (rawProbes["LOCKSCREEN_CLOCK_GEOMETRY"] == true &&
                rawProbes["LINKAGE_DIRECTION"] == true &&
                rawProbes["LINKAGE_GEOMETRY"] == true) caps += XiaomiCapability.LINKAGE_GEOMETRY
            if (rawProbes["LOCKSCREEN_EDITOR_GESTURE"] == true) caps += XiaomiCapability.LOCKSCREEN_EDITOR_GESTURE
            if (rawProbes["VIDEO_DEPTH"] == true) caps += XiaomiCapability.VIDEO_DEPTH
        }
        if (rawProbes["RAISE_TO_AOD"] == true) caps += XiaomiCapability.RAISE_TO_AOD
        return caps
    }
}

internal object XiaomiCapabilityStore {
    internal const val PREFS = "xiaomi_capabilities"

    fun save(context: Context, bundle: Bundle) {
        val report = XiaomiCapabilityBundleCodec.fromBundle(bundle) ?: return
        val reportedAt = report.reportedAtUtcMillis.takeIf { it > 0L }
            ?: System.currentTimeMillis()
        val probeNames = report.rawProbes.keys.map { it.name }.toSet()
        val presentProbes = report.rawProbes.filterValues { it }.keys.map { it.name }.toSet()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_PROTOCOL, report.protocolVersion)
            .putLong(KEY_REPORTED_AT, reportedAt)
            .putString(KEY_SYSTEM_UI, report.systemUiVersion)
            .putString(KEY_AOD, report.aodVersion)
            .putBoolean(KEY_VERIFIED, report.verifiedRuntimeProfile)
            .putStringSet(KEY_CAPABILITIES, report.capabilities.map { it.name }.toSet())
            .putString(KEY_PROFILE_STATE, report.profileState.wireValue)
            .putBoolean(KEY_EXPERIMENTAL_ACTIVE, report.experimentalModeActive)
            .putStringSet(KEY_PROBE_NAMES, probeNames)
            .putStringSet(KEY_PRESENT_PROBES, presentProbes)
            .putString(KEY_SUMMARY, report.summary())
            .apply()
    }

    fun read(context: Context): StoredXiaomiCapabilityReport {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val protocolVersion = prefs.getInt(KEY_PROTOCOL, 0)
        val probeNames = prefs.getStringSet(KEY_PROBE_NAMES, emptySet()).orEmpty()
            .filterTo(linkedSetOf()) { name ->
                XiaomiSymbolProbe.entries.any { it.name == name }
            }
        val presentProbes = prefs.getStringSet(KEY_PRESENT_PROBES, emptySet()).orEmpty()
        val profileState = XiaomiProfileState.fromWireValue(
            prefs.getString(KEY_PROFILE_STATE, "").orEmpty()
        ) ?: legacyProfileState(
            verifiedRuntimeProfile = prefs.getBoolean(KEY_VERIFIED, false),
            capabilities = prefs.getStringSet(KEY_CAPABILITIES, emptySet()).orEmpty()
        )
        return StoredXiaomiCapabilityReport(
            protocolVersion = protocolVersion,
            reportedAtUtcMillis = prefs.getLong(KEY_REPORTED_AT, 0L),
            systemUiVersion = prefs.getString(KEY_SYSTEM_UI, "unknown").orEmpty(),
            aodVersion = prefs.getString(KEY_AOD, "unknown").orEmpty(),
            verifiedRuntimeProfile = prefs.getBoolean(KEY_VERIFIED, false),
            capabilities = prefs.getStringSet(KEY_CAPABILITIES, emptySet()).orEmpty(),
            profileState = profileState,
            experimentalModeActive = prefs.getBoolean(KEY_EXPERIMENTAL_ACTIVE, false),
            rawProbes = probeNames.associateWith { it in presentProbes },
            summary = prefs.getString(KEY_SUMMARY, "No SystemUI capability report yet").orEmpty()
        )
    }

    private fun legacyProfileState(
        verifiedRuntimeProfile: Boolean,
        capabilities: Set<String>
    ): XiaomiProfileState = when {
        !verifiedRuntimeProfile -> XiaomiProfileState.UNSUPPORTED_PROFILE
        LEGACY_BASELINE_CAPABILITIES.all(capabilities::contains) ->
            XiaomiProfileState.VERIFIED_PROFILE
        else -> XiaomiProfileState.VERIFIED_PROFILE_MISSING_SYMBOLS
    }

    private val LEGACY_BASELINE_CAPABILITIES = setOf(
        XiaomiCapability.AOD_SURFACE.name,
        XiaomiCapability.LOCKSCREEN_HOST.name,
        XiaomiCapability.LOCKSCREEN_GEOMETRY.name
    )

    private const val KEY_PROTOCOL = "protocol_version"
    private const val KEY_REPORTED_AT = "reported_at_utc_millis"
    private const val KEY_SYSTEM_UI = "system_ui_version"
    private const val KEY_AOD = "aod_version"
    private const val KEY_VERIFIED = "verified"
    private const val KEY_CAPABILITIES = "capabilities"
    private const val KEY_PROFILE_STATE = "profile_state"
    private const val KEY_EXPERIMENTAL_ACTIVE = "experimental_active"
    private const val KEY_PROBE_NAMES = "probe_names"
    private const val KEY_PRESENT_PROBES = "present_probes"
    private const val KEY_SUMMARY = "summary"
}

internal object XiaomiCapabilityBundleCodec {
    internal const val CURRENT_PROTOCOL = 2
    private const val LEGACY_PROTOCOL = 1

    fun toBundle(report: XiaomiCapabilityReport): Bundle = Bundle().apply {
        putInt(KEY_PROTOCOL, CURRENT_PROTOCOL)
        putLong(KEY_REPORTED_AT, report.reportedAtUtcMillis)
        putString(KEY_SYSTEM_UI, report.systemUiVersion)
        putString(KEY_AOD, report.aodVersion)
        putBoolean(KEY_VERIFIED, report.verifiedRuntimeProfile)
        putString(KEY_PROFILE_STATE, report.profileState.wireValue)
        putBoolean(KEY_EXPERIMENTAL_ACTIVE, report.experimentalModeActive)
        putStringArrayList(
            KEY_PROBE_NAMES,
            ArrayList(report.rawProbes.keys.map { it.name }.take(MAX_PROBES))
        )
        putStringArrayList(
            KEY_PRESENT_PROBES,
            ArrayList(report.rawProbes.filterValues { it }.keys.map { it.name }.take(MAX_PROBES))
        )
        putStringArrayList(
            KEY_CAPABILITIES,
            ArrayList(report.capabilities.map { it.name }.take(MAX_CAPABILITIES))
        )
    }

    fun fromBundle(bundle: Bundle): XiaomiCapabilityReport? = decodeXiaomiCapabilityPayload(
        XiaomiCapabilityWirePayload(
            protocolVersion = bundle.getInt(KEY_PROTOCOL, 0),
            reportedAtUtcMillis = bundle.getLong(KEY_REPORTED_AT, 0L),
            systemUiVersion = bundle.getString(KEY_SYSTEM_UI).orEmpty(),
            aodVersion = bundle.getString(KEY_AOD).orEmpty(),
            verifiedRuntimeProfile = bundle.getBoolean(KEY_VERIFIED, false),
            profileState = bundle.getString(KEY_PROFILE_STATE).orEmpty(),
            experimentalModeActive = bundle.getBoolean(KEY_EXPERIMENTAL_ACTIVE, false),
            probeNames = bundle.getStringArrayList(KEY_PROBE_NAMES).orEmpty(),
            presentProbeNames = bundle.getStringArrayList(KEY_PRESENT_PROBES).orEmpty(),
            capabilityNames = bundle.getStringArrayList(KEY_CAPABILITIES).orEmpty()
        )
    )

    internal fun decodeXiaomiCapabilityPayload(
        payload: XiaomiCapabilityWirePayload
    ): XiaomiCapabilityReport? {
        if (payload.capabilityNames.size > MAX_CAPABILITIES) return null
        return when (payload.protocolVersion) {
            LEGACY_PROTOCOL -> decodeLegacy(payload)
            CURRENT_PROTOCOL -> decodeCurrent(payload)
            else -> null
        }
    }

    private fun decodeLegacy(payload: XiaomiCapabilityWirePayload): XiaomiCapabilityReport {
        val capabilities = decodeCapabilities(payload.capabilityNames)
        val verified = payload.verifiedRuntimeProfile
        val profileState = if (verified && LEGACY_BASELINE_CAPABILITIES.all(capabilities::contains)) {
            XiaomiProfileState.VERIFIED_PROFILE
        } else if (verified) {
            XiaomiProfileState.VERIFIED_PROFILE_MISSING_SYMBOLS
        } else {
            XiaomiProfileState.UNSUPPORTED_PROFILE
        }
        return XiaomiCapabilityReport(
            protocolVersion = LEGACY_PROTOCOL,
            systemUiVersion = boundedVersion(payload.systemUiVersion),
            aodVersion = boundedVersion(payload.aodVersion),
            verifiedRuntimeProfile = verified,
            capabilities = capabilities,
            profileState = profileState,
            rawProbes = emptyMap()
        )
    }

    private fun decodeCurrent(payload: XiaomiCapabilityWirePayload): XiaomiCapabilityReport? {
        val probeNames = payload.probeNames
        val presentProbeNames = payload.presentProbeNames
        if (probeNames.size > MAX_PROBES || presentProbeNames.size > MAX_PROBES) return null
        if (!probeNames.toSet().containsAll(presentProbeNames)) return null
        val probes = probeNames.mapNotNull { name ->
            XiaomiSymbolProbe.entries.firstOrNull { it.name == name }
        }.distinct()
        val present = presentProbeNames.toSet()
        val profileState = XiaomiProfileState.fromWireValue(
            payload.profileState
        ) ?: return null
        if (!isConsistentProfileState(
                profileState,
                payload.verifiedRuntimeProfile,
                payload.experimentalModeActive
            )
        ) return null
        return XiaomiCapabilityReport(
            protocolVersion = CURRENT_PROTOCOL,
            reportedAtUtcMillis = payload.reportedAtUtcMillis.coerceAtLeast(0L),
            systemUiVersion = boundedVersion(payload.systemUiVersion),
            aodVersion = boundedVersion(payload.aodVersion),
            verifiedRuntimeProfile = payload.verifiedRuntimeProfile,
            capabilities = decodeCapabilities(payload.capabilityNames),
            profileState = profileState,
            experimentalModeActive = payload.experimentalModeActive,
            rawProbes = probes.associateWith { it.name in present }
        )
    }

    private fun decodeCapabilities(names: List<String>): Set<XiaomiCapability> {
        return names.take(MAX_CAPABILITIES)
            .mapNotNull { name -> XiaomiCapability.entries.firstOrNull { it.name == name } }
            .toSet()
    }

    private fun boundedVersion(value: String): String = value.take(MAX_VERSION_CHARS)

    private fun isConsistentProfileState(
        state: XiaomiProfileState,
        verifiedRuntimeProfile: Boolean,
        experimentalModeActive: Boolean
    ): Boolean = when (state) {
        XiaomiProfileState.VERIFIED_PROFILE,
        XiaomiProfileState.VERIFIED_PROFILE_MISSING_SYMBOLS ->
            verifiedRuntimeProfile && !experimentalModeActive
        XiaomiProfileState.UNSUPPORTED_PROFILE,
        XiaomiProfileState.EXPERIMENTAL_ELIGIBLE ->
            !verifiedRuntimeProfile && !experimentalModeActive
        XiaomiProfileState.EXPERIMENTAL_ACTIVE ->
            !verifiedRuntimeProfile && experimentalModeActive
    }

    private val LEGACY_BASELINE_CAPABILITIES = setOf(
        XiaomiCapability.AOD_SURFACE,
        XiaomiCapability.LOCKSCREEN_HOST,
        XiaomiCapability.LOCKSCREEN_GEOMETRY
    )

    private const val MAX_VERSION_CHARS = 100
    private const val MAX_PROBES = 32
    private const val MAX_CAPABILITIES = 32
    private const val KEY_PROTOCOL = "protocol"
    private const val KEY_REPORTED_AT = "reportedAtUtcMillis"
    private const val KEY_SYSTEM_UI = "systemUiVersion"
    private const val KEY_AOD = "aodVersion"
    private const val KEY_VERIFIED = "verified"
    private const val KEY_PROFILE_STATE = "profileState"
    private const val KEY_EXPERIMENTAL_ACTIVE = "experimentalModeActive"
    private const val KEY_PROBE_NAMES = "probeNames"
    private const val KEY_PRESENT_PROBES = "presentProbes"
    private const val KEY_CAPABILITIES = "capabilities"
}

internal data class XiaomiCapabilityWirePayload(
    val protocolVersion: Int,
    val reportedAtUtcMillis: Long = 0L,
    val systemUiVersion: String = "",
    val aodVersion: String = "",
    val verifiedRuntimeProfile: Boolean = false,
    val profileState: String = "",
    val experimentalModeActive: Boolean = false,
    val probeNames: List<String> = emptyList(),
    val presentProbeNames: List<String> = emptyList(),
    val capabilityNames: List<String> = emptyList()
)
