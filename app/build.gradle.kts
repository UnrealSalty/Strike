plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val debugAbi = providers.gradleProperty("android.injected.build.abi").orNull
    ?.split(',')?.firstOrNull { it == "arm64-v8a" || it == "x86_64" } ?: "arm64-v8a"

android {
    namespace = "com.strike"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.strike"
        // minSdk 28: ImageReader hardware buffers on the head unit.
        // targetSdk 25: keeps parked surveillance free of O background limits.
        minSdk = 28
        targetSdk = 25
        versionCode = 5
        versionName = "0.5"

        externalNativeBuild { cmake { arguments += "-DANDROID_STL=none" } }
    }

    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }

    buildTypes {
        debug {
            isMinifyEnabled = false
            ndk { abiFilters += debugAbi }
        }
        release {
            ndk { abiFilters += "arm64-v8a" }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions { jvmTarget = "11" }

    androidResources { noCompress += listOf("html", "css", "js") }

    lint { disable += "ExpiredTargetSdkVersion" }

    // android.util.Log throws from the mockable android.jar without this.
    testOptions { unitTests.isReturnDefaultValues = true }

    packaging { resources.excludes += setOf("META-INF/LICENSE.md", "META-INF/LICENSE-notice.md") }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "kotlin/**")
        // app_process loads native libraries from extracted files.
        jniLibs { useLegacyPackaging = true }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.dadb)
    implementation(libs.tensorflow.lite)
    testImplementation(libs.junit)
    // Use real org.json in unit tests; android.jar only provides stubs.
    testImplementation(libs.json)
}
