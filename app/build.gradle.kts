plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.lubabs770.motoguard"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.lubabs770.motoguard"
        minSdk = 28          // Moto G7 = Android 9 / API 28
        targetSdk = 34
        versionCode = 4
        versionName = "0.4.0-boot-tailscale"
    }

    val ksFile = System.getenv("KEYSTORE_FILE")

    signingConfigs {
        if (ksFile != null) {
            create("release") {
                storeFile = file(ksFile)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
                storeType = "PKCS12"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Stable release key from CI secrets when present (so `adb install -r`
            // works across builds); falls back to the ephemeral debug key locally
            // / on PRs where the keystore secret isn't available.
            signingConfig = if (ksFile != null)
                signingConfigs.getByName("release")
            else
                signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // Intentionally no AppCompat / androidx — plain android.* keeps the APK tiny
    // and the build dependency-free.
}
