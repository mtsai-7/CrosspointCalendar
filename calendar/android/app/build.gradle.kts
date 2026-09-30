plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.mtsai7.xcal"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.mtsai7.xcal"
        // Android 12+: runtime BLUETOOTH_ADVERTISE / BLUETOOTH_CONNECT permissions.
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    // Built-in Kotlin takes its jvmTarget from targetCompatibility.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
