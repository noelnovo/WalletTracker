import org.jetbrains.kotlin.gradle.dsl.JvmTarget

val majorVersion = 3
val minorVersion = 0
val patchVersion = 0

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "win.downops.wallettracker"
    compileSdk = 36

    defaultConfig {
        applicationId = "win.downops.wallettracker"
        minSdk = 24
        targetSdk = 36
        versionCode = majorVersion * 10000 + minorVersion * 100 + patchVersion
        versionName = "$majorVersion.$minorVersion.$patchVersion"
        // Compiled UI capability only; authorization always belongs to the backend.
        buildConfigField("boolean", "REGISTRATION_UI_AVAILABLE", "true")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
    testOptions {
        unitTests.all { it.maxHeapSize = "128m" }
    }

    val devKeyFile = System.getenv("DEV_KEYSTORE_FILE")
    if (!devKeyFile.isNullOrBlank()) {
        signingConfigs.create("development") {
            storeFile = file(devKeyFile)
            storePassword = System.getenv("DEV_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("DEV_KEY_ALIAS")
            keyPassword = System.getenv("DEV_KEY_PASSWORD")
        }
    }
    buildTypes {
        debug {
            if (!devKeyFile.isNullOrBlank()) signingConfig = signingConfigs.getByName("development")
            buildConfigField("String", "API_BASE_URL", "\"https://wallettracker-dev.int.downops.win\"")
            buildConfigField("String", "API_VERSION", "\"2\"")
            buildConfigField("String", "SIGN_SECRET", "\"\"") // Legacy v1 code only; never used by v2.
            isDebuggable = true
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-dev"

        }
        release {
          val apiBaseUrl = System.getenv("API_BASE_URL") ?: "https://api.downops.win"

          buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
          buildConfigField("String", "API_VERSION", "\"2\"")
          buildConfigField("String", "SIGN_SECRET", "\"\"")
          isMinifyEnabled = true
          isShrinkResources = true
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
    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.androidx.legacy.support.v4)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.activity)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    implementation(libs.retrofit)
    implementation(libs.gson)
    implementation(libs.swiperefreshlayout)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.biometric)
}
