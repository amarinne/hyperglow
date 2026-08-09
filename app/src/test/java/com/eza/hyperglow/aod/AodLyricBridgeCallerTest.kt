package com.eza.hyperglow.aod

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AodLyricBridgeCallerTest {
    @Test
    fun systemUiOnTheSharedSystemUidIsAccepted() {
        assertTrue(isSystemUiBridgeCaller(callingUid = 1000, systemUiUid = 1000))
    }

    /** The Xiaomi 17 / HyperOS 3 case: SystemUI runs under an ordinary app uid. */
    @Test
    fun systemUiOnAnAppUidIsAccepted() {
        assertTrue(isSystemUiBridgeCaller(callingUid = 10277, systemUiUid = 10277))
    }

    @Test
    fun otherCallersAreRejected() {
        assertFalse(isSystemUiBridgeCaller(callingUid = 10399, systemUiUid = 10277))
        assertFalse(isSystemUiBridgeCaller(callingUid = 1000, systemUiUid = 10277))
        assertFalse(isSystemUiBridgeCaller(callingUid = 10277, systemUiUid = 1000))
    }

    /** An unresolvable SystemUI package must not turn the gate into an allow-all. */
    @Test
    fun unresolvedSystemUiUidRejectsEveryCaller() {
        assertFalse(
            isSystemUiBridgeCaller(
                callingUid = UNRESOLVED_SYSTEM_UI_UID,
                systemUiUid = UNRESOLVED_SYSTEM_UI_UID
            )
        )
        assertFalse(isSystemUiBridgeCaller(callingUid = 1000, systemUiUid = UNRESOLVED_SYSTEM_UI_UID))
    }
}
