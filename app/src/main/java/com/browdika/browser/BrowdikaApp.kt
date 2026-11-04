package com.browdika.browser

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class BrowdikaApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val pref = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString(KEY_THEME, THEME_SYSTEM)
        val mode = when (pref) {
            THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    companion object {
        const val PREFS = "browdika_settings"
        const val KEY_THEME = "theme_mode"
        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"
    }
}
