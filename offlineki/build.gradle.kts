plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "de.offline.ki"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.offline.ki"
        minSdk = 31          // On-Device-Spracherkennung ab Android 12
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        ndk {
            // Samsung Galaxy S25 (Snapdragon 8 Elite) ist 64-Bit-ARM
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                // Auch die Debug-APK nativ optimiert bauen, sonst ist die KI sehr langsam.
                arguments += "-DCMAKE_BUILD_TYPE=Release"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Gleicher fester Debug-Schlüssel wie die andere App -> Updates installierbar.
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("app/debug.keystore")
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
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
