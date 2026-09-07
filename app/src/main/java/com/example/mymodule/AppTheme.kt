package com.example.mymodule

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * Wraps the whole app in a MiuiX theme.
 *
 * [ThemeController] follows the system dark/light mode and, on Xiaomi/Android 12+,
 * can generate a Monet dynamic color scheme.
 */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val controller = remember { ThemeController(ColorSchemeMode.System) }
    MiuixTheme(controller = controller, content = content)
}