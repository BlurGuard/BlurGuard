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

    // Identity policy only. No LiteRT/TFLite and no MediaPipe here: every model
    // runtime dependency belongs to engine/ml, and this module must not be able
    // to reach one by accident (review fix 15). It also does not depend on
    // engine/ml — it reaches models through FaceRecognizer.
    implementation(libs.camera.core)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
}