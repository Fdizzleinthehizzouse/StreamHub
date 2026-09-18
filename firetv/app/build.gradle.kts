plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.felix.streamhub"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.felix.streamhub"
        // Fire OS 7 is API 28; Fire OS 8 and the TV sets are newer. 23 keeps
        // even older Fire TV hardware in scope at no real cost.
        minSdk = 23
        // Deliberately 28. From API 29 Android forbids background apps from
        // starting activities - and once we hand off to Netflix we are the
        // background app, so every launch after the first would be dropped
        // silently. The restriction is keyed on targetSdk, and a sideloaded
        // personal app has no store requirement to target higher.
        targetSdk = 28
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
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
        viewBinding = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Small, stable, single-artifact HTTP server for the PC/phone control link.
    implementation("org.nanohttpd:nanohttpd:2.3.1")
}
