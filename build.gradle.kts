// Built the same way as Olympus Reader: Kotlin + Compose Desktop, shipped as a folder
// with app\*.jar, a bundled Java runtime and .bat launchers (see packaging/).
plugins {
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.compose") version "2.1.0"
    kotlin("plugin.serialization") version "2.1.0"
}

group = "com.kolnovel.reader"
version = "0.4.0"

repositories {
    mavenCentral()
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // Compose Desktop 1.7.3 + OkHttp + Jsoup + kotlinx.serialization, the same jar set
    // Olympus Reader ships with (Google Maven is not reachable from the build machine,
    // so the jars come from libs/ instead of being resolved).
    compileOnly(fileTree("libs") { include("*.jar") })
    testImplementation(kotlin("test"))
    testImplementation(fileTree("libs") { include("*.jar"); exclude("skiko-awt-runtime-windows*") })
    // Lets the screenshot test draw the UI on Linux.
    testImplementation("org.jetbrains.skiko:skiko-awt-runtime-linux-x64:0.8.18")
}

tasks.jar {
    archiveFileName.set("KolNovelReader-${project.version}.jar")
    manifest { attributes("Main-Class" to "com.kolnovel.reader.MainKt") }
}

tasks.test { useJUnitPlatform() }
