plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release version comes from the git tag in CI (-PversionName=2.0); defaults to 1.0 locally.
val facetVersionName: String = (project.findProperty("versionName") as String?) ?: "1.0"
val facetVersionCode: Int = (project.findProperty("versionCode") as String?)?.toIntOrNull()
    ?: facetVersionName.substringBefore('.').toIntOrNull()
    ?: 1

android {
    namespace = "app.facet.browser"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.facet.browser"
        minSdk = 26
        targetSdk = 34
        versionCode = facetVersionCode
        versionName = facetVersionName
    }

    val keystorePath: String? = System.getenv("FACET_KEYSTORE")
    if (keystorePath != null && file(keystorePath).exists()) {
        signingConfigs {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("FACET_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("FACET_KEY_ALIAS")
                keyPassword = System.getenv("FACET_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}
