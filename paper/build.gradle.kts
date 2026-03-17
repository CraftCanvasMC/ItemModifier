plugins {
    id("xyz.jpenilla.run-paper") version "2.3.1"
    id("io.canvasmc.weaver.userdev") version "2.3.12"
}

dependencies {
    paperweight.paperDevBundle("1.21.11-R0.1-SNAPSHOT")
}

tasks {
    runServer {
        minecraftVersion("1.21.11")
    }
}
