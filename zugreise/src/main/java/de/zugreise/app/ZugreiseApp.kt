package de.zugreise.app

import android.app.Application
import android.content.Context

/** Merkt sich einen Absturz, damit die App ihn beim nächsten Start anzeigen kann. */
class ZugreiseApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(KEY_CRASH, error.stackTraceToString().take(4000))
                    .commit()
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
    }

    companion object {
        const val PREFS = "crash"
        const val KEY_CRASH = "last"

        /** Liefert den letzten gespeicherten Absturz einmalig zurück. */
        fun takeLastCrash(context: Context): String? {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val crash = prefs.getString(KEY_CRASH, null) ?: return null
            prefs.edit().remove(KEY_CRASH).apply()
            return crash
        }
    }
}
