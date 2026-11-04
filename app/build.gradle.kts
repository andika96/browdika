plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val browdikaKeystorePath: String? = System.getenv("BROWDIKA_KEYSTORE_PATH")

android {
    namespace = "com.browdika.browser"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.browdika.browser"
        minSdk = 26
        targetSdk = 35
        versionCode = 9
        versionName = "1.8"
    }

    signingConfigs {
        if (browdikaKeystorePath != null) {
            create("release") {
                storeFile = file(browdikaKeystorePath)
                storePassword = System.getenv("BROWDIKA_STORE_PASSWORD")
                keyAlias = System.getenv("BROWDIKA_KEY_ALIAS")
                keyPassword = System.getenv("BROWDIKA_KEY_PASSWORD")
                storeType = "PKCS12"
            }
        }
    }

    buildTypes {
        debug {
            if (browdikaKeystorePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        release {
            if (browdikaKeystorePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.webkit:webkit:1.12.0")
}
