package com.eza.hyperglow.diagnostics

internal data class SpicySetupEvidence(
    val moduleStatus: String = "unknown",
    val spotifyScopeStatus: String = "unknown",
    val publishingStatus: String = "unknown"
)

internal val SPICY_MODULE_STATUSES = setOf("enabled", "disabled", "not_registered", "unknown")
internal val SPICY_SCOPE_STATUSES = setOf("selected", "not_selected", "unknown")
internal val SPICY_PUBLISHING_STATUSES = setOf("enabled", "disabled", "default_disabled", "unknown")

/** The APK path and user come from this process, never from report text or imported settings. */
internal fun spicySetupProbeCommand(apkPath: String, userId: Int): String? {
    if (userId !in 0..99_999 || !apkPath.startsWith("/data/app/") ||
        !apkPath.endsWith("/base.apk") ||
        !apkPath.matches(Regex("/[A-Za-z0-9_./+=~-]+")) ||
        apkPath.split('/').any { it == "." || it == ".." }
    ) return null
    return "CLASSPATH='$apkPath' /system/bin/app_process /system/bin " +
        "com.eza.hyperglow.diagnostics.SpicySetupProbe $userId 2>/dev/null"
}

internal fun parseSpicySetupEvidence(output: String, userId: Int): SpicySetupEvidence? {
    if (output.utf8Size() > 4_096) return null
    val lines = output.lineSequence().filter { it.startsWith("spicy_setup_v1 ") }.toList()
    if (lines.size != 1) return null
    val match = Regex(
        "spicy_setup_v1 user=([0-9]{1,5}) module=([a-z_]+) " +
            "scope=([a-z_]+) publishing=([a-z_]+)"
    ).matchEntire(lines.single()) ?: return null
    val (user, module, scope, publishing) = match.destructured
    if (user.toIntOrNull() != userId || module !in SPICY_MODULE_STATUSES ||
        scope !in SPICY_SCOPE_STATUSES || publishing !in SPICY_PUBLISHING_STATUSES
    ) return null
    return SpicySetupEvidence(module, scope, publishing)
}
