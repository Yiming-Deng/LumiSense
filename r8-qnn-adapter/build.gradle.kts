import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
}

val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use(::load)
}
val qairtSdkRoot = providers.gradleProperty("qairtSdkRoot").orNull
    ?: System.getenv("QAIRT_SDK_ROOT")
val bundledQnnHeaders = file("src/main/cpp/include/QNN/QnnInterface.h").isFile
val qnnNativeBuildEnabled = bundledQnnHeaders || !qairtSdkRoot.isNullOrBlank()

android {
    namespace = "com.oppovisual.r8qnn"
    compileSdk = 36
    ndkVersion = "27.2.12479018"
    localProperties.getProperty("oppovisual.ndk.dir")?.let { ndkPath = it }

    defaultConfig {
        minSdk = 29
        ndk { abiFilters += "arm64-v8a" }
        if (qnnNativeBuildEnabled) {
            externalNativeBuild {
                cmake {
                    cppFlags += listOf("-std=c++17", "-Wall", "-Wextra", "-Werror")
                    if (!qairtSdkRoot.isNullOrBlank()) {
                        arguments += "-DQAIRT_SDK_ROOT=$qairtSdkRoot"
                    }
                }
            }
        }
    }

    if (qnnNativeBuildEnabled) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation(libs.junit)
}
