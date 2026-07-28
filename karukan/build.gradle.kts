plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

/**
 * Whether to bundle the neural model into the APK.
 *
 * Off by default, and a build-time choice rather than a runtime one, because the weights are tens
 * of megabytes that most builds have no use for — and because the upstream model repositories
 * carry no licence statement, so putting them inside a published APK is a decision for whoever
 * publishes it rather than a default.
 *
 *     ./gradlew :app:assembleDebug -Pzinna.karukan.model=true
 *
 * Run scripts/fetch_karukan_model.sh first to put the files on disk.
 */
val bundleModel = providers.gradleProperty("zinna.karukan.model").orNull == "true"
val modelDir = rootProject.layout.projectDirectory.dir("third_party/karukan-model")

android {
    namespace = "dev.oss.ime.karukan"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        // Read by KarukanEngine so the runtime knows whether to expect a model at all, without
        // having to probe the asset list.
        buildConfigField("boolean", "MODEL_BUNDLED", bundleModel.toString())
    }

    buildFeatures {
        buildConfig = true
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

    // Both the 8MB engine and the weights live outside the module and are added only when asked
    // for. Nothing to exclude on a default build, which is the point: srcDirs() adds to AGP's
    // defaults rather than replacing them, so "put it in the module and subtract it later" quietly
    // ships the library anyway.
    if (bundleModel) {
        sourceSets["main"].jniLibs.srcDirs(
            rootProject.layout.projectDirectory.dir("third_party/karukan-libs")
        )
        sourceSets["main"].assets.srcDirs(modelDir)
    }
}

if (bundleModel) {
    gradle.projectsEvaluated {
        val gguf = modelDir.asFile.listFiles { f -> f.name.endsWith(".gguf") }
        require(!gguf.isNullOrEmpty()) {
            "zinna.karukan.model=true but no .gguf in ${modelDir.asFile}. " +
                "Run scripts/fetch_karukan_model.sh first."
        }

        // Assets are shared by every ABI split, but the engine is not: scripts/build_karukan.sh
        // builds arm64 alone unless told otherwise. Any other split therefore ships the weights
        // with nothing able to read them — tens of megabytes of dead payload, which is exactly what
        // this switch exists to avoid. The app degrades cleanly (KarukanEngine.open returns null on
        // UnsatisfiedLinkError), so this is a warning rather than an error.
        val libsDir = rootProject.layout.projectDirectory.dir("third_party/karukan-libs").asFile
        val built = libsDir.listFiles { f -> f.isDirectory }?.map { it.name }?.sorted().orEmpty()
        val model = gguf.first().length() / 1024 / 1024
        logger.lifecycle("karukan: bundling ${gguf.first().name} (${model} MB) for ABIs $built")
        if (built.isEmpty()) {
            logger.warn(
                "karukan: no libkarukan.so anywhere — run scripts/build_karukan.sh, or every " +
                    "split will carry the model with no engine to run it"
            )
        } else {
            logger.lifecycle(
                "karukan: other ABI splits will carry the model unusably; build them with " +
                    "KARUKAN_ABIS, or install the arm64 APK"
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit)
}
