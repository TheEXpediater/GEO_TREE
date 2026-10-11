plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Default server: the LOCAL Docker backend through the emulator host alias. The DINOv2 leaf
// assessment milestone is tested locally (never against the hosted deployment). Override per
// build, e.g. `-Pgeotree.baseUrl=http://192.168.1.20:8000` (phone on the LAN) or
// `-Pgeotree.baseUrl=https://geotree.museuma7k9x3.tech` (production APK on the Hostinger VPS);
// the app also lets the user change and verify the server URL at runtime.
val defaultBaseUrl = (findProperty("geotree.baseUrl") as String?) ?: "http://10.0.2.2:8000"

/** localhost, loopback, the emulator alias and private LAN ranges: the development servers. */
fun isLocalServer(url: String): Boolean {
    val host = url.substringAfter("://").substringBefore("/").substringBefore(":").lowercase()
    if (host == "localhost") return true
    val octets = host.split(".").mapNotNull { it.toIntOrNull() }
    if (octets.size != 4) return false
    val (a, b) = octets
    return a == 127 || a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168)
}

// Debug-only login prefill. Values end up readable inside the debug APK: a testing convenience,
// NOT secret storage. The development password (admin123) is prefilled only for local servers;
// a hosted build starts with an empty password unless -Pgeotree.prefillPassword is supplied.
val prefillEmail = (findProperty("geotree.prefillEmail") as String?) ?: "admin@gmail.com"
val prefillPassword = (findProperty("geotree.prefillPassword") as String?)
    ?: if (isLocalServer(defaultBaseUrl)) "admin123" else ""

android {
    namespace = "com.geotree.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.geotree.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "0.5.0-hostinger"
        buildConfigField("String", "DEFAULT_BASE_URL", "\"$defaultBaseUrl\"")
    }

    buildTypes {
        debug {
            // Development/test credentials: present in debug builds only (release stays empty).
            buildConfigField("String", "DEV_EMAIL", "\"$prefillEmail\"")
            buildConfigField("String", "DEV_PASSWORD", "\"$prefillPassword\"")
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
    testImplementation(libs.sqlite.jdbc)
}
