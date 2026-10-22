plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.manuubett.lindaplay"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.manuubett.lindaplay"
        minSdk = 23
        targetSdk = 28

        // GitHub Actions sets these on every build so each APK is newer
        // than the last. On your own computer they fall back to defaults.
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "0.1.0"
    }

    // One permanent key signs every release, so updates install over the
    // old app. The key itself lives in GitHub Secrets, never in the code.
    signingConfigs {
        val keystorePath = System.getenv("KEYSTORE_PATH")

        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    lint {
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
