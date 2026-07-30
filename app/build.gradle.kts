plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.hiltAndroid)
    alias(libs.plugins.ksp)
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
    compileSdk = 37

    defaultConfig {
        applicationId = "com.bizzarosn.heightmark"
        minSdk = 34
        targetSdk = 36

        // Support dynamic versioning from CI, with local fallback
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 4
        versionName = project.findProperty("versionName") as String? ?: "1.0.0-dev"

        testInstrumentationRunner = "com.bizzarosn.heightmark.HiltTestRunner"
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
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    testOptions {
        unitTests.all {
            it.maxParallelForks = Runtime.getRuntime().availableProcessors()
        }
    }
    lint {
        // Severities live in lint.xml, where the whole Accessibility category
        // is promoted to error; abortOnError (the AGP default, made explicit)
        // turns those findings into CI build failures.
        abortOnError = true
    }
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
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.androidx.datastore.preferences)

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

    // Hilt Testing
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.compiler)
}
