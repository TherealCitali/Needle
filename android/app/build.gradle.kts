plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// ---------------------------------------------------------------------------
// Signing
//
// Release APKs are always signed. The repository ships a project keystore so a
// fresh clone, a fork, or a pull request can all produce an installable APK
// without any extra setup; the same four values can be overridden from the
// environment (or GitHub Actions secrets) for a private key.
//
//   NEEDLE_KEYSTORE_FILE      path to a .p12/.jks
//   NEEDLE_KEYSTORE_PASSWORD  store password
//   NEEDLE_KEY_ALIAS          key alias
//   NEEDLE_KEY_PASSWORD       key password
// ---------------------------------------------------------------------------
fun env(name: String): String? = providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() }

val needleKeystoreFile: File =
    env("NEEDLE_KEYSTORE_FILE")?.let { rootProject.file(it).takeIf { f -> f.exists() } ?: File(it) }
        ?: rootProject.file("keystore/needle-release.p12")
val needleKeystorePassword: String = env("NEEDLE_KEYSTORE_PASSWORD") ?: "needle-release"
val needleKeyAlias: String = env("NEEDLE_KEY_ALIAS") ?: "needle"
val needleKeyPassword: String = env("NEEDLE_KEY_PASSWORD") ?: "needle-release"

// ---------------------------------------------------------------------------
// On-device Needle model (Cactus Compute, Apache-2.0)
//
// The engine is linked into libneedlejni.so at build time; the 35 MB weight
// archive is downloaded once on the device and verified against this hash, so
// the APK itself stays small.
// ---------------------------------------------------------------------------
val needleEngineVersion = "3.0.2"
val needleWeightsUrl = "https://huggingface.co/Cactus-Compute/needle3/resolve/main/needle3.cact?download=true"
val needleWeightsSha256 = "c9d915eca282ed42d1a09b143b592adb4cc6744ffe2d294adf5cfc5548170c38"
val needleWeightsSize = 35_335_380L

val needleVersionCode: Int = env("NEEDLE_VERSION_CODE")?.toIntOrNull() ?: 1
val needleVersionName: String = env("NEEDLE_VERSION_NAME") ?: "1.0.0"

android {
    namespace = "dev.citali.needle"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "dev.citali.needle"
        minSdk = 29
        targetSdk = 35
        versionCode = needleVersionCode
        versionName = needleVersionName

        // Cactus Compute publishes the Needle engine for arm64-v8a and
        // armeabi-v7a. Only arm64-v8a is built by default: the published 32-bit
        // archive was compiled against an older libc++ and calls internal
        // helpers (std::__hash_memory) that current NDKs no longer ship, so it
        // cannot be linked against NDK 27. Override with NEEDLE_ABIS to try.
        ndk {
            abiFilters += (System.getenv("NEEDLE_ABIS") ?: "arm64-v8a")
                .split(",").map { it.trim() }.filter { it.isNotEmpty() }
        }

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DNEEDLE_ENGINE_VERSION=$needleEngineVersion",
                    "-DNEEDLE_ALLOW_STUB=${env("NEEDLE_ALLOW_STUB") ?: "OFF"}",
                    // The Needle engine archive is C++: the JNI bridge is C, so
                    // the runtime has to be requested explicitly.
                    "-DANDROID_STL=c++_shared",
                )
                cFlags += listOf("-O2", "-fvisibility=hidden")
            }
        }

        buildConfigField("String", "NEEDLE_ENGINE_VERSION", "\"$needleEngineVersion\"")
        buildConfigField("String", "NEEDLE_WEIGHTS_URL", "\"$needleWeightsUrl\"")
        buildConfigField("String", "NEEDLE_WEIGHTS_SHA256", "\"$needleWeightsSha256\"")
        buildConfigField("long", "NEEDLE_WEIGHTS_SIZE", "${needleWeightsSize}L")
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        create("needleRelease") {
            storeFile = needleKeystoreFile
            storeType = "PKCS12"
            storePassword = needleKeystorePassword
            keyAlias = needleKeyAlias
            keyPassword = needleKeyPassword
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("needleRelease")
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

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.savedstate:savedstate-ktx:1.2.1")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
