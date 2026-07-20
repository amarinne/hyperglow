package com.eza.hyperglow.aod

import android.content.Context
import android.os.Bundle
import com.eza.hyperglow.root.capability.XiaomiCapability
import com.eza.hyperglow.root.capability.XiaomiCapabilityReport

internal data class StoredXiaomiCapabilityReport(
    val systemUiVersion: String = "unknown",
    val aodVersion: String = "unknown",
    val verifiedRuntimeProfile: Boolean = false,
    val capabilities: Set<String> = emptySet(),
    val summary: String = "No SystemUI capability report yet"
) {
    fun has(capability: XiaomiCapability): Boolean = capability.name in capabilities
}

internal object XiaomiCapabilityStore {
    internal const val PREFS = "xiaomi_capabilities"

    fun save(context: Context, bundle: Bundle) {
        val report = XiaomiCapabilityBundleCodec.fromBundle(bundle) ?: return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_SYSTEM_UI, report.systemUiVersion)
            .putString(KEY_AOD, report.aodVersion)
            .putBoolean(KEY_VERIFIED, report.verifiedRuntimeProfile)
            .putStringSet(KEY_CAPABILITIES, report.capabilities.map { it.name }.toSet())
            .putString(KEY_SUMMARY, report.summary())
            .apply()
    }

    fun read(context: Context): StoredXiaomiCapabilityReport {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return StoredXiaomiCapabilityReport(
            systemUiVersion = prefs.getString(KEY_SYSTEM_UI, "unknown").orEmpty(),
            aodVersion = prefs.getString(KEY_AOD, "unknown").orEmpty(),
            verifiedRuntimeProfile = prefs.getBoolean(KEY_VERIFIED, false),
            capabilities = prefs.getStringSet(KEY_CAPABILITIES, emptySet()).orEmpty(),
            summary = prefs.getString(KEY_SUMMARY, "No SystemUI capability report yet").orEmpty()
        )
    }

    private const val KEY_SYSTEM_UI = "system_ui_version"
    private const val KEY_AOD = "aod_version"
    private const val KEY_VERIFIED = "verified"
    private const val KEY_CAPABILITIES = "capabilities"
    private const val KEY_SUMMARY = "summary"
}

internal object XiaomiCapabilityBundleCodec {
    fun toBundle(report: XiaomiCapabilityReport): Bundle = Bundle().apply {
        putInt(KEY_PROTOCOL, 1)
        putString(KEY_SYSTEM_UI, report.systemUiVersion)
        putString(KEY_AOD, report.aodVersion)
        putBoolean(KEY_VERIFIED, report.verifiedRuntimeProfile)
        putStringArrayList(KEY_CAPABILITIES, ArrayList(report.capabilities.map { it.name }))
    }

    fun fromBundle(bundle: Bundle): XiaomiCapabilityReport? {
        if (bundle.getInt(KEY_PROTOCOL, 0) != 1) return null
        val capabilities = bundle.getStringArrayList(KEY_CAPABILITIES).orEmpty()
            .mapNotNull { name -> XiaomiCapability.entries.firstOrNull { it.name == name } }
            .toSet()
        return XiaomiCapabilityReport(
            systemUiVersion = bundle.getString(KEY_SYSTEM_UI).orEmpty().take(100),
            aodVersion = bundle.getString(KEY_AOD).orEmpty().take(100),
            verifiedRuntimeProfile = bundle.getBoolean(KEY_VERIFIED, false),
            capabilities = capabilities
        )
    }

    private const val KEY_PROTOCOL = "protocol"
    private const val KEY_SYSTEM_UI = "systemUiVersion"
    private const val KEY_AOD = "aodVersion"
    private const val KEY_VERIFIED = "verified"
    private const val KEY_CAPABILITIES = "capabilities"
}
