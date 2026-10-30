plugins {
    id("com.android.application")
}

// Release version comes from the git tag in CI (-PversionName=2.0); defaults to 1.0 locally.
val facetVersionName: String = (project.findProperty("versionName") as String?) ?: "1.0"
val facetVersionCode: Int = (project.findProperty("versionCode") as String?)?.toIntOrNull()
    ?: ((facetVersionName.substringBefore('.').toIntOrNull() ?: 1) * 1000 +
        (facetVersionName.substringAfter('.', "0").toIntOrNull() ?: 0))

val geckoVersion: String = (project.findProperty("geckoVersion") as String?) ?: "LATEST"
val geckoArtifact: String = (project.findProperty("geckoArtifact") as String?) ?: "geckoview-arm64-v8a"

android {
    namespace = "app.facet.browser"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.facet.browser"
        minSdk = 26
        targetSdk = 34
        versionCode = facetVersionCode
        versionName = facetVersionName
        manifestPlaceholders["geckoVersion"] = geckoVersion
    }

    // One shared debug key, committed on purpose, so every debug build can update the previous one.
    // Release builds use the FACET_KEYSTORE secrets when present.
    val keystorePath: String? = System.getenv("FACET_KEYSTORE")
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (keystorePath != null && file(keystorePath).exists()) {
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
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    // Mozilla's browser engine, bundled inside the APK. Facet is standalone: no system WebView.
    implementation("org.mozilla.geckoview:$geckoArtifact:$geckoVersion")
}
