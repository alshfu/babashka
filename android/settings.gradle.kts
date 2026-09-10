pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // libadb-android (свой ADB-клиент с TLS-парингом) публикуется через JitPack.
        maven("https://jitpack.io")
    }
}

rootProject.name = "pult"

// core — общий код протокола и крипто пары. Дублировать его в двух приложениях нельзя:
// разъедется, и телефоны перестанут подтверждать пару друг другу.
include(":core")
include(":grandma")
