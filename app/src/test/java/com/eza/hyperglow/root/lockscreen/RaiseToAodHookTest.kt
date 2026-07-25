package com.eza.hyperglow.root.lockscreen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RaiseToAodHookTest {
    @Test
    fun onlyVerifiedEnabledPickupWakeIsSuppressed() {
        assertTrue(
            shouldSuppressPickupWake(
                enabled = true,
                wakeHookSupported = true,
                details = "com.android.systemui:PICK_UP"
            )
        )
        assertFalse(shouldSuppressPickupWake(false, true, "com.android.systemui:PICK_UP"))
        assertFalse(shouldSuppressPickupWake(true, false, "com.android.systemui:PICK_UP"))
        assertFalse(shouldSuppressPickupWake(true, true, "android.policy:POWER"))
        assertFalse(shouldSuppressPickupWake(true, true, null))
    }
}
