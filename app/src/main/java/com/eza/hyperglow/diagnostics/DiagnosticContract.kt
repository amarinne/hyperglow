package com.eza.hyperglow.diagnostics

import com.eza.hyperglow.root.capability.XiaomiCapability
import com.eza.hyperglow.root.capability.XiaomiProfileState
import com.eza.hyperglow.root.capability.XiaomiSymbolProbe
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.math.BigInteger
import java.security.SecureRandom

internal object DiagnosticLimits {
    const val DESCRIPTION_BYTES = 4_000
    const val CLIENT_BODY_BYTES = 384 * 1024
    const val LOGCAT_BYTES = 160 * 1024
    const val CRASH_BYTES = 64 * 1024
    const val LSPOSED_BYTES = 64 * 1024
    const val MEDIA_METADATA_BYTES = 512
    const val LYRIC_LINE_BYTES = 8 * 1024
    const val CAPTURE_TTL_MS = 30L * 60L * 1000L
    const val COMMAND_TIMEOUT_MS = 5_000L
}

@Serializable
internal data class DiagnosticMediaEvidence(
    val present: Boolean,
    val trackUri: String,
    val title: String,
    val artist: String,
    val album: String,
    val source: String,
    val provider: String,
    val language: String,
    val timingType: String,
    val lineIndex: Int,
    val originalLine: String,
    val romanizedLine: String,
    val translatedLine: String,
    val stateAgeMs: Long?
)

internal enum class HyperGlowReportCategory(
    val wireValue: String,
    val displayName: String
) {
    COMPATIBILITY("compatibility", "Compatibility"),
    AOD_SURFACE("aod_surface", "AOD surface"),
    LOCKSCREEN_SURFACE("lockscreen_surface", "Lock screen surface"),
    PLAYBACK_BRIDGE("playback_bridge", "Spotify bridge"),
    SYSTEM_UI_FAILURE("systemui_failure", "System UI crash or restart"),
    CONFIGURATION("configuration", "Configuration"),
    OTHER("other", "Other");

    companion object {
        fun fromWireValue(value: String): HyperGlowReportCategory? = entries.firstOrNull {
            it.wireValue == value
        }
    }
}

@Serializable
internal data class DiagnosticPackageVersion(
    val present: Boolean,
    val versionName: String,
    val versionCode: Long
)

@Serializable
internal data class DiagnosticCommonMetadata(
    val appVersionName: String,
    val appVersionCode: Int,
    val buildType: String,
    val manufacturer: String,
    val brand: String,
    val model: String,
    val device: String,
    val product: String,
    val androidRelease: String,
    val androidApi: Int,
    val androidSecurityPatch: String,
    val androidDisplay: String,
    val androidIncremental: String,
    val buildFingerprint: String,
    val xiaomiOsProperties: Map<String, String>,
    val locales: List<String>,
    val packageVersions: Map<String, DiagnosticPackageVersion>
)

@Serializable
internal data class HyperGlowProductMetadata(
    val capabilityReportProtocol: Int,
    val capabilityReportAgeMs: Long?,
    val profileState: String,
    val rawSymbolProbes: Map<String, Boolean>,
    val resolvedCapabilities: List<String>,
    val configuredSurfaces: Map<String, Boolean>,
    val systemUiCallbackPresent: Boolean,
    val spotifyProducerStatePresent: Boolean,
    val spotifyProducerSafeStatus: String,
    val spotifyProducerPlaying: Boolean?,
    val spotifyProducerStateAgeMs: Long?,
    val diagnosticLoggingAvailable: Boolean,
    val diagnosticLoggingEnabled: Boolean,
    val rootAccessStatus: String,
    val currentMediaEvidence: DiagnosticMediaEvidence,
    val setupChecks: HyperGlowSetupChecks = HyperGlowSetupChecks()
)

@Serializable
internal data class HyperGlowSetupChecks(
    val setupState: String = "warning",
    val setupFailures: List<String> = listOf("requirements_not_checked"),
    val rootAccessStatus: String = "not_checked",
    val capabilityReportPresent: Boolean = false,
    val systemUiHookActive: Boolean = false,
    val profileSupported: Boolean = false,
    val spotifyProducerBridgePresent: Boolean = false,
    val spicyExPackagePresent: Boolean = false,
    val requiredPackagesPresent: Boolean = false
)

@Serializable
internal data class DiagnosticCaptureMetadata(
    val outcome: String,
    val startedAtUtc: String?,
    val finishedAtUtc: String?,
    val previousDiagnosticLoggingEnabled: Boolean?,
    val rootAccessStatus: String,
    val commandFailures: List<String>,
    val truncationFlags: Map<String, Boolean>
)

@Serializable
internal data class DiagnosticRawData(
    val diagnosticEventsAndLogs: String,
    val crashExcerpt: String,
    val lsposedModuleLines: String,
    val runtimeSettings: Map<String, String>
)

@Serializable
internal data class DiagnosticReportEnvelope(
    val envelopeVersion: Int = 1,
    val reportId: String,
    val product: String = "hyperglow",
    val productReportVersion: Int = 2,
    val createdAtUtc: String,
    val category: String,
    val description: String,
    val commonMetadata: DiagnosticCommonMetadata,
    val productMetadata: HyperGlowProductMetadata,
    val capture: DiagnosticCaptureMetadata,
    val rawDiagnostics: DiagnosticRawData
)

internal object DiagnosticReportCodec {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
    }
    private val prettyJson = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        prettyPrint = true
    }

    fun encode(report: DiagnosticReportEnvelope): String {
        require(isValidReport(report)) { "Invalid diagnostic report" }
        val encoded = json.encodeToString(report)
        require(encoded.utf8Size() <= DiagnosticLimits.CLIENT_BODY_BYTES) {
            "Diagnostic report exceeds client limit"
        }
        return encoded
    }

    fun decodeOrNull(encoded: String): DiagnosticReportEnvelope? = runCatching {
        json.decodeFromString<DiagnosticReportEnvelope>(encoded)
    }.getOrNull()?.takeIf(::isValidReport)

    fun encodePretty(report: DiagnosticReportEnvelope): String {
        require(isValidReport(report)) { "Invalid diagnostic report" }
        return prettyJson.encodeToString(report)
    }

    fun isValidReport(report: DiagnosticReportEnvelope): Boolean {
        if (report.envelopeVersion != 1 || report.product != "hyperglow" ||
            report.productReportVersion != 2 || !isValidDiagnosticReportId(report.reportId) ||
            HyperGlowReportCategory.fromWireValue(report.category) == null ||
            report.createdAtUtc.length !in 1..64 ||
            report.description.isBlank() ||
            report.description.utf8Size() > DiagnosticLimits.DESCRIPTION_BYTES ||
            report.commonMetadata.xiaomiOsProperties.size > 16 ||
            report.commonMetadata.locales.size > 8 ||
            report.commonMetadata.packageVersions.size > 8 ||
            report.productMetadata.rawSymbolProbes.size > 32 ||
            report.productMetadata.resolvedCapabilities.size > 32 ||
            report.productMetadata.configuredSurfaces.size > 4 ||
            report.productMetadata.setupChecks.setupFailures.size > 12 ||
            report.capture.commandFailures.size > 8 ||
            report.capture.truncationFlags.size > 8 ||
            report.rawDiagnostics.runtimeSettings.size > 32
        ) return false
        if (report.rawDiagnostics.diagnosticEventsAndLogs.utf8Size() >
            DiagnosticLimits.LOGCAT_BYTES ||
            report.rawDiagnostics.crashExcerpt.utf8Size() > DiagnosticLimits.CRASH_BYTES ||
            report.rawDiagnostics.lsposedModuleLines.utf8Size() > DiagnosticLimits.LSPOSED_BYTES
        ) return false
        if (report.productMetadata.profileState !in PROFILE_STATES ||
            report.productMetadata.spotifyProducerSafeStatus !in PRODUCER_STATUSES ||
            report.productMetadata.rootAccessStatus !in ROOT_ACCESS_STATES ||
            report.productMetadata.setupChecks.setupState !in SETUP_STATES ||
            report.productMetadata.setupChecks.rootAccessStatus !in ROOT_ACCESS_STATES ||
            !SETUP_FAILURE_KEYS.containsAll(report.productMetadata.setupChecks.setupFailures) ||
            report.capture.outcome !in CAPTURE_OUTCOMES ||
            report.capture.rootAccessStatus !in ROOT_ACCESS_STATES ||
            !PACKAGE_KEYS.containsAll(report.commonMetadata.packageVersions.keys) ||
            !XIAOMI_PROPERTY_KEYS.containsAll(report.commonMetadata.xiaomiOsProperties.keys) ||
            !CONFIGURED_SURFACE_KEYS.containsAll(report.productMetadata.configuredSurfaces.keys) ||
            !COMMAND_FAILURE_KEYS.containsAll(report.capture.commandFailures) ||
            !TRUNCATION_KEYS.containsAll(report.capture.truncationFlags.keys) ||
            !RUNTIME_SETTING_KEYS.containsAll(report.rawDiagnostics.runtimeSettings.keys) ||
            !RAW_PROBE_KEYS.containsAll(report.productMetadata.rawSymbolProbes.keys) ||
            !CAPABILITY_KEYS.containsAll(report.productMetadata.resolvedCapabilities) ||
            report.productMetadata.capabilityReportProtocol !in 0..2 ||
            report.productMetadata.capabilityReportAgeMs?.let { it < 0L } == true ||
            report.productMetadata.spotifyProducerStateAgeMs?.let { it < 0L } == true ||
            report.productMetadata.currentMediaEvidence.stateAgeMs?.let { it < 0L } == true ||
            report.productMetadata.currentMediaEvidence.lineIndex !in -1..5_000 ||
            report.productMetadata.rootAccessStatus !=
                report.productMetadata.setupChecks.rootAccessStatus ||
            report.commonMetadata.packageVersions.values.any { it.versionCode < 0L }
        ) return false
        if (!report.productMetadata.currentMediaEvidence.isValid()) return false
        return report.commonMetadata.allStringsBounded() &&
            report.productMetadata.allStringsBounded() &&
            report.rawDiagnostics.runtimeSettings.all {
                it.key.length <= 64 && it.value.utf8Size() <= 256
            }
    }

    private fun DiagnosticCommonMetadata.allStringsBounded(): Boolean =
        listOf(
            appVersionName,
            buildType,
            manufacturer,
            brand,
            model,
            device,
            product,
            androidRelease,
            androidSecurityPatch,
            androidDisplay,
            androidIncremental
        ).all { it.utf8Size() <= 256 } && buildFingerprint.utf8Size() <= 1_024 &&
            xiaomiOsProperties.all { it.key.length <= 64 && it.value.utf8Size() <= 256 } &&
            locales.all { it.utf8Size() <= 64 } && packageVersions.all {
                it.key.length <= 64 && it.value.versionName.utf8Size() <= 256
            }

    private fun HyperGlowProductMetadata.allStringsBounded(): Boolean =
        profileState.length <= 64 && spotifyProducerSafeStatus.length <= 64 &&
            rootAccessStatus.length <= 32 &&
            setupChecks.setupState.length <= 16 &&
            setupChecks.rootAccessStatus.length <= 32 &&
            setupChecks.setupFailures.all { it.length <= 64 } &&
            rawSymbolProbes.keys.all { it.length <= 64 } &&
            resolvedCapabilities.all { it.length <= 64 } &&
            configuredSurfaces.keys.all { it.length <= 32 }

    private fun DiagnosticMediaEvidence.isValid(): Boolean {
        val metadata = listOf(
            trackUri,
            title,
            artist,
            album,
            source,
            provider,
            language,
            timingType
        )
        if (metadata.any { it.utf8Size() > DiagnosticLimits.MEDIA_METADATA_BYTES } ||
            originalLine.utf8Size() > DiagnosticLimits.LYRIC_LINE_BYTES ||
            romanizedLine.utf8Size() > DiagnosticLimits.LYRIC_LINE_BYTES ||
            translatedLine.utf8Size() > DiagnosticLimits.LYRIC_LINE_BYTES
        ) return false
        return if (present) {
            trackUri.startsWith("spotify:track:") && title.isNotBlank()
        } else {
            metadata.all(String::isEmpty) && lineIndex == -1 &&
                originalLine.isEmpty() && romanizedLine.isEmpty() && translatedLine.isEmpty() &&
                stateAgeMs == null
        }
    }

    // "no_systemui_report" is a synthetic sentinel DiagnosticReportFactory sends when there is no
    // capability report at all; it has no XiaomiProfileState counterpart. Every other value is
    // derived from the enum rather than hand-copied, so a new profile state is accepted here the
    // moment it exists instead of needing this set remembered and updated separately.
    private val PROFILE_STATES = XiaomiProfileState.entries.mapTo(hashSetOf()) { it.wireValue } +
        "no_systemui_report"
    private val PRODUCER_STATUSES = setOf("absent", "loading", "ready", "no_lyrics")
    private val ROOT_ACCESS_STATES = setOf("not_checked", "granted", "denied", "error")
    private val SETUP_STATES = setOf("ready", "warning", "failed")
    private val SETUP_FAILURE_KEYS = setOf(
        "requirements_not_checked",
        "root_access",
        "capability_report",
        "systemui_hook",
        "unsupported_profile",
        "systemui_package",
        "xiaomi_aod_package",
        "spicy_ex_package",
        "spotify_package",
        "spotify_bridge"
    )
    private val CAPTURE_OUTCOMES = setOf(
        "not_requested",
        "captured",
        "partial_capture",
        "metadata_only_root_denied"
    )
    private val PACKAGE_KEYS = setOf(
        "hyperglow",
        "systemui",
        "xiaomi_aod",
        "spicy_ex",
        "spotify"
    )
    private val XIAOMI_PROPERTY_KEYS = setOf(
        "ro.mi.os.version.name",
        "ro.mi.os.version.incremental",
        "ro.miui.ui.version.name",
        "ro.miui.ui.version.code",
        "ro.product.mod_device",
        "ro.miui.build.region"
    )
    private val CONFIGURED_SURFACE_KEYS = setOf("aod", "lockscreen")
    private val COMMAND_FAILURE_KEYS = setOf(
        "root_access",
        "logs",
        "systemui_processes",
        "framework",
        "crash",
        "lsposed"
    )
    private val TRUNCATION_KEYS = setOf(
        "diagnosticEventsAndLogs",
        "crashExcerpt",
        "lsposedModuleLines"
    )
    private val RUNTIME_SETTING_KEYS = setOf(
        "aodConfigured",
        "lockscreenConfigured",
        "keepAodActive",
        "keepAodActiveWithoutTimedLyrics",
        "keepAodActiveDurationMs",
        "lockscreenKeepAwake",
        "raiseToAod",
        "positionFollowing",
        "diagnosticLogging",
        "diagnosticLoggingDuringCapture"
    )
    private val RAW_PROBE_KEYS = XiaomiSymbolProbe.entries.mapTo(hashSetOf()) { it.name }
    private val CAPABILITY_KEYS = XiaomiCapability.entries.mapTo(hashSetOf()) { it.name }
}

internal data class DiagnosticJsonPreview(
    val reportJson: String,
    val diagnosticEventsAndLogs: String,
    val crashExcerpt: String,
    val lsposedModuleLines: String,
    val runtimeSettingsJson: String
)

internal object DiagnosticJsonPreviewFormatter {
    fun format(encoded: String): DiagnosticJsonPreview? {
        val report = DiagnosticReportCodec.decodeOrNull(encoded) ?: return null
        val root = runCatching { Json.parseToJsonElement(encoded).jsonObject }.getOrNull()
            ?: return null
        val reportWithoutRawDiagnostics = JsonObject(root - "rawDiagnostics")
        return DiagnosticJsonPreview(
            reportJson = PRETTY_JSON.encodeToString(reportWithoutRawDiagnostics),
            diagnosticEventsAndLogs = report.rawDiagnostics.diagnosticEventsAndLogs,
            crashExcerpt = report.rawDiagnostics.crashExcerpt,
            lsposedModuleLines = report.rawDiagnostics.lsposedModuleLines,
            runtimeSettingsJson = PRETTY_JSON.encodeToString(report.rawDiagnostics.runtimeSettings)
        )
    }

    private val PRETTY_JSON = Json { prettyPrint = true }
}

internal object DiagnosticReportId {
    private val secureRandom = SecureRandom()
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    fun generate(): String = fromBytes(ByteArray(16).also(secureRandom::nextBytes))

    internal fun fromBytes(bytes: ByteArray): String {
        require(bytes.size == 16)
        var value = BigInteger(1, bytes)
        val encoded = CharArray(26) { '0' }
        for (index in encoded.lastIndex downTo 0) {
            val division = value.divideAndRemainder(BigInteger.valueOf(32L))
            encoded[index] = ALPHABET[division[1].toInt()]
            value = division[0]
        }
        return "R1-${String(encoded)}"
    }
}

internal fun isValidDiagnosticReportId(value: String): Boolean =
    value.length == 29 && value.startsWith("R1-") &&
        value.substring(3).all { it in "0123456789ABCDEFGHJKMNPQRSTVWXYZ" }

internal data class BoundedDiagnosticText(
    val text: String,
    val truncated: Boolean,
    val originalBytes: Int,
    val includedBytes: Int
)

internal fun truncateDiagnosticText(value: String, maxBytes: Int): BoundedDiagnosticText {
    require(maxBytes >= 0)
    val originalBytes = value.utf8Size()
    if (originalBytes <= maxBytes) {
        return BoundedDiagnosticText(value, false, originalBytes, originalBytes)
    }
    val marker = "\n--- TRUNCATED: middle removed ---\n"
    if (maxBytes <= marker.utf8Size()) {
        val text = value.utf8Prefix(maxBytes)
        return BoundedDiagnosticText(text, true, originalBytes, text.utf8Size())
    }
    val contentBudget = maxBytes - marker.utf8Size()
    val firstBudget = contentBudget / 4
    val newestBudget = contentBudget - firstBudget
    val text = value.utf8Prefix(firstBudget) + marker + value.utf8Suffix(newestBudget)
    return BoundedDiagnosticText(text, true, originalBytes, text.utf8Size())
}

internal fun truncateDiagnosticLines(value: String, maxBytes: Int): BoundedDiagnosticText {
    require(maxBytes >= 0)
    val originalBytes = value.utf8Size()
    if (originalBytes <= maxBytes) {
        return BoundedDiagnosticText(value, false, originalBytes, originalBytes)
    }
    val marker = "\n--- TRUNCATED: middle removed ---\n"
    if (maxBytes <= marker.utf8Size()) {
        val text = value.utf8Prefix(maxBytes)
        return BoundedDiagnosticText(text, true, originalBytes, text.utf8Size())
    }
    val contentBudget = maxBytes - marker.utf8Size()
    val firstBudget = contentBudget / 4
    val newestBudget = contentBudget - firstBudget
    val prefixCandidate = value.utf8Prefix(firstBudget)
    val prefix = prefixCandidate.lastIndexOf('\n').let { newline ->
        if (newline >= 0) prefixCandidate.substring(0, newline + 1) else prefixCandidate
    }
    val suffixCandidate = value.utf8Suffix(newestBudget)
    val suffixStart = value.length - suffixCandidate.length
    val suffix = when {
        suffixCandidate.isEmpty() -> ""
        suffixStart == 0 || value[suffixStart - 1] == '\n' -> suffixCandidate
        else -> suffixCandidate.indexOf('\n').let { newline ->
            if (newline >= 0 && newline + 1 < suffixCandidate.length) {
                suffixCandidate.substring(newline + 1)
            } else {
                ""
            }
        }
    }
    val text = prefix + marker + suffix
    return BoundedDiagnosticText(text, true, originalBytes, text.utf8Size())
}

internal fun String.utf8Size(): Int = toByteArray(Charsets.UTF_8).size

internal fun String.utf8Prefix(maxBytes: Int): String {
    if (maxBytes <= 0) return ""
    var index = 0
    var bytes = 0
    while (index < length) {
        val codePoint = codePointAt(index)
        val nextBytes = utf8CodePointBytes(codePoint)
        if (bytes + nextBytes > maxBytes) break
        bytes += nextBytes
        index += Character.charCount(codePoint)
    }
    return substring(0, index)
}

internal fun String.utf8Suffix(maxBytes: Int): String {
    if (maxBytes <= 0) return ""
    var index = length
    var bytes = 0
    while (index > 0) {
        val codePoint = codePointBefore(index)
        val nextBytes = utf8CodePointBytes(codePoint)
        if (bytes + nextBytes > maxBytes) break
        bytes += nextBytes
        index -= Character.charCount(codePoint)
    }
    return substring(index)
}

private fun utf8CodePointBytes(codePoint: Int): Int = when {
    codePoint <= 0x7f -> 1
    codePoint <= 0x7ff -> 2
    codePoint <= 0xffff -> 3
    else -> 4
}

internal data class DiagnosticGitHubIssue(
    val title: String,
    val body: String
)

internal const val HYPERGLOW_DIAGNOSTIC_DATA_POLICY_URL =
    "https://github.com/amarinne/hyperglow/blob/main/DIAGNOSTIC_DATA_POLICY.md"

internal fun buildHyperGlowGitHubIssue(report: DiagnosticReportEnvelope): DiagnosticGitHubIssue {
    val product = report.commonMetadata
    val compatibility = report.productMetadata
    val category = HyperGlowReportCategory.fromWireValue(report.category)?.displayName
        ?: "Problem report"
    val systemUi = product.packageVersions["systemui"]?.versionName ?: "unknown"
    val aod = product.packageVersions["xiaomi_aod"]?.versionName ?: "unknown"
    val spicyEx = product.packageVersions["spicy_ex"]?.versionName ?: "unknown"
    val spotify = product.packageVersions["spotify"]?.versionName ?: "unknown"
    val media = compatibility.currentMediaEvidence
    return DiagnosticGitHubIssue(
        title = "[HyperGlow] $category — ${report.reportId}",
        body = buildString {
            append("## Description\n\n")
            append(report.description.trim()).append("\n\n")
            append("## Report details\n\n")
            append("- **Report ID:** `").append(report.reportId).append("`\n")
            append("- **HyperGlow:** `").append(product.appVersionName)
                .append("` (`vC").append(product.appVersionCode).append("`)\n")
            append("- **Device:** ").append(markdownText(product.manufacturer)).append(' ')
                .append(markdownText(product.model)).append(" (`")
                .append(markdownText(product.device)).append("`)\n")
            append("- **Compatibility:** ")
                .append(markdownText(compatibility.profileState.replace('_', ' ')))
                .append(" (").append(compatibility.resolvedCapabilities.size)
                .append(" capabilities)\n")
            append("- **System UI / AOD:** `").append(markdownText(systemUi))
                .append("` / `").append(markdownText(aod)).append("`\n")
            append("- **Spicy EX / Spotify:** `").append(markdownText(spicyEx))
                .append("` / `").append(markdownText(spotify)).append("`\n\n")
            if (media.present) {
                append("## Song evidence\n\n")
                append("- **Song:** ").append(markdownText(media.title)).append(" — ")
                    .append(markdownText(media.artist)).append('\n')
                append("- **Spotify URI:** `").append(markdownText(media.trackUri)).append("`\n")
                append("- **Lyrics:** provider=`").append(markdownText(media.provider))
                    .append("`, language=`").append(markdownText(media.language))
                    .append("`, timing=`").append(markdownText(media.timingType)).append("`\n\n")
            }
            append("## Diagnostic data\n\n")
            append("Private diagnostic data is stored under the report ID and is not included ")
            append("in this issue. See the [diagnostic data policy](")
                .append(HYPERGLOW_DIAGNOSTIC_DATA_POLICY_URL).append(").")
        }
    )
}

private fun markdownText(value: String): String = value
    .replace('`', '\'')
    .replace('\n', ' ')
    .replace('\r', ' ')
