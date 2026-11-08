plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.ocrclicker"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.ocrclicker"
        minSdk = 29            // Android 10+
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // OCR offline (modelo embutido no APK, sem internet)
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
