package com.eza.hyperglow.diagnostics

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpicySetupEvidenceTest {
    @Test
    fun probeOutputRetainsOnlyKnownStatusesForTheRequestedUser() {
        val output = "ignored private exception\n" +
            "spicy_setup_v1 user=999 module=disabled scope=selected publishing=default_disabled\n"

        assertEquals(
            SpicySetupEvidence("disabled", "selected", "default_disabled"),
            parseSpicySetupEvidence(output, 999)
        )
        assertNull(parseSpicySetupEvidence(output, 0))
    }

    @Test
    fun missingMalformedDuplicateAndOversizedProbeOutputCannotEstablishSetup() {
        val valid = "spicy_setup_v1 user=0 module=enabled scope=selected publishing=enabled"
        for (output in listOf(
            "", "private exception", valid.replace("enabled", "future"),
            "$valid\n$valid", "$valid secret=private", "x".repeat(4_097) + "\n" + valid
        )) assertNull(parseSpicySetupEvidence(output, 0))
    }

    @Test
    fun rootCommandRejectsPathsAndUserIdsOutsideTheInstalledApkBoundary() {
        assertEquals(
            "CLASSPATH='/data/app/~~random/com.eza.hyperglow-random/base.apk' " +
                "/system/bin/app_process /system/bin com.eza.hyperglow.diagnostics.SpicySetupProbe 999 2>/dev/null",
            spicySetupProbeCommand("/data/app/~~random/com.eza.hyperglow-random/base.apk", 999)
        )
        for (path in listOf("/tmp/base.apk", "/data/app/../base.apk", "/data/app/a'$(id)/base.apk")) {
            assertNull(spicySetupProbeCommand(path, 0))
        }
        assertNull(spicySetupProbeCommand("/data/app/a/base.apk", -1))
    }

    @Test
    fun moduleStateKeepsMissingPerUserStateUnknownAndScopeIndependent() {
        assertArrayEquals(arrayOf("enabled", "selected"), SpicySetupProbe.moduleStatuses(1, 1, 1))
        assertArrayEquals(arrayOf("disabled", "not_selected"), SpicySetupProbe.moduleStatuses(1, 0, 0))
        assertArrayEquals(arrayOf("unknown", "selected"), SpicySetupProbe.moduleStatuses(1, null, 1))
        assertArrayEquals(arrayOf("unknown", "selected"), SpicySetupProbe.moduleStatuses(1, 2, 1))
        assertArrayEquals(arrayOf("not_registered", "not_selected"), SpicySetupProbe.moduleStatuses(0, null, 0))
        assertArrayEquals(arrayOf("unknown", "unknown"), SpicySetupProbe.moduleStatuses(2, 1, 1))
    }

    @Test
    fun preferenceProbeDistinguishesExplicitValuesAndMissingKey() {
        assertEquals("enabled", preference("<boolean name=\"lyrics_hyper_aod_lyrics_enabled\" value=\"true\"/>"))
        assertEquals("disabled", preference("<boolean name=\"lyrics_hyper_aod_lyrics_enabled\" value=\"false\"/>"))
        assertEquals("default_disabled", preference("<string name=\"unrelated\">private</string>"))
    }

    @Test
    fun invalidPreferenceAndExternalEntitiesNeverBecomeDisabled() {
        for (xml in listOf(
            "<map>", "<other/>", "<map>invalid root text</map>",
            "<map><string name=\"lyrics_hyper_aod_lyrics_enabled\">true</string></map>",
            "<map><boolean name=\"lyrics_hyper_aod_lyrics_enabled\" value=\"future\"/></map>",
            "<map><boolean name=\"lyrics_hyper_aod_lyrics_enabled\" value=\"true\">invalid</boolean></map>",
            "<map><boolean name=\"lyrics_hyper_aod_lyrics_enabled\" value=\"true\"/>" +
                "<boolean name=\"lyrics_hyper_aod_lyrics_enabled\" value=\"false\"/></map>",
            "<!DOCTYPE map [<!ENTITY secret SYSTEM 'file:///private'>]><map>&secret;</map>"
        )) assertEquals("unknown", parsePreference(xml))
    }

    private fun preference(entries: String) = parsePreference("<map>$entries</map>")

    private fun parsePreference(xml: String) = SpicySetupProbe.parsePublishingPreference(
        ByteArrayInputStream(xml.toByteArray())
    )
}
