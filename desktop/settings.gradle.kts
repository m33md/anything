// Standalone build for the Windows/desktop app. It compiles the shared, platform-free
// innertube and lyrics sources directly, so it needs neither the Android Gradle plugin
// nor an Android SDK.

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
        maven { setUrl("https://jitpack.io") }
    }
}

rootProject.name = "convx-desktop"
