package ch.ciuin.app.core

import platform.Foundation.NSUserDefaults

internal class IosSettingsStorage : SettingsStorage {
    private val defaults = NSUserDefaults.standardUserDefaults
    override fun read(): String? = defaults.stringForKey("ch.ciuin.settings")
    override fun write(json: String) {
        defaults.setObject(json, forKey = "ch.ciuin.settings")
    }
}
