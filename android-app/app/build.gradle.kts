plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val debugSignedRelease = providers
    .gradleProperty("mobilecore.debugSignedRelease")
    .map { it.toBoolean() }
    .getOrElse(false)

val releaseStoreFile = providers.gradleProperty("mobilecore.releaseStoreFile")
    .orElse(providers.environmentVariable("TUIMA_RELEASE_STORE_FILE"))
    .orNull
val releaseStorePassword = providers.gradleProperty("mobilecore.releaseStorePassword")
    .orElse(providers.environmentVariable("TUIMA_RELEASE_STORE_PASSWORD"))
    .orNull
val releaseKeyAlias = providers.gradleProperty("mobilecore.releaseKeyAlias")
    .orElse(providers.environmentVariable("TUIMA_RELEASE_KEY_ALIAS"))
    .orNull
val releaseKeyPassword = providers.gradleProperty("mobilecore.releaseKeyPassword")
    .orElse(providers.environmentVariable("TUIMA_RELEASE_KEY_PASSWORD"))
    .orNull
val releaseSigningConfigured = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

android {
    namespace = "com.mobilecore.app"
    compileSdk = 35
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.mobilecore.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "0.1.3-rc2"

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

    signingConfigs {
        if (releaseSigningConfigured) {
            create("releaseUpload") {
                storeFile = file(requireNotNull(releaseStoreFile))
                storePassword = requireNotNull(releaseStorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("releaseUpload")
            } else if (debugSignedRelease) {
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
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
