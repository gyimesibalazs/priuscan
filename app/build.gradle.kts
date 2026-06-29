plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "hu.codingo.priuscan"
    compileSdk = 35

    defaultConfig {
        applicationId = "hu.codingo.priuscan"
        minSdk = 26          // fejegysegek: Android 9-12, boven belefer
        targetSdk = 35
        versionCode = 13     // bump on every GitHub release; the in-app updater compares this
        versionName = "2.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures { compose = true; buildConfig = true }

    // .bgf are already zstd-compressed -> don't let aapt recompress (keeps openFd() usable for
    // size checks, and avoids double work)
    androidResources { noCompress += "bgf" }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // the h3-java jar ships desktop native libs as resources (darwin/.dylib, windows/.dll, linux,
    // freebsd) — useless on Android (we load the .so from jniLibs). Drop them to slim the APK.
    packaging {
        resources {
            excludes += setOf("**/*.dylib", "**/*.dll", "darwin-*/**", "windows-*/**",
                              "linux-*/**", "freebsd-*/**")
        }
    }
}

dependencies {
    implementation("com.github.mik3y:usb-serial-for-android:3.8.1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
    // belterület geofence: H3 cell lookup (native .so in jniLibs/{arm64-v8a,armeabi-v7a})
    // + zstd to decode the .bgf cell blob
    implementation("com.uber:h3:4.1.1")
    implementation("com.github.luben:zstd-jni:1.5.6-3@aar")
}
