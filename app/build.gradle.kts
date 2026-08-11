plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.sam.motoguard"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.sam.motoguard"
        minSdk = 28          // Moto G7 = Android 9 / API 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Debug-signed for now so `adb install` works with zero keystore setup.
            // Swap to a real release keystore before you trust it in the field.
            signingConfig = signingConfigs.getByName("debug")
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
