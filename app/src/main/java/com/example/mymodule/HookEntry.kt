package com.example.mymodule

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * LSPosed module entry point.
 *
 * LSPosed instantiates this class (public no-arg constructor) inside every scope
 * app process and calls the lifecycle callbacks below. The class name is listed
 * in `META-INF/xposed/java_init.list`.
 */
class HookEntry : XposedModule() {

    /** Called once when the module is first loaded into a process. */
    override fun onModuleLoaded(param: ModuleLoadedParam) {
        log(Log.INFO, TAG, "Module loaded into process '${param.processName}' (framework ${frameworkName} ${frameworkVersion})")
    }

    /**
     * Called when a scope app is loaded into this process.
     * The modern API no longer hooks the module's own app.
     */
    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (param.packageName !in SCOPE) return

        log(Log.INFO, TAG, "${param.packageName}: hook_enabled=${getRemotePreferences(PREFS_NAME).getBoolean(KEY_HOOK_ENABLED, true)}")

        if (!getRemotePreferences(PREFS_NAME).getBoolean(KEY_HOOK_ENABLED, true)) {
            log(Log.INFO, TAG, "${param.packageName}: hooks disabled by settings")
            return
        }

        // Always install the hook — it re-reads its own pref inside each callback.
        // getRemotePreferences() may return stale defaults, so we gate inside the hook instead.
        if (param.packageName == "com.android.camera") {
            try {
                log(Log.INFO, TAG, "Installing camera smooth zoom hook")
                CameraZoomHooks.injectSmoothZoom(this, param.defaultClassLoader)
            } catch (e: Throwable) {
                log(Log.ERROR, TAG, "injectSmoothZoom FAILED: ${e.message}")
            }
            try {
                log(Log.INFO, TAG, "Installing camera zoom buttons hook")
                CameraZoomHooks.injectZoomButtons(this, param.defaultClassLoader)
            } catch (e: Throwable) {
                log(Log.ERROR, TAG, "injectZoomButtons FAILED: ${e.message}")
            }
        }
    }

    /** Called when system_server starts (only if the "android" scope is enabled). */
    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        log(Log.INFO, TAG, "System server starting")
    }

    private companion object {
        const val TAG = "MyModule"
        const val PREFS_NAME = "module_config"
        const val KEY_HOOK_ENABLED = "hook_enabled"

        // Packages this module hooks (must match META-INF/xposed/scope.list).
        val SCOPE = setOf(
            "com.android.camera",
        )
    }
}