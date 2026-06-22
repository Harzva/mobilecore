plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val debugSignedRelease = providers
    .gradleProperty("mobilecore.debugSignedRelease")
    .map { it.toBoolean() }
    .getOrElse(false)

android {
    namespace = "com.mobilecore.app"
    compileSdk = 34
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.mobilecore.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "0.1.2-rc1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            if (debugSignedRelease) {
                signingConfig = signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.18.0")
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
