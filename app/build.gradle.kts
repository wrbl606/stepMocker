import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Local signing values are optional; CI can supply the same values as environment variables.
val releaseProperties = Properties().apply {
    rootProject.file("signing.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
fun signingValue(key: String, environment: String): String? =
    providers.environmentVariable(environment).orNull?.takeIf { it.isNotBlank() }
        ?: releaseProperties.getProperty(key)?.takeIf { it.isNotBlank() }

val releaseStoreFile = signingValue("storeFile", "STEPMOCKER_STORE_FILE")
val releaseStorePassword = signingValue("storePassword", "STEPMOCKER_STORE_PASSWORD")
val releaseKeyAlias = signingValue("keyAlias", "STEPMOCKER_KEY_ALIAS")
val releaseKeyPassword = signingValue("keyPassword", "STEPMOCKER_KEY_PASSWORD")
val signingValues = listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword)
val hasReleaseSigning = signingValues.all { it != null }
require(signingValues.all { it == null } || hasReleaseSigning) {
    "Release signing is incomplete. Supply storeFile, storePassword, keyAlias and keyPassword in signing.properties or the corresponding STEPMOCKER_* environment variables."
}

val appVersionCode = providers.gradleProperty("appVersionCode").orElse("1").get().toIntOrNull()
require(appVersionCode != null && appVersionCode in 1..2_100_000_000) {
    "appVersionCode must be an integer between 1 and 2100000000."
}
val appVersionName = providers.gradleProperty("appVersionName").orElse("1.0").get()
require(appVersionName.isNotBlank()) { "appVersionName must not be blank." }

android {
    namespace = "xyz.wrbl.stepMocker"
    compileSdk = 36
    defaultConfig {
        applicationId = "xyz.wrbl.stepMocker"
        minSdk = 28
        targetSdk = 36
        versionCode = appVersionCode!!
        versionName = appVersionName
    }
    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }
    buildTypes {
        release {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.health.connect:connect-client:1.1.0")
    testImplementation("junit:junit:4.13.2")
}
