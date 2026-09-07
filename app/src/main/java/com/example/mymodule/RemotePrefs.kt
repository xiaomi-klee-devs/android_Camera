package com.example.mymodule

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Read-write handle to the LSPosed framework's remote preferences for group "module_config".
 *
 * The settings UI MUST write through this handle (via [prefs]) instead of local
 * SharedPreferences: inside hooked processes, `getRemotePreferences()` reads from the
 * LSPosed database, NOT from the module app's internal shared_prefs XML files.
 * Writing only to local prefs meant hooks always saw code defaults.
 */
object RemotePrefs {

    const val PREFS_NAME = "module_config"

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var remotePrefs: SharedPreferences? = null

    private val listeners = CopyOnWriteArrayList<(SharedPreferences) -> Unit>()
    private var registered = false

    /** Called once from [App.onCreate]. Delivers the handle when the framework binds. */
    fun init(context: Context) {
        appContext = context.applicationContext
        if (registered) return
        registered = true
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                val p = service.getRemotePreferences(PREFS_NAME)
                migrateLocalToRemote(p)
                remotePrefs = p
                Handler(Looper.getMainLooper()).post { listeners.forEach { it(p) } }
            }

            override fun onServiceDied(service: XposedService) {
                // Keep last handle; writes will fail loudly if the framework dies.
            }
        })
    }

    /**
     * One-time migration: the old settings UI wrote to the module app's local
     * shared_prefs (which hooks never read). Copy those values into the LSPosed
     * database so the user's existing settings carry over.
     */
    private fun migrateLocalToRemote(remote: SharedPreferences) {
        val ctx = appContext ?: return
        val local = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (local.all.isEmpty()) return
        if (remote.all.isNotEmpty()) return // already has values
        val editor = remote.edit()
        for ((key, value) in local.all) {
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Float -> editor.putFloat(key, value)
                is String -> editor.putString(key, value)
            }
        }
        editor.commit()
    }

    /** Current handle, or null if the framework service hasn't bound yet. */
    val prefs: SharedPreferences?
        get() = remotePrefs

    /** Invoke [onChange] now if bound, else when the handle becomes available. */
    fun observe(onChange: (SharedPreferences) -> Unit) {
        val p = remotePrefs
        if (p != null) onChange(p) else listeners.add(onChange)
    }
}