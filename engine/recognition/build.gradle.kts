plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.nash.engine.recognition"
    compileSdk = 37

    defaultConfig {
        minSdk = 27
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":engine:api"))
    implementation(project(":core:model"))
    implementation(project(":core:common"))

    // Identity policy only. No LiteRT/TFLite, no MediaPipe, and no dependency
    // on engine/ml: models are reached through FaceRecognizer (review fix 15).
    // camera-core and lifecycle-runtime were also dropped — nothing here is
    // frame-type or lifecycle aware.
    implementation(libs.coroutines.core)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
