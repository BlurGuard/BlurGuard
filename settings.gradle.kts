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
    }
}

rootProject.name = "BlurGuard"

// App
include(":app")

// Core
include(":core:common")
include(":core:model")
include(":core:domain")
include(":core:data")
include(":core:designsystem")

// Features
include(":feature:camera")
include(":feature:gallery")
include(":feature:settings")

// Engine
include(":engine:api")
include(":engine:impl")
include(":engine:camera")
include(":engine:render")
include(":engine:ml")
include(":engine:tracking")
include(":engine:recognition")

// Benchmarks
include(":benchmark")
