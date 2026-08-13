plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.yukarlo.unlockmymac.wear"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        // Deliberately the same applicationId as the phone app. The Wear Data Layer only routes
        // between apps that share a package name and signature, and that link is how the watch
        // hands its public key to the phone for enrolment.
        applicationId = "com.yukarlo.unlockmymac"
        // Matches the phone: BLUETOOTH_ADVERTISE and BLUETOOTH_CONNECT are API 31+. A watch still
        // on Wear OS 3 (API 30) would need the legacy permission path instead.
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Debug-signed so a release build can be sideloaded for measurement. Compose in a
            // debug build carries composition-tracking instrumentation that a watch CPU feels
            // acutely, so a like-for-like comparison needs an installable release APK.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.wear.compose.material)
    implementation(libs.androidx.wear.compose.foundation)

    // Data Layer: carries the watch's public key to the phone so it can vouch for it.
    implementation(libs.play.services.wearable)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
