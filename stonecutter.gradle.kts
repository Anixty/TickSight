plugins {
    id("dev.kikugie.stonecutter")
    id("dev.architectury.loom") version "1.17-SNAPSHOT" apply false
    id("dev.architectury.loom-no-remap") version "1.17-SNAPSHOT" apply false
    id("architectury-plugin") version "3.5-SNAPSHOT" apply false
    id("org.jetbrains.kotlin.jvm") version "2.4.10" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.10" apply false
    id("com.gradleup.shadow") version "9.6.0" apply false
}
stonecutter active "1.21.1" /* [SC] DO NOT EDIT */
tasks.register("chiseledBuild") {
    group = "build"
    description = "Builds every supported Minecraft and loader combination."
    dependsOn(stonecutter.tasks.named("build").map { it.values })
}
tasks.register("collectJars") {
    group = "build"
    description = "Builds all variants and collects distributable jars in build/libs."
    dependsOn(stonecutter.tasks.named("collectJar") { branch.id.isNotEmpty() }.map { it.values })
    dependsOn(":call-trace-agent:collectJar")
}
tasks.register("build") {
    group = "build"
    description = "Builds and collects every supported loader variant."
    dependsOn("collectJars")
}

