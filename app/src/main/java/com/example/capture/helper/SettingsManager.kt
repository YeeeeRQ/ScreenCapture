package com.example.capture.helper

import android.content.Context

object SettingsManager {
    private const val PREFS_NAME = "screen_record_settings"
    const val KEY_RECORDING_MODE = "recording_mode"
    const val KEY_THEME_MODE = "theme_mode"
    
    const val MODE_REAUTH = "reauth"
    const val MODE_REUSE = "reuse"
    
    const val THEME_LIGHT = "light"
    const val THEME_DARK = "dark"
    const val THEME_SYSTEM = "system"
    
    fun getRecordingMode(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_RECORDING_MODE, MODE_REAUTH) ?: MODE_REAUTH
    }
    
    fun setRecordingMode(context: Context, mode: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_RECORDING_MODE, mode).apply()
    }
    
    fun getThemeMode(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_THEME_MODE, THEME_SYSTEM) ?: THEME_SYSTEM
    }
    
    fun setThemeMode(context: Context, mode: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_THEME_MODE, mode).apply()
    }
}