package com.eza.hyperglow.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateAvailabilityTest {
    @Test
    fun olderInstalledVersionIsOutdated() {
        val latest = LatestVersion(versionCode = 79, versionName = "0.3.67")

        assertEquals(
            UpdateAvailability.UpdateAvailable(latest),
            resolveUpdateAvailability(installedVersionCode = 62, latest = latest)
        )
    }

    @Test
    fun matchingOrNewerInstalledVersionIsCurrent() {
        val latest = LatestVersion(versionCode = 79, versionName = "0.3.67")

        assertEquals(
            UpdateAvailability.UpToDate,
            resolveUpdateAvailability(installedVersionCode = 79, latest = latest)
        )
        // A local build ahead of the published release must not be told to downgrade.
        assertEquals(
            UpdateAvailability.UpToDate,
            resolveUpdateAvailability(installedVersionCode = 80, latest = latest)
        )
    }

    @Test
    fun noAnswerIsUnknownRatherThanCurrent() {
        assertEquals(
            UpdateAvailability.Unknown,
            resolveUpdateAvailability(installedVersionCode = 62, latest = null)
        )
    }

    @Test
    fun wellFormedResponseParses() {
        val parsed = parseLatestVersion(
            """{"currentVersionCode":79,"currentVersionName":"0.3.67"}"""
        )

        assertEquals(LatestVersion(79, "0.3.67"), parsed)
    }

    /** A field added server-side must not brick the check on every installed build. */
    @Test
    fun unknownFieldsAreTolerated() {
        val parsed = parseLatestVersion(
            """{"currentVersionCode":79,"currentVersionName":"0.3.67","notes":"anything"}"""
        )

        assertEquals(LatestVersion(79, "0.3.67"), parsed)
    }

    @Test
    fun malformedResponsesAreRejected() {
        assertNull(parseLatestVersion(""))
        assertNull(parseLatestVersion("not json"))
        assertNull(parseLatestVersion("""{"currentVersionName":"0.3.67"}"""))
        assertNull(parseLatestVersion("""{"currentVersionCode":79}"""))
        assertNull(parseLatestVersion("""{"currentVersionCode":0,"currentVersionName":"0.3.67"}"""))
        assertNull(
            parseLatestVersion("""{"currentVersionCode":-1,"currentVersionName":"0.3.67"}""")
        )
        assertNull(
            parseLatestVersion(
                """{"currentVersionCode":999999999,"currentVersionName":"0.3.67"}"""
            )
        )
    }

    @Test
    fun displayedVersionNameCharsetIsRestricted() {
        assertNull(
            parseLatestVersion("""{"currentVersionCode":79,"currentVersionName":"<b>0.3.67</b>"}""")
        )
        assertNull(parseLatestVersion("""{"currentVersionCode":79,"currentVersionName":""}"""))
        assertNull(
            parseLatestVersion(
                """{"currentVersionCode":79,"currentVersionName":"${"x".repeat(65)}"}"""
            )
        )
    }

    @Test
    fun versionEndpointIsASiblingOfTheReportEndpoint() {
        assertEquals(
            "https://reports.eza.dpdns.org/v1/version",
            deriveVersionEndpoint("https://reports.eza.dpdns.org/v1/reports")
        )
    }

    @Test
    fun versionEndpointRejectsAnythingButAPlainHttpsReportUrl() {
        assertNull(deriveVersionEndpoint("http://reports.eza.dpdns.org/v1/reports"))
        assertNull(deriveVersionEndpoint("https://reports.eza.dpdns.org/v2/reports"))
        assertNull(deriveVersionEndpoint("https://user@reports.eza.dpdns.org/v1/reports"))
        assertNull(deriveVersionEndpoint("https://reports.eza.dpdns.org/v1/reports?x=1"))
        assertNull(deriveVersionEndpoint(""))
    }

    @Test
    fun checkIsDueOnFirstRunAndAfterTheInterval() {
        val day = 24L * 60L * 60L * 1000L

        assertTrue(isUpdateCheckDue(lastCheckedAtMillis = 0L, nowMillis = 1_000L, intervalMillis = day))
        assertFalse(isUpdateCheckDue(lastCheckedAtMillis = 1_000L, nowMillis = 2_000L, intervalMillis = day))
        assertTrue(
            isUpdateCheckDue(lastCheckedAtMillis = 1_000L, nowMillis = 1_000L + day, intervalMillis = day)
        )
    }

    /** A clock moved backwards must not park the next check a day in the future. */
    @Test
    fun aTimestampFromTheFutureIsDue() {
        val day = 24L * 60L * 60L * 1000L

        assertTrue(
            isUpdateCheckDue(lastCheckedAtMillis = 9_000L, nowMillis = 1_000L, intervalMillis = day)
        )
    }
}
