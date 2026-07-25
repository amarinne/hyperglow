package com.eza.hyperglow

import com.eza.hyperglow.customization.SceneCompiler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticLoggingTest {
    @Test
    fun runtimeRequestCannotBypassBuildCeiling() {
        assertFalse(diagnosticLoggingEnabled(available = false, requested = false))
        assertFalse(diagnosticLoggingEnabled(available = false, requested = true))
        assertFalse(diagnosticLoggingEnabled(available = true, requested = false))
        assertTrue(diagnosticLoggingEnabled(available = true, requested = true))
    }

    @Test
    fun runtimeFlagChangesCompiledIdentityWithoutChangingProfiles() {
        val base = SceneCompiler.compile(SceneCompiler.safeDefaultDocument())
        val disabled = RuntimeCustomization.withDiagnosticLogging(
            base,
            diagnosticLogging = false,
            available = true
        )
        val enabled = RuntimeCustomization.withDiagnosticLogging(
            base,
            diagnosticLogging = true,
            available = true
        )

        assertFalse(disabled.diagnosticLogging)
        assertTrue(enabled.diagnosticLogging)
        assertNotEquals(disabled.hash, enabled.hash)
        assertNotEquals(disabled.revision, enabled.revision)
        assertEquals(base.profiles, disabled.profiles)
        assertEquals(base.profiles, enabled.profiles)
    }

    @Test
    fun unavailableBuildCompilesRequestedLoggingAsDisabled() {
        val base = SceneCompiler.compile(SceneCompiler.safeDefaultDocument())

        val compiled = RuntimeCustomization.withDiagnosticLogging(
            base,
            diagnosticLogging = true,
            available = false
        )

        assertFalse(compiled.diagnosticLogging)
    }

    @Test
    fun lockscreenKeepAwakeChangesRuntimeIdentityWithoutChangingProfiles() {
        val base = SceneCompiler.compile(SceneCompiler.safeDefaultDocument())
        val disabled = RuntimeCustomization.withDiagnosticLogging(
            base,
            diagnosticLogging = false,
            available = true,
            lockscreenKeepAwake = false
        )
        val enabled = RuntimeCustomization.withDiagnosticLogging(
            base,
            diagnosticLogging = false,
            available = true,
            lockscreenKeepAwake = true
        )

        assertFalse(disabled.lockscreenKeepAwake)
        assertTrue(enabled.lockscreenKeepAwake)
        assertNotEquals(disabled.hash, enabled.hash)
        assertEquals(base.profiles, enabled.profiles)
    }

    @Test
    fun raiseToAodChangesRuntimeIdentityIndependentlyOfLyrics() {
        val base = SceneCompiler.compile(SceneCompiler.safeDefaultDocument())
        val enabled = RuntimeCustomization.withDiagnosticLogging(
            base,
            diagnosticLogging = false,
            available = true,
            raiseToAod = true
        )

        assertTrue(enabled.raiseToAod)
        assertFalse(enabled.lockscreenKeepAwake)
        assertNotEquals(base.hash, enabled.hash)
        assertEquals(base.profiles, enabled.profiles)
    }

    @Test
    fun editorGestureSuppressionChangesRuntimeIdentityIndependentlyOfLyrics() {
        val base = SceneCompiler.compile(SceneCompiler.safeDefaultDocument())
        val enabled = RuntimeCustomization.withDiagnosticLogging(
            base,
            diagnosticLogging = false,
            available = true,
            suppressLockscreenEditorLongPress = true
        )

        assertTrue(enabled.suppressLockscreenEditorLongPress)
        assertNotEquals(base.hash, enabled.hash)
        assertEquals(base.profiles, enabled.profiles)
    }
}
