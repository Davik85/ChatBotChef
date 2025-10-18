import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.bundling.Jar

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

kotlin { jvmToolchain(21) }

/**
 * Обычный JAR (тонкий).
 */
tasks.withType<Jar> {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest { attributes["Main-Class"] = "app.MainKt" }
}

/**
 * Теневой JAR — настраиваем без ссылок на класс ShadowJar.
 * Задача `shadowJar` уже создаётся плагином; тут мы просто правим её как обычный Jar.
 */
tasks.named("shadowJar") {
    // приводим к Jar, чтобы IDE не требовала ShadowJar-класс
    val j = this as Jar
    j.archiveClassifier.set("all")
    j.manifest { attributes["Main-Class"] = "app.MainKt" }
}
