plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.example.klipa.core.media"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Media3 types touch android.util.Pair in static initializers; let the stubs return defaults.
    testOptions.unitTests.isReturnDefaultValues = true
}

dependencies {
    api(project(":core:model"))
    implementation(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.ui.graphics)
    api(libs.androidx.media3.transformer)
    api(libs.androidx.media3.effect)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(testFixtures(project(":core:model")))
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.junit4)
}
