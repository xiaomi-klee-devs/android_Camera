plugins {
    alias(libs.plugins.android.application)
    // AGP 9 has built-in Kotlin support; the compose compiler plugin wires
    // the Compose compiler into that built-in Kotlin compilation.
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.example.mymodule"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.camerazoommod"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Compile Kotlin to JVM 17 bytecode (running on JDK 25 is fine).
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // --- Xposed framework (provided by LSPosed at runtime, do NOT bundle) ---
    compileOnly(libs.libxposed.api)
    compileOnly(libs.androidx.annotation)

    // --- Xposed service: lets the module app write settings into the LSPosed
    //     database that hooked processes read via getRemotePreferences() ---
    implementation(libs.libxposed.service)

    // --- App host / Compose runtime ---
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.runtime)

    // --- MiuiX: Xiaomi-style UI ---
    implementation(libs.miuix.ui)
    implementation(libs.miuix.preference)
}