plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.a2uishop"
    // A2UI requires compileSdk 37.1.
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.a2uishop"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        // The fake agent backend, reached through `adb reverse tcp:8000 tcp:8000`.
        // Apps targeting SDK 37 can't reach the local network (e.g. 10.0.2.2) without the
        // ACCESS_LOCAL_NETWORK runtime permission. Loopback through adb reverse needs no permission.
        buildConfigField("String", "AGENT_BASE_URL", "\"http://localhost:8000\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.material3.a2ui)

    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    // A2UI doesn't bundle an image loader. We plug Coil into the catalog's Image component.
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    debugImplementation(libs.compose.ui.tooling)
}
