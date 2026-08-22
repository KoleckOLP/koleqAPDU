plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.koleq.apdu"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.koleq.apdu"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "22.08.26"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}