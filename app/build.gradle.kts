import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Google-Maps-API-Schlüssel: aus local.properties (MAPS_API_KEY=...) oder der
// Umgebungsvariable MAPS_API_KEY (z. B. GitHub-Secret im CI).
val mapsApiKey: String = Properties().run {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
    getProperty("MAPS_API_KEY")
} ?: System.getenv("MAPS_API_KEY") ?: ""

android {
    namespace = "de.standort.tracker"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.standort.tracker"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "1.3"
        manifestPlaceholders["MAPS_API_KEY"] = mapsApiKey
        buildConfigField("boolean", "HAS_MAPS_KEY", (mapsApiKey.isNotBlank()).toString())
    }

    // Fester Signaturschlüssel im Repository, damit jede neue APK (auch aus GitHub
    // Actions) als Update über die installierte Version installiert werden kann.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
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
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // OpenStreetMap-Karte, kein API-Schlüssel nötig
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    implementation("com.google.android.gms:play-services-maps:19.0.0")
}
