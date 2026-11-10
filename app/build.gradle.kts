plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

/**
 * The release keystore.
 *
 * Every debug build is signed with a fresh key, which is why Android refused to install a new
 * build over the old one ("App not installed" / "Update not applied"). A stable key fixes that
 * permanently: same package, same signer, increasing versionCode → a normal update that keeps
 * the library, translations and settings.
 *
 * The keystore in build-support/ is a self-signed key generated for this project. It is not
 * secret in the usual sense (it is in the repository, and every build uses it), but it IS the
 * identity of the app: keep it, and every future build will install over the previous one.
 * Replace it with your own before publishing, and never lose the replacement.
 */
val keystoreFile = rootProject.file("build-support/mangascribe-release.p12")
val keystorePassword = System.getenv("MANGASCRIBE_KEYSTORE_PASSWORD") ?: "mangascribe"
val keystoreAlias = System.getenv("MANGASCRIBE_KEY_ALIAS") ?: "mangascribe"

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
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // The same key as the test builds: an update replaces the installed app instead of
            // being refused.
            if (keystoreFile.exists()) signingConfig = signingConfigs.getByName("stable")
        }
        // Installable builds are named "debug" for history's sake but are signed with the
        // stable key and use the real application id, so they install over each other and
        // keep the user's data. A separate `.debug` id would have made every build a second
        // app that could never update the first.
        getByName("debug") {
            versionNameSuffix = "-debug"
            if (keystoreFile.exists()) signingConfig = signingConfigs.getByName("stable")
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
