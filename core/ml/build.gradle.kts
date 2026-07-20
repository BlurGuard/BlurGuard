plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.hilt.android)        // ← add
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.nash.core.ml"
    compileSdk = 37

    defaultConfig {
        minSdk = 27
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }



    // Never compress .tflite models inside the APK — the runtime memory-maps them.
    androidResources {
        noCompress += "tflite"
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))

    // ImageProxy only — depending on the camera-core LIBRARY is allowed;
    // depending on the :core:camera MODULE is not (dependency rules).
    implementation(libs.camera.core)

    // BlazeFace via MediaPipe Tasks (TFLite/LiteRT under the hood, fully on-device).
    implementation(libs.mediapipe.tasks.vision)
    implementation(libs.litert)
    implementation(libs.coroutines.android)
    implementation(libs.hilt.android)       // ← add
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}