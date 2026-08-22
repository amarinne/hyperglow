package com.eza.hyperglow.ui

import androidx.compose.ui.graphics.vector.ImageVector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LucideIconsRegistryTest {
    @Test
    fun everyRegistryEntryMaterializesWithSourceGeometryAndName() {
        val entries: Map<String, ImageVector> = mapOf(
            "House" to LucideIcons.House,
            "Settings" to LucideIcons.Settings,
            "Download" to LucideIcons.Download,
            "BadgeCheck" to LucideIcons.BadgeCheck,
            "Info" to LucideIcons.Info,
            "MoonStar" to LucideIcons.MoonStar,
            "Lock" to LucideIcons.Lock,
            "Activity" to LucideIcons.Activity,
            "Bug" to LucideIcons.Bug,
            "RefreshCw" to LucideIcons.RefreshCw,
            "EyeOff" to LucideIcons.EyeOff,
            "SquareStack" to LucideIcons.SquareStack,
            "FileDown" to LucideIcons.FileDown,
            "FileUp" to LucideIcons.FileUp,
            "Music" to LucideIcons.Music,
            "ExternalLink" to LucideIcons.ExternalLink,
            "Globe" to LucideIcons.Globe,
            "Palette" to LucideIcons.Palette,
            "Pause" to LucideIcons.Pause,
            "Power" to LucideIcons.Power,
            "Clock" to LucideIcons.Clock,
            "TimerReset" to LucideIcons.TimerReset,
            "Lightbulb" to LucideIcons.Lightbulb,
            "Move" to LucideIcons.Move,
            "UnfoldHorizontal" to LucideIcons.UnfoldHorizontal,
            "UnfoldVertical" to LucideIcons.UnfoldVertical,
            "Layers" to LucideIcons.Layers,
            "TextAlignCenter" to LucideIcons.TextAlignCenter,
            "WholeWord" to LucideIcons.WholeWord,
            "SunMedium" to LucideIcons.SunMedium,
            "Rows3" to LucideIcons.Rows3,
            "Disc3" to LucideIcons.Disc3,
            "ALargeSmall" to LucideIcons.ALargeSmall,
            "Baseline" to LucideIcons.Baseline,
            "Type" to LucideIcons.Type,
            "WandSparkles" to LucideIcons.WandSparkles,
            "Sparkle" to LucideIcons.Sparkle,
            "RectangleHorizontal" to LucideIcons.RectangleHorizontal,
            "RotateCcw" to LucideIcons.RotateCcw,
            "ChevronLeft" to LucideIcons.ChevronLeft,
            "Minus" to LucideIcons.Minus,
            "Plus" to LucideIcons.Plus
        )
        assertEquals(42, entries.size)
        for ((propertyName, vector) in entries) {
            // Reading any property forces lazy materialization, so a malformed
            // path string surfaces here as a parse failure rather than later.
            assertEquals(24f, vector.viewportWidth, 0f)
            assertEquals(24f, vector.viewportHeight, 0f)
            assertTrue(vector.name.isNotBlank())
            assertEquals(
                "property $propertyName must be the PascalCase form of ${vector.name}",
                "Lucide.$propertyName",
                vector.name
            )
        }
    }

    @Test
    fun glowRowUsesSingularSparkleReservedPluralStaysOut() {
        // Principle 4 of B3: plural "sparkles" is reserved as the Spicy EX AI identity,
        // so the glow row materializes the singular sparkle file.
        assertEquals("Lucide.Sparkle", LucideIcons.Sparkle.name)
        assertEquals("Lucide.WandSparkles", LucideIcons.WandSparkles.name)
    }
}
