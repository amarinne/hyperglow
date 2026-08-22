package com.eza.hyperglow.ui

import android.content.pm.PackageManager

/**
 * The LAUNCHER alias is the only component hidden when the user hides the desktop icon. MainActivity
 * itself must stay enabled under every preference state: LSPosed resolves its launch entry through
 * MainActivity's MODULE_SETTINGS/INFO categories, and disabling it would cut that entry.
 */
internal const val LAUNCHER_ALIAS_CLASS = "com.eza.hyperglow.ui.MainActivityAlias"

/** Maps the hide-launcher-icon preference onto the alias component's enabled state. */
internal fun launcherAliasEnabledState(launcherIconHidden: Boolean): Int =
    if (launcherIconHidden) {
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    } else {
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    }
