plugins {
    java
    id("xyz.jpenilla.run-paper") version "2.3.1"
    id("io.canvasmc.weaver.userdev") version "2.3.12"
}

group = "io.canvasmc.itemmodifer"
version = "1.0.0-SNAPSHOT"

dependencies {
    paperweight.paperDevBundle("1.21.11-R0.1-SNAPSHOT")
}

tasks {
    runServer {
        minecraftVersion("1.21.11")
    }
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

tasks {
    compileJava {
        options.release = 21
    }
    javadoc {
        options.encoding = Charsets.UTF_8.name()
    }
}