package com.eza.hyperglow.ui

import android.content.pm.PackageManager
import org.junit.Assert.assertEquals
import org.junit.Test

class LauncherEntryPolicyTest {
    @Test
    fun hiddenPreferenceDisablesOnlyTheLauncherAlias() {
        assertEquals(
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            launcherAliasEnabledState(launcherIconHidden = true)
        )
    }

    @Test
    fun shownPreferenceExplicitlyEnablesTheLauncherAlias() {
        assertEquals(
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            launcherAliasEnabledState(launcherIconHidden = false)
        )
    }

    /** The alias, not MainActivity: disabling MainActivity would cut LSPosed's launch entry. */
    @Test
    fun aliasClassTargetsTheAliasComponent() {
        assertEquals("com.eza.hyperglow.ui.MainActivityAlias", LAUNCHER_ALIAS_CLASS)
    }
}
