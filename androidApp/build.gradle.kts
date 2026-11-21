plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dogsc.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "io.github.halichina.dogsc"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0"
    }
    // Fixed signing key: CI decodes DOGSC_KEYSTORE_BASE64 into the path given by
    // DOGSC_KEYSTORE_PATH, so every build shares one certificate and updates can
    // install over the previous build. Without it (plain local dev), the default
    // debug key is used.
    val dogscKeystore = System.getenv("DOGSC_KEYSTORE_PATH")
    signingConfigs {
        if (dogscKeystore != null) {
            create("fixed") {
                storeFile = file(dogscKeystore)
                storePassword = System.getenv("DOGSC_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("DOGSC_KEY_ALIAS")
                keyPassword = System.getenv("DOGSC_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        debug {
            if (dogscKeystore != null) signingConfig = signingConfigs.getByName("fixed")
        }
        release {
            isMinifyEnabled = false
            if (dogscKeystore != null) signingConfig = signingConfigs.getByName("fixed")
        }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":ui"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.transformer)
    implementation(libs.media3.ui)
    implementation(platform("androidx.compose:compose-bom:2024.10.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.runtime:runtime")
}
