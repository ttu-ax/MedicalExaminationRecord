import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.medicalrecord"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.medicalrecord"
        minSdk = 26
        targetSdk = 35
        versionCode = 11
        versionName = "0.5.6"
    }

    buildFeatures {
        compose = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.ui:ui:1.6.8")
    implementation("androidx.compose.foundation:foundation:1.6.8")
    implementation("androidx.compose.material3:material3:1.2.1")
}

// Keep release signing material out of source control. See keystore.properties.example.
val releaseSigningFile = rootProject.file("keystore.properties")
if (releaseSigningFile.isFile) {
    val releaseSigning = Properties().apply {
        releaseSigningFile.inputStream().use(::load)
    }
    android.signingConfigs.create("release") {
        storeFile = rootProject.file(requireNotNull(releaseSigning.getProperty("storeFile")))
        storePassword = requireNotNull(releaseSigning.getProperty("storePassword"))
        keyAlias = requireNotNull(releaseSigning.getProperty("keyAlias"))
        keyPassword = requireNotNull(releaseSigning.getProperty("keyPassword"))
    }
    android.buildTypes.getByName("release") {
        signingConfig = android.signingConfigs.getByName("release")
    }
}
