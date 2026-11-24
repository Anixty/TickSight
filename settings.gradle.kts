pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven("https://maven.fabricmc.net/")
        maven("https://maven.architectury.dev/")
        maven("https://maven.minecraftforge.net/")
        maven("https://maven.neoforged.net/releases/")
        maven("https://maven.kikugie.dev/releases")
    }
}
plugins {
    id("dev.kikugie.stonecutter") version "0.9.7"
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
rootProject.name = "TickSight"
include("call-trace-agent")
stonecutter {
    centralScript = "build.gradle"
    kotlinController = true
    create(rootProject) {
        versions("1.20.1", "1.21.1", "1.21.4")
        versions("26.1.2").buildscript("build.26.gradle")
        branch("fabric") {
            inherit()
            versions("26.1.2").buildscript("build.26.gradle")
        }
        branch("forge") { versions("1.20.1") }
        branch("neoforge") {
            versions("1.21.1", "1.21.4")
            versions("26.1.2").buildscript("build.26.gradle")
        }
        vcsVersion = "1.21.1"
    }
}

