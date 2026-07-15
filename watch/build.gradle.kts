plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.wearbabymonitor"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.wearbabymonitor"
        minSdk = 30
        targetSdk = 35
        versionCode = 6
        versionName = "0.5.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("com.google.android.gms:play-services-wearable:20.0.1")
}
