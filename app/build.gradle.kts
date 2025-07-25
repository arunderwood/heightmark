plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.jetbrainsKotlinAndroid)
}

android {
    namespace = "com.bizzarosn.heightmark"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.bizzarosn.heightmark"
        minSdk = 34
        targetSdk = 36
        versionCode = 4
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }


    signingConfigs {
        create("release") {
            // Only configure signing if environment variables are available (CI/CD)
            val keystoreFile = System.getenv("KEYSTORE_FILE")
            val keystorePassword = System.getenv("KEYSTORE_PASSWORD")
            val keyAlias = System.getenv("KEY_ALIAS")
            val keyPassword = System.getenv("KEY_PASSWORD")
            
            if (keystoreFile != null && keystorePassword != null && keyAlias != null && keyPassword != null) {
                storeFile = file(keystoreFile)
                storePassword = keystorePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Generate debug symbols for native code
            ndk {
                debugSymbolLevel = "FULL"
            }
            // Only use signing config if all environment variables are available
            val hasSigningConfig = System.getenv("KEYSTORE_FILE") != null && 
                                 System.getenv("KEYSTORE_PASSWORD") != null && 
                                 System.getenv("KEY_ALIAS") != null && 
                                 System.getenv("KEY_PASSWORD") != null
            if (hasSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            isMinifyEnabled = false
            isDebuggable = true
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            ndk {
                debugSymbolLevel = "FULL"
            }
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
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.androidx.datastore.preferences)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.runner)
}
