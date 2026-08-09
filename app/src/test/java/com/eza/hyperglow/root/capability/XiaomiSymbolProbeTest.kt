package com.eza.hyperglow.root.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private open class ProbeBase {
    @JvmField
    var hoistedField: CharSequence = ""

    fun hoistedMethod(): Boolean = true
}

private class ProbeSubject : ProbeBase() {
    @JvmField
    var ownField: CharSequence = ""

    fun ownMethod(value: Int): Boolean = value > 0
}

class XiaomiSymbolProbeTest {
    private val loader: ClassLoader = XiaomiSymbolProbeTest::class.java.classLoader!!
    private val subject = ProbeSubject::class.java.name

    @Test
    fun fieldProbeFindsAFieldDeclaredOnTheClass() {
        assertTrue(XiaomiCapabilityResolver.hasField(loader, subject, "ownField"))
    }

    /** The regression this walk exists for: a ROM refactor hoisting a field into a base class. */
    @Test
    fun fieldProbeFindsAnInheritedField() {
        assertTrue(XiaomiCapabilityResolver.hasField(loader, subject, "hoistedField"))
    }

    @Test
    fun fieldProbeStillRejectsAnAbsentFieldAndAWrongType() {
        assertFalse(XiaomiCapabilityResolver.hasField(loader, subject, "noSuchField"))
        assertFalse(
            XiaomiCapabilityResolver.hasField(
                loader,
                subject,
                "hoistedField",
                "java.lang.Runnable"
            )
        )
        assertTrue(
            XiaomiCapabilityResolver.hasField(
                loader,
                subject,
                "hoistedField",
                "java.lang.CharSequence"
            )
        )
    }

    @Test
    fun fieldProbeRejectsAnUnloadableClassOrExpectedType() {
        assertFalse(XiaomiCapabilityResolver.hasField(loader, "no.such.Class", "ownField"))
        assertFalse(
            XiaomiCapabilityResolver.hasField(loader, subject, "ownField", "no.such.Type")
        )
    }

    @Test
    fun methodProbeFindsAMethodDeclaredOnTheClass() {
        assertTrue(XiaomiCapabilityResolver.hasMethod(loader, subject, "ownMethod", "int"))
    }

    /**
     * Deliberately asymmetric with the field probe. These gate hook installation, and a hook binds
     * to the exact declaring method — resolving an inherited one would either fail at the hook site
     * or bind a base-class method far more widely than intended.
     */
    @Test
    fun methodProbeDoesNotResolveAnInheritedMethod() {
        assertFalse(XiaomiCapabilityResolver.hasMethod(loader, subject, "hoistedMethod"))
        assertTrue(
            XiaomiCapabilityResolver.hasMethod(loader, ProbeBase::class.java.name, "hoistedMethod")
        )
    }

    @Test
    fun methodProbeRejectsAWrongSignature() {
        assertFalse(XiaomiCapabilityResolver.hasMethod(loader, subject, "ownMethod"))
        assertFalse(XiaomiCapabilityResolver.hasMethod(loader, subject, "noSuchMethod"))
    }

    @Test
    fun missingProbesAreNamedNotJustCounted() {
        val probes = XiaomiSymbolSnapshot(aodSurface = true, aodHostContainer = true).rawProbes()

        val missing = missingProbeNames(probes)

        assertFalse(missing.contains(XiaomiSymbolProbe.AOD_SURFACE_LIFECYCLE.name))
        assertTrue(missing.contains(XiaomiSymbolProbe.AOD_WAKE_SEAM.name))
        assertEquals(14, missing.split(",").size)
    }

    @Test
    fun aFullyResolvedBuildReportsNoneRatherThanAnEmptyString() {
        val probes = XiaomiSymbolProbe.entries.associateWith { true }

        assertEquals("none", missingProbeNames(probes))
    }
}
