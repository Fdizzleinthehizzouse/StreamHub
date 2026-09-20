import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing. The keystore is deliberately outside the repo (and .jks is
// git-ignored): if it ever leaked, anyone could publish an "update" to
// StreamHub. keystore.properties says where it is - see INSTALL.md.
//
// Losing the keystore is unrecoverable. Android identifies an app by its
// signature, so an APK signed with a different key will not install over an
// installed StreamHub; the only way back is uninstall, which erases the
// watchlist. That is why the build refuses to produce an unsigned release
// rather than handing over an APK that looks fine and installs nowhere.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val hasReleaseKey = keystorePropsFile.exists()

gradle.taskGraph.whenReady {
    val wantsRelease = allTasks.any {
        it.name == "assembleRelease" || it.name == "bundleRelease" || it.name == "installRelease"
    }
    if (wantsRelease && !hasReleaseKey) {
        throw GradleException(
            "No signing key: firetv/keystore.properties is missing, so a release " +
            "APK would come out unsigned and no Fire TV would install it. " +
            "Create the key and that file as described in INSTALL.md " +
            "(\"Rebuilding the app yourself\"), then run this again."
        )
    }
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

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                // v1 as well as v2/v3: Fire OS 7 is API 28, which verifies v2,
                // but the older Fire TV hardware minSdk 23 keeps in scope
                // only knows v1.
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
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

    lint {
        // targetSdk 28 is deliberate (see defaultConfig above), and lint fails
        // the release build over it by default. ExpiredTargetSdkVersion is a
        // Google Play publishing requirement; StreamHub is sideloaded onto one
        // household's Fire TV and is never submitted to a store. Raising the
        // target to silence this would break launching a service after the
        // first one of the session, which is the whole product.
        disable += "ExpiredTargetSdkVersion"
    }

    // The JVM tests start the real ControlServer. Android calls it makes along
    // the way (Handler, PackageManager) just return defaults off-device.
    testOptions {
        unitTests.isReturnDefaultValues = true
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

    testImplementation("junit:junit:4.13.2")
    // android.jar's org.json is a stub off-device; this is the real thing.
    testImplementation("org.json:json:20240303")
}
