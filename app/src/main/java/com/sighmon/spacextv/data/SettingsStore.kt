package com.sighmon.spacextv.data

import android.content.Context

class SettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences("spacex-tv", Context.MODE_PRIVATE)

    var xApiBearerToken: String
        get() = preferences.getString("xApiBearerToken", "") ?: ""
        set(value) = preferences.edit().putString("xApiBearerToken", value).remove("dailyBroadcastCache").apply()

    var usesXApiBearerToken: Boolean
        get() = preferences.getBoolean("usesXApiBearerToken", false)
        set(value) = preferences.edit().putBoolean("usesXApiBearerToken", value).remove("dailyBroadcastCache").apply()

    var showsPlayerDebugOverlay: Boolean
        get() = preferences.getBoolean("showsPlayerDebugOverlay", false)
        set(value) = preferences.edit().putBoolean("showsPlayerDebugOverlay", value).apply()

    var showsNextLaunchCountdown: Boolean
        get() = preferences.getBoolean("showsNextLaunchCountdown", true)
        set(value) = preferences.edit().putBoolean("showsNextLaunchCountdown", value).apply()

    var showsCardFilters: Boolean
        get() = preferences.getBoolean("showsCardFilters", true)
        set(value) = preferences.edit().putBoolean("showsCardFilters", value).apply()

    var showsSpaceXLogos: Boolean
        get() = preferences.getBoolean("showsSpaceXLogos", false)
        set(value) = preferences.edit().putBoolean("showsSpaceXLogos", value).apply()

    var prefersMp4Playback: Boolean
        get() = preferences.getBoolean("prefersMp4Playback", false)
        set(value) = preferences.edit().putBoolean("prefersMp4Playback", value).remove("dailyBroadcastCache").apply()

    var totalViewingTime: Long
        get() = preferences.getLong("totalViewingTime", 0L)
        set(value) = preferences.edit().putLong("totalViewingTime", value.coerceAtLeast(0L)).apply()

    var cachedBroadcastsJson: String?
        get() = preferences.getString("dailyBroadcastCache", null)
        set(value) = preferences.edit().putString("dailyBroadcastCache", value).apply()

    fun clearCaches() {
        preferences.edit()
            .remove("dailyBroadcastCache")
            .remove("cardResolutionCache")
            .apply()
    }
}
