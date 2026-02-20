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
        targetSdk = 36
        versionCode = 9
        versionName = "1.5.3"
    }

	buildTypes {
		release {
			isMinifyEnabled = true
			isShrinkResources = true
            isCrunchPngs = false
            vcsInfo.include = false
			proguardFiles(
				getDefaultProguardFile("proguard-android-optimize.txt"),
				"proguard-rules.pro"
			)

            // Use release keystore if available (CI), otherwise fall back to debug
            val ksFile = System.getenv("KEYSTORE_FILE")
            signingConfig = if (ksFile != null && file(ksFile).exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
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

    buildFeatures {
		buildConfig = true
        compose = true
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    packaging {
        jniLibs.keepDebugSymbols.add("**/*.so")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.icons.extended)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(libs.material)

    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.junit)

    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
