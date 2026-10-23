plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "com.example.klipa"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.klipa"
        minSdk = 26
        targetSdk = 37
        versionCode = 6
        versionName = "0.5.0"
    }

    // Only ship the languages the app is translated into, so library UI never shows
    // in a third language next to ours.
    androidResources {
        localeFilters += listOf("en", "in")
    }

    signingConfigs {
        // A development key, committed on purpose so APKs built on any machine install
        // over each other. It is not secret and must not sign a Play Store release.
        create("dev") {
            storeFile = file("klipa-dev.keystore")
            storePassword = "klipa-dev"
            keyAlias = "klipa-dev"
            keyPassword = "klipa-dev"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("dev")
        }
        release {
            signingConfig = signingConfigs.getByName("dev")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // Robolectric reaches into FileDescriptor internals, closed by default since JDK 17.
            it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:edit"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:media"))
    implementation(project(":feature:timeline"))
    implementation(project(":feature:preview"))
    implementation(project(":feature:export"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // Screenshot tests render the real UI on the JVM (Robolectric native graphics).
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.androidx.compose.ui.test.junit4)
}
