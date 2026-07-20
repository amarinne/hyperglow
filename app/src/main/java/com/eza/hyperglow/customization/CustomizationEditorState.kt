package com.eza.hyperglow.customization

data class CustomizationEditorState(
    val document: CustomizationDocument,
    val selectedSurface: String = SceneCompiler.SURFACE_LOCKSCREEN
) {
    fun selectSurface(surface: String): CustomizationEditorState = copy(
        selectedSurface = surface.takeIf {
            it == SceneCompiler.SURFACE_LOCKSCREEN || it == SceneCompiler.SURFACE_AOD
        } ?: selectedSurface
    )

    fun setLinkSurfaces(linked: Boolean): CustomizationEditorState {
        if (!linked) return copy(document = document.copy(linkSurfaces = false))
        val source = document.profiles[selectedSurface] ?: SurfaceProfile()
        val profiles = document.profiles.toMutableMap()
        for (surface in listOf(SceneCompiler.SURFACE_LOCKSCREEN, SceneCompiler.SURFACE_AOD)) {
            val existing = profiles[surface] ?: SurfaceProfile()
            profiles[surface] = source.copy(enabled = existing.enabled)
        }
        return copy(document = document.copy(linkSurfaces = true, profiles = profiles))
    }

    fun updateSelected(transform: (SurfaceProfile) -> SurfaceProfile): CustomizationEditorState {
        val profiles = document.profiles.toMutableMap()
        val updated = transform(profiles[selectedSurface] ?: SurfaceProfile())
        profiles[selectedSurface] = updated
        if (document.linkSurfaces) {
            val other = if (selectedSurface == SceneCompiler.SURFACE_AOD) {
                SceneCompiler.SURFACE_LOCKSCREEN
            } else {
                SceneCompiler.SURFACE_AOD
            }
            val existing = profiles[other] ?: SurfaceProfile()
            profiles[other] = updated.copy(enabled = existing.enabled)
        }
        return copy(document = document.copy(profiles = profiles))
    }

    fun reset(): CustomizationEditorState = CustomizationEditorState(
        SceneCompiler.safeDefaultDocument(),
        selectedSurface
    )
}
