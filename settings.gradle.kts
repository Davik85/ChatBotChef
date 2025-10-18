pluginManagement {
    repositories {
        gradlePluginPortal()      // <-- обязательно для com.github.johnrengelman.shadow
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "ChatBotChef"
// если проект одномодульный — НИЧЕГО больше не включаем.
// если у тебя есть модуль app/, оставь строку ниже:
// include(":app")
