plugins {
    alias(libs.plugins.android.library)
}

android {
    // Only the namespace is `.core`; the source packages stay `com.yukarlo.unlockmymac.*` so the
    // extraction moves files without rewriting a single import. The module has no resources, so
    // nothing depends on which R class this namespace generates.
    namespace = "com.yukarlo.unlockmymac.core"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 31
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    // `api`, not `implementation`: consumers handle the types these expose — DataStore-backed
    // flows, Context extensions — so they need them on their own compile classpath.
    api(libs.androidx.core.ktx)
    api(libs.androidx.datastore.preferences)
    // BleUnlockService is a LifecycleService, and it lives here so a phone and a watch share it.
    api(libs.androidx.lifecycle.service)

    testImplementation(libs.junit)
    // Real org.json for JVM unit tests; the android.jar stub throws "not mocked".
    testImplementation(libs.json)
}
