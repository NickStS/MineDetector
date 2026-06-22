import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    id("kotlin-parcelize")
}

// Read local.properties explicitly (project.findProperty does NOT load local.properties)
val localProps = Properties()
val localPropsFile = rootProject.file("local.properties")
if (localPropsFile.exists()) localPropsFile.inputStream().use { localProps.load(it) }

android {
    namespace = "com.minedetector"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.nick.minedetector"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // arm64-v8a — все Android устройства с 2016 года (99% реальных пользователей).
            // armeabi-v7a (32-bit) убран — экономит ~60-70 МБ на DJI нативных библиотеках.
            // Если нужна поддержка старых устройств — добавь "armeabi-v7a" обратно.
            abiFilters.addAll(listOf("arm64-v8a"))
        }

        // DJI API Key from local.properties (not committed to VCS)
        val djiApiKey = localProps.getProperty("DJI_API_KEY")
            ?: project.findProperty("DJI_API_KEY")?.toString()
            ?: ""
        manifestPlaceholders["DJI_API_KEY"] = djiApiKey
        buildConfigField("String", "DJI_API_KEY", "\"$djiApiKey\"")

        // Google Maps API Key from local.properties
        val googleMapsKey = localProps.getProperty("GOOGLE_MAPS_API_KEY")
            ?: project.findProperty("GOOGLE_MAPS_API_KEY")?.toString()
            ?: ""
        manifestPlaceholders["GOOGLE_MAPS_API_KEY"] = googleMapsKey

        // Mapbox Access Token from local.properties
        val mapboxToken = localProps.getProperty("MAPBOX_ACCESS_TOKEN")
            ?: project.findProperty("MAPBOX_ACCESS_TOKEN")?.toString()
            ?: ""
        buildConfigField("String", "MAPBOX_ACCESS_TOKEN", "\"$mapboxToken\"")

        // Server URL from local.properties
        val serverUrl = localProps.getProperty("SERVER_URL")
            ?: project.findProperty("SERVER_URL")?.toString()
            ?: "https://api.minedetector.example.com/"
        buildConfigField("String", "SERVER_URL", "\"$serverUrl\"")
    }

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

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        viewBinding = true
        dataBinding = true
        buildConfig = true
    }

    packaging {
        resources {
            merges += "dji/thirdparty/okhttp3/internal/publicsuffix/publicsuffixes.gz"

            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/LICENSE"
            excludes += "/META-INF/LICENSE.txt"
            excludes += "/META-INF/license.txt"
            excludes += "/META-INF/NOTICE"
            excludes += "/META-INF/NOTICE.txt"
            excludes += "/META-INF/notice.txt"
            excludes += "/META-INF/ASL2.0"
            excludes += "/META-INF/*.kotlin_module"
        }
    }
}

dependencies {
    // AndroidX Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.fragment.ktx)

    // MultiDex (used in MineDetectorApplication)
    implementation(libs.androidx.multidex)

    // Lifecycle & ViewModel
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)

    // Room Database - KSP instead of kapt for Kotlin 2.0+
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // DJI SDK v4
    implementation(libs.dji.sdk)
    compileOnly(libs.dji.sdk.provided)

    // DJI UX SDK v4 (pre-built widgets: FPVWidget, DashboardWidget, etc.)
    implementation(libs.dji.uxsdk) {
        exclude(group = "com.mapbox.mapboxsdk", module = "mapbox-android-core")
        exclude(group = "com.mapbox.mapboxsdk", module = "mapbox-android-telemetry")
    }


    // ExoPlayer / Media3
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.common)

    // TensorFlow Lite for mine detection
    implementation(libs.tensorflow.lite)
    implementation(libs.tensorflow.lite.gpu)
    implementation(libs.tensorflow.lite.support)

    // Image processing - PhotoView
    implementation(libs.photoview)

    // Image Loading - Coil (used in MediaViewerActivity, MediaAdapter)
    implementation(libs.coil)

    // Mapbox for mapping
    implementation(libs.mapbox.android)

    // Google Play Services Maps (required by DJI MapWidget)
    implementation(libs.play.services.maps)

    // Network
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)

    // WorkManager (used in ModelUpdateService, SyncWorker)
    implementation(libs.androidx.work.runtime.ktx)

    // Permissions (used in MainMenuActivity)
    implementation(libs.permissionx)

    // ExifInterface (used in ExifHelper)
    implementation(libs.androidx.exifinterface)

    // Preferences (used in SettingsActivity)
    implementation(libs.androidx.preference.ktx)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}