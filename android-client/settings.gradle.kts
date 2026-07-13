pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "webterm"
include(":app")
include(":terminal-interaction")
include(":terminal-protocol")
include(":terminal-model")
include(":terminal-renderer")
include(":transport-api")
include(":transport-websocket")
include(":core-api")
include(":core-config")
include(":core-cache")
include(":core-session")
include(":core-relay")
include(":ui-common")
include(":feature:settings")
include(":feature:relay")
include(":feature:terminal")
include(":feature:home")
