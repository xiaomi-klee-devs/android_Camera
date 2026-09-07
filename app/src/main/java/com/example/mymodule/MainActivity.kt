package com.example.mymodule

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

/**
 * The module's settings screen — a normal Android Activity running
 * Compose + MiuiX inside the module APK. It is completely separate from
 * the Xposed hook code ([HookEntry]).
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                SettingsScreen()
            }
        }
    }
}