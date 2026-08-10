import java.util.Properties
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.isFile) {
        keystorePropertiesFile.inputStream().use(::load)
    }
}
val deliveryArm64Only = providers.gradleProperty("deliveryArm64Only")
    .orElse("false")
    .get()
    .toBoolean()
val finalLiteRtModelName = "gesture_pose_final_fullqat_w8a16_640.tflite"
val finalLiteRtModelFile = file("src/main/assets/$finalLiteRtModelName")
val missingModelSha256 = "0".repeat(64)
val retiredR8ModelSha256 = "1857269f778914e5414a60a6d8bf3e04f742f1f926b1d1bdbf374dbbeab8a3fa"

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

val finalLiteRtModelSha256 = if (finalLiteRtModelFile.isFile) {
    sha256(finalLiteRtModelFile)
} else {
    missingModelSha256
}

val verifyFinalLiteRtModel by tasks.registering {
    group = "verification"
    description = "Rejects delivery builds that do not contain the final full-QAT LiteRT model."
    doLast {
        check(finalLiteRtModelFile.isFile) {
            "Missing final LiteRT model: ${finalLiteRtModelFile.absolutePath}"
        }
        val actualSha256 = sha256(finalLiteRtModelFile)
        check(actualSha256 != retiredR8ModelSha256) {
            "The retired R8 model cannot be packaged as the final LiteRT model."
        }
        check(actualSha256 == finalLiteRtModelSha256 && actualSha256 != missingModelSha256) {
            "Final LiteRT model hash changed during the build."
        }
    }
}

val requiredQnnHtpLibraries = listOf(
    "libQnnHtpV73Stub.so",
    "libQnnHtpV73Skel.so",
    "libQnnHtpV75Stub.so",
    "libQnnHtpV75Skel.so",
    "libQnnHtpV79Stub.so",
    "libQnnHtpV79Skel.so",
)
val releaseQnnLibraryDir = file("src/release/jniLibs/arm64-v8a")
val verifyQnnHtpRuntime by tasks.registering {
    group = "verification"
    description = "Checks that delivery builds contain the supported QNN HTP generations."
    doLast {
        val missing = requiredQnnHtpLibraries.filterNot { releaseQnnLibraryDir.resolve(it).isFile }
        check(missing.isEmpty()) {
            "Missing QNN HTP runtime libraries: ${missing.joinToString()}"
        }
    }
}

android {
    namespace = "com.oppovisual.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.oppovisual.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 5
        versionName = "1.0.4"
        buildConfigField("String", "R8_MODEL_ASSET", "\"$finalLiteRtModelName\"")
        buildConfigField(
            "String",
            "R8_MODEL_SHA256",
            "\"$finalLiteRtModelSha256\"",
        )
        buildConfigField("String", "R8_MODEL_VERSION", "\"gesture-pose-final-fullqat-w8a16-640\"")
        buildConfigField("String", "R8_ACCELERATOR", "\"npu_gpu_cpu\"")
        buildConfigField("String", "GESTURE_RUNTIME", "\"litert\"")
        buildConfigField("boolean", "R8_DIAGNOSTIC_TIMING_LOG", "false")
        buildConfigField("String", "R8_NPU_PERFORMANCE_MODE", "\"default\"")
        buildConfigField(
            "String",
            "R8_NPU_OPTIMIZATION_LEVEL",
            "\"htp_optimize_for_inference\"",
        )
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
        if (deliveryArm64Only) {
            ndk {
                abiFilters += "arm64-v8a"
            }
        }
    }

    signingConfigs {
        if (keystorePropertiesFile.isFile) {
            create("release") {
                storeFile = rootProject.file(requireNotNull(keystoreProperties.getProperty("storeFile")))
                storePassword = requireNotNull(keystoreProperties.getProperty("storePassword"))
                keyAlias = requireNotNull(keystoreProperties.getProperty("keyAlias"))
                keyPassword = requireNotNull(keystoreProperties.getProperty("keyPassword"))
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
            buildConfigField("String", "GESTURE_RUNTIME", "\"qnn_htp\"")
            buildConfigField("String", "R8_NPU_PERFORMANCE_MODE", "\"sustained\"")
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
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        jniLibs.useLegacyPackaging = true
    }

    sourceSets.getByName("release").jniLibs.srcDir("src/release/jniLibs")
}

tasks.configureEach {
    if (name == "mergeReleaseAssets") {
        dependsOn(verifyFinalLiteRtModel)
    }
    if (name == "mergeReleaseNativeLibs") {
        dependsOn(verifyQnnHtpRuntime)
    }
}

dependencies {
    implementation(project(":recognition-core"))
    implementation(project(":r8-litert-adapter"))
    implementation(project(":r8-qnn-adapter"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation("androidx.lifecycle:lifecycle-service:2.9.1")
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.mediapipe.tasks.vision)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.filament.android)
    implementation(libs.filament.gltfio.android)
    implementation(libs.filament.utils.android)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
