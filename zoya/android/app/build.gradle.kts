import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * The API key never lives in source control.
 *
 * Priority:
 *   1. GEMINI_API_KEY environment variable (CI / local shell)
 *   2. zoya.properties at the repo root (git-ignored), key = GEMINI_API_KEY
 *   3. empty -> the app still builds, onboarding explains what to add
 */
val zoyaProps = Properties().apply {
    val f = rootProject.file("zoya.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val geminiKey: String = System.getenv("GEMINI_API_KEY")
    ?: zoyaProps.getProperty("GEMINI_API_KEY", "")

android {
    namespace = "ai.zoya.assistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "ai.zoya.assistant"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        buildConfigField("String", "GEMINI_API_KEY", "\"$geminiKey\"")
        buildConfigField("String", "ZOYA_MODEL", "\"gemini-3.1-flash-live-preview\"")
        // Optional: a token-minting server (the web app's /api/token). When set,
        // the device never holds the long-lived key.
        buildConfigField(
            "String",
            "TOKEN_SERVER_URL",
            "\"${zoyaProps.getProperty("TOKEN_SERVER_URL", "")}\"",
        )
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
        release {
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
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/*.kotlin_module")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation("junit:junit:4.13.2")

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
}
