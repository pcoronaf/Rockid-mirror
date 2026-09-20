plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.rokidmirror.receiver"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.rokidmirror.receiver"
        // Rokid Glasses / YodaOS-Sprite = Android 12 (API 32). minSdk 31 tolerates a 12.0 build.
        minSdk = 31
        // Target the device's own API level so no newer-platform behaviour changes apply on the glasses.
        targetSdk = 32
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += listOf("arm64-v8a") } // Qualcomm AR1 is arm64-v8a; no native code today anyway
    }
    buildTypes {
        release { isMinifyEnabled = false }
        debug { applicationIdSuffix = ".debug" }
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { targetSdk = 32 }
}

dependencies {
    implementation("com.rokidmirror:mirror-protocol")
    implementation(project(":platform"))
    implementation(project(":decoder"))
    implementation(project(":transport"))
    implementation(project(":renderer"))
    implementation(project(":control"))
    implementation(project(":telemetry"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}
