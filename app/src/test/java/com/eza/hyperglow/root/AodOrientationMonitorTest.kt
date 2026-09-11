package com.eza.hyperglow.root.aod

import org.junit.Assert.assertEquals
import org.junit.Test

class AodOrientationMonitorTest {

    @Test
    fun uprightPortraitResolvesZero() {
        assertEquals(0, resolveAodRotationStep(0f, 9.8f, 0))
    }

    @Test
    fun upsideDownResolves180() {
        assertEquals(180, resolveAodRotationStep(0f, -9.8f, 0))
    }

    @Test
    fun landscapeRightResolves90() {
        assertEquals(90, resolveAodRotationStep(9.8f, 0f, 0))
    }

    @Test
    fun landscapeLeftResolves270() {
        assertEquals(270, resolveAodRotationStep(-9.8f, 0f, 0))
    }

    @Test
    fun flatDeviceHoldsCurrentStep() {
        assertEquals(90, resolveAodRotationStep(0.1f, 0.2f, 90))
    }

    @Test
    fun diagonalTiltHoldsCurrentStep() {
        assertEquals(0, resolveAodRotationStep(6.9f, 6.9f, 0))
    }

    @Test
    fun tiltedPortraitKeepsZero() {
        assertEquals(0, resolveAodRotationStep(1.5f, 9.0f, 0))
    }

    @Test
    fun nonFiniteSampleHoldsCurrentStep() {
        assertEquals(270, resolveAodRotationStep(Float.NaN, 9.8f, 270))
    }

    @Test
    fun frameworkStepAppliesWithoutSensorHold() {
        assertEquals(true, frameworkRotationStepApplies(0, null))
        assertEquals(true, frameworkRotationStepApplies(0, 0))
        assertEquals(true, frameworkRotationStepApplies(0, 180))
        assertEquals(true, frameworkRotationStepApplies(90, 0))
    }

    @Test
    fun frameworkPortraitDoesNotClobberSensorHeldSideStep() {
        assertEquals(false, frameworkRotationStepApplies(0, 90))
        assertEquals(false, frameworkRotationStepApplies(0, 270))
    }

    @Test
    fun frameworkSideStepsStillApplyOverSensorHold() {
        assertEquals(true, frameworkRotationStepApplies(90, 90))
        assertEquals(true, frameworkRotationStepApplies(270, 90))
        assertEquals(true, frameworkRotationStepApplies(180, 270))
    }
}
