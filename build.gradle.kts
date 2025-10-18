import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.bundling.Jar
import org.gradle.kotlin.dsl.getByName
import org.gradle.kotlin.dsl.registering
import org.gradle.kotlin.dsl.the

plugins {
    kotlin("jvm") version "2.0.21"
    application
    id("com.github.johnrengelman.shadow") version "8.3.0" // сам плагин — без импортов его классов
}

repositories { mavenCentral() }

dependencies {
    // DB
    implementation("org.jetbrains.exposed:exposed-core:0.53.0")
    implementation("org.jetbrains.exposed:exposed-dao:0.53.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.53.0")
    implementation("org.xerial:sqlite-jdbc:3.45.3.0")

    // HTTP + JSON + .env
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.2")
    implementation("io.github.cdimascio:dotenv-kotlin:6.4.1")

    // Логи
    implementation("org.slf4j:slf4j-simple:2.0.13")

    testImplementation(kotlin("test"))
}

application {
    mainClass.set("app.MainKt")
}

tasks.jar {
    // Обычный jar будет «тонким». Выполнять можно через shadowJar (толстый).
    manifest {
        attributes["Main-Class"] = "app.MainKt"
    }
}

tasks.withType<Jar>().configureEach {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

val mainSourceSet = the<SourceSetContainer>().getByName("main")

val shadowJar by tasks.registering(Jar::class) {
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes["Main-Class"] = "app.MainKt"
    }

    from(mainSourceSet.output)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.exists() && it.extension == "jar" }
            .map { zipTree(it) }
    })
}

tasks.named("assemble") {
    dependsOn(shadowJar)
}
