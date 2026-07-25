package com.eza.hyperglow.root.lockscreen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockscreenEditorGestureHookTest {
    @Test
    fun suppressionRequiresSettingAndVerifiedCapability() {
        assertTrue(shouldSuppressLockscreenEditorGesture(enabled = true, supported = true))
        assertFalse(shouldSuppressLockscreenEditorGesture(enabled = false, supported = true))
        assertFalse(shouldSuppressLockscreenEditorGesture(enabled = true, supported = false))
    }
}
