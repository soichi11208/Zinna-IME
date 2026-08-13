import com.google.protobuf.gradle.id

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.protobuf)
}

android {
    namespace = "io.github.soichi11208.zinna.mozc"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
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

    // ProfileBackup's path rules log what they reject, and android.util.Log is an empty stub off
    // the device. Returning defaults lets the rules themselves be tested without pulling in a
    // framework simulator for the sake of one call.
    testOptions.unitTests.isReturnDefaultValues = true

    sourceSets["main"].jniLibs.srcDirs("src/main/jniLibs")
    sourceSets["main"].assets.srcDirs("src/main/assets")

    // mozc.data stays deflated in the APK — it compresses to roughly half its 18 MB, and the file
    // has to be extracted to a real path for DataManager either way, so leaving it uncompressed
    // would only inflate the download. See MozcEngine.extractDataFile.
}

protobuf {
    protoc {
        artifact = libs.protobuf.protoc.get().toString()
    }
    generateProtoTasks {
        all().configureEach {
            builtins {
                // javalite keeps the generated code small; mozc protos are large.
                id("java") { option("lite") }
            }
        }
    }
}

dependencies {
    // `api`, not `implementation`: the generated mozc protos are this module's public vocabulary,
    // so consumers need GeneratedMessageLite and friends on their compile classpath too.
    api(libs.protobuf.javalite)
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit)
}
