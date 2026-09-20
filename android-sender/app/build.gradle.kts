plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.rokidmirror.sender"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.rokidmirror.sender"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Release signing is configured locally (keystore.properties, git-ignored); see docs/security.md.
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions.unitTests.isReturnDefaultValues = true
    packaging { resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1") }
}

dependencies {
    implementation("com.rokidmirror:mirror-protocol")
    implementation(project(":capture"))
    implementation(project(":encoder"))
    implementation(project(":transport"))
    implementation(project(":discovery"))
    implementation(project(":control"))
    implementation(project(":telemetry"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
