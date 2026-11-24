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
rootProject.name = "DSHN"
include(
    ":app",
    ":core:security",
    ":core:data",
    ":core:bridge",
    ":core:runtime",
    ":core:ui",
    ":feature:setup",
    ":feature:chat",
    ":feature:terminal",
    ":feature:bridge",
    ":feature:settings"
)