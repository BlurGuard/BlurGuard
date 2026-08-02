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
    implementation(project(":core:model"))
    implementation(project(":core:common"))

    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.coroutines.core)

    testImplementation(libs.junit)
}
