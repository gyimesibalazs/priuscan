// Drop-in replacement for the head unit's factory CAN service (QF_Canbus, package com.qf.vehicle).
// See canstub/README.md. Built separately from the main app: ./gradlew :canstub:assembleDebug
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "hu.codingo.canstub"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.qf.vehicle"     // MUST match: the ROM apps bind by this package name
        minSdk = 26
        targetSdk = 28                        // old target: no background-start / receiver limits
        versionCode = 1
        versionName = "1.0-priuscan"
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { checkReleaseBuilds = false; abortOnError = false }
}
