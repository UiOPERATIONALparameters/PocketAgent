plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt.android)
}

android {
    namespace = "com.pocketagent"
    compileSdk = 35

    defaultConfig {
        // v6: Restored applicationId to com.pocketagent — no longer hijacking com.termux.
        // The app now talks to the user's real Termux via HTTP daemon on localhost:8765.
        // This means the app coexists with real Termux, and updates flow through `pkg upgrade`.
        applicationId = "com.pocketagent"
        minSdk = 26
        // v6: Kept targetSdk 28 — required for executing binaries from app-private
        // storage (W^X exemption). No longer strictly needed since we don't run binaries
        // directly anymore, but kept for compatibility with any future features.
        targetSdk = 28
        versionCode = 60
        versionName = "6.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Sign with debug for v0.1.0 simplicity; CI can override with release keystore
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Disable lint fatal checks — we intentionally use targetSdk 28 for W^X exemption
    // (Android 10+ blocks executing binaries from app-private storage at targetSdk 29+)
    // Lint warns about ExpiredTargetSdkVersion, which we acknowledge.
    lint {
        abortOnError = false
        checkReleaseBuilds = false
        disable += setOf("ExpiredTargetSdkVersion", "NewApi")
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/DEPENDENCIES"
        }
    }
}

dependencies {
    // Compose
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.runtime)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Lifecycle
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Networking
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.okhttp.logging)

    // Tar extraction (for Linux rootfs)
    implementation("org.apache.commons:commons-compress:1.26.1")

    // Serialization
    implementation(libs.kotlinx.serialization.json)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Security (encrypted prefs for API keys)
    implementation(libs.androidx.security.crypto)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // Splash screen
    implementation(libs.androidx.core.splashscreen)
}
