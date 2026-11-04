plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// Bildgenerierung (stable-diffusion.cpp) als eigene native Bibliothek. Sie bringt
// ihre eigene ggml-Version mit und darf deshalb nicht mit llama.cpp in derselben
// .so landen.
android {
    namespace = "de.offline.ki.bild"
    compileSdk = 35

    defaultConfig {
        minSdk = 31
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                // Auch in Debug-Builds optimiert, sonst dauert ein Bild ewig.
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
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
