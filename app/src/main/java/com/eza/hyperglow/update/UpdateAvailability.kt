package com.eza.hyperglow.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What the intake host publishes as the current release. `versionCode` is the comparison key
 * because that is what the owner's release scheme increases monotonically; `versionName` is only
 * ever displayed.
 */
internal data class LatestVersion(
    val versionCode: Long,
    val versionName: String
)

internal sealed interface UpdateAvailability {
    /** No usable answer yet: never checked, offline, or a response that failed validation. */
    data object Unknown : UpdateAvailability

    data object UpToDate : UpdateAvailability

    data class UpdateAvailable(val latest: LatestVersion) : UpdateAvailability
}

@Serializable
private data class LatestVersionResponse(
    @SerialName("currentVersionCode") val currentVersionCode: Long? = null,
    @SerialName("currentVersionName") val currentVersionName: String? = null
)

/**
 * Unknown keys are tolerated here, unlike the report receipt. A version endpoint that gains a field
 * must not brick the update check on every installed build — that failure mode is the one this
 * feature exists to prevent.
 */
private val json = Json { ignoreUnknownKeys = true }

/** Upper bound on a plausible versionCode, so a malformed number cannot pin the UI to "outdated". */
private const val MAX_VERSION_CODE = 10_000_000L

/** Displayed verbatim, so the charset is restricted rather than trusted. */
private val VERSION_NAME_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

internal fun parseLatestVersion(body: String): LatestVersion? {
    val response = runCatching { json.decodeFromString<LatestVersionResponse>(body) }.getOrNull()
        ?: return null
    val versionCode = response.currentVersionCode ?: return null
    val versionName = response.currentVersionName ?: return null
    if (versionCode !in 1..MAX_VERSION_CODE) return null
    if (!VERSION_NAME_PATTERN.matches(versionName)) return null
    return LatestVersion(versionCode = versionCode, versionName = versionName)
}

internal fun resolveUpdateAvailability(
    installedVersionCode: Long,
    latest: LatestVersion?
): UpdateAvailability = when {
    latest == null -> UpdateAvailability.Unknown
    // A local build ahead of the published release is current, not a downgrade prompt.
    installedVersionCode >= latest.versionCode -> UpdateAvailability.UpToDate
    else -> UpdateAvailability.UpdateAvailable(latest)
}

/**
 * The version endpoint is a sibling of the report endpoint on the same host, derived rather than
 * configured separately so the two can never point at different origins.
 */
internal fun deriveVersionEndpoint(intakeUrl: String): String? {
    val trimmed = intakeUrl.trim()
    if (!trimmed.startsWith("https://")) return null
    if (!trimmed.endsWith(REPORT_PATH)) return null
    if (trimmed.contains('?') || trimmed.contains('#') || trimmed.contains('@')) return null
    return trimmed.removeSuffix(REPORT_PATH) + VERSION_PATH
}

private const val REPORT_PATH = "/v1/reports"
internal const val VERSION_PATH = "/v1/version"
