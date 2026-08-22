package com.eza.hyperglow.ui

import com.eza.hyperglow.aod.AodRenderConfig
import com.eza.hyperglow.customization.SceneCompiler
import org.junit.Assert.assertEquals
import org.junit.Test

class ConfigBackupCodecTest {
    /** A profile far from the defaults, including every Long at a value that fits in an Int. */
    private fun nonDefaultPreferences() = AodRenderConfig(
        aodEnabled = false,
        lockscreenEnabled = true,
        alignment = "end",
        secondaryMode = "Both",
        overflowMode = "Clip",
        metadataVisible = "show",
        metadataAnchor = "bottom",
        weight = "Bold",
        textSize = "large",
        textSizeCustom = 137,
        fontFamily = "noto",
        animation = "Minimal",
        glow = "On",
        adaptiveSectioning = false,
        keepAwake = false,
        keepAwakeUnsynced = true,
        keepAwakeDurationMs = 300_000L,
        experimentalPositionFollowing = true,
        burnInPattern = "four_corner",
        burnInIntervalMs = 60_000L,
        pauseLingerMs = 5_000L,
        lockscreenKeepAwake = true,
        raiseToAod = true,
        suppressLockscreenEditorLongPress = true,
        songChangeInfoEnabled = false,
        hideLauncherIcon = true,
        hideFromRecents = true
    )

    @Test
    fun nonDefaultProfileRoundTripsFieldForField() {
        val encoded = ConfigBackupCodec.encode(nonDefaultPreferences(), null)

        val result = ConfigBackupCodec.decode(encoded.toByteArray())

        val decoded = (result as ConfigBackupDecodeResult.Success).preferences
        // The three Long preferences are exactly the fork's corruption case: each value fits in an
        // Int, and a guessing ladder returns them as Int and crashes the next getLong read.
        assertEquals(300_000L, decoded.keepAwakeDurationMs)
        assertEquals(60_000L, decoded.burnInIntervalMs)
        assertEquals(5_000L, decoded.pauseLingerMs)
        assertEquals(nonDefaultPreferences(), decoded)
    }

    @Test
    fun customizationDocumentRoundTrips() {
        val document = SceneCompiler.safeDefaultDocument()

        val result = ConfigBackupCodec.decode(
            ConfigBackupCodec.encode(AodRenderConfig(), document).toByteArray()
        )

        assertEquals(document, (result as ConfigBackupDecodeResult.Success).customizationDocument)
    }

    @Test
    fun numericStringValueSurvivesAsAStringNotANumber() {
        val preferences = nonDefaultPreferences().copy(fontFamily = "100")

        val result = ConfigBackupCodec.decode(
            ConfigBackupCodec.encode(preferences, null).toByteArray()
        )

        assertEquals("100", (result as ConfigBackupDecodeResult.Success).preferences.fontFamily)
    }

    @Test
    fun unknownKeysAreDroppedNeverDecoded() {
        val withExtras = """
            {
              "format": "${ConfigBackupCodec.FORMAT}",
              "version": ${ConfigBackupCodec.VERSION},
              "injectedTopLevel": {"deep": [1, 2, 3]},
              "renderPreferences": {
                "${com.eza.hyperglow.aod.AodRenderPreferences.KEEP_AWAKE}": false,
                "arbitrary_key": 42,
                "${com.eza.hyperglow.aod.AodRenderPreferences.PAUSE_LINGER_MS}": "5000"
              }
            }
        """.trimIndent()

        val withoutExtras = """
            {
              "format": "${ConfigBackupCodec.FORMAT}",
              "version": ${ConfigBackupCodec.VERSION},
              "renderPreferences": {
                "${com.eza.hyperglow.aod.AodRenderPreferences.KEEP_AWAKE}": false
              }
            }
        """.trimIndent()

        val fromExtras = ConfigBackupCodec.decode(withExtras.toByteArray())
        val fromClean = ConfigBackupCodec.decode(withoutExtras.toByteArray())

        assertEquals(fromClean, fromExtras)
    }

    @Test
    fun wrongTypedValueFallsBackToDefaultInsteadOfGuessing() {
        val payload = """
            {
              "format": "${ConfigBackupCodec.FORMAT}",
              "version": ${ConfigBackupCodec.VERSION},
              "renderPreferences": {
                "${com.eza.hyperglow.aod.AodRenderPreferences.KEEP_AWAKE_DURATION_MS}": 300000.5,
                "${com.eza.hyperglow.aod.AodRenderPreferences.WEIGHT}": 100
              }
            }
        """.trimIndent()

        val result = ConfigBackupCodec.decode(payload.toByteArray())

        val decoded = (result as ConfigBackupDecodeResult.Success).preferences
        assertEquals(-1L, decoded.keepAwakeDurationMs)
        assertEquals("Medium", decoded.weight)
    }

    @Test
    fun wrongFormatTagIsRejected() {
        val payload = """
            {"format": "something-else", "version": ${ConfigBackupCodec.VERSION}}
        """.trimIndent()

        val result = ConfigBackupCodec.decode(payload.toByteArray())

        assertEquals(
            ConfigBackupDecodeResult.Rejected(ConfigBackupRejection.BAD_FORMAT),
            result
        )
    }

    @Test
    fun unsupportedVersionIsRejected() {
        val payload = """
            {"format": "${ConfigBackupCodec.FORMAT}", "version": 999}
        """.trimIndent()

        val result = ConfigBackupCodec.decode(payload.toByteArray())

        assertEquals(
            ConfigBackupDecodeResult.Rejected(ConfigBackupRejection.BAD_VERSION),
            result
        )
    }

    @Test
    fun missingVersionIsRejected() {
        val payload = """{"format": "${ConfigBackupCodec.FORMAT}"}"""

        val result = ConfigBackupCodec.decode(payload.toByteArray())

        assertEquals(
            ConfigBackupDecodeResult.Rejected(ConfigBackupRejection.BAD_VERSION),
            result
        )
    }

    @Test
    fun malformedJsonIsRejected() {
        val result = ConfigBackupCodec.decode("{not json".toByteArray())

        assertEquals(
            ConfigBackupDecodeResult.Rejected(ConfigBackupRejection.MALFORMED),
            result
        )
    }

    @Test
    fun oversizePayloadIsRejectedBeforeParsing() {
        val oversize = ByteArray(ConfigBackupCodec.MAX_BYTES + 1) { 'z'.code.toByte() }

        val result = ConfigBackupCodec.decode(oversize)

        assertEquals(
            ConfigBackupDecodeResult.Rejected(ConfigBackupRejection.OVERSIZE),
            result
        )
    }

    @Test
    fun payloadAtExactlyTheLimitReachesParsing() {
        // The boundary itself is not refused for size: garbage at exactly MAX_BYTES fails as
        // malformed, not oversized, so the cap rejects strictly larger inputs.
        val atLimit = ByteArray(ConfigBackupCodec.MAX_BYTES) { 'z'.code.toByte() }

        val result = ConfigBackupCodec.decode(atLimit)

        assertEquals(
            ConfigBackupDecodeResult.Rejected(ConfigBackupRejection.MALFORMED),
            result
        )
    }
}
