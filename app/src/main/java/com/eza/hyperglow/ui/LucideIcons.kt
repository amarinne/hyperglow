package com.eza.hyperglow.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Materialized Lucide icons used by the settings UI, one property per Lucide
 * file name in the Lucide icon source tree (24x24, stroke currentColor,
 * stroke-width 2, round caps and joins). The property name is the PascalCase
 * form of the Lucide file name so HyperGlow and Spicy EX read the same table.
 *
 * Vectors keep the source stroke geometry and render through Miuix Icon,
 * whose default tint is the local content color. Placeholder color black is
 * always replaced by the tint at draw time.
 */
internal object LucideIcons {

    val House: ImageVector by lazy {
        materialize("House") {
            lucideStroked(
                addPathNodes("M15 21v-8a1 1 0 0 0-1-1h-4a1 1 0 0 0-1 1v8")
            )
            lucideStroked(
                addPathNodes("M3 10a2 2 0 0 1 .709-1.528l7-6a2 2 0 0 1 2.582 0l7 6A2 2 0 0 1 21 10v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z")
            )
        }
    }
    val Settings: ImageVector by lazy {
        materialize("Settings") {
            lucideStroked(
                addPathNodes("M9.671 4.136a2.34 2.34 0 0 1 4.659 0 2.34 2.34 0 0 0 3.319 1.915 2.34 2.34 0 0 1 2.33 4.033 2.34 2.34 0 0 0 0 3.831 2.34 2.34 0 0 1-2.33 4.033 2.34 2.34 0 0 0-3.319 1.915 2.34 2.34 0 0 1-4.659 0 2.34 2.34 0 0 0-3.32-1.915 2.34 2.34 0 0 1-2.33-4.033 2.34 2.34 0 0 0 0-3.831A2.34 2.34 0 0 1 6.35 6.051a2.34 2.34 0 0 0 3.319-1.915")
            )
            lucideStroked(
                addPathNodes("M9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0Z")
            )
        }
    }
    val Download: ImageVector by lazy {
        materialize("Download") {
            lucideStroked(
                addPathNodes("M12 15V3")
            )
            lucideStroked(
                addPathNodes("M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4")
            )
            lucideStroked(
                addPathNodes("m7 10 5 5 5-5")
            )
        }
    }
    val BadgeCheck: ImageVector by lazy {
        materialize("BadgeCheck") {
            lucideStroked(
                addPathNodes("M3.85 8.62a4 4 0 0 1 4.78-4.77 4 4 0 0 1 6.74 0 4 4 0 0 1 4.78 4.78 4 4 0 0 1 0 6.74 4 4 0 0 1-4.77 4.78" +
                "4 4 0 0 1-6.75 0 4 4 0 0 1-4.78-4.77 4 4 0 0 1 0-6.76Z")
            )
            lucideStroked(
                addPathNodes("m9 12 2 2 4-4")
            )
        }
    }
    val Info: ImageVector by lazy {
        materialize("Info") {
            lucideStroked(
                addPathNodes("M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0Z")
            )
            lucideStroked(
                addPathNodes("M12 16v-4")
            )
            lucideStroked(
                addPathNodes("M12 8h.01")
            )
        }
    }
    val MoonStar: ImageVector by lazy {
        materialize("MoonStar") {
            lucideStroked(
                addPathNodes("M18 5h4")
            )
            lucideStroked(
                addPathNodes("M20 3v4")
            )
            lucideStroked(
                addPathNodes("M20.985 12.486a9 9 0 1 1-9.473-9.472c.405-.022.617.46.402.803a6 6 0 0 0 8.268 8.268c.344-.215.825-.004.803.401")
            )
        }
    }
    val Lock: ImageVector by lazy {
        materialize("Lock") {
            lucideStroked(
                addPathNodes("M5 11H19A2 2 0 0 1 21 13V20A2 2 0 0 1 19 22H5A2 2 0 0 1 3 20V13A2 2 0 0 1 5 11Z")
            )
            lucideStroked(
                addPathNodes("M7 11V7a5 5 0 0 1 10 0v4")
            )
        }
    }
    val Activity: ImageVector by lazy {
        materialize("Activity") {
            lucideStroked(
                addPathNodes("M22 12h-2.48a2 2 0 0 0-1.93 1.46l-2.35 8.36a.25.25 0 0 1-.48 0L9.24 2.18a.25.25 0 0 0-.48 0l-2.35 8.36A2" +
                "2 0 0 1 4.49 12H2")
            )
        }
    }
    val Bug: ImageVector by lazy {
        materialize("Bug") {
            lucideStroked(addPathNodes("M12 20v-9"))
            lucideStroked(addPathNodes("M14 7a4 4 0 0 1 4 4v3a6 6 0 0 1-12 0v-3a4 4 0 0 1 4-4z"))
            lucideStroked(addPathNodes("M14.12 3.88 16 2"))
            lucideStroked(addPathNodes("M21 21a4 4 0 0 0-3.81-4"))
            lucideStroked(addPathNodes("M21 5a4 4 0 0 1-3.55 3.97"))
            lucideStroked(addPathNodes("M22 13h-4"))
            lucideStroked(addPathNodes("M3 21a4 4 0 0 1 3.81-4"))
            lucideStroked(addPathNodes("M3 5a4 4 0 0 0 3.55 3.97"))
            lucideStroked(addPathNodes("M6 13H2"))
            lucideStroked(addPathNodes("m8 2 1.88 1.88"))
            lucideStroked(addPathNodes("M9 7.13V6a3 3 0 1 1 6 0v1.13"))
        }
    }
    val RefreshCw: ImageVector by lazy {
        materialize("RefreshCw") {
            lucideStroked(
                addPathNodes("M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8")
            )
            lucideStroked(
                addPathNodes("M21 3v5h-5")
            )
            lucideStroked(
                addPathNodes("M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16")
            )
            lucideStroked(
                addPathNodes("M8 16H3v5")
            )
        }
    }
    val EyeOff: ImageVector by lazy {
        materialize("EyeOff") {
            lucideStroked(
                addPathNodes("M10.733 5.076a10.744 10.744 0 0 1 11.205 6.575 1 1 0 0 1 0 .696 10.747 10.747 0 0 1-1.444 2.49")
            )
            lucideStroked(
                addPathNodes("M14.084 14.158a3 3 0 0 1-4.242-4.242")
            )
            lucideStroked(
                addPathNodes("M17.479 17.499a10.75 10.75 0 0 1-15.417-5.151 1 1 0 0 1 0-.696 10.75 10.75 0 0 1 4.446-5.143")
            )
            lucideStroked(
                addPathNodes("m2 2 20 20")
            )
        }
    }
    val SquareStack: ImageVector by lazy {
        materialize("SquareStack") {
            lucideStroked(
                addPathNodes("M4 10c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h4c1.1 0 2 .9 2 2")
            )
            lucideStroked(
                addPathNodes("M10 16c-1.1 0-2-.9-2-2v-4c0-1.1.9-2 2-2h4c1.1 0 2 .9 2 2")
            )
            lucideStroked(
                addPathNodes("M16 14H20A2 2 0 0 1 22 16V20A2 2 0 0 1 20 22H16A2 2 0 0 1 14 20V16A2 2 0 0 1 16 14Z")
            )
        }
    }
    val FileDown: ImageVector by lazy {
        materialize("FileDown") {
            lucideStroked(
                addPathNodes("M6 22a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h8a2.4 2.4 0 0 1 1.704.706l3.588 3.588A2.4 2.4 0 0 1 20 8v12a2 2 0 0" +
                "1-2 2z")
            )
            lucideStroked(
                addPathNodes("M14 2v5a1 1 0 0 0 1 1h5")
            )
            lucideStroked(
                addPathNodes("M12 18v-6")
            )
            lucideStroked(
                addPathNodes("m9 15 3 3 3-3")
            )
        }
    }
    val FileUp: ImageVector by lazy {
        materialize("FileUp") {
            lucideStroked(
                addPathNodes("M6 22a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h8a2.4 2.4 0 0 1 1.704.706l3.588 3.588A2.4 2.4 0 0 1 20 8v12a2 2 0 0" +
                "1-2 2z")
            )
            lucideStroked(
                addPathNodes("M14 2v5a1 1 0 0 0 1 1h5")
            )
            lucideStroked(
                addPathNodes("M12 12v6")
            )
            lucideStroked(
                addPathNodes("m15 15-3-3-3 3")
            )
        }
    }
    val Music: ImageVector by lazy {
        materialize("Music") {
            lucideStroked(
                addPathNodes("M9 18V5l12-2v13")
            )
            lucideStroked(
                addPathNodes("M3 18a3 3 0 1 0 6 0a3 3 0 1 0 -6 0Z")
            )
            lucideStroked(
                addPathNodes("M15 16a3 3 0 1 0 6 0a3 3 0 1 0 -6 0Z")
            )
        }
    }
    val ExternalLink: ImageVector by lazy {
        materialize("ExternalLink") {
            lucideStroked(
                addPathNodes("M15 3h6v6")
            )
            lucideStroked(
                addPathNodes("M10 14 21 3")
            )
            lucideStroked(
                addPathNodes("M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6")
            )
        }
    }
    val Globe: ImageVector by lazy {
        materialize("Globe") {
            lucideStroked(
                addPathNodes("M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0Z")
            )
            lucideStroked(
                addPathNodes("M12 2a14.5 14.5 0 0 0 0 20 14.5 14.5 0 0 0 0-20")
            )
            lucideStroked(
                addPathNodes("M2 12h20")
            )
        }
    }
    val Palette: ImageVector by lazy {
        materialize("Palette") {
            lucideStroked(
                addPathNodes("M12 22a1 1 0 0 1 0-20 10 9 0 0 1 10 9 5 5 0 0 1-5 5h-2.25a1.75 1.75 0 0 0-1.4 2.8l.3.4a1.75 1.75 0 0" +
                "1-1.4 2.8z")
            )
            lucideFilledAndStroked(
                addPathNodes("M13 6.5a0.5 0.5 0 1 0 1 0a0.5 0.5 0 1 0 -1 0Z")
            )
            lucideFilledAndStroked(
                addPathNodes("M17 10.5a0.5 0.5 0 1 0 1 0a0.5 0.5 0 1 0 -1 0Z")
            )
            lucideFilledAndStroked(
                addPathNodes("M6 12.5a0.5 0.5 0 1 0 1 0a0.5 0.5 0 1 0 -1 0Z")
            )
            lucideFilledAndStroked(
                addPathNodes("M8 7.5a0.5 0.5 0 1 0 1 0a0.5 0.5 0 1 0 -1 0Z")
            )
        }
    }
    val Pause: ImageVector by lazy {
        materialize("Pause") {
            lucideStroked(
                addPathNodes("M15 3H18A1 1 0 0 1 19 4V20A1 1 0 0 1 18 21H15A1 1 0 0 1 14 20V4A1 1 0 0 1 15 3Z")
            )
            lucideStroked(
                addPathNodes("M6 3H9A1 1 0 0 1 10 4V20A1 1 0 0 1 9 21H6A1 1 0 0 1 5 20V4A1 1 0 0 1 6 3Z")
            )
        }
    }
    val Power: ImageVector by lazy {
        materialize("Power") {
            lucideStroked(
                addPathNodes("M12 2v10")
            )
            lucideStroked(
                addPathNodes("M18.4 6.6a9 9 0 1 1-12.77.04")
            )
        }
    }
    val Clock: ImageVector by lazy {
        materialize("Clock") {
            lucideStroked(
                addPathNodes("M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0Z")
            )
            lucideStroked(
                addPathNodes("M12 6v6l4 2")
            )
        }
    }
    val TimerReset: ImageVector by lazy {
        materialize("TimerReset") {
            lucideStroked(
                addPathNodes("M10 2h4")
            )
            lucideStroked(
                addPathNodes("M12 14v-4")
            )
            lucideStroked(
                addPathNodes("M4 13a8 8 0 0 1 8-7 8 8 0 1 1-5.3 14L4 17.6")
            )
            lucideStroked(
                addPathNodes("M9 17H4v5")
            )
        }
    }
    val Lightbulb: ImageVector by lazy {
        materialize("Lightbulb") {
            lucideStroked(
                addPathNodes("M15 14c.2-1 .7-1.7 1.5-2.5 1-.9 1.5-2.2 1.5-3.5A6 6 0 0 0 6 8c0 1 .2 2.2 1.5 3.5.7.7 1.3 1.5 1.5 2.5")
            )
            lucideStroked(
                addPathNodes("M9 18h6")
            )
            lucideStroked(
                addPathNodes("M10 22h4")
            )
        }
    }
    val Move: ImageVector by lazy {
        materialize("Move") {
            lucideStroked(
                addPathNodes("M12 2v20")
            )
            lucideStroked(
                addPathNodes("m15 19-3 3-3-3")
            )
            lucideStroked(
                addPathNodes("m19 9 3 3-3 3")
            )
            lucideStroked(
                addPathNodes("M2 12h20")
            )
            lucideStroked(
                addPathNodes("m5 9-3 3 3 3")
            )
            lucideStroked(
                addPathNodes("m9 5 3-3 3 3")
            )
        }
    }
    val UnfoldHorizontal: ImageVector by lazy {
        materialize("UnfoldHorizontal") {
            lucideStroked(
                addPathNodes("M16 12h6")
            )
            lucideStroked(
                addPathNodes("M8 12H2")
            )
            lucideStroked(
                addPathNodes("M12 2v2")
            )
            lucideStroked(
                addPathNodes("M12 8v2")
            )
            lucideStroked(
                addPathNodes("M12 14v2")
            )
            lucideStroked(
                addPathNodes("M12 20v2")
            )
            lucideStroked(
                addPathNodes("m19 15 3-3-3-3")
            )
            lucideStroked(
                addPathNodes("m5 9-3 3 3 3")
            )
        }
    }
    val UnfoldVertical: ImageVector by lazy {
        materialize("UnfoldVertical") {
            lucideStroked(
                addPathNodes("M12 22v-6")
            )
            lucideStroked(
                addPathNodes("M12 8V2")
            )
            lucideStroked(
                addPathNodes("M4 12H2")
            )
            lucideStroked(
                addPathNodes("M10 12H8")
            )
            lucideStroked(
                addPathNodes("M16 12h-2")
            )
            lucideStroked(
                addPathNodes("M22 12h-2")
            )
            lucideStroked(
                addPathNodes("m15 19-3 3-3-3")
            )
            lucideStroked(
                addPathNodes("m15 5-3-3-3 3")
            )
        }
    }
    val Layers: ImageVector by lazy {
        materialize("Layers") {
            lucideStroked(
                addPathNodes("M12.83 2.18a2 2 0 0 0-1.66 0L2.6 6.08a1 1 0 0 0 0 1.83l8.58 3.91a2 2 0 0 0 1.66 0l8.58-3.9a1 1 0 0 0" +
                "0-1.83z")
            )
            lucideStroked(
                addPathNodes("M2 12a1 1 0 0 0 .58.91l8.6 3.91a2 2 0 0 0 1.65 0l8.58-3.9A1 1 0 0 0 22 12")
            )
            lucideStroked(
                addPathNodes("M2 17a1 1 0 0 0 .58.91l8.6 3.91a2 2 0 0 0 1.65 0l8.58-3.9A1 1 0 0 0 22 17")
            )
        }
    }
    val TextAlignCenter: ImageVector by lazy {
        materialize("TextAlignCenter") {
            lucideStroked(
                addPathNodes("M21 5H3")
            )
            lucideStroked(
                addPathNodes("M17 12H7")
            )
            lucideStroked(
                addPathNodes("M19 19H5")
            )
        }
    }
    val WholeWord: ImageVector by lazy {
        materialize("WholeWord") {
            lucideStroked(
                addPathNodes("M4 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0Z")
            )
            lucideStroked(
                addPathNodes("M10 9v6")
            )
            lucideStroked(
                addPathNodes("M14 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0Z")
            )
            lucideStroked(
                addPathNodes("M14 7v8")
            )
            lucideStroked(
                addPathNodes("M22 17v1c0 .5-.5 1-1 1H3c-.5 0-1-.5-1-1v-1")
            )
        }
    }
    val SunMedium: ImageVector by lazy {
        materialize("SunMedium") {
            lucideStroked(
                addPathNodes("M8 12a4 4 0 1 0 8 0a4 4 0 1 0 -8 0Z")
            )
            lucideStroked(
                addPathNodes("M12 3v1")
            )
            lucideStroked(
                addPathNodes("M12 20v1")
            )
            lucideStroked(
                addPathNodes("M3 12h1")
            )
            lucideStroked(
                addPathNodes("M20 12h1")
            )
            lucideStroked(
                addPathNodes("m18.364 5.636-.707.707")
            )
            lucideStroked(
                addPathNodes("m6.343 17.657-.707.707")
            )
            lucideStroked(
                addPathNodes("m5.636 5.636.707.707")
            )
            lucideStroked(
                addPathNodes("m17.657 17.657.707.707")
            )
        }
    }
    val Rows3: ImageVector by lazy {
        materialize("Rows3") {
            lucideStroked(
                addPathNodes("M5 3H19A2 2 0 0 1 21 5V19A2 2 0 0 1 19 21H5A2 2 0 0 1 3 19V5A2 2 0 0 1 5 3Z")
            )
            lucideStroked(
                addPathNodes("M21 9H3")
            )
            lucideStroked(
                addPathNodes("M21 15H3")
            )
        }
    }
    val Disc3: ImageVector by lazy {
        materialize("Disc3") {
            lucideStroked(
                addPathNodes("M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0Z")
            )
            lucideStroked(
                addPathNodes("M6 12c0-1.7.7-3.2 1.8-4.2")
            )
            lucideStroked(
                addPathNodes("M10 12a2 2 0 1 0 4 0a2 2 0 1 0 -4 0Z")
            )
            lucideStroked(
                addPathNodes("M18 12c0 1.7-.7 3.2-1.8 4.2")
            )
        }
    }
    val ALargeSmall: ImageVector by lazy {
        materialize("ALargeSmall") {
            lucideStroked(
                addPathNodes("m15 16 2.536-7.328a1.02 1.02 1 0 1 1.928 0L22 16")
            )
            lucideStroked(
                addPathNodes("M15.697 14h5.606")
            )
            lucideStroked(
                addPathNodes("m2 16 4.039-9.69a.5.5 0 0 1 .923 0L11 16")
            )
            lucideStroked(
                addPathNodes("M3.304 13h6.392")
            )
        }
    }
    val Baseline: ImageVector by lazy {
        materialize("Baseline") {
            lucideStroked(
                addPathNodes("M4 20h16")
            )
            lucideStroked(
                addPathNodes("m6 16 6-12 6 12")
            )
            lucideStroked(
                addPathNodes("M8 12h8")
            )
        }
    }
    val Type: ImageVector by lazy {
        materialize("Type") {
            lucideStroked(
                addPathNodes("M12 4v16")
            )
            lucideStroked(
                addPathNodes("M4 7V5a1 1 0 0 1 1-1h14a1 1 0 0 1 1 1v2")
            )
            lucideStroked(
                addPathNodes("M9 20h6")
            )
        }
    }
    val WandSparkles: ImageVector by lazy {
        materialize("WandSparkles") {
            lucideStroked(
                addPathNodes("m21.64 3.64-1.28-1.28a1.21 1.21 0 0 0-1.72 0L2.36 18.64a1.21 1.21 0 0 0 0 1.72l1.28 1.28a1.2 1.2 0 0 0" +
                "1.72 0L21.64 5.36a1.2 1.2 0 0 0 0-1.72")
            )
            lucideStroked(
                addPathNodes("m14 7 3 3")
            )
            lucideStroked(
                addPathNodes("M5 6v4")
            )
            lucideStroked(
                addPathNodes("M19 14v4")
            )
            lucideStroked(
                addPathNodes("M10 2v2")
            )
            lucideStroked(
                addPathNodes("M7 8H3")
            )
            lucideStroked(
                addPathNodes("M21 16h-4")
            )
            lucideStroked(
                addPathNodes("M11 3H9")
            )
        }
    }
    val Sparkle: ImageVector by lazy {
        materialize("Sparkle") {
            lucideStroked(
                addPathNodes("M11.017 2.814a1 1 0 0 1 1.966 0l1.051 5.558a2 2 0 0 0 1.594 1.594l5.558 1.051a1 1 0 0 1 0 1.966l-5.558" +
                "1.051a2 2 0 0 0-1.594 1.594l-1.051 5.558a1 1 0 0 1-1.966 0l-1.051-5.558a2 2 0 0" +
                "0-1.594-1.594l-5.558-1.051a1 1 0 0 1 0-1.966l5.558-1.051a2 2 0 0 0 1.594-1.594z")
            )
        }
    }
    val RectangleHorizontal: ImageVector by lazy {
        materialize("RectangleHorizontal") {
            lucideStroked(
                addPathNodes("M4 6H20A2 2 0 0 1 22 8V16A2 2 0 0 1 20 18H4A2 2 0 0 1 2 16V8A2 2 0 0 1 4 6Z")
            )
        }
    }
    val RotateCcw: ImageVector by lazy {
        materialize("RotateCcw") {
            lucideStroked(
                addPathNodes("M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8")
            )
            lucideStroked(
                addPathNodes("M3 3v5h5")
            )
        }
    }
    val ChevronLeft: ImageVector by lazy {
        materialize("ChevronLeft") {
            lucideStroked(
                addPathNodes("m15 18-6-6 6-6")
            )
        }
    }
    val Minus: ImageVector by lazy {
        materialize("Minus") {
            lucideStroked(
                addPathNodes("M5 12h14")
            )
        }
    }
    val Plus: ImageVector by lazy {
        materialize("Plus") {
            lucideStroked(
                addPathNodes("M5 12h14")
            )
            lucideStroked(
                addPathNodes("M12 5v14")
            )
        }
    }

    private fun materialize(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = "Lucide.$name",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply(block).build()

    private fun ImageVector.Builder.lucideStroked(pathData: List<PathNode>) {
        addPath(
            pathData = pathData,
            pathFillType = PathFillType.NonZero,
            fill = null,
            fillAlpha = 1f,
            stroke = SolidColor(Color.Black),
            strokeAlpha = 1f,
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            strokeLineMiter = 4f
        )
    }

    private fun ImageVector.Builder.lucideFilledAndStroked(pathData: List<PathNode>) {
        addPath(
            pathData = pathData,
            pathFillType = PathFillType.NonZero,
            fill = SolidColor(Color.Black),
            fillAlpha = 1f,
            stroke = SolidColor(Color.Black),
            strokeAlpha = 1f,
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            strokeLineMiter = 4f
        )
    }
}
