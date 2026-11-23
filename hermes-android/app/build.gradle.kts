plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "me.ashwinkarthick.hermes"
    compileSdk = 35

    defaultConfig {
        applicationId = "me.ashwinkarthick.hermes"
        minSdk = 26
        // 28 on purpose: apps targeting 29+ may not run programs from their own storage, which the
        // bundled Linux needs (same choice as Termux). This also means no Google Play listing.
        targetSdk = 28
        versionCode = 2
        versionName = "0.1.1-proof"
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        // TEST-ONLY key, committed on purpose so every build (local or CI) can update the previous
        // one in place. Never use it for a public release.
        create("test") {
            storeFile = rootProject.file("signing/hermes-mobile-test.jks")
            storePassword = "hermes-mobile-test"
            keyAlias = "hermes-mobile-test"
            keyPassword = "hermes-mobile-test"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("test") }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("test")
        }
    }

    // Keep native libraries as real files on disk (proot and its loader are run from there).
    packaging { jniLibs { useLegacyPackaging = true } }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }

    lint {
        // Target SDK 28 is deliberate (see above).
        disable += setOf("ExpiredTargetSdkVersion", "OldTargetApi")
        checkReleaseBuilds = false
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

val prootLibs = file("src/main/jniLibs/arm64-v8a")
tasks.named("preBuild") {
    doFirst {
        val need = listOf("libproot.so", "libproot-loader.so", "libtalloc.so", "libandroid-shmem.so")
        val missing = need.filterNot { File(prootLibs, it).isFile }
        if (missing.isNotEmpty()) {
            throw GradleException("Missing $missing in $prootLibs. Run tools/fetch-proot.sh first.")
        }
    }
}

dependencies {
    implementation("com.github.termux.termux-app:terminal-view:v0.118.1")
    implementation("org.apache.commons:commons-compress:1.27.1")
    testImplementation("junit:junit:4.13.2")
}

tasks.withType<Test>().configureEach {
    System.getProperty("rootfs.tarball")?.let { systemProperty("rootfs.tarball", it) }
    testLogging { events("passed", "failed", "skipped"); showStandardStreams = true; exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
