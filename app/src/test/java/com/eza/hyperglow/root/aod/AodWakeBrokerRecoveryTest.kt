package com.eza.hyperglow.root.aod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regressions for report `R1-5X8J5PQVR8TRWBN0MS06X59FDW` (vC185, POCO `peridot`, HyperOS 3 CN).
 * The wake broker warned once and then never captured a host, so keepalive could not re-light AOD
 * while the wake-broker capability still resolved. Two faults produced that identical silence: a
 * plugin instance that pre-dated the constructor hook, and an install that bailed without saying so.
 */
class AodWakeBrokerRecoveryTest {
    @Test
    fun aLiveHostAdoptedFromAnExistingHookSatisfiesEveryReference() {
        // The constructor seam is the only capture path that existed before. Adoption is what makes
        // a plugin instance created before the hook usable.
        assertEquals(
            AodWakeAvailability.READY,
            resolveAodWakeAvailability(
                hostCaptured = true,
                methodResolved = true,
                powerManagerResolved = true,
                interactive = false
            )
        )
    }

    @Test
    fun everyMissingReferenceNamesItself() {
        // A single "unavailable" string is what made this cost a round trip: the log could not say
        // which reference was absent, so the two faults were indistinguishable in the field.
        assertEquals(
            AodWakeAvailability.NO_HOST,
            resolveAodWakeAvailability(
                hostCaptured = false,
                methodResolved = true,
                powerManagerResolved = true,
                interactive = false
            )
        )
        assertEquals(
            AodWakeAvailability.NO_METHOD,
            resolveAodWakeAvailability(
                hostCaptured = true,
                methodResolved = false,
                powerManagerResolved = true,
                interactive = false
            )
        )
        assertEquals(
            AodWakeAvailability.NO_POWER_MANAGER,
            resolveAodWakeAvailability(
                hostCaptured = true,
                methodResolved = true,
                powerManagerResolved = false,
                interactive = false
            )
        )
    }

    @Test
    fun aMissingReferenceOutranksAnInteractiveScreenSoTheFaultIsNotHidden() {
        // Interactive is the ordinary suppressed case. Reporting it instead of a dead reference
        // would describe a healthy suppression as a defect.
        assertEquals(
            AodWakeAvailability.NO_HOST,
            resolveAodWakeAvailability(
                hostCaptured = false,
                methodResolved = false,
                powerManagerResolved = false,
                interactive = true
            )
        )
        assertEquals(
            AodWakeAvailability.INTERACTIVE,
            resolveAodWakeAvailability(
                hostCaptured = true,
                methodResolved = true,
                powerManagerResolved = true,
                interactive = true
            )
        )
        assertTrue(AodWakeAvailability.INTERACTIVE.isFault.not())
        assertTrue(AodWakeAvailability.READY.isFault.not())
    }

    @Test
    fun adoptionReplacesOnlyWithADifferentLiveInstance() {
        val host = Any()
        val recreated = Any()
        assertTrue(shouldAdoptAodWakeReference(current = null, candidate = host))
        assertTrue(shouldAdoptAodWakeReference(current = Any(), candidate = host))
        // Xiaomi tearing its plugin down and building another host is the recovery this seam exists
        // for, so a different live instance replaces the old reference.
        assertTrue(shouldAdoptAodWakeReference(current = host, candidate = recreated))
        // A repeat of the same instance must not re-log, and a null must never clear a reference
        // Xiaomi is still using.
        assertFalse(shouldAdoptAodWakeReference(current = host, candidate = host))
        assertFalse(shouldAdoptAodWakeReference(current = host, candidate = null))
    }

    @Test
    fun aDistinctLaterFaultIsStillReported() {
        // The single global latch this replaces reported the first fault only, so a process that
        // lost the host and then the power manager read the same as one that never had a host.
        assertTrue(
            shouldReportAodWakeUnavailable(
                AodWakeAvailability.NO_HOST,
                lastReported = null
            )
        )
        assertFalse(
            shouldReportAodWakeUnavailable(
                AodWakeAvailability.NO_HOST,
                lastReported = AodWakeAvailability.NO_HOST
            )
        )
        assertTrue(
            shouldReportAodWakeUnavailable(
                AodWakeAvailability.NO_POWER_MANAGER,
                lastReported = AodWakeAvailability.NO_HOST
            )
        )
    }

    @Test
    fun aSuppressedInteractiveRequestIsNeverLoggedAsAFault() {
        assertFalse(
            shouldReportAodWakeUnavailable(
                AodWakeAvailability.INTERACTIVE,
                lastReported = null
            )
        )
        assertFalse(
            shouldReportAodWakeUnavailable(AodWakeAvailability.READY, lastReported = null)
        )
    }

    @Test
    fun aHostRefusalCarriesTheRecordedInstallReasons() {
        // "no host" alone could not say whether the host was never handed over or the installer
        // never bound, and that is the whole question a field report has to answer.
        assertTrue(aodWakeUnavailableDetail(AodWakeAvailability.NO_HOST, installSkips = "triggers_class")
            .contains("install=triggers_class"))
        assertTrue(
            aodWakeUnavailableDetail(AodWakeAvailability.NO_HOST, installSkips = "")
                .contains("install=none")
        )
        // A fault that is not a missing host has no install story to tell.
        assertEquals(
            "",
            aodWakeUnavailableDetail(AodWakeAvailability.NO_METHOD, installSkips = "triggers_class")
        )
    }
}
