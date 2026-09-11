package com.eza.hyperglow.root.lockscreen

import com.eza.hyperglow.customization.DEFAULT_CARD_ALPHA
import com.eza.hyperglow.customization.DEFAULT_CARD_COLOR
import org.junit.Assert.assertEquals
import org.junit.Test

class LockscreenCardAppearanceTest {
    @Test
    fun presetsAndCustomCardColorsKeepAlphaIndependent() {
        assertEquals(
            0xD91A1A1A.toInt(),
            lockscreenCardPaintColor(DEFAULT_CARD_COLOR, DEFAULT_CARD_ALPHA)
        )
        assertEquals(0xD91A1A1A.toInt(), lockscreenCardPaintColor("charcoal", DEFAULT_CARD_ALPHA))
        assertEquals(0xCC151519.toInt(), lockscreenCardPaintColor("#151519", 0.8f))
    }

    @Test
    fun invalidCardAlphaAndColorFailClosed() {
        assertEquals(0x001A1A1A, lockscreenCardPaintColor("unknown", -1f))
        assertEquals(0xFF1A1A1A.toInt(), lockscreenCardPaintColor("unknown", 2f))
        assertEquals(0xD91A1A1A.toInt(), lockscreenCardPaintColor("#12345", Float.NaN))
    }
}
