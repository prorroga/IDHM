import org.apache.tools.ant.filters.ReplaceTokens

plugins {
    id("java-library")
    id("com.gradleup.shadow") version "9.6.1"
    id("xyz.jpenilla.run-paper") version "3.1.0"
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.catnies.top/releases")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT")
    implementation("net.momirealms:sparrow-yaml:1.0.6")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

val pluginVersion = providers.gradleProperty("version").getOrElse("dev")
val configVersion = providers.gradleProperty("config_version").getOrElse("1")
val projectDescription = project.description ?: ""

tasks {
    build {
        dependsOn(shadowJar)
    }

    shadowJar {
        archiveBaseName.set("IDHM")
        archiveClassifier.set("")
        archiveVersion.set("")
        relocate("net.momirealms.sparrow", "net.prorrogam.idhm.libraries.sparrow")
    }

    runServer {
        minecraftVersion("1.21.8")
    }

    processResources {
        filteringCharset = "UTF-8"

        inputs.property("plugin_version", pluginVersion)
        inputs.property("config_version", configVersion)
        inputs.property("description", projectDescription)

        filesMatching("plugin.yml") {
            filter<ReplaceTokens>("tokens" to mapOf(
                "version" to pluginVersion,
                "description" to projectDescription
            ))
        }
        filesMatching("config.yml") {
            filter<ReplaceTokens>("tokens" to mapOf(
                "config_version" to configVersion
            ))
        }
        filesMatching("idhm.properties") {
            filter<ReplaceTokens>("tokens" to mapOf(
                "plugin_version" to pluginVersion,
                "config_version" to configVersion
            ))
        }
    }
}