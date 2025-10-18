import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.plugins.JavaBasePlugin
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.bundling.Jar
import org.gradle.kotlin.dsl.getByName
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.the

plugins {
    kotlin("jvm") version "2.0.21"
    application
}

repositories {
    mavenCentral()
}

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

    // Логи (чтобы не было NOP)
    implementation("org.slf4j:slf4j-simple:2.0.13")

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
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

val shadowJar = tasks.register<Jar>("shadowJar") {
    archiveClassifier.set("all")
    group = JavaBasePlugin.BUILD_GROUP
    description = "Assembles an executable fat JAR that bundles all runtime dependencies."
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    manifest {
        attributes["Main-Class"] = "app.MainKt"
    }

    from(mainSourceSet.output)
    val runtimeClasspath = configurations.named("runtimeClasspath")
    from({
        runtimeClasspath.get().filter { it.isDirectory }
    })
    from({
        runtimeClasspath.get()
            .filter { it.isFile && it.extension == "jar" }
            .map { zipTree(it) }
    })
}

tasks.named("assemble") {
    dependsOn(shadowJar)
}
