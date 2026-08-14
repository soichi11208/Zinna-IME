import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * Release signing, kept out of the repository.
 *
 * Read from `keystore.properties` in the project root, which is gitignored along with the key
 * itself. When it is absent — a fresh clone, or anyone building from source — the release variant
 * still assembles, just unsigned, so a missing private key never blocks a build.
 */
val signing = rootProject.file("keystore.properties").takeIf { it.isFile }?.let { file ->
    Properties().apply { file.inputStream().use { load(it) } }
}

android {
    namespace = "io.github.soichi11208.zinna"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.soichi11208.zinna"
        minSdk = 24
        targetSdk = 35
        versionCode = 11
        versionName = "0.1.1"
    }

    signingConfigs {
        if (signing != null) {
            create("release") {
                storeFile = file(signing.getProperty("storeFile"))
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
                // v2 alone would be enough — it covers everything from API 24, which is our floor.
                // v3 is what makes key rotation possible later, and it has to be in the signature
                // from the start: a device that installed a v2-only APK will not accept a rotated
                // key afterwards. Cheap now, impossible to add retroactively.
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            applicationIdSuffix = ".debug"
        }
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

    buildFeatures {
        compose = true
    }

    packaging {
        // libmozc.so must stay page-aligned for the 16 KB page-size requirement on Android 15+.
        jniLibs.useLegacyPackaging = false
    }

    // libmozc.so is 13-16 MB per ABI, so a universal APK lands near 83 MB. Splitting drops each
    // device's download to roughly a third of that; the universal APK stays available for
    // sideloading and CI.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            isUniversalApk = true
        }
    }
}

dependencies {
    implementation(project(":mozc"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
