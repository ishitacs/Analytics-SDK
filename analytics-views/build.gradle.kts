plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.example.analytics.views"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 23
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    api(project(":analytics-core"))
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel)
    // compileOnly: apps that use the RecyclerView helper already ship RecyclerView; others don't pay for it.
    compileOnly(libs.androidx.recyclerview)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.recyclerview)
    testImplementation(libs.androidx.activity)
}
