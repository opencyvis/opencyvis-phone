plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "ai.opencyvis"
    compileSdk = 36

    // Platform signing: only enable if platform-key/platform.jks exists.
    // Without it, builds use the default debug keystore.
    val platformKeyFile = file("../platform-key/platform.jks")
    if (platformKeyFile.exists()) {
        signingConfigs {
            create("platform") {
                storeFile = platformKeyFile
                storePassword = System.getenv("PLATFORM_STORE_PASSWORD") ?: "android"
                keyAlias = System.getenv("PLATFORM_KEY_ALIAS") ?: "platform"
                keyPassword = System.getenv("PLATFORM_KEY_PASSWORD") ?: "android"
            }
        }
    }

    defaultConfig {
        applicationId = "ai.opencyvis"
        minSdk = 30  // Android 11+ for SurfaceControl.screenshot()
        targetSdk = 36
        versionCode = 100
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            if (signingConfigs.findByName("platform") != null) {
                signingConfig = signingConfigs.getByName("platform")
            }
        }
        release {
            isMinifyEnabled = false
            if (signingConfigs.findByName("platform") != null) {
                signingConfig = signingConfigs.getByName("platform")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    buildFeatures {
        viewBinding = true
    }

    androidResources {
        noCompress += listOf("onnx", "model")
    }
}

dependencies {
    // Hidden APIs accessed via reflection at runtime — no compile-time stubs needed

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // On-device streaming ASR. Sherpa 8.5.1's JNI library is linked against
    // ONNX Runtime 1.24.3; exclude the stale transitive lib-onnx package.
    implementation("com.bihe0832.android:lib-sherpa-onnx:8.5.1") {
        exclude(group = "com.bihe0832.android", module = "lib-onnx")
    }
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.24.3")

    // Lifecycle (process-wide foreground/background detection for OverlayService)
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.lifecycle:lifecycle-common-java8:2.8.7")

    // OkHttp for LLM API calls
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // JSON parsing
    implementation("org.json:json:20240303")

    // Room
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.14.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
