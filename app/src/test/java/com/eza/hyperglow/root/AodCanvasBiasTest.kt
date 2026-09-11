package com.eza.hyperglow.root.aod

import org.junit.Assert.assertEquals
import org.junit.Test

class AodCanvasBiasTest {

    @Test
    fun nullBiasPreservesLegacyPlacement() {
        assertEquals(0f, resolveVerticalBiasShift(400f, 600f, 0f, 1000f, null), 0f)
    }

    @Test
    fun centerBiasHoldsCenteredSpan() {
        assertEquals(0f, resolveVerticalBiasShift(400f, 600f, 0f, 1000f, 0.5f), 0f)
    }

    @Test
    fun topBiasMovesSpanUpByHalfFreeSpace() {
        assertEquals(-400f, resolveVerticalBiasShift(400f, 600f, 0f, 1000f, 0f), 0f)
    }

    @Test
    fun bottomBiasMovesSpanDownByHalfFreeSpace() {
        assertEquals(400f, resolveVerticalBiasShift(400f, 600f, 0f, 1000f, 1f), 0f)
    }

    @Test
    fun overfullSpanStaysPut() {
        assertEquals(0f, resolveVerticalBiasShift(-100f, 1100f, 0f, 1000f, 0f), 0f)
    }

    @Test
    fun shiftClampsInsidePaddedBounds() {
        assertEquals(0f, resolveVerticalBiasShift(0f, 100f, 0f, 1000f, 0f), 0f)
        assertEquals(0f, resolveVerticalBiasShift(900f, 1000f, 0f, 1000f, 1f), 0f)
    }
}
