plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}
android {
    namespace = "com.boxlabs.hexdroid"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.boxlabs.hexdroid"
        minSdk = 26
        targetSdk = 37
        versionCode = 25
        versionName = "1.6.9"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        create("release") {
            val ksFile = System.getenv("KEYSTORE_FILE")
            if (ksFile != null && file(ksFile).exists()) {
                storeFile = file(ksFile)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val ksFile = System.getenv("KEYSTORE_FILE")
            signingConfig = if (ksFile != null && file(ksFile).exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
    androidResources {
        noCompress += "png"
    }
    packaging {
        resources {
            excludes += "META-INF/version-control-info.textproto"
        }
    }
}
// Kotlin 2.3
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.collections.immutable)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.material)
    implementation("com.pierfrancescosoffritti.androidyoutubeplayer:core:13.0.0")
    // Media3 / ExoPlayer for inline Twitter/X video playback. HLS module is needed because
    // fxtwitter returns progressive MP4 for most clips but HLS (.m3u8) for longer ones; all
    // three modules must share the same version.
    implementation("androidx.media3:media3-exoplayer:1.10.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.10.0")
    implementation("androidx.media3:media3-ui:1.10.0")
    implementation("com.squareup.okhttp3:okhttp:5.3.0")
    // +AGE crypto backend: BouncyCastle lightweight API (native Ed25519 + X25519). We use the
    // low-level org.bouncycastle.crypto.* classes directly (no JCA provider registration), so it
    // never collides with Android's platform-repackaged com.android.org.bouncycastle.
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
