import java.util.Properties

pluginManagement {
    repositories {
        maven("https://api.xposed.info/")
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
        val properties = Properties()
        val localProperties = File(rootDir, "local.properties")
        if (localProperties.exists()) properties.load(localProperties.inputStream())
        maven {
            url = uri("https://maven.pkg.github.com/compose-miuix-ui/miuix")
            credentials {
                username = properties.getProperty("gpr.user") ?: System.getenv("GITHUB_ACTOR")
                password = properties.getProperty("gpr.key") ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
}

rootProject.name = "hyperglow"
include(":app")
