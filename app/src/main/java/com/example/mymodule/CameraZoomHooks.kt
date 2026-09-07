package com.example.mymodule

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Range
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.exp

/**
 * Smooth zoom easing for the Xiaomi camera.
 *
 * Adapts to two camera architectures:
 *  - Old ROM (POCO F7 HyperOS 4): single ZoomManager `X5.f` with `e0(float,int)`
 *    (current zoom from `n0()`).
 *  - New ROM (e.g. Camera 6.3.006130.8): ZoomManager base `b8.b` with
 *    `l0(float,int)` (current zoom from `v0()`), plus `c8.D` (Super Moon) and
 *    `c8.h` (Cosmetic Mirror) subclasses that override `l0`. Video `c8.K`
 *    inherits `l0` from `b8.b`.
 */
object CameraZoomHooks {

    private const val TAG = "MyModule"
    private const val PREFS_NAME = "module_config"
    private const val KEY_ENABLED = "camera_zoom_smooth_enabled"
    private const val KEY_DURATION = "camera_zoom_smooth_duration"
    private const val KEY_FORCE_MAX_PHOTO = "camera_zoom_force_max_photo"
    private const val KEY_FORCE_MAX_VIDEO = "camera_zoom_force_max_video"
    private const val KEY_PRESETS_ENABLED = "zoom_presets_enabled"
    private const val KEY_ACTIVE_PRESETS = "active_zoom_presets"
    private const val DEFAULT_DURATION_MS = 200f
    private const val DEFAULT_FORCE_MAX_PHOTO = 20f
    private const val DEFAULT_FORCE_MAX_VIDEO = 30f
    private const val EPSILON = 0.001f
    private const val MODE_PORTRAIT = 171
    private const val MODE_VIDEO = 162

    /** Cache the current camera mode so static hooks (HAL max) can read it. */
    private val currentMode = java.util.concurrent.atomic.AtomicInteger(-1)

    private val inCallback = ConcurrentHashMap.newKeySet<Any>()
    private val smoothers = ConcurrentHashMap<Any, Smoother>()

    private class Smoother {
        var current = 0f
        var target = 0f
        var action = 0
        var animator: ValueAnimator? = null
        var lastFrameTime = 0L
    }

    fun injectSmoothZoom(api: XposedInterface, classLoader: ClassLoader) {
        // ── Step 1: Try X5.f (old ROMs — F7 etc.) ──
        if (installZoomHook(api, classLoader, "X5.f", "e0", "n0", neutralizeOldAnimators = true)) {
            api.log(Log.INFO, TAG, "[ZOOM] X5.f.e0 zoom hook installed")
            installForceZoomMaxForClass(api, runCatching { Class.forName("X5.f", false, classLoader) }.getOrNull(), classLoader)
        }

        // ── Step 2: Try new ROM classes (F8 Ultra etc.) ──
        if (runCatching { Class.forName("X5.f", false, classLoader) }.getOrNull() == null) {
            var installed = 0
            for (clsName in listOf("b8.b", "c8.D", "c8.h")) {
                if (installZoomHook(api, classLoader, clsName, "l0", "v0", neutralizeOldAnimators = false)) {
                    val cls = runCatching { Class.forName(clsName, false, classLoader) }.getOrNull()
                    installForceZoomMaxForClass(api, cls, classLoader)
                    installed++
                }
            }
            if (installed > 0) {
                api.log(Log.INFO, TAG, "[ZOOM] new-style zoom hook installed on $installed class(es)")
            }

            // Hook the concrete per-lens managers too. Their l5() Range getters
            // hardcode caps (c8.a periscope = 10x, c8.e = 6x) that the base-class
            // scan above never reaches. Real runtime names (jadx renames C2451a→a,
            // C2455e→e, C2450K→K; z is already lowercase).
            for (clsName in listOf("c8.a", "c8.e", "c8.K", "c8.z")) {
                val cls = runCatching { Class.forName(clsName, false, classLoader) }.getOrNull()
                installForceZoomMaxForClass(api, cls, classLoader)
            }
        }

        // ── Fix slider zoom-out freeze: unlock AEAF for action type 3 (slider) ──
        hookSliderAeafUnlock(api, classLoader)
    }

    /**
     * Hook Y5.p.m7(float, float, int) → boolean
     *
     * The stock code only unlocks AEAF for action types {0,4,6,15,16,17} before
     * a lens switch, but NOT for action type 3 (slider bar).  When dragging the
     * slider from >10x back down across a lens boundary, rapid re-entrant
     * async camera switches pile up without AEAF unlock, causing the preview
     * to freeze.  Fix: also unlock AEAF for action type 3.
     */
    private fun hookSliderAeafUnlock(api: XposedInterface, classLoader: ClassLoader) {
        val pCls = runCatching { Class.forName("Y5.p", false, classLoader) }.getOrNull() ?: return
        val m7Method = pCls.declaredMethods.firstOrNull {
            it.name == "m7" && it.parameterTypes.size == 3 &&
                it.parameterTypes[0] == Float::class.javaPrimitiveType &&
                it.parameterTypes[1] == Float::class.javaPrimitiveType &&
                it.parameterTypes[2] == Int::class.javaPrimitiveType
        } ?: return

        api.hook(m7Method).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept(object : Hooker {
                override fun intercept(chain: Chain): Any? {
                    val actionType = chain.args[2] as Int
                    // Action type 3 = SLIDER_BAR — unlock AEAF before lens switch
                    if (actionType == 3) {
                        runCatching {
                            val self = chain.thisObject
                            val baseModuleField = self.javaClass.getDeclaredField("f8821m").apply { isAccessible = true }
                            val baseModule = baseModuleField.get(self) ?: return@runCatching
                            val getCameraManager = baseModule.javaClass.getMethod("getCameraManager")
                            val cameraManager = getCameraManager.invoke(baseModule) ?: return@runCatching
                            val w0 = cameraManager.javaClass.getMethod("w0").invoke(cameraManager) as? Boolean ?: false
                            if (w0) {
                                cameraManager.javaClass.getMethod("r0").invoke(cameraManager)
                            }
                        }
                    }
                    return chain.proceed()
                }
            })
        api.log(Log.INFO, TAG, "[SLIDER] AEAF unlock hook installed for action type 3")
    }

    /** Holds the reflection info for a discovered zoom manager class. */
    private data class ZoomManagerInfo(
        val cls: Class<*>,
        val setterName: String,
        val getterName: String,
    )

    /**
     * Enumerate all class names from the app's DEX files via DexPathList reflection,
     * load each one, and check for the zoom manager signature:
     *   - boolean e0(float, int) or boolean l0(float, int)   [zoom setter]
     *   - float n0() or float v0() or similar no-arg float   [current zoom getter]
     *   - Range<Float> G() or similar no-arg Range getter    [range getter]
     */
    private fun scanDexForZoomManager(api: XposedInterface, classLoader: ClassLoader): ZoomManagerInfo? {
        val setterNames = listOf("e0", "l0")
        val getterNames = listOf("n0", "v0")

        // Enumerate class names from DEX files via reflection
        val classNames = enumerateDexClasses(classLoader)
        api.log(Log.INFO, TAG, "[ZOOM] DEX scan: ${classNames.size} classes found")

        for (className in classNames) {
            // Skip Android framework / Kotlin / Java classes
            if (className.startsWith("android.") || className.startsWith("java.") ||
                className.startsWith("kotlin.") || className.startsWith("kotlinx.") ||
                className.startsWith("dalvik.") || className.startsWith("com.android.internal.") ||
                className.startsWith("io.github.libxposed.") || className.startsWith("com.example.mymodule.")) continue

            val cls = runCatching { Class.forName(className, false, classLoader) }.getOrNull() ?: continue

            // Check for zoom setter: boolean (float, int)
            val setter = cls.declaredMethods.firstOrNull { m ->
                m.name in setterNames &&
                m.returnType == Boolean::class.javaPrimitiveType &&
                m.parameterTypes.size == 2 &&
                m.parameterTypes[0] == Float::class.javaPrimitiveType &&
                m.parameterTypes[1] == Int::class.javaPrimitiveType
            } ?: continue

            // Check for current zoom getter: no-arg float
            val getter = cls.declaredMethods.firstOrNull { m ->
                m.name in getterNames &&
                m.returnType == Float::class.javaPrimitiveType &&
                m.parameterTypes.isEmpty()
            } ?: continue

            // Check for range getter: no-arg Range<Float>
            val rangeGetter = cls.declaredMethods.firstOrNull { m ->
                m.parameterTypes.isEmpty() &&
                Range::class.java.isAssignableFrom(m.returnType)
            } ?: continue

            api.log(Log.INFO, TAG, "[ZOOM] DEX match: ${cls.name} — setter=${setter.name}, getter=${getter.name}, range=${rangeGetter.name}")
            return ZoomManagerInfo(cls, setter.name, getter.name)
        }
        return null
    }

    /** Use DexFile.entries() via reflection to list all classes in the app's DEX files. */
    private fun enumerateDexClasses(classLoader: ClassLoader): List<String> {
        val result = mutableListOf<String>()
        try {
            val pathListField = classLoader.javaClass.getDeclaredField("pathList")
            pathListField.isAccessible = true
            val pathList = pathListField.get(classLoader)

            val dexElementsField = pathList.javaClass.getDeclaredField("dexElements")
            dexElementsField.isAccessible = true
            val dexElements = dexElementsField.get(pathList) as? Array<*> ?: return result

            for (element in dexElements) {
                if (element == null) continue
                try {
                    val dexFileField = element.javaClass.getDeclaredField("dexFile")
                    dexFileField.isAccessible = true
                    val dexFile = dexFileField.get(element) ?: continue

                    val entriesMethod = dexFile.javaClass.getMethod("entries")
                    @Suppress("UNCHECKED_CAST")
                    val entries = entriesMethod.invoke(dexFile) as? java.util.Enumeration<String> ?: continue
                    while (entries.hasMoreElements()) {
                        result.add(entries.nextElement())
                    }
                } catch (_: Exception) { }
            }
        } catch (_: Exception) { }
        return result
    }

    /** Install smooth zoom on a dynamically discovered class. */
    private fun installSmoothZoomOnClass(
        api: XposedInterface,
        classLoader: ClassLoader,
        info: ZoomManagerInfo,
    ) {
        val setterMethod = info.cls.declaredMethods.firstOrNull {
            it.name == info.setterName && it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == Float::class.javaPrimitiveType &&
                it.parameterTypes[1] == Int::class.javaPrimitiveType
        } ?: return

        api.hook(setterMethod).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept(object : Hooker {
                override fun intercept(chain: Chain): Any? {
                    val self = chain.thisObject
                    patchRangeFields(self, api)
                    if (inCallback.contains(self)) return chain.proceed()
                    if (Looper.myLooper() != Looper.getMainLooper()) return chain.proceed()

                    val prefs = runCatching { api.getRemotePreferences(PREFS_NAME) }.getOrNull()
                    val enabled = prefs?.getBoolean(KEY_ENABLED, true) ?: true
                    if (!enabled) return chain.proceed()

                    val target = chain.args[0] as Float
                    val action = chain.args[1] as Int

                    val mode = runCatching {
                        self.javaClass.getField("c").getInt(self)
                    }.getOrDefault(-1)
                    if (mode == MODE_PORTRAIT) return chain.proceed()

                    val s = smoothers.computeIfAbsent(self) {
                        Smoother().also { sm ->
                            sm.current = currentZoom(self, info.getterName) ?: target
                        }
                    }
                    if (target.isNaN() || s.current.isNaN()) return chain.proceed()
                    s.target = target
                    s.action = action

                    val anim = s.animator
                    if (anim == null || !anim.isRunning) {
                        startSmoother(s, self, setterMethod, api)
                    }
                    return true
                }
            })

        neutralizePillAnimator(api, classLoader)
        api.log(Log.INFO, TAG, "[ZOOM] smooth zoom installed on ${info.cls.name}.${info.setterName}")
    }

    /**
     * Force-zoom max for any class — hook all Range<Float> getters + patch fields.
     * Does NOT require knowing the class name upfront.
     */
    private fun installForceZoomMaxForClass(api: XposedInterface, zoomCls: Class<*>?, cl: ClassLoader?) {
        if (zoomCls == null) return
        android.util.Log.e(TAG, "[FORCE_ZOOM] scanning ${zoomCls.name} for Range getters")

        // Collect all zero-arg Range<Float> getters from the entire class hierarchy
        val hookedGetters = mutableListOf<String>()
        var cls: Class<*>? = zoomCls
        while (cls != null && cls != Any::class.java) {
            cls.declaredMethods.filter {
                it.parameterTypes.isEmpty() && Range::class.java.isAssignableFrom(it.returnType)
            }.forEach { method ->
                api.hook(method).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(object : Hooker {
                        override fun intercept(chain: Chain): Any? {
                            val raw = chain.proceed()
                            val range = raw as? Range<Float> ?: return raw
                            val self = chain.thisObject
                            val mode = runCatching {
                                self.javaClass.getField("c").getInt(self)
                            }.getOrDefault(-1)
                            val forceMax = getForceMax(api, mode)
                            if (forceMax > 0f && range.upper < forceMax) {
                                return Range.create(range.lower, forceMax)
                            }
                            return range
                        }
                    })
                hookedGetters.add("${cls!!.simpleName}.${method.name}")
            }
            cls = cls.superclass
        }

        if (hookedGetters.isNotEmpty()) {
            android.util.Log.e(TAG, "[FORCE_ZOOM] hooked range getters: ${hookedGetters.joinToString()}")
        } else {
            android.util.Log.e(TAG, "[FORCE_ZOOM] NO range getters found in hierarchy")
        }

        // Critical: hook C1255d.x(C1253c) → the static method that returns
        // SCALER_AVAILABLE_MAX_DIGITAL_ZOOM.  ALL zoom clamping in the codebase
        // funnels through this: B(), H(), Math.min(6.0f, C1255d.x(...)), etc.
        if (cl != null) {
            hookHalMaxZoom(api, cl)
            hookPerLensMaxZoom(api, cl)
        }

        android.util.Log.e(TAG, "[FORCE_ZOOM] force zoom max installed on ${zoomCls.name}")
    }

    /**
     * Hook C1255d.x(C1253c) — the single funnel for HAL max digital zoom.
     *
     * Returns SCALER_AVAILABLE_MAX_DIGITAL_ZOOM (hardware limit, e.g. 10.0).
     * Every clamping path uses this:
     *   B() → MathUtils.clamp(f, 1.0f, C1255d.x(capabilities))
     *   H() → same pattern
     *   Math.min(6.0f, C1255d.x(capabilities)) in range computations
     *   etc.
     *
     * By hooking this to return forceMax, we raise the ceiling for ALL paths.
     */
    /**
     * Hook Y5.J.s(float, float, String, C1253c) — the video zoom manager's max limiter.
     *
     * This method caps the periscope/ultrawide zoom to a config string value
     * (bVar.l() → e.g. "10").  Even though C1255d.x() returns our forceMax,
     * s() does Math.min(forceMax, configValue) which brings it back down.
     *
     * By hooking s() to always return the first parameter (the C1255d.x() value),
     * we bypass the config cap and let our forceMax through.
     */
    private fun hookPerLensMaxZoom(api: XposedInterface, classLoader: ClassLoader) {
        // ── F7 ROM: s(float, float, String, C1253c) → float ──
        // Covers Y5.J (VideoZoomManager) and all its subclasses.
        val f7Candidates = listOf("Y5.J", "X5.f", "b8.b")
        for (clsName in f7Candidates) {
            val cls = runCatching { Class.forName(clsName, false, classLoader) }.getOrNull() ?: continue
            val sMethod = cls.declaredMethods.firstOrNull {
                it.name == "s" && it.parameterTypes.size == 4 &&
                    it.parameterTypes[0] == Float::class.javaPrimitiveType &&
                    it.parameterTypes[1] == Float::class.javaPrimitiveType &&
                    it.parameterTypes[2] == String::class.java &&
                    it.returnType == Float::class.javaPrimitiveType
            }
            if (sMethod != null) {
                api.hook(sMethod).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(object : Hooker {
                        override fun intercept(chain: Chain): Any? {
                            // Return the first parameter (C1255d.x() value) unchanged.
                            // This bypasses Math.min(f, configStr) and the
                            // E7.a.x(configStr * zoomTable[last]) path.
                            return chain.args[0]
                        }
                    })
                android.util.Log.e(TAG, "[FORCE_ZOOM] hooked ${clsName}.s() → bypass per-lens config cap")
                return
            }
        }

        // ── F8 Ultra (HyperOS 4): v(float, String) → float ──
        // The periscope cap on this ROM is v() doing Math.min(f3, Float.parseFloat(str)).
        // Base b8.C2347b.v() returns f3 unchanged; subclasses c8.C2450K / c8.z (tele) /
        // c8.C2455e override it with the Math.min cap. Hook each declared v() so the
        // subclass overrides are intercepted too.
        // NOTE: these are the REAL runtime names. jadx renames classes for
        // case-insensitive filesystems (b8.C2347b → b8.b, c8.C2450K → c8.K,
        // c8.C2455e → c8.e); Class.forName on the decompile names fails silently.
        val f8Candidates = listOf("b8.b", "c8.K", "c8.z", "c8.e")
        var hookedV = 0
        for (clsName in f8Candidates) {
            val cls = runCatching { Class.forName(clsName, false, classLoader) }.getOrNull() ?: continue
            val vMethod = cls.declaredMethods.firstOrNull {
                it.name == "v" && it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == Float::class.javaPrimitiveType &&
                    it.parameterTypes[1] == String::class.java &&
                    it.returnType == Float::class.javaPrimitiveType
            }
            if (vMethod != null) {
                api.hook(vMethod).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(object : Hooker {
                        override fun intercept(chain: Chain): Any? {
                            // Return the first parameter (the zoom ratio / HAL max)
                            // unchanged, bypassing Math.min(f3, Float.parseFloat(str)).
                            return chain.args[0]
                        }
                    })
                hookedV++
                android.util.Log.e(TAG, "[FORCE_ZOOM] hooked ${clsName}.v() → bypass per-lens config cap")
            }
        }
        if (hookedV > 0) return
        android.util.Log.e(TAG, "[FORCE_ZOOM] s(float,float,String,C1253c) / v(float,String) NOT FOUND in any candidate class")
    }

    /**
     * Hook the zoom data provider that returns float[] of zoom ratio presets.
     * Uses DEX scan to find any static method: float[] Q(int, boolean)
     * by signature — doesn't depend on the obfuscated class name.
     */
    fun injectZoomButtons(api: XposedInterface, classLoader: ClassLoader) {
        val dataCls = runCatching {
            Class.forName("com.android.camera.data.data.h", false, classLoader)
        }.getOrNull() ?: return

        val qMethod = dataCls.declaredMethods.firstOrNull {
            it.name == "Q" && it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                it.parameterTypes[1] == Boolean::class.javaPrimitiveType &&
                it.returnType == FloatArray::class.java
        } ?: return

        val MODE_VIDEO = 162
        val MODE_PORTRAIT = 171
        val DEFAULT_PRESETS = floatArrayOf(0.6f, 1.0f, 1.5f, 2.0f, 3.0f, 5.0f, 10f, 15f)

        api.hook(qMethod).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept(object : Hooker {
                override fun intercept(chain: Chain): Any? {
                    val mode = chain.args[0] as Int
                    if (mode == MODE_PORTRAIT) return chain.proceed()

                    val prefs = runCatching { api.getRemotePreferences(PREFS_NAME) }.getOrNull()
                    val presetsEnabled = prefs?.getBoolean(KEY_PRESETS_ENABLED, false) ?: false
                    if (!presetsEnabled) return chain.proceed()

                    // Read saved presets — fall back to defaults if not yet saved
                    val saved = prefs?.getString(KEY_ACTIVE_PRESETS, null)
                    val desired = if (!saved.isNullOrBlank()) {
                        saved.split(",")
                            .mapNotNull { it.trim().toFloatOrNull() }
                            .filter { it > 0f }
                            .toFloatArray()
                    } else {
                        DEFAULT_PRESETS
                    }
                    if (desired.isEmpty()) return chain.proceed()

                    val result = chain.proceed() as? FloatArray ?: return floatArrayOf(1.0f)
                    val merged = (result + desired).distinct().toFloatArray().also { it.sort() }
                    return if (!merged.contentEquals(result)) merged else result
                }
            })
        api.log(Log.INFO, TAG, "[BUTTONS] Mode-aware zoom buttons installed")
    }

    private fun hookHalMaxZoom(api: XposedInterface, classLoader: ClassLoader) {
        // ── F7 ROM: a6.C1255d.x(C1253c) → float ──
        val utilsCls = runCatching { Class.forName("a6.C1255d", false, classLoader) }.getOrNull()
        if (utilsCls != null) {
            // Find x(C1253c) → float
            val xMethod = utilsCls.declaredMethods.firstOrNull {
                it.name == "x" && it.parameterTypes.size == 1 &&
                    it.returnType == Float::class.javaPrimitiveType
            }
            if (xMethod != null) {
                api.hook(xMethod).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(object : Hooker {
                        override fun intercept(chain: Chain): Any? {
                            val result = chain.proceed()
                            val raw = result as? Float
                            if (raw == null) return result
                            val forceMax = getForceMax(api, currentMode.get())
                            if (forceMax > 0f && raw < forceMax) {
                                return forceMax
                            }
                            return raw
                        }
                    })
                android.util.Log.e(TAG, "[FORCE_ZOOM] hooked C1255d.x() → HAL max override active")
            }
        }

        // ── F8 Ultra (HyperOS 4): E8.C3014g.y(C3012f) / l0(int, C3012f) → float ──
        // y() returns c3012f.A() which reads SCALER_AVAILABLE_MAX_DIGITAL_ZOOM.
        // l0() returns the video-quality max zoom. Both funnel into the Range
        // getters (e.g. Math.min(6.0f, C3014g.y(...))), so override both.
        // Real runtime name is e8.g (jadx renamed E8.C3014g for case-insensitive FS).
        val gCls = runCatching { Class.forName("e8.g", false, classLoader) }.getOrNull()
        if (gCls != null) {
            val yMethod = gCls.declaredMethods.firstOrNull {
                it.name == "y" && it.parameterTypes.size == 1 &&
                    it.returnType == Float::class.javaPrimitiveType
            }
            if (yMethod != null) {
                api.hook(yMethod).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(object : Hooker {
                        override fun intercept(chain: Chain): Any? {
                            val result = chain.proceed()
                            val raw = result as? Float
                            if (raw == null) return result
                            val forceMax = getForceMax(api, currentMode.get())
                            if (forceMax > 0f && raw < forceMax) {
                                return forceMax
                            }
                            return raw
                        }
                    })
                android.util.Log.e(TAG, "[FORCE_ZOOM] hooked C3014g.y() → HAL max override active")
            }
            val l0Method = gCls.declaredMethods.firstOrNull {
                it.name == "l0" && it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                    it.returnType == Float::class.javaPrimitiveType
            }
            if (l0Method != null) {
                api.hook(l0Method).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(object : Hooker {
                        override fun intercept(chain: Chain): Any? {
                            val result = chain.proceed()
                            val raw = result as? Float
                            if (raw == null) return result
                            val forceMax = getForceMax(api, currentMode.get())
                            if (forceMax > 0f && raw < forceMax) {
                                return forceMax
                            }
                            return raw
                        }
                    })
                android.util.Log.e(TAG, "[FORCE_ZOOM] hooked C3014g.l0() → video max override active")
            }
        }
    }

    /**
     * Directly overwrite all Range<Float> fields on the ZoomManager instance.
     * e0() reads this.f8534h and this.f8535i by field access, bypassing getters.
     * This forces the internal clamping logic to use our extended range.
     */
    private fun installZoomHook(
        api: XposedInterface,
        classLoader: ClassLoader,
        className: String,
        setterName: String,
        getterName: String,
        neutralizeOldAnimators: Boolean,
    ): Boolean {
        val zoomCls = runCatching { Class.forName(className, false, classLoader) }.getOrNull()
        if (zoomCls == null) {
            api.log(Log.WARN, TAG, "[ZOOM] $className NOT FOUND")
            return false
        }

        val setterMethod = zoomCls.declaredMethods.firstOrNull {
            it.name == setterName && it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == Float::class.javaPrimitiveType &&
                it.parameterTypes[1] == Int::class.javaPrimitiveType
        }
        if (setterMethod == null) {
            api.log(Log.WARN, TAG, "[ZOOM] $className.$setterName(float,int) NOT FOUND")
            return false
        }

        api.hook(setterMethod).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept(object : Hooker {
                override fun intercept(chain: Chain): Any? {
                    val self = chain.thisObject

                    // Always patch the Range fields directly on the instance before
                    // the original runs.  The original e0() reads this.f8534h and
                    // this.f8535i by FIELD access (not via getters), so our getter
                    // hooks on G()/V4()/W()/Z4()/b0() have no effect inside e0().
                    patchRangeFields(self, api)

                    if (inCallback.contains(self)) return chain.proceed()
                    if (Looper.myLooper() != Looper.getMainLooper()) return chain.proceed()

                    val prefs = runCatching { api.getRemotePreferences(PREFS_NAME) }.getOrNull()
                    val enabled = prefs?.getBoolean(KEY_ENABLED, true) ?: true
                    if (!enabled) return chain.proceed()

                    val target = chain.args[0] as Float
                    val action = chain.args[1] as Int

                    // Portrait mode (171) → instant, no smoothing.
                    val mode = runCatching {
                        self.javaClass.getField("c").getInt(self)
                    }.getOrDefault(-1)
                    if (mode == MODE_PORTRAIT) return chain.proceed()

                    val s = smoothers.computeIfAbsent(self) {
                        Smoother().also { sm ->
                            sm.current = currentZoom(self, getterName) ?: target
                        }
                    }
                    if (target.isNaN() || s.current.isNaN()) return chain.proceed()
                    s.target = target
                    s.action = action

                    val anim = s.animator
                    if (anim == null || !anim.isRunning) {
                        startSmoother(s, self, setterMethod, api)
                    }
                    return true
                }
            })

        if (neutralizeOldAnimators) {
            neutralizeZbg(api, classLoader)
            neutralizeZoomToggle(api, classLoader)
        }
        // Both old and new ROMs use ZoomRatioToggleView with a pill AnimatorSet.
        // The 400ms pill glide is the visual conflict the user sees on the F8 Ultra
        // when tapping preset zoom buttons.
        neutralizePillAnimator(api, classLoader)
        return true
    }

    /** Current zoom ratio, read via a no-arg method returning float (n0 / v0). */
    private fun currentZoom(self: Any, getterName: String): Float? {
        val getter = self.javaClass.methods.firstOrNull {
            it.name == getterName && it.parameterTypes.isEmpty() &&
                it.returnType == Float::class.javaPrimitiveType
        } ?: return null
        return runCatching { getter.invoke(self) as? Float }.getOrNull()
    }

    /**
     * Old-ROM only: neutralize the stock zoom animators that would otherwise
     * fight our smooth driver. These classes do not exist in the new ROMs.
     *
     * FragmentZoomToggle has TWO ValueAnimator fields:
     *   - "f"  — created by Yh(float,float) for the pill slide animation
     *   - "g"  — created by ti(float,int) → Zb.g.i() for the smooth ratio tween
     *
     * The button-click handler picks one of three paths:
     *   1. Yh(this.e, target)  → field f
     *   2. pg(target, 16)      → direct (no animator)
     *   3. ti(target, 16)      → field g via Zb.g.i
     *
     * We neutralize all three:
     *   - Hook Zb.g.i(float,float) → set duration 0 (catches path 3 at the utility)
     *   - Hook FragmentZoomToggle.Yh(float,float) → end field f (catches path 1)
     *   - Hook FragmentZoomToggle.ti(float,int) → end field g (catches path 3 directly)
     */
    private fun neutralizeZbg(api: XposedInterface, classLoader: ClassLoader) {
        val zoomUtilCls = runCatching { Class.forName("Zb.g", false, classLoader) }.getOrNull()
        if (zoomUtilCls == null) {
            api.log(Log.WARN, TAG, "[ZOOM] Zb.g NOT FOUND")
            return
        }
        val zi = zoomUtilCls.declaredMethods.firstOrNull {
            it.name == "i" && it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == Float::class.javaPrimitiveType &&
                it.parameterTypes[1] == Float::class.javaPrimitiveType &&
                it.returnType == ValueAnimator::class.java
        }
        if (zi != null) {
            api.hook(zi).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(object : Hooker {
                    override fun intercept(chain: Chain): Any? {
                        val anim = chain.proceed() as? ValueAnimator
                        if (anim != null) anim.duration = 0L
                        return anim
                    }
                })
            api.log(Log.INFO, TAG, "[ZOOM] neutralized Zb.g.i")
        } else {
            api.log(Log.WARN, TAG, "[ZOOM] Zb.g.i(float,float) NOT FOUND")
        }
    }

    private fun neutralizeZoomToggle(api: XposedInterface, classLoader: ClassLoader) {
        val toggleCls = runCatching {
            Class.forName("com.android.camera.fragment.dual.FragmentZoomToggle", false, classLoader)
        }.getOrNull() ?: run {
            api.log(Log.WARN, TAG, "[ZOOM] FragmentZoomToggle NOT FOUND")
            return
        }

        // Yh(float,float) → field "f" — pill slide animator
        val yh = toggleCls.declaredMethods.firstOrNull {
            it.name == "Yh" && it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == Float::class.javaPrimitiveType &&
                it.parameterTypes[1] == Float::class.javaPrimitiveType
        }
        if (yh != null) {
            api.hook(yh).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(object : Hooker {
                    override fun intercept(chain: Chain): Any? {
                        val result = chain.proceed()
                        runCatching {
                            val f = toggleCls.getDeclaredField("f")
                            f.isAccessible = true
                            (f.get(chain.thisObject) as? ValueAnimator)?.end()
                        }
                        return result
                    }
                })
            api.log(Log.INFO, TAG, "[ZOOM] neutralized FragmentZoomToggle.Yh → field f")
        } else {
            api.log(Log.WARN, TAG, "[ZOOM] FragmentZoomToggle.Yh(float,float) NOT FOUND")
        }

        // ti(float,int) → field "f13193g" (renamed from: g) — smooth ratio tween via Zb.g.i
        val ti = toggleCls.declaredMethods.firstOrNull {
            it.name == "ti" && it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == Float::class.javaPrimitiveType &&
                it.parameterTypes[1] == Int::class.javaPrimitiveType
        }
        if (ti != null) {
            api.hook(ti).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(object : Hooker {
                    override fun intercept(chain: Chain): Any? {
                        val result = chain.proceed()
                        runCatching {
                            val g = toggleCls.getDeclaredField("f13193g")
                            g.isAccessible = true
                            (g.get(chain.thisObject) as? ValueAnimator)?.end()
                            api.log(Log.INFO, TAG, "[ZOOM] ended FragmentZoomToggle.ti field f13193g")
                        }
                        return result
                    }
                })
            api.log(Log.INFO, TAG, "[ZOOM] neutralized FragmentZoomToggle.ti → field f13193g")
        } else {
            api.log(Log.WARN, TAG, "[ZOOM] FragmentZoomToggle.ti(float,int) NOT FOUND")
        }
    }

    private fun neutralizePillAnimator(api: XposedInterface, classLoader: ClassLoader) {
        val animatorSetCls = runCatching { Class.forName("android.animation.AnimatorSet", false, classLoader) }.getOrNull()
            ?: return
        val setDuration = runCatching {
            animatorSetCls.getMethod("setDuration", Long::class.javaPrimitiveType)
        }.getOrNull() ?: return

        api.hook(setDuration).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept(object : Hooker {
                override fun intercept(chain: Chain): Any? {
                    val duration = chain.args[0] as Long
                    // The pill AnimatorSet uses exactly 400ms
                    if (duration == 400L) {
                        chain.args[0] = 0L
                        api.log(Log.INFO, TAG, "[ZOOM] killed pill AnimatorSet 400ms → 0ms")
                    }
                    return chain.proceed()
                }
            })
        api.log(Log.INFO, TAG, "[ZOOM] pill AnimatorSet neutralizer installed")
    }

    /**
     * New-ROM neutralizer: kill the stock zoom-toggle animators (z3.x fields
     * "i" and "j") that fight our smooth driver.
     *
     * Stock flow on new ROMs:
     *   z3.x.Mj / z3.x.Nj creates ValueAnimator → update listener calls
     *   z3.x.uj(float,int) → InterfaceC2164z0.a().Y1(...) → b8.b.l0(float,int)
     *   (our hook).  If the stock animator runs at full duration (100-150 ms),
     *   it keeps pushing intermediate targets, and our smoother can't settle →
     *   jitter.
     *
     * By ending the animator right after stock starts it, the update listener
     * fires once with the final value, our hook receives the target, and our
     * smoother eases to it smoothly.
     */
    private fun neutralizeNewZoomToggle(api: XposedInterface, classLoader: ClassLoader) {
        val toggleCls = runCatching {
            Class.forName("z3.x", false, classLoader)
        }.getOrNull() ?: return

        // Mj(float,float) → field "i" (f18222i) — portrait zoom animator
        val mj = toggleCls.declaredMethods.firstOrNull {
            it.name == "Mj" && it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == Float::class.javaPrimitiveType &&
                it.parameterTypes[1] == Float::class.javaPrimitiveType
        }
        if (mj != null) {
            api.hook(mj).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(object : Hooker {
                    override fun intercept(chain: Chain): Any? {
                        val result = chain.proceed()
                        runCatching {
                            // Field name is f18222i (JADX: "renamed from: i")
                            val f = toggleCls.getDeclaredField("f18222i")
                            f.isAccessible = true
                            (f.get(chain.thisObject) as? ValueAnimator)?.end()
                            api.log(Log.INFO, TAG, "[ZOOM] ended z3.x.Mj field f18222i")
                        }
                        return result
                    }
                })
            api.log(Log.DEBUG, TAG, "[ZOOM] neutralized z3.x.Mj → field f18222i")
        }

        // Nj(float,int) → field "f18223j" — main zoom-ratio toggle animator
        val nj = toggleCls.declaredMethods.firstOrNull {
            it.name == "Nj" && it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == Float::class.javaPrimitiveType &&
                it.parameterTypes[1] == Int::class.javaPrimitiveType
        }
        if (nj != null) {
            api.hook(nj).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(object : Hooker {
                    override fun intercept(chain: Chain): Any? {
                        val result = chain.proceed()
                        runCatching {
                            // Field name is f18223j (JADX: "renamed from: j")
                            val f = toggleCls.getDeclaredField("f18223j")
                            f.isAccessible = true
                            (f.get(chain.thisObject) as? ValueAnimator)?.end()
                            api.log(Log.INFO, TAG, "[ZOOM] ended z3.x.Nj field f18223j")
                        }
                        return result
                    }
                })
            api.log(Log.DEBUG, TAG, "[ZOOM] neutralized z3.x.Nj → field f18223j")
        }
    }

    /**
     * Directly overwrite all Range<Float> fields on the ZoomManager instance.
     * e0() reads this.f8534h and this.f8535i by field access, bypassing getters.
     * This forces the internal clamping logic to use our extended range.
     */
    private fun patchRangeFields(self: Any, api: XposedInterface) {
        val mode = runCatching {
            self.javaClass.getField("c").getInt(self)
        }.getOrDefault(-1)
        currentMode.set(mode)
        val forceMax = getForceMax(api, mode)
        if (forceMax <= 0f) return

        runCatching {
            // Walk up the entire class hierarchy to find ALL Range fields
            // (declaredFields only returns fields declared in THAT class, not parents)
            var cls: Class<*>? = self.javaClass
            while (cls != null && cls != Any::class.java) {
                cls.declaredFields
                    .filter { Range::class.java.isAssignableFrom(it.type) }
                    .forEach { field ->
                        field.isAccessible = true
                        val current = field.get(self) as? Range<Float> ?: return@forEach
                        if (current.upper < forceMax) {
                            val patched = Range.create(current.lower, forceMax)
                            field.set(self, patched)
                            android.util.Log.e(TAG, "[FORCE_ZOOM] patched ${cls!!.simpleName}.${field.name}: ${current.upper} → $forceMax")
                        }
                    }
                cls = cls.superclass
            }
        }.onFailure { e ->
            android.util.Log.e(TAG, "[FORCE_ZOOM] patchRangeFields failed: ${e.message}")
        }
    }

    private fun getForceMax(api: XposedInterface, mode: Int = -1): Float {
        return runCatching {
            val prefs = api.getRemotePreferences(PREFS_NAME) ?: return@runCatching 0f
            val photoMax = prefs.getFloat(KEY_FORCE_MAX_PHOTO, DEFAULT_FORCE_MAX_PHOTO)
            val videoMax = prefs.getFloat(KEY_FORCE_MAX_VIDEO, DEFAULT_FORCE_MAX_VIDEO)
            // Both 0 means disabled
            if (photoMax == 0f && videoMax == 0f) return@runCatching 0f
            when (mode) {
                MODE_VIDEO -> if (videoMax > 0f) videoMax else photoMax
                MODE_PORTRAIT -> 0f  // never force max in portrait
                else -> if (photoMax > 0f) photoMax else videoMax
            }
        }.getOrDefault(0f)
    }

    private fun startSmoother(
        s: Smoother,
        self: Any,
        setterMethod: java.lang.reflect.Method,
        api: XposedInterface,
    ) {
        val prefs = runCatching { api.getRemotePreferences(PREFS_NAME) }.getOrNull()
        val durationMs = runCatching {
            (prefs?.getFloat(KEY_DURATION, DEFAULT_DURATION_MS) ?: DEFAULT_DURATION_MS)
                .coerceIn(50f, 800f)
        }.getOrDefault(DEFAULT_DURATION_MS)

        val tau = durationMs * 0.6f

        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = 60_000L
            addUpdateListener { a ->
                val now = SystemClock.uptimeMillis()
                val dt = if (s.lastFrameTime == 0L) 16L else (now - s.lastFrameTime).coerceIn(1L, 100L)
                s.lastFrameTime = now

                val alpha = (1f - exp(-dt / tau).toFloat()).coerceIn(0.01f, 1f)
                s.current += (s.target - s.current) * alpha

                if (abs(s.target - s.current) < EPSILON) {
                    s.current = s.target
                    applyValue(s, self, setterMethod)
                    a.cancel()
                } else {
                    applyValue(s, self, setterMethod)
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    s.animator = null
                    s.lastFrameTime = 0L
                    if (abs(s.target - s.current) >= EPSILON) {
                        startSmoother(s, self, setterMethod, api)
                    }
                }
                override fun onAnimationCancel(animation: Animator) {
                    s.animator = null
                    s.lastFrameTime = 0L
                }
            })
        }
        s.animator = animator
        animator.start()
    }

    private fun applyValue(s: Smoother, self: Any, setterMethod: java.lang.reflect.Method) {
        inCallback.add(self)
        try {
            runCatching { setterMethod.invoke(self, s.current, s.action) }
        } finally {
            inCallback.remove(self)
        }
    }
}