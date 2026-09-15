plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.encoder"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.encoder"
        minSdk = 24
        targetSdk = 35
        versionCode = 4
        versionName = "1.0.3"

        buildConfigField("String", "GITHUB_OWNER", "\"Sergey125\"")
        buildConfigField("String", "GITHUB_REPO", "\"encoder-debag-app\"")
    }

    signingConfigs {
        // Один и тот же ключ на всех сборках (локально и в CI), иначе
        // Android отказывается ставить "обновление" поверх старой версии,
        // подписанной другим (случайным) debug-ключом.
        getByName("debug") {
            storeFile = file("../keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}