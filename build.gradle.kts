// AGP 9 ships its own Kotlin support. GeckoView depends on a newer Kotlin standard library
// than AGP bundles, so the Kotlin compiler is raised to match.
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    }
}

plugins {
    id("com.android.application") version "9.1.0" apply false
}
