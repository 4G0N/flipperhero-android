pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "FlipperHero"

// Pure-JVM core modules (Step 1/2). :agent and :app are added in later steps.
include(":proto")
include(":flipper")
include(":agent")
