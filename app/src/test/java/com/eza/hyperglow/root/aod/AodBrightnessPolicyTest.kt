package com.eza.hyperglow.root.aod

import org.junit.Assert.assertEquals
import org.junit.Test

class AodBrightnessPolicyTest {
    @Test
    fun activeLyricGuardClampsOnlyLowNonzeroBrightnessInExactAodState() {
        assertEquals(
            255,
            resolveAodBrightnessRequest(
                requestedBrightness = 1,
                readableBrightness = 255,
                lyricGuardActive = true,
                dozeStateName = "DOZE_AOD"
            )
        )
        assertEquals(
            255,
            resolveAodBrightnessRequest(
                requestedBrightness = 128,
                readableBrightness = 255,
                lyricGuardActive = true,
                dozeStateName = "DOZE_AOD"
            )
        )
    }

    @Test
    fun inactiveGuardAndNonAodStatesKeepXiaomiBrightnessAuthority() {
        val states = listOf(
            null,
            "DOZE",
            "DOZE_AOD_PAUSING",
            "DOZE_AOD_PAUSED",
            "DOZE_REQUEST_PULSE",
            "FINISH"
        )

        assertEquals(1, resolveAodBrightnessRequest(1, 255, false, "DOZE_AOD"))
        states.forEach { state ->
            assertEquals(1, resolveAodBrightnessRequest(1, 255, true, state))
        }
    }

    @Test
    fun offInvalidAndAlreadyReadableRequestsPassThrough() {
        listOf(-1, 0, 255, 300).forEach { requested ->
            assertEquals(
                requested,
                resolveAodBrightnessRequest(requested, 255, true, "DOZE_AOD")
            )
        }
        assertEquals(1, resolveAodBrightnessRequest(1, 0, true, "DOZE_AOD"))
        assertEquals(1, resolveAodBrightnessRequest(1, -1, true, "DOZE_AOD"))
    }

    @Test
    fun stateGateRequiresExactAodStateBeforeClamping() {
        assertEquals(1, resolveAodBrightnessRequest(1, 255, true, null))
        assertEquals(255, resolveAodBrightnessRequest(1, 255, true, "DOZE_AOD"))
    }
}
