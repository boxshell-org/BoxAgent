import java.io.ByteArrayOutputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    // JVM screenshot rendering of Compose UI (src/test/.../ui): lets UI
    // changes be reviewed as images without a device.
    id("app.cash.paparazzi") version "1.3.5"
}

// ---- version derivation --------------------------------------------------
// versionName: latest git tag (v0.1.1-beta -> 0.1.1-beta), or VERSION_NAME env
// versionCode: commit count (monotonic on main), or VERSION_CODE env
fun gitOut(vararg args: String): String = try {
    val out = ByteArrayOutputStream()
    exec {
        commandLine("git", *args)
        standardOutput = out
        errorOutput = ByteArrayOutputStream()
        isIgnoreExitValue = true
    }
    out.toString().trim()
} catch (e: Exception) {
    ""
}

val appVersionName = (System.getenv("VERSION_NAME")
    ?: gitOut("describe", "--tags", "--abbrev=0"))
    .removePrefix("v").ifEmpty { "0.1.0-dev" }
val appVersionCode = System.getenv("VERSION_CODE")?.toIntOrNull()
    ?: gitOut("rev-list", "--count", "HEAD").toIntOrNull() ?: 1

// CI release builds sign with a persistent keystore injected via env so every
// published APK shares one identity and overwrite-install works. Local builds
// keep the per-machine debug key.
val ciKeystore = System.getenv("CI_KEYSTORE")?.takeIf { it.isNotEmpty() }

android {
    namespace = "com.boxagent.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.boxagent.app"
        minSdk = 30
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "CORE_LIB", "\"boxagent\"")
        // Only the ABIs cargo-ndk builds (scripts/build-rust.sh). Without
        // this, androidx's armeabi-v7a/x86 libs make 32-bit devices accept
        // the APK and then crash on loadLibrary("boxagent").
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    signingConfigs {
        if (ciKeystore != null) {
            create("ci") {
                storeFile = file(ciKeystore)
                storePassword = System.getenv("CI_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("CI_KEY_ALIAS") ?: "boxagent"
                keyPassword = System.getenv("CI_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (ciKeystore != null) signingConfig = signingConfigs.getByName("ci")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    buildFeatures {
        compose = true
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

    sourceSets {
        getByName("main") {
            jniLibs.srcDir("src/main/jniLibs")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

// Builds the Rust workspace with cargo-ndk:
//   - boxagent-core cdylib  -> jniLibs/<abi>/libboxagent.so
//   - boxagentd    bin      -> jniLibs/<abi>/libboxagentd.so (pushed to device at runtime)
val cargoNdkBuild = tasks.register<Exec>("cargoNdkBuild") {
    val skip = providers.gradleProperty("skipRustBuild").orNull == "true"
    onlyIf { !skip }
    workingDir = rootProject.file("core")
    commandLine = listOf(
        "bash", rootProject.file("scripts/build-rust.sh").absolutePath,
        rootProject.file("app/src/main/jniLibs").absolutePath
    )
    environment("ANDROID_NDK_HOME", providers.environmentVariable("ANDROID_NDK_HOME")
        .orElse(providers.environmentVariable("ANDROID_HOME").map { "$it/ndk/27.0.12077973" })
        .get())
    // Daemon binary is stamped with this; the app respawns it on mismatch.
    environment("BOXAGENT_APP_VERSION", appVersionName)
}

tasks.named("preBuild") {
    dependsOn(cargoNdkBuild)
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.4")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM tests (android.jar only has stubs).
    testImplementation("org.json:json:20240303")
}
