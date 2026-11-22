package ch.ciuin.app.personal

import platform.Foundation.NSUserDefaults

internal class IosPersonalModelStorage : PersonalModelStorage {
    private val defaults = NSUserDefaults.standardUserDefaults

    override fun read(): String? = defaults.stringForKey(KEY)

    override fun write(json: String) {
        defaults.setObject(json, forKey = KEY)
    }

    private companion object {
        const val KEY = "ch.ciuin.personal-model"
    }
}
