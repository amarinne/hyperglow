package com.eza.hyperglow.aod

import org.junit.Assert.assertEquals
import org.junit.Test

class AodLyricBridgeNotificationTest {
    /**
     * The app never holds POST_NOTIFICATIONS, so the steady state is SUPPRESSED; POSTED remains
     * representable because the log line must stay truthful if the platform ever reports an
     * enabled notification channel anyway.
     */
    @Test
    fun withoutPostingPermissionTheDisclosureIsSuppressed() {
        assertEquals(
            BridgeNotificationPresentation.SUPPRESSED,
            resolveBridgeNotificationPresentation(notificationsEnabled = false)
        )
    }

    @Test
    fun withPostingPermissionTheDisclosureWouldPost() {
        assertEquals(
            BridgeNotificationPresentation.POSTED,
            resolveBridgeNotificationPresentation(notificationsEnabled = true)
        )
    }
}
