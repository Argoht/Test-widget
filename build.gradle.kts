plugins {
    id("com.android.application") version "8.11.1"
    id("org.jetbrains.kotlin.android") version "2.1.21"
}

android {
    namespace = "app.calwidget"
    compileSdk = 35
    defaultConfig {
        applicationId = "app.calwidget"
        minSdk = 31
        targetSdk = 35
        // each CI build must be installable over the previous one
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.$versionCode"
    }
    // shortcut: committed debug key so updates keep the same signature; use a private key before any public distribution
    signingConfigs { getByName("debug") { storeFile = file("debug.keystore") } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }
