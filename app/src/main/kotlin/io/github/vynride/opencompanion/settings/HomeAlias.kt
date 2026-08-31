// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.settings

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/** Toggles the disabled `HomeAlias` activity-alias that lets the app act as a home screen. */
object HomeAlias {
    fun setEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        val component = ComponentName(context, "io.github.vynride.opencompanion.HomeAlias")
        val state =
            if (enabled) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
        context.packageManager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
    }

    fun isEnabled(context: Context): Boolean {
        val component = ComponentName(context, "io.github.vynride.opencompanion.HomeAlias")
        return context.packageManager.getComponentEnabledSetting(component) ==
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    }
}
