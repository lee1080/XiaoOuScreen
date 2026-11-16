import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val composeCompilerVersion = "1.5.8"

// 自动版本自增管理 (每构建一次增加一个小版本)
val versionPropsFile = rootProject.file("version.properties")
val versionProps = Properties()

if (versionPropsFile.exists()) {
    FileInputStream(versionPropsFile).use { versionProps.load(it) }
} else {
    versionProps["VERSION_MAJOR"] = "1"
    versionProps["VERSION_MINOR"] = "0"
    versionProps["VERSION_PATCH"] = "0"
    versionProps["VERSION_BUILD"] = "0"
}

val isBuilding = gradle.startParameter.taskNames.any { name ->
    val lower = name.lowercase()
    lower.contains("assemble") || lower.contains("bundle") || lower.contains("build") || lower.contains("package") || lower.contains("install")
}

var major = (versionProps["VERSION_MAJOR"] as? String)?.toIntOrNull() ?: 1
var minor = (versionProps["VERSION_MINOR"] as? String)?.toIntOrNull() ?: 0
var patch = (versionProps["VERSION_PATCH"] as? String)?.toIntOrNull() ?: 0
var buildNum = (versionProps["VERSION_BUILD"] as? String)?.toIntOrNull() ?: 0

if (isBuilding) {
    patch += 1
    buildNum += 1
    versionProps["VERSION_PATCH"] = patch.toString()
    versionProps["VERSION_BUILD"] = buildNum.toString()
    FileOutputStream(versionPropsFile).use { 
        versionProps.store(it, "Auto-incremented build version for XiaoOu Screen") 
    }
}

val currentVersionCode = buildNum.coerceAtLeast(1)
val currentVersionName = "$major.$minor.$patch"

android {
    namespace = "com.car.xiaoouscreen"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.car.xiaoouscreen"
        minSdk = 24
        targetSdk = 28
        versionCode = currentVersionCode
        versionName = currentVersionName
    }

    signingConfigs {
        create("aospPlatform") {
            val keyFile = rootProject.file("key/aosp_platform.jks")
            if (keyFile.exists()) {
                storeFile = keyFile
                storePassword = "android"
                keyAlias = "android"
                keyPassword = "android"
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            val aospConfig = signingConfigs.getByName("aospPlatform")
            if (aospConfig.storeFile != null) {
                signingConfig = aospConfig
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    applicationVariants.all {
        outputs.all {
            val outputImpl = this as? com.android.build.gradle.internal.api.ApkVariantOutputImpl
            if (outputImpl != null) {
                if (buildType.name == "release") {
                    outputImpl.outputFileName = "XiaoOuScreen-v${versionName}.apk"
                } else {
                    outputImpl.outputFileName = "XiaoOuScreen-v${versionName}-${buildType.name}.apk"
                }
            }
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
        aidl = true
        buildConfig = true
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = composeCompilerVersion
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.6.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.6.2")

    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Retrofit + OkHttp + Gson
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Glide for album cover loading
    implementation("com.github.bumptech.glide:glide:4.16.0")

    // Palette for cover color extraction
    implementation("androidx.palette:palette-ktx:1.0.0")

    // Media session compat
    implementation("androidx.media:media:1.7.0")

    // Rhino JavaScript engine (pure Java, runs on all Android architectures)
    implementation("org.mozilla:rhino:1.7.15")

    // UI layouts
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}
