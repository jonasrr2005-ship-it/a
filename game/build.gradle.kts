plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "de.palmera.game"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.palmera.game"
        minSdk = 26
        targetSdk = 35
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
    kotlinOptions {
        jvmTarget = "17"
    }
    androidResources {
        // three.js-Module unkomprimiert lassen, damit die WebView sie schnell lädt
        noCompress += listOf("js", "html", "css")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    // Liefert die Spieldateien über https://appassets.androidplatform.net aus (ES-Module brauchen echte URLs)
    implementation("androidx.webkit:webkit:1.12.1")
}
