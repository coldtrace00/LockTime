plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Постійний ключ підпису задається через змінні середовища (у GitHub — через секрети).
// Без нього APK підписується тимчасовим debug-ключем.
val keystorePath: String? = System.getenv("KEYSTORE_FILE")
val keystorePassword: String? = System.getenv("KEYSTORE_PASSWORD")
// Номер збірки GitHub Actions — щоб кожна нова версія ставилася поверх старої.
val buildNumber: Int = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "io.github.timelock"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.timelock"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    signingConfigs {
        if (keystorePath != null && keystorePassword != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = keystorePassword
                keyAlias = "locktime"
                keyPassword = keystorePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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

// Жодних сторонніх бібліотек: лише Android SDK і Kotlin.
