plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.rokidmirror.receiver.camera"
    compileSdk = 35
    defaultConfig { minSdk = 31 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions.unitTests.isReturnDefaultValues = true
}

dependencies {
    implementation(project(":telemetry"))
    implementation(libs.kotlinx.coroutines.android)
    // On-device face detection with the model bundled in the APK: no network, no Play services.
    implementation(libs.mlkit.face.detection)
    testImplementation(libs.junit)
}
