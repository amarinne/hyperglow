pluginManagement {
    repositories {
        mavenLocal()
        mavenCentral()
        google()
        gradlePluginPortal()
        maven("https://api.xposed.info/")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // miuix publishes to Maven Central, so the build needs no credentials. Anyone who clones
        // the public mirror can build; only signing a release requires the owner's private keystore.
        mavenLocal()
        mavenCentral()
        google()
    }
}

rootProject.name = "hyperglow"
include(":app")
