plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}
android {
    namespace = "com.boxlabs.hexdroid"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.boxlabs.hexdroid"
        minSdk = 26
        targetSdk = 37
        versionCode = 30
        versionName = "1.7.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // Release signing
    //
    // Reproducible-build verifiers (rbtlog, IzzyOnDroid, anyone following the README)
    // build from a clean checkout with no signing environment. They must get an
    // UNSIGNED release APK: the verifier supplies the signature from the published
    // artifact itself (apksigcopier) and compares the payload.
    //
    // What must never happen is a fall back to the debug signing config.
    //
    // Behaviour:
    //   - all four env vars set and the keystore exists -> sign with it
    //   - none set                                      -> build unsigned, say so
    //   - some set                                      -> fail, don't guess
    //   - -PrequireSigning=true                         -> fail if nothing will sign
    //     (for automated or scripted builds, where nobody reads the log)
    val keystorePath = providers.environmentVariable("KEYSTORE_FILE").orNull?.takeIf { it.isNotBlank() }
    val keystorePassword = providers.environmentVariable("KEYSTORE_PASSWORD").orNull?.takeIf { it.isNotBlank() }
    val keystoreAlias = providers.environmentVariable("KEY_ALIAS").orNull?.takeIf { it.isNotBlank() }
    val keystoreKeyPassword = providers.environmentVariable("KEY_PASSWORD").orNull?.takeIf { it.isNotBlank() }
    val requireSigning = providers.gradleProperty("requireSigning").orNull?.toBoolean() ?: false
    val injectedSigning = providers
    .gradleProperty("android.injected.signing.store.file").orNull
    ?.isNotBlank() == true

    val signingVarsPresent = listOf(
        "KEYSTORE_FILE" to keystorePath,
        "KEYSTORE_PASSWORD" to keystorePassword,
        "KEY_ALIAS" to keystoreAlias,
        "KEY_PASSWORD" to keystoreKeyPassword,
    )
    val signingVarsMissing = signingVarsPresent.filter { it.second == null }.map { it.first }
    val signingRequested = signingVarsMissing.size < signingVarsPresent.size

    if (signingRequested && signingVarsMissing.isNotEmpty()) {
        // A partially configured environment is always a mistake
        throw GradleException(
            "Release signing is partially configured. Missing: ${signingVarsMissing.joinToString(", ")}. " +
            "Set all of KEYSTORE_FILE, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD, or none of them " +
            "to build unsigned."
        )
    }
    if (signingRequested && !file(keystorePath!!).exists()) {
        throw GradleException("KEYSTORE_FILE is set to '$keystorePath' but no such file exists.")
    }
    if (requireSigning && !signingRequested && !injectedSigning) {
        throw GradleException(
            "-PrequireSigning=true was passed but no signing environment is configured. " +
            "Set KEYSTORE_FILE, KEYSTORE_PASSWORD, KEY_ALIAS and KEY_PASSWORD, or build " +
            "through Android Studio's signing wizard."
        )
    }

    // Record the mode in the build log so a verifier's transcript says which one ran,
    // and so "why won't this install" answers itself.
    logger.lifecycle(
        when {
            signingRequested -> "HexDroid: release will be signed with the configured keystore."
            injectedSigning -> "HexDroid: release will be signed with the injected (IDE wizard) keystore."
            else -> "HexDroid: release will be UNSIGNED (no signing environment set). " +
                "Use apksigcopier to attach the published signature when verifying."
        }
    )

    signingConfigs {
        if (signingRequested) {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = keystorePassword
                keyAlias = keystoreAlias
                keyPassword = keystoreKeyPassword
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
            // Explicitly null when unsigned. Never the debug config - see the note above
            // the signingConfigs block.
            signingConfig = if (signingRequested) signingConfigs.getByName("release") else null
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
