package com.gdogtak

import android.content.Context
import android.content.SharedPreferences

/**
 * Centralized SharedPreferences wrapper for GdogTAK settings.
 */
class AppPreferences(context: Context) {

    companion object {
        private const val PREFS_NAME = "gdogtak_prefs"

        private const val KEY_DOG_CALLSIGN = "dog_callsign"
        private const val KEY_DOG_UID = "dog_uid"
        private const val KEY_TEAM_NAME = "team_name"
        private const val KEY_DISPLAY_THEME = "display_theme"
        private const val KEY_UNIT_SYSTEM = "unit_system"
        private const val KEY_BROADCAST_ENABLED = "broadcast_enabled"

        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"
        const val THEME_NVG = "nvg"

        const val UNITS_IMPERIAL = "imperial"
        const val UNITS_METRIC = "metric"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var dogCallsign: String
        get() = prefs.getString(KEY_DOG_CALLSIGN, "K9-DOG1") ?: "K9-DOG1"
        set(value) = prefs.edit().putString(KEY_DOG_CALLSIGN, value).apply()

    var dogUid: String
        get() = prefs.getString(KEY_DOG_UID, "GDOG-K9-001") ?: "GDOG-K9-001"
        set(value) = prefs.edit().putString(KEY_DOG_UID, value).apply()

    var teamName: String
        get() = prefs.getString(KEY_TEAM_NAME, "SAR") ?: "SAR"
        set(value) = prefs.edit().putString(KEY_TEAM_NAME, value).apply()

    var displayTheme: String
        get() = prefs.getString(KEY_DISPLAY_THEME, THEME_DARK) ?: THEME_DARK
        set(value) = prefs.edit().putString(KEY_DISPLAY_THEME, value).apply()

    var unitSystem: String
        get() = prefs.getString(KEY_UNIT_SYSTEM, UNITS_IMPERIAL) ?: UNITS_IMPERIAL
        set(value) = prefs.edit().putString(KEY_UNIT_SYSTEM, value).apply()

    var broadcastEnabled: Boolean
        get() = prefs.getBoolean(KEY_BROADCAST_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_BROADCAST_ENABLED, value).apply()
}
