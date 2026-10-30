plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ee.alfred.cell"
    compileSdk = 35

    lint {
        disable += "ObsoleteSdkInt"
    }

    defaultConfig {
        applicationId = "ee.alfred.cell"
        minSdk = 26
        targetSdk = 35
        versionCode = 13
        versionName = "1.12"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}