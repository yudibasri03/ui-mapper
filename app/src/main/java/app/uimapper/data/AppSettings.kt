package app.uimapper.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** SharedPreferences-backed settings. [version] ticks on every change so Compose can observe it. */
object AppSettings {

    private lateinit var prefs: SharedPreferences
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.getSharedPreferences("uimapper_settings", Context.MODE_PRIVATE)
    }

    private fun edit(block: SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).apply()
        _version.value = _version.value + 1
    }

    /** Jaccard similarity at/above which two captures count as the same screen. */
    var similarityThreshold: Float
        get() = prefs.getFloat("similarity", 0.82f)
        set(v) = edit { putFloat("similarity", v.coerceIn(0.5f, 0.99f)) }

    var captureScreenshots: Boolean
        get() = prefs.getBoolean("screenshots", true)
        set(v) = edit { putBoolean("screenshots", v) }

    /** Quiet period (no UI events) before a screen counts as settled and is captured. */
    var settleMs: Long
        get() = prefs.getLong("settle_ms", 800L)
        set(v) = edit { putLong("settle_ms", v.coerceIn(200L, 5000L)) }

    /** Show the floating inspector bubble automatically when the service connects. */
    var overlayOnConnect: Boolean
        get() = prefs.getBoolean("overlay_on_connect", true)
        set(v) = edit { putBoolean("overlay_on_connect", v) }

    /** App currently selected as mapping target on the home screen. */
    val targetPkg: String?
        get() = prefs.getString("target_pkg", null)

    val targetLabel: String?
        get() = prefs.getString("target_label", null)

    fun setTarget(pkg: String, label: String) = edit {
        putString("target_pkg", pkg)
        putString("target_label", label)
    }
}
