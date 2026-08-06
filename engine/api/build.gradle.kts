plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.nash.engine.api"
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
    // api, not implementation: engine/api's own contracts expose core/model
    // types (TrackId, TrackedBox, FrameMetadata) in their signatures, so every
    // consumer of this module needs them on its compile classpath.
    api(project(":core:model"))
    implementation(project(":core:common"))

    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.coroutines.core)

    testImplementation(libs.junit)
}