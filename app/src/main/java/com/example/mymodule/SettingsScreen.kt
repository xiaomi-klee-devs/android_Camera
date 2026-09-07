package com.example.mymodule

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    // Read/write through the LSPosed framework's remote preferences — the store that
    // hooked processes read via getRemotePreferences(). Falls back to local prefs
    // until the framework service handle arrives (usually within ms of app start).
    var remotePrefs by remember { mutableStateOf(RemotePrefs.prefs) }
    LaunchedEffect(Unit) {
        RemotePrefs.observe { remotePrefs = it }
    }
    val effectivePrefs = remotePrefs ?: remember {
        context.getSharedPreferences("module_config", Context.MODE_PRIVATE)
    }

    // ── Master ──
    var hookEnabled by remember(effectivePrefs) { mutableStateOf(effectivePrefs.getBoolean("hook_enabled", true)) }

    // ── Camera Zoom ──
    var cameraZoomSmoothEnabled by remember(effectivePrefs) { mutableStateOf(effectivePrefs.getBoolean("camera_zoom_smooth_enabled", true)) }
    var cameraZoomDuration by remember(effectivePrefs) { mutableFloatStateOf(effectivePrefs.getFloat("camera_zoom_smooth_duration", 200f)) }
    var cameraZoomForceMaxEnabled by remember(effectivePrefs) { mutableStateOf(effectivePrefs.getFloat("camera_zoom_force_max_photo", 0f) > 0f || effectivePrefs.getFloat("camera_zoom_force_max_video", 0f) > 0f) }
    var cameraZoomForceMaxPhoto by remember(effectivePrefs) { mutableFloatStateOf(effectivePrefs.getFloat("camera_zoom_force_max_photo", 20f)) }
    var cameraZoomForceMaxVideo by remember(effectivePrefs) { mutableFloatStateOf(effectivePrefs.getFloat("camera_zoom_force_max_video", 30f)) }

    // ── Zoom Presets ──
    val defaultPresets = listOf(0.6f, 1.0f, 1.5f, 2.0f, 3.0f, 5.0f, 10.0f, 15.0f)
    var zoomPresetsEnabled by remember(effectivePrefs) {
        mutableStateOf(effectivePrefs.getBoolean("zoom_presets_enabled", false))
    }
    var activeZoomPresets by remember(effectivePrefs) {
        val saved = effectivePrefs.getString("active_zoom_presets", null)
        mutableStateOf(
            if (saved != null) {
                saved.split(",").mapNotNull { it.trim().toFloatOrNull() }.filter { it > 0f }
            } else {
                defaultPresets
            }
        )
    }

    var showRefreshDialog by remember { mutableStateOf(false) }

    fun saveFloat(key: String, value: Float) {
        effectivePrefs.edit().putFloat(key, value).commit()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = "Camera Zoom Mod",
                subtitle = "v1.0",
                actions = {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .clickable { showRefreshDialog = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "↻",
                            fontSize = 20.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                        )
                    }
                },
            )
        }
    ) { innerPadding: PaddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding()),
            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 12.dp),
        ) {
            // ═══════════════ General ═══════════════
            item {
                SmallTitle("General")
                Card {
                    SwitchPreference(
                        title = "Enable hooks",
                        summary = "Master switch for all hooks",
                        checked = hookEnabled,
                        onCheckedChange = { checked ->
                            hookEnabled = checked
                            effectivePrefs.edit().putBoolean("hook_enabled", checked).commit()
                        },
                    )
                }
            }

            // ═══════════════ Camera Zoom ═══════════════
            item {
                SmallTitle("Camera Zoom")
                Card {
                    SwitchPreference(
                        title = "Smooth zoom easing",
                        summary = "Ease-out curve instead of instant snappy zoom",
                        checked = cameraZoomSmoothEnabled,
                        onCheckedChange = { checked ->
                            cameraZoomSmoothEnabled = checked
                            effectivePrefs.edit().putBoolean("camera_zoom_smooth_enabled", checked).commit()
                        },
                    )
                    SliderPreference(
                        value = cameraZoomDuration,
                        onValueChange = { cameraZoomDuration = it },
                        title = "Easing duration",
                        summary = "Higher = smoother but more delayed zoom",
                        valueText = "%.0f ms".format(cameraZoomDuration),
                        valueRange = 50f..500f,
                        onValueChangeFinished = { saveFloat("camera_zoom_smooth_duration", cameraZoomDuration) },
                        endActions = {
                            ResetButton(
                                enabled = cameraZoomDuration != 200f,
                                onReset = {
                                    cameraZoomDuration = 200f
                                    effectivePrefs.edit().putFloat("camera_zoom_smooth_duration", 200f).commit()
                                },
                            )
                        },
                    )
                }
            }

            // ═══════════════ Force Zoom ═══════════════
            item {
                SmallTitle("Force Zoom Max")
                Card {
                    SwitchPreference(
                        title = "Override max zoom",
                        summary = "Raise the stock zoom cap (default 6x)",
                        checked = cameraZoomForceMaxEnabled,
                        onCheckedChange = { checked ->
                            cameraZoomForceMaxEnabled = checked
                            if (!checked) {
                                // Set to 0 = disabled
                                effectivePrefs.edit().putFloat("camera_zoom_force_max_photo", 0f).putFloat("camera_zoom_force_max_video", 0f).commit()
                            } else {
                                // Set to defaults
                                effectivePrefs.edit().putFloat("camera_zoom_force_max_photo", cameraZoomForceMaxPhoto).putFloat("camera_zoom_force_max_video", cameraZoomForceMaxVideo).commit()
                            }
                        },
                    )
                    if (cameraZoomForceMaxEnabled) {
                        SliderPreference(
                            value = cameraZoomForceMaxPhoto.coerceAtLeast(6f),
                            onValueChange = { cameraZoomForceMaxPhoto = it },
                            title = "Photo max zoom",
                            summary = "Max zoom in photo mode (above 20x disables OIS)",
                            valueText = "%.0fx".format(cameraZoomForceMaxPhoto),
                            valueRange = 6f..30f,
                            onValueChangeFinished = {
                                effectivePrefs.edit().putFloat("camera_zoom_force_max_photo", cameraZoomForceMaxPhoto).commit()
                            },
                            endActions = {
                                ResetButton(
                                    enabled = cameraZoomForceMaxPhoto != 20f,
                                    onReset = {
                                        cameraZoomForceMaxPhoto = 20f
                                        effectivePrefs.edit().putFloat("camera_zoom_force_max_photo", 20f).commit()
                                    },
                                )
                            },
                        )
                        SliderPreference(
                            value = cameraZoomForceMaxVideo.coerceAtLeast(6f),
                            onValueChange = { cameraZoomForceMaxVideo = it },
                            title = "Video max zoom",
                            summary = "Max zoom in video mode",
                            valueText = "%.0fx".format(cameraZoomForceMaxVideo),
                            valueRange = 6f..30f,
                            onValueChangeFinished = {
                                effectivePrefs.edit().putFloat("camera_zoom_force_max_video", cameraZoomForceMaxVideo).commit()
                            },
                            endActions = {
                                ResetButton(
                                    enabled = cameraZoomForceMaxVideo != 30f,
                                    onReset = {
                                        cameraZoomForceMaxVideo = 30f
                                        effectivePrefs.edit().putFloat("camera_zoom_force_max_video", 30f).commit()
                                    },
                                )
                            },
                        )
                    }
                }
            }

            // ═══════════════ Zoom Presets ═══════════════
            item {
                SmallTitle("Zoom Presets")
                Card {
                    SwitchPreference(
                        title = "Custom zoom buttons",
                        summary = "Replace stock zoom buttons with custom presets (photo & video only)",
                        checked = zoomPresetsEnabled,
                        onCheckedChange = { checked ->
                            zoomPresetsEnabled = checked
                            effectivePrefs.edit().putBoolean("zoom_presets_enabled", checked).commit()
                        },
                    )
                    if (zoomPresetsEnabled) {
                        defaultPresets.forEach { preset ->
                            val isActive = activeZoomPresets.contains(preset)
                            val label = if (preset == 1.0f) "1x (wide)" else if (preset == 0.6f) "0.6x (ultrawide)" else "${preset}x"
                            SwitchPreference(
                                title = label,
                                summary = when {
                                    preset <= 0.6f -> "Ultra-wide lens"
                                    preset <= 1.0f -> "Main wide lens"
                                    preset <= 2.0f -> "Short tele"
                                    preset <= 5.0f -> "Mid tele"
                                    else -> "Periscope telephoto"
                                },
                                checked = isActive,
                                onCheckedChange = { checked ->
                                    val current = activeZoomPresets.toMutableList()
                                    if (checked) {
                                        if (!current.contains(preset)) current.add(preset)
                                    } else {
                                        current.remove(preset)
                                    }
                                    activeZoomPresets = current.sorted()
                                    effectivePrefs.edit().putString(
                                        "active_zoom_presets",
                                        activeZoomPresets.joinToString(",") { "%.1f".format(it) },
                                    ).commit()
                                },
                            )
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (showRefreshDialog) {
        Dialog(onDismissRequest = { showRefreshDialog = false }) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { showRefreshDialog = false },
                contentAlignment = Alignment.BottomCenter,
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                ) {
                    Column(Modifier.padding(vertical = 20.dp)) {
                        RefreshOption(
                            label = "Camera",
                            packageName = "com.android.camera",
                            context = context,
                            onDone = { showRefreshDialog = false },
                        )
                        // Cancel button
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MiuixTheme.colorScheme.surfaceVariant)
                                .clickable { showRefreshDialog = false },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "Cancel",
                                modifier = Modifier.padding(vertical = 12.dp),
                                color = Color(0xFFE8453C),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Force-stops a package via root so the hooked process restarts with fresh code. */
private fun refreshPackage(packageName: String, context: Context) {
    Thread {
        val result = try {
            var proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "am force-stop $packageName"))
            var success = proc.waitFor() == 0
            if (!success) {
                proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "killall $packageName"))
                success = proc.waitFor() == 0
            }
            success
        } catch (_: Throwable) {
            false
        }
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(
                context,
                if (result) "Restarted $packageName" else "Restart failed (no root?)",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }.start()
}

@Composable
private fun RefreshOption(
    label: String,
    packageName: String,
    context: Context,
    onDone: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                onDone()
                refreshPackage(packageName, context)
            }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label)
    }
}

/** Small circular reset-to-default affordance for sliders. */
@Composable
private fun ResetButton(
    enabled: Boolean,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val contentColor = if (enabled) {
        MiuixTheme.colorScheme.onSurfaceVariantActions
    } else {
        MiuixTheme.colorScheme.disabledOnSecondaryVariant
    }
    Box(
        modifier = modifier
            .size(32.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onReset),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "↺",
            fontSize = 18.sp,
            color = contentColor,
        )
    }
}