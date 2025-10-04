plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.hackyeah"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.hackyeah"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {

    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
//    val camerax_version = "1.3.4"
//    implementation("androidx.camera:camera-core:$camerax_version")
//    implementation("androidx.camera:camera-camera2:$camerax_version")
//    implementation("androidx.camera:camera-lifecycle:$camerax_version")
//    implementation("androidx.camera:camera-view:$camerax_version")
//    implementation("androidx.camera:camera-extensions:$camerax_version")
    implementation("org.opencv:opencv:4.11.0")
    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}