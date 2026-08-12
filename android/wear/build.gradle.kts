plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.yukarlo.unlockmymac.wear"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.yukarlo.unlockmymac.wear"
        // Matches the phone app so the runtime permission model is identical: BLUETOOTH_ADVERTISE
        // and BLUETOOTH_CONNECT are API 31+. A watch still on Wear OS 3 (API 30) would need the
        // legacy BLUETOOTH/BLUETOOTH_ADMIN plus location path instead.
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
}
