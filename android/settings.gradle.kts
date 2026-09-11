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
    // Маркерные POM плагинов (…​.gradle.plugin) у AGP лежат на dl.google.com, у Kotlin —
    // на портале, и вместе с основным артефактом они не кэшируются: при недоступности
    // этих хостов сборка падает, хотя сами плагины в кэше есть. Ссылаемся на модули
    // напрямую, маркеры не нужны.
    resolutionStrategy {
        eachPlugin {
            val id = requested.id.id
            when (id) {
                "com.android.application", "com.android.library" ->
                    useModule("com.android.tools.build:gradle:${requested.version}")
                "org.jetbrains.kotlin.android" ->
                    useModule("org.jetbrains.kotlin:kotlin-gradle-plugin:${requested.version}")
                "org.jetbrains.kotlin.plugin.serialization" ->
                    useModule("org.jetbrains.kotlin:kotlin-serialization:${requested.version}")
                else -> Unit
            }
        }
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
