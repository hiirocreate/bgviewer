plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.hono.bgviewer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hono.bgviewer"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    // BGRecorderと全く同じ鍵（keystoreファイル・alias・パスワード）で署名する。
    // これにより2つのアプリの証明書が完全一致し、BGRecorder側で定義した
    // signature権限（ACCESS_RECORDINGS）がこのアプリにだけ自動的に付与される。
    signingConfigs {
        create("release") {
            storeFile = file("../keystore/release.keystore")
            storePassword = "bgrecorder123"
            keyAlias = "bgrecorder"
            keyPassword = "bgrecorder123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.1")
}
