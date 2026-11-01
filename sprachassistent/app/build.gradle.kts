plugins {
    id("com.android.application")
}

android {
    namespace = "de.jonas.sprachassistent"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.jonas.sprachassistent"
        // Android 4.0.4 = API 15
        minSdk = 15
        // Bewusst niedrig: das Handy ist alt, neuere Regeln braucht es nicht
        targetSdk = 22
        versionCode = 1
        versionName = "1.0-schritt1"
        ndk {
            // Das Xperia tipo hat einen ARMv7-Prozessor - andere Varianten weglassen spart Platz
            abiFilters += listOf("armeabi-v7a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Mit dem Debug-Schluessel signieren, damit die APK ohne Extra-Schritte installierbar ist
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
        // prueft, ob versehentlich Funktionen neuer als Android 4.0.4 benutzt werden
        checkOnly += listOf("NewApi", "InlinedApi")
        textReport = true
        textOutput = file("stdout")
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    // Offline-Erkennung des Aktivierungsworts (CMU PocketSphinx, unterstuetzt ab API 14)
    implementation(files("libs/pocketsphinx-android.aar"))
    // Moderne Verschluesselung (TLS 1.2/1.3) fuer Android 4.0.4 - Version 2.5.2 laeuft ab API 9
    implementation("org.conscrypt:conscrypt-android:2.5.2")
}
