import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

/**
 * Signing, and why there is more than one key.
 *
 * Android only installs an update whose signer matches the installed app and whose versionCode
 * is higher. Two different audiences therefore need two different identities:
 *
 * - **`upload`** — *the user's* key. It signs the `.aab` for Play *and* the release APKs on
 *   GitHub Releases, so a build from this repository is the app's permanent identity: once it
 *   is installed, every future build updates it in place. The key is never committed; it is
 *   supplied through the environment (GitHub secrets) and read here.
 * - **`stable`** — the project key in `build-support/`, still used for the `debug` variant so
 *   forks and CI test builds stay installable without any secrets.
 *
 * When the upload key is absent (a fork, a local build without the secrets) release builds fall
 * back to `stable` instead of failing: an unsigned release could not be installed at all.
 */
val uploadKeystoreBase64: String? = System.getenv("MANGASCRIBE_UPLOAD_KEYSTORE_BASE64")
val uploadKeystorePath: String? = System.getenv("MANGASCRIBE_UPLOAD_KEYSTORE_PATH")
val uploadKeystoreFile: File? = when {
    !uploadKeystoreBase64.isNullOrBlank() -> {
        // Written into the build directory, never into the source tree, and rebuilt each time.
        val decoded = File(rootProject.layout.buildDirectory.get().asFile, "upload-key.p12")
        decoded.parentFile.mkdirs()
        decoded.writeBytes(Base64.getMimeDecoder().decode(uploadKeystoreBase64.trim()))
        decoded
    }
    !uploadKeystorePath.isNullOrBlank() -> File(uploadKeystorePath!!).takeIf { it.exists() }
    else -> null
}
val uploadStorePassword = System.getenv("MANGASCRIBE_UPLOAD_STORE_PASSWORD").orEmpty()
val uploadKeyAlias = System.getenv("MANGASCRIBE_UPLOAD_KEY_ALIAS") ?: "mangascribe"
val uploadKeyPassword = System.getenv("MANGASCRIBE_UPLOAD_KEY_PASSWORD") ?: uploadStorePassword

val keystoreFile = rootProject.file("build-support/mangascribe-release.p12")
val keystorePassword = System.getenv("MANGASCRIBE_KEYSTORE_PASSWORD") ?: "mangascribe"
val keystoreAlias = System.getenv("MANGASCRIBE_KEY_ALIAS") ?: "mangascribe"

/**
 * Which identity this build carries. Release APKs and the Play bundle both use the upload key
 * when it exists, so installing `apk-<n>` from GitHub and then updating it from Play both land
 * on the same signer and the same library.
 */
val useUploadKey = uploadKeystoreFile != null

android {
    namespace = "app.mangascribe"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.mangascribe"
        minSdk = 26
        targetSdk = 35
        // Bumped per release: Android only accepts an update whose versionCode is higher.
        // CI overwrites this from the run number so every published APK can install over the
        // last one without the user uninstalling anything.
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }

    signingConfigs {
        create("upload") {
            if (uploadKeystoreFile != null) {
                storeFile = uploadKeystoreFile
                storePassword = uploadStorePassword
                keyAlias = uploadKeyAlias
                keyPassword = uploadKeyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
        create("stable") {
            if (keystoreFile.exists()) {
                storeFile = keystoreFile
                storePassword = keystorePassword
                keyAlias = keystoreAlias
                keyPassword = keystorePassword
                // v1 is needed for API 26 devices that predate v2's reliability; v2/v3 for the
                // rest. Without v1 some older phones report "package appears to be corrupt".
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // The user's upload key when CI provides it; the project key otherwise. Either way
            // a signed release, because an unsigned one cannot be installed.
            signingConfig = when {
                useUploadKey -> signingConfigs.getByName("upload")
                keystoreFile.exists() -> signingConfigs.getByName("stable")
                else -> null
            }
        }
        // The builds published as GitHub releases are named "debug" for history's sake but use
        // the real application id, so they install over each other and keep the user's data.
        // They carry the same identity as the Play bundle when the upload key is present, so a
        // sideloaded install and a Play install are the same app; without the secrets they fall
        // back to the project key and still update each other.
        getByName("debug") {
            versionNameSuffix = "-debug"
            signingConfig = when {
                useUploadKey -> signingConfigs.getByName("upload")
                keystoreFile.exists() -> signingConfigs.getByName("stable")
                else -> null
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    bundle {
        // Play re-splits the app per device; a bundle must therefore carry everything.
        language { enableSplit = false } // OCR models are loaded by language at runtime
        density { enableSplit = true }
        abi { enableSplit = true }
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        // Arabic-script text handling in unit tests depends on JVM locale data being present.
        unitTests.all {
            it.systemProperty("user.language", "en")
            it.systemProperty("file.encoding", "UTF-8")
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Data: Room for the library, DataStore for settings
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Media: page decoding, zoomable reader, archive (cbz/cbr-free) import
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.documentfile:documentfile:1.0.1")

    // ML Kit: text recognition for the on-device OCR pipeline
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-japanese:16.0.0")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("com.google.mlkit:text-recognition-korean:16.0.0")
    implementation("com.google.mlkit:text-recognition-devanagari:16.0.0")

    // Image loading + async image pipeline helpers
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
