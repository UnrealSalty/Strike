plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.strike"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.strike"
        // minSdk 28: ImageReader hardware buffers on the head unit.
        // targetSdk 25: keeps parked surveillance free of O background limits.
        minSdk = 28
        targetSdk = 25
        versionCode = 1
        versionName = "0.1"

        ndk { abiFilters += "arm64-v8a" }

        // The camera's gralloc buffers reach GL through EGL entry points the
        // Java SDK does not expose, so that one bind is native and nothing else.
        externalNativeBuild { cmake { arguments += "-DANDROID_STL=none" } }
    }

    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
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

    // android.util.Log throws from the mockable android.jar without this.
    testOptions { unitTests.isReturnDefaultValues = true }

    // dadb drags in nine jars that each ship these.
    packaging { resources.excludes += setOf("META-INF/LICENSE.md", "META-INF/LICENSE-notice.md") }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "kotlin/**")
        // The daemon is app_process, not an app, so it can only load a library
        // from a real directory. Left packed, libstrike.so never reaches disk.
        jniLibs { useLegacyPackaging = true }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    // Shell UID 2000 is the only way to run pm disable-user, and there is no API for it.
    implementation(libs.dadb)
    // Person and vehicle boxes on device. tensorflow-lite-support is not here:
    // FrameSink already hands over a frame that fits the model's square, so
    // nothing needs its resize.
    implementation(libs.tensorflow.lite)
    testImplementation(libs.junit)
    // The mockable android.jar stubs org.json to return nothing, which makes every JSON test pass vacuously.
    testImplementation(libs.json)
}
