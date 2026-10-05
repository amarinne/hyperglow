package com.eza.hyperglow.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortBackingVocalTest {
    @Test
    fun authoredBackingVocalsBelowOneSecondRemainDuetCompanions() {
        // Timing-only cases from the selected org candidate for Right Now (Na Na Na).
        val timings = listOf(
            listOf(126145L, 128502L, 127531L),
            listOf(128502L, 131950L, 131021L),
            listOf(142410L, 145901L, 144916L),
        )
        for ((start, end, backgroundStart) in timings) {
            val lead = row("LEAD", start, end)
            val backing = row("BACKGROUND", backgroundStart, end)
            val document = document(lead, backing)

            assertEquals(listOf(backing), document.concurrentRowsAt(backgroundStart + 1, lead))
            assertEquals(listOf(lead), document.concurrentRowsAt(backgroundStart + 1, backing))
            assertEquals(listOf(backing), document.concurrentRowsAt(start, lead))
        }
    }

    @Test
    fun shortAuthoredBackingVocalWaitsForSurvivorAfterItsEnd() {
        val lead = row("LEAD", 1000, 5000)
        val backing = row("BACKGROUND", 2000, 2500)
        val document = document(lead, backing)

        assertEquals(listOf(backing), document.concurrentRowsAt(3000, lead))
    }

    @Test
    fun backingVocalWithoutSharedWindowDoesNotJoin() {
        val lead = row("LEAD", 1000, 2000)
        val backing = row("BACKGROUND", 2000, 2500)
        val document = document(lead, backing)

        assertTrue(document.concurrentRowsAt(1500, lead).isEmpty())
    }

    private fun row(role: String, start: Long, end: Long) = SpicyBridgeRow(
        role, start, end, end, false, role, "", "", emptyList(),
    )

    private fun document(vararg rows: SpicyBridgeRow) = SpicyBridgeDocument(
        "producer", 1, "spotify:track:test", "Spicy Lyrics", "en", "Syllable", 241000,
        1, rows.toList(),
    )
}
