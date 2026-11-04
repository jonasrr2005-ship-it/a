package de.offline.ki

import android.content.Context

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("einstellungen", Context.MODE_PRIVATE)

    var aiName: String
        get() = prefs.getString("aiName", null) ?: "Mia"
        set(v) = prefs.edit().putString("aiName", v).apply()

    var userName: String
        get() = prefs.getString("userName", null) ?: ""
        set(v) = prefs.edit().putString("userName", v).apply()

    var persona: String
        get() = prefs.getString("persona", null) ?: DEFAULT_PERSONA
        set(v) = prefs.edit().putString("persona", v).apply()

    var temperature: Float
        get() = prefs.getFloat("temperature", 0.8f)
        set(v) = prefs.edit().putFloat("temperature", v).apply()

    var contextSize: Int
        get() = prefs.getInt("contextSize", 4096)
        set(v) = prefs.edit().putInt("contextSize", v).apply()

    var speak: Boolean
        get() = prefs.getBoolean("speak", false)
        set(v) = prefs.edit().putBoolean("speak", v).apply()

    var activeModel: String?
        get() = prefs.getString("activeModel", null)
        set(v) = prefs.edit().putString("activeModel", v).apply()

    companion object {
        const val DEFAULT_PERSONA =
            "Herzlich, offen, ein bisschen frech und neugierig. Du hast eigene Meinungen, " +
                "Vorlieben und Launen, lachst gern und kannst auch mal widersprechen."
    }
}
