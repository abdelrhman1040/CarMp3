plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.carplayer.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.carplayer.app"
        minSdk = 33
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
    }

    // Fixed debug key so every new build installs over the previous one
    // and keeps your folders / devices / settings.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
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
