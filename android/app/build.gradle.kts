import java.security.MessageDigest
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Default servers. Release (client) builds talk to the hosted Render API; debug builds to the
// LOCAL Docker backend through the emulator host alias. `-Pgeotree.baseUrl=...` overrides both, e.g.
// `-Pgeotree.baseUrl=http://192.168.1.20:8000` for a debug build on a phone on the LAN. A release
// build must use a hosted HTTPS server that is not a retired host (checked by verifyReleaseBaseUrl
// below). The address is not editable in Settings; the login screen keeps a verified "Change server"
// for development.
val baseUrlOverride = findProperty("geotree.baseUrl") as String?
val releaseBaseUrl = baseUrlOverride ?: "https://geo-tree.onrender.com"
val debugBaseUrl = baseUrlOverride ?: "http://10.0.2.2:8000"

/** Hosts that no longer serve GEO Tree (the former Hostinger VPS expired on 2026-10-19). */
val retiredHosts = setOf("geotree.museuma7k9x3.tech", "geotree.museuma7k9x.tech")

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
    ?: if (isLocalServer(debugBaseUrl)) "admin123" else ""

// Distribution signing. The keystore and its passwords live OUTSIDE the repository in a properties
// file (storeFile, storePassword, keyAlias, keyPassword) passed as
// -Pgeotree.signingProperties=<path>; without it a release build is left unsigned. GEO Tree keeps the
// certificate that signed earlier releases (SHA-256 071b9904…) so installed apps upgrade in place;
// a different key would force every tester to uninstall. See README "Hosted deployment".
val signingProperties = (findProperty("geotree.signingProperties") as String?)?.let { path ->
    val file = File(path)
    if (!file.isFile) throw GradleException("geotree.signingProperties: $path not found")
    Properties().apply { file.inputStream().use(::load) }
}

android {
    namespace = "com.geotree.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.geotree.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 13
        versionName = "0.8.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingProperties?.let { props ->
        signingConfigs {
            create("distribution") {
                storeFile = File(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField("String", "DEFAULT_BASE_URL", "\"$debugBaseUrl\"")
            // Development/test credentials: present in debug builds only (release stays empty).
            buildConfigField("String", "DEV_EMAIL", "\"$prefillEmail\"")
            buildConfigField("String", "DEV_PASSWORD", "\"$prefillPassword\"")
        }
        release {
            isMinifyEnabled = false
            isDebuggable = false
            signingConfigs.findByName("distribution")?.let { signingConfig = it }
            buildConfigField("String", "DEFAULT_BASE_URL", "\"$releaseBaseUrl\"")
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

// A client (release) APK must never default to a development server or plain HTTP.
val verifyReleaseBaseUrl by tasks.registering {
    val url = releaseBaseUrl
    val hostedHttps = url.startsWith("https://") && !isLocalServer(url)
    val host = url.substringAfter("://").substringBefore("/").substringBefore(":").lowercase()
    doLast {
        if (host.isEmpty()) throw GradleException("Release base URL is missing or invalid: '$url'")
        if (!hostedHttps) throw GradleException("Release base URL must be a hosted HTTPS server, got: $url")
        if (host in retiredHosts) throw GradleException("Release base URL points at a retired host ($host); use the Render API.")
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(verifyReleaseBaseUrl) }

// The on-device leaf model (assets/leaf_model, gitignored: trained weights) must be present and
// intact in every APK, or the first offline assessment would fail. Generate it with
// backend/scripts/export_mobile_model.py (README "On-device leaf assessment").
val verifyLeafModel by tasks.registering {
    val modelDir = layout.projectDirectory.dir("src/main/assets/leaf_model").asFile
    inputs.dir(modelDir).optional()
    doLast {
        val manifestFile = modelDir.resolve("manifest.json")
        if (!manifestFile.isFile) {
            throw GradleException("On-device leaf model missing: ${modelDir.path}. Run backend/scripts/export_mobile_model.py (README).")
        }
        @Suppress("UNCHECKED_CAST")
        val manifest = groovy.json.JsonSlurper().parse(manifestFile) as Map<String, Any?>
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
        for (key in listOf("backbone", "head")) {
            @Suppress("UNCHECKED_CAST")
            val entry = manifest[key] as Map<String, Any?>
            val file = modelDir.resolve(entry["file"] as String)
            if (!file.isFile || sha256(file) != entry["sha256"]) {
                throw GradleException("On-device leaf model file ${file.name} is missing or does not match manifest.json.")
            }
        }
    }
}
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach { dependsOn(verifyLeafModel) }

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
    implementation(libs.onnxruntime.android)
    implementation(libs.androidx.exifinterface)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.sqlite.jdbc)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
