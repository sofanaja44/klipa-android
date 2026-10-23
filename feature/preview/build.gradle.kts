plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.klipa.feature.preview"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // Media3 types touch android.util.Pair in static initializers; let the stubs return defaults.
    testOptions.unitTests.isReturnDefaultValues = true
}

dependencies {
    api(project(":core:media"))
    implementation(project(":core:designsystem"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.media3.exoplayer)

    testImplementation(testFixtures(project(":core:model")))
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.junit4)
}
