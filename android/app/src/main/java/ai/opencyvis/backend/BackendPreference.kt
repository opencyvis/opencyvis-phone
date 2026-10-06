package ai.opencyvis.backend

import android.content.Context

/**
 * Remembers which privilege backend the user chose (connector name: "root",
 * "shizuku", "adb-direct"), so detection tries it first next time and does not
 * reach for root on a rooted device whose owner picked something else.
 */
object BackendPreference {
    private const val PREFS = "backend_preference"
    private const val KEY_PREFERRED = "preferred"

    fun get(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PREFERRED, null)

    fun set(context: Context, name: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_PREFERRED, name).apply()
    }

    fun setIfAbsent(context: Context, name: String) {
        if (get(context) == null) set(context, name)
    }
}
