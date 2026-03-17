pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
        maven {
            name = "canvasmc"
            url = uri("https://maven.canvasmc.io/releases")
        }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "ItemModifier"
