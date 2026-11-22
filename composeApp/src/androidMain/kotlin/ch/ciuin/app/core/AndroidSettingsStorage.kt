package ch.ciuin.app.core

import android.content.Context
import android.provider.Settings

internal class AndroidSettingsStorage(context: Context) : SettingsStorage {
    private val preferences = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    override fun read(): String? = preferences.getString("json", null)
    override fun write(json: String) {
        preferences.edit().putString("json", json).apply()
    }
}

internal fun androidReduceMotion(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
