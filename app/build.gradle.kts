plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.hiltAndroid)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlinCompose)
    alias(libs.plugins.baselineprofile)
}

// Release signing material, present only in CI. Absent locally, which leaves
// the signing config empty and assembleRelease/bundleRelease output unsigned.
val keystoreFile: String? = System.getenv("KEYSTORE_FILE")
val keystorePassword: String? = System.getenv("KEYSTORE_PASSWORD")
val releaseKeyAlias: String? = System.getenv("KEY_ALIAS")
val releaseKeyPassword: String? = System.getenv("KEY_PASSWORD")
val hasSigningEnv = listOf(
    keystoreFile, keystorePassword, releaseKeyAlias, releaseKeyPassword
).all { it != null }

android {
    namespace = "com.bizzarosn.heightmark"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.bizzarosn.heightmark"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()

        // Support dynamic versioning from CI, with local fallback
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 4
        versionName = project.findProperty("versionName") as String? ?: "1.0.0-dev"

        testInstrumentationRunner = "com.bizzarosn.heightmark.HiltTestRunner"
        // With the orchestrator below, `pm clear` runs before every test. That
        // also resets runtime permissions, so each test sees exactly what its
        // own GrantPermissionRule granted, and no DataStore state carries over.
        testInstrumentationRunnerArguments["clearPackageData"] = "true"
    }


    signingConfigs {
        create("release") {
            if (hasSigningEnv) {
                storeFile = file(keystoreFile!!)
                storePassword = keystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        // Generate debug symbols for native code
        all {
            ndk {
                debugSymbolLevel = "FULL"
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasSigningEnv) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            isMinifyEnabled = false
            isDebuggable = true
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }
    buildFeatures {
        compose = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.jvmTarget.get())
        targetCompatibility = JavaVersion.toVersion(libs.versions.jvmTarget.get())
    }
    testOptions {
        // One instrumentation process per test: GrantPermissionRule grants
        // can't be revoked mid-run, so without this the first test to grant
        // fine location would leave it granted for every later test.
        execution = "ANDROIDX_TEST_ORCHESTRATOR"
        unitTests.all {
            it.maxParallelForks = Runtime.getRuntime().availableProcessors()
        }
        // Run with ./gradlew allGroupDebugAndroidTest, or one device with
        // ./gradlew pixel8proapi36DebugAndroidTest. AGP downloads the image,
        // boots a headless emulator, runs the tests, and shuts it down.
        // aosp-atd: no Google services, which the app never uses, and a
        // lighter image than google_apis.
        //
        // API 36 is the newest aosp-atd image Google publishes. Behavior gated
        // on targetSdk (forced edge-to-edge, default predictive back) only
        // runs on a device at that API level or above, so this device is the
        // only one that sees the platform the app targets. Move it up when a
        // newer aosp-atd image appears.
        managedDevices {
            // CI runs one job per device; android_build.yml's matrix must list
            // the same devices.
            val deviceApiLevels = listOf(35, 36)
            localDevices {
                deviceApiLevels.forEach { level ->
                    create("pixel8proapi$level") {
                        device = "Pixel 8 Pro"
                        apiLevel = level
                        systemImageSource = "aosp-atd"
                        // The emulator runs only images of the host's own ABI,
                        // and the x86_64 aosp-atd image has no ARM translation.
                        // AGP 10 defaults testedAbi to arm64-v8a on every host,
                        // which would leave x86_64 CI runners without a usable
                        // device.
                        testedAbi = when (System.getProperty("os.arch")) {
                            "aarch64", "arm64" -> "arm64-v8a"
                            else -> "x86_64"
                        }
                    }
                }
            }
            groups {
                create("all") {
                    deviceApiLevels.forEach { targetDevices.add(localDevices["pixel8proapi$it"]) }
                }
            }
        }
    }
    // The dependency-metadata block AGP writes into the APK signing block is
    // encrypted with Google's key. F-Droid's scanner rejects it as an opaque
    // blob, so the sideload APK leaves it out. The Play bundle keeps it,
    // because Play uses it for SDK Index advisories.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = true
    }
    // AGP strips native libraries only when an NDK is installed, so
    // stripping would make the release APK differ between build machines.
    // Unstripped, the AAR libraries ship as published, for about 9 KB.
    packaging {
        jniLibs {
            keepDebugSymbols += "**/*.so"
        }
    }
    lint {
        // Severities live in lint.xml, where the whole Accessibility category
        // is promoted to error; abortOnError (the AGP default, made explicit)
        // turns those findings into CI build failures. warningsAsErrors gives
        // every other warning the same weight, so none piles up unseen.
        abortOnError = true
        warningsAsErrors = true
    }
}

baselineProfile {
    // The profile in src/release/generated/baselineProfiles is committed.
    // Release builds, CI and F-Droid-style source builds read that file and
    // never boot an emulator to regenerate it.
    automaticGenerationDuringBuild = false
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":baselineprofile"))

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)

    // Mocking for unit tests
    testImplementation(libs.mockk)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.espresso.accessibility)
    androidTestImplementation(libs.accessibility.test.framework)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestUtil(libs.androidx.test.orchestrator)
    androidTestUtil(libs.androidx.test.services)

    // Hilt Testing
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.compiler)
}
