plugins {
    alias(libs.plugins.androidTest)
    alias(libs.plugins.baselineprofile)
}

// One device generates every committed profile and runs StartupBenchmark.
val PROFILE_DEVICE = "pixel8proapi36profile"

android {
    namespace = "com.bizzarosn.heightmark.baselineprofile"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Every device this module runs on is a Gradle Managed Device.
        // Emulator timings are fine for comparing two compilation modes on the
        // same device, which is all StartupBenchmark does.
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR"
    }

    targetProjectPath = ":app"

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.jvmTarget.get())
        targetCompatibility = JavaVersion.toVersion(libs.versions.jvmTarget.get())
    }

    testOptions.managedDevices.localDevices {
        create(PROFILE_DEVICE) {
            device = "Pixel 8 Pro"
            apiLevel = 36
            systemImageSource = "aosp-atd"
            // Same constraint as app/build.gradle.kts: the emulator runs only
            // images of the host's own ABI.
            testedAbi = when (System.getProperty("os.arch")) {
                "aarch64", "arm64" -> "arm64-v8a"
                else -> "x86_64"
            }
        }
    }
}

baselineProfile {
    managedDevices += PROFILE_DEVICE
    // Generation never touches an emulator or phone that happens to be
    // attached, so every committed profile comes from the same device.
    useConnectedDevices = false
}

dependencies {
    implementation(libs.androidx.junit)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
