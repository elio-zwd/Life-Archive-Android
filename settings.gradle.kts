pluginManagement {
    repositories {
        google()
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

rootProject.name = "LifeArchive"
include(":app", ":core:designsystem", ":core:document", ":core:backup", ":core:preferences", ":domain:notes", ":data:notes")
include(":feature:notes", ":feature:editor")
include(":feature:settings")
include(":domain:sync", ":data:sync")
include(":core:markdown")
include(":domain:jianyu", ":data:jianyu")
include(":feature:jianyu")
include(":domain:ai", ":data:ai", ":domain:pet", ":data:pet", ":feature:pet")
