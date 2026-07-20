package com.eza.hyperglow.customization

import kotlinx.serialization.Serializable

@Serializable
data class CustomizationDocument(
    val version: Int = CURRENT_CUSTOMIZATION_VERSION,
    val id: String = "default_continuity",
    val name: String = "Seamless Default",
    val linkSurfaces: Boolean = false,
    val profiles: Map<String, SurfaceProfile> = emptyMap()
)

@Serializable
data class SurfaceProfile(
    val enabled: Boolean = true,
    val anchor: String = "below_stock_clock",
    val widthFraction: Float = 0.88f,
    val maxHeightFraction: Float = 0.46f,
    val verticalBias: Float = 0.5f,
    val collisionPolicy: String = "avoid",
    val widgets: List<WidgetSpec> = listOf(WidgetSpec("lyrics")),
    val transition: TransitionPreset = TransitionPreset(),
    val alignment: String = "auto",
    val secondaryMode: String = "Main only",
    val metadataVisible: Boolean = false,
    val metadataAnchor: String = "top",
    val weight: String = "Medium",
    val textSize: String = "normal",
    val textSizeCustom: Int = 100,
    val fontFamily: String = "spotify",
    val animation: String = "Gradient",
    val glow: String = "Off",
    val lineSyncFillMode: String = "Left to right (main only)",
    val overflow: String = "Wrap",
    val adaptiveSectioning: Boolean = true,
    val palette: Map<String, String> = emptyMap(),
    val backgroundStyle: String = "auto"
)

@Serializable
data class WidgetSpec(
    val type: String,
    val style: String = "primary",
    val optional: Boolean = false,
    val visible: Boolean = true
)

@Serializable
data class TransitionPreset(
    val id: String = "continuity",
    val durationMs: Int = 320,
    val easing: String = "fast_out_slow_in"
)

@Serializable
data class CompiledCustomization(
    val version: Int,
    val revision: Long,
    val hash: String,
    val sourceId: String,
    val linkSurfaces: Boolean,
    val profiles: Map<String, CompiledSurfaceProfile>,
    val diagnosticLogging: Boolean = false,
    val lockscreenKeepAwake: Boolean = false,
    val raiseToAod: Boolean = false
)

@Serializable
data class CompiledSurfaceProfile(
    val surface: String,
    val enabled: Boolean,
    val anchor: String,
    val widthFraction: Float,
    val maxHeightFraction: Float,
    val verticalBias: Float,
    val collisionPolicy: String,
    val widgets: List<WidgetSpec>,
    val transition: TransitionPreset,
    val alignment: String,
    val secondaryMode: String,
    val metadataVisible: Boolean,
    val metadataAnchor: String,
    val weight: String,
    val textSize: String,
    val textSizeCustom: Int,
    val fontFamily: String,
    val animation: String,
    val glow: String,
    val lineSyncFillMode: String,
    val overflow: String,
    val adaptiveSectioning: Boolean,
    val palette: Map<String, String>,
    val backgroundStyle: String = "none"
)

const val CURRENT_CUSTOMIZATION_VERSION = 1
