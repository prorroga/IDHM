import org.apache.tools.ant.filters.ReplaceTokens
import org.gradle.kotlin.dsl.repositories

plugins {
    id("java-library")
    id("com.gradleup.shadow") version "9.6.1"
    id("xyz.jpenilla.run-paper") version "3.1.0"
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.catnies.top/releases")
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
    maven("https://jitpack.io")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT")
    compileOnly("me.clip:placeholderapi:2.11.6")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7") {
        exclude(group = "org.bukkit", module = "bukkit")
    }
    implementation("net.momirealms:sparrow-yaml:1.0.6")
    implementation("com.h2database:h2:2.3.232")
    implementation("com.zaxxer:HikariCP:6.2.1")
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
        relocate("org.h2", "net.prorrogam.idhm.libraries.h2")
        relocate("com.zaxxer.hikari", "net.prorrogam.idhm.libraries.hikari")
    }

    runServer {
        minecraftVersion("1.21.8")
        jvmArgs("-Xms2G", "-Xmx2G", "-Duser.language=en", "-Duser.country=US")
        workingDirectory.set(layout.projectDirectory.dir("run"))
    }

    processResources {
        filteringCharset = "UTF-8"

        inputs.property("plugin_version", pluginVersion)
        inputs.property("config_version", configVersion)
        inputs.property("description", projectDescription)

        filesMatching("paper-plugin.yml") {
            filter(ReplaceTokens::class, "tokens" to mapOf(
                "version" to pluginVersion,
                "description" to projectDescription
            ))
        }
        filesMatching("config.yml") {
            filter(ReplaceTokens::class, "tokens" to mapOf(
                "config_version" to configVersion
            ))
        }
        filesMatching("idhm.properties") {
            filter(ReplaceTokens::class, "tokens" to mapOf(
                "plugin_version" to pluginVersion,
                "config_version" to configVersion
            ))
        }
    }
}