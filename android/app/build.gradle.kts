plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Optional per-developer override, e.g. `-Pgeotree.baseUrl=http://192.168.1.20:8000`.
// The app also lets the user change and verify the server URL at runtime.
val defaultBaseUrl = (findProperty("geotree.baseUrl") as String?) ?: "http://10.0.2.2:8000"

android {
    namespace = "com.geotree.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.geotree.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-give1"
        buildConfigField("String", "DEFAULT_BASE_URL", "\"$defaultBaseUrl\"")
    }

    buildTypes {
        debug {
            // Development-only credentials: present in debug builds only.
            buildConfigField("String", "DEV_EMAIL", "\"admin@gmail.com\"")
            buildConfigField("String", "DEV_PASSWORD", "\"admin123\"")
        }
        release {
            isMinifyEnabled = false
            buildConfigField("String", "DEV_EMAIL", "\"\"")
            buildConfigField("String", "DEV_PASSWORD", "\"\"")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.play.services.location)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.coil.compose)
    implementation(libs.maplibre.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
