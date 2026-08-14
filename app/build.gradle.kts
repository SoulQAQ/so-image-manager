import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val versionPropertiesFile = rootProject.file("version.properties")
val versionProperties = Properties().apply {
    require(versionPropertiesFile.isFile) { "Missing version source: ${versionPropertiesFile.path}" }
    versionPropertiesFile.inputStream().use(::load)
}

val publishVersionProperties = gradle.startParameter.projectProperties
val soimVersionName = (
    publishVersionProperties["soimVersionName"]
        ?: versionProperties.getProperty("SOIM_VERSION_NAME").orEmpty()
    ).trim()
require(soimVersionName.isNotBlank()) { "SoIM version name must not be blank" }

val rawSoimVersionCode = (
    publishVersionProperties["soimVersionCode"]
        ?: versionProperties.getProperty("SOIM_VERSION_CODE").orEmpty()
    ).trim()
val soimVersionCode = rawSoimVersionCode.toIntOrNull()
    ?: error("SoIM version code must be a positive integer")
require(soimVersionCode > 0) { "SoIM version code must be a positive integer" }

val releaseIdentityFile = rootProject.file("release-identity.properties")
val releaseIdentity = Properties().apply {
    require(releaseIdentityFile.isFile) { "Missing public release identity: ${releaseIdentityFile.path}" }
    releaseIdentityFile.inputStream().use(::load)
}
fun releaseIdentityValue(name: String): String = releaseIdentity.getProperty(name).orEmpty().trim()
val officialPackageId = releaseIdentityValue("SOIM_OFFICIAL_PACKAGE_ID")
val firstOfficialVersion = releaseIdentityValue("SOIM_FIRST_OFFICIAL_VERSION")
val firstOfficialTag = releaseIdentityValue("SOIM_FIRST_OFFICIAL_TAG")
val officialCertSha256 = releaseIdentityValue("SOIM_OFFICIAL_CERT_SHA256")
require(officialPackageId == "cn.soul2.imageai") { "Unexpected official package ID" }
require(firstOfficialTag == "v$firstOfficialVersion") { "Official release tag/version mismatch" }
require(officialCertSha256.isEmpty() || officialCertSha256.matches(Regex("^[0-9A-Fa-f]{64}$"))) {
    "Official release certificate SHA-256 must be empty or exactly 64 hexadecimal characters"
}

val releaseSigningProperties = Properties().apply {
    val signingFile = rootProject.file("keystore.properties")
    if (signingFile.isFile) signingFile.inputStream().use(::load)
}
fun signingValue(property: String, environment: String): String? =
    (releaseSigningProperties.getProperty(property) ?: System.getenv(environment))
        ?.trim()
        ?.takeIf(String::isNotEmpty)
val releaseStoreFile = signingValue("storeFile", "SOIM_SIGNING_STORE_FILE")
val releaseStorePassword = signingValue("storePassword", "SOIM_SIGNING_STORE_PASSWORD")
val releaseKeyAlias = signingValue("keyAlias", "SOIM_SIGNING_KEY_ALIAS")
val releaseKeyPassword = signingValue("keyPassword", "SOIM_SIGNING_KEY_PASSWORD")
val hasReleaseSigning = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { it != null }
fun semanticParts(value: String): List<Int> = value.split('.').map { part ->
    part.toIntOrNull() ?: error("SoIM version must use strict numeric SemVer")
}.also { require(it.size == 3) { "SoIM version must use strict SemVer" } }
val requiresOfficialSigning = semanticParts(soimVersionName).let { current ->
    val first = semanticParts(firstOfficialVersion)
    current.zip(first).firstOrNull { (left, right) -> left != right }?.let { (left, right) -> left > right }
        ?: true
}
if (requiresOfficialSigning) {
    require(officialCertSha256.isNotEmpty()) { "Official releases require a pinned certificate SHA-256" }
    require(hasReleaseSigning) { "Official releases require the long-term release keystore" }
}

ksp {
    arg("room.schemaLocation", file("$projectDir/schemas").path)
    arg("room.incremental", "true")
}

android {
    namespace = "cn.soul2.imageai"
    compileSdk = 36

    defaultConfig {
        applicationId = officialPackageId
        minSdk = 29
        targetSdk = 36
        versionCode = soimVersionCode
        versionName = soimVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "SOIM_OFFICIAL_PACKAGE_ID", "\"$officialPackageId\"")
        buildConfigField("String", "SOIM_FIRST_OFFICIAL_VERSION", "\"$firstOfficialVersion\"")
        buildConfigField("String", "SOIM_FIRST_OFFICIAL_TAG", "\"$firstOfficialTag\"")
        buildConfigField("String", "SOIM_OFFICIAL_CERT_SHA256", "\"${officialCertSha256.lowercase()}\"")

    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (hasReleaseSigning) {
            maybeCreate("release").apply {
                storeFile = rootProject.file(checkNotNull(releaseStoreFile))
                storePassword = checkNotNull(releaseStorePassword)
                keyAlias = checkNotNull(releaseKeyAlias)
                keyPassword = checkNotNull(releaseKeyPassword)
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isDebuggable = false
            isMinifyEnabled = false
            if (requiresOfficialSigning && hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
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

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
    testOptions {
        animationsDisabled = true
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.material.icons.extended)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.room.paging)
    ksp(libs.room.compiler)

    // Paging
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)

    // Images
    implementation(libs.coil.compose)
    implementation(libs.pinyin4j)
    implementation(libs.okhttp)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.androidx.paging.testing)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.room.testing)
}

tasks.withType<Test>().configureEach {
    systemProperty(
        "soim.scaleBenchmark",
        gradle.startParameter.projectProperties["soimScaleBenchmark"] ?: "false",
    )
}
