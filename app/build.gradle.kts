import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val traceLoggingOverride = providers.gradleProperty("traceLogging").orNull?.let { value ->
    require(value == "true" || value == "false") {
        "traceLogging must be true or false"
    }
    value.toBoolean()
}
val diagnosticIntakeUrl = providers.gradleProperty("diagnosticIntakeUrl")
    .orElse("https://reports.eza.dpdns.org/v1/reports")
val diagnosticIntakeUri = URI(diagnosticIntakeUrl.get())
require(
    diagnosticIntakeUri.scheme == "https" &&
        !diagnosticIntakeUri.host.isNullOrBlank() &&
        diagnosticIntakeUri.userInfo == null &&
        diagnosticIntakeUri.query == null &&
        diagnosticIntakeUri.fragment == null
) {
    "diagnosticIntakeUrl must be an HTTPS URL without embedded credentials"
}

val signingKeystoreFile = providers.environmentVariable("SIGNING_KEYSTORE_FILE").orNull
val signingStorePassword = providers.environmentVariable("SIGNING_STORE_PASSWORD").orNull
val signingKeyAlias = providers.environmentVariable("SIGNING_KEY_ALIAS").orNull
val signingKeyPassword = providers.environmentVariable("SIGNING_KEY_PASSWORD").orNull
val releaseSigningConfigured = listOf(
    signingKeystoreFile,
    signingStorePassword,
    signingKeyAlias,
    signingKeyPassword
).all { !it.isNullOrBlank() }

android {
    namespace = "com.eza.hyperglow"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.eza.hyperglow"
        minSdk = 33
        targetSdk = 37
        versionCode = 81
        versionName = "0.3.69"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField(
            "String",
            "DIAGNOSTIC_INTAKE_URL",
            "\"$diagnosticIntakeUri\""
        )
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = file(requireNotNull(signingKeystoreFile))
                storePassword = requireNotNull(signingStorePassword)
                keyAlias = requireNotNull(signingKeyAlias)
                keyPassword = requireNotNull(signingKeyPassword)
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField(
                "boolean",
                "TRACE_LOGGING_AVAILABLE",
                (traceLoggingOverride ?: true).toString()
            )
        }
        release {
            isMinifyEnabled = true
            signingConfigs.findByName("release")?.let { signingConfig = it }
            buildConfigField(
                "boolean",
                "TRACE_LOGGING_AVAILABLE",
                (traceLoggingOverride ?: true).toString()
            )
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        aidl = true
        buildConfig = true
        compose = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.12.4")
    val composeBom = platform("androidx.compose:compose-bom:2026.04.01")
    implementation(composeBom)
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-icons-android:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    compileOnly("io.github.libxposed:api:102.0.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
