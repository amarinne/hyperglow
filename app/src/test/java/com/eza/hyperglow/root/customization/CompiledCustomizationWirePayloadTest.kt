package com.eza.hyperglow.root.customization

import com.eza.hyperglow.RuntimeCustomization
import com.eza.hyperglow.customization.SceneCompiler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompiledCustomizationWirePayloadTest {
    @Test
    fun validPayloadRoundTripsWithoutAndroidBundle() {
        val configuration = RuntimeCustomization.withDiagnosticLogging(
            SceneCompiler.compile(SceneCompiler.safeDefaultDocument()),
            diagnosticLogging = true,
            available = true,
            raiseToAod = true
        )
        val payload = CompiledCustomizationBundleCodec.toWirePayload(configuration, userId = 10)

        assertTrue(CompiledCustomizationBundleCodec.isValidWirePayload(payload))
        assertEquals(
            configuration,
            CompiledCustomizationBundleCodec.fromWirePayload(payload, expectedUserId = 10)
        )
        assertTrue(
            CompiledCustomizationBundleCodec.fromWirePayload(
                payload,
                expectedUserId = 10
            )?.diagnosticLogging == true
        )
        assertTrue(
            CompiledCustomizationBundleCodec.fromWirePayload(
                payload,
                expectedUserId = 10
            )?.raiseToAod == true
        )
        assertNull(
            CompiledCustomizationBundleCodec.fromWirePayload(payload, expectedUserId = 0)
        )
    }

    @Test
    fun invalidEnvelopeFieldsFailClosedBeforeJsonDecode() {
        val configuration = SceneCompiler.compile(SceneCompiler.safeDefaultDocument())
        val payload = CompiledCustomizationBundleCodec.toWirePayload(configuration)

        assertFalse(
            CompiledCustomizationBundleCodec.isValidWirePayload(payload.copy(protocol = 99))
        )
        assertFalse(
            CompiledCustomizationBundleCodec.isValidWirePayload(payload.copy(userId = -1))
        )
        assertFalse(
            CompiledCustomizationBundleCodec.isValidWirePayload(payload.copy(revision = -1L))
        )
        assertFalse(
            CompiledCustomizationBundleCodec.isValidWirePayload(payload.copy(hash = "not-sha256"))
        )
        assertNull(
            CompiledCustomizationBundleCodec.fromWirePayload(
                payload.copy(revision = payload.revision + 1L)
            )
        )
    }

    @Test
    fun oversizedAsciiAndUtf8PayloadsFailClosed() {
        val configuration = SceneCompiler.compile(SceneCompiler.safeDefaultDocument())
        val payload = CompiledCustomizationBundleCodec.toWirePayload(configuration)

        assertFalse(
            CompiledCustomizationBundleCodec.isValidWirePayload(
                payload.copy(json = "x".repeat(SceneCompiler.MAX_CONFIG_BYTES + 1))
            )
        )
        assertFalse(
            CompiledCustomizationBundleCodec.isValidWirePayload(
                payload.copy(json = "é".repeat(SceneCompiler.MAX_CONFIG_BYTES / 2 + 1))
            )
        )
    }
}
