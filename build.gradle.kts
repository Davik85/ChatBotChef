import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.bundling.Jar
import org.gradle.kotlin.dsl.register

plugins {
    kotlin("jvm") version "2.0.21"
    application
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

kotlin { jvmToolchain(21) }

application {
    mainClass.set("app.MainKt")
}

/**
 * Обычный (тонкий) JAR — чтобы запускать с classpath.
 */
tasks.withType<Jar> {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest { attributes["Main-Class"] = "app.MainKt" }
}

/**
 * “Толстый” JAR без сторонних плагинов.
 * Готовый файл: build/libs/ChatBotChef-all.jar
 */
tasks.register<Jar>("fatJar") {
    group = "build"
    archiveBaseName.set("ChatBotChef")
    archiveClassifier.set("all")

    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest { attributes["Main-Class"] = "app.MainKt" }

    // Кладём свои классы
    from(sourceSets.main.get().output)

    // Кладём зависимости
    val cp = configurations.runtimeClasspath.get()
    dependsOn(cp)
    from(
        cp.filter { it.name.endsWith(".jar") }
            .map { zipTree(it) }
    )
}
