import java.util.Properties

pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
        val properties = Properties()
        val localProperties = File(rootDir, "local.properties")
        if (localProperties.exists()) properties.load(localProperties.inputStream())
        val gprUser = properties.getProperty("gpr.user") ?: System.getenv("GITHUB_ACTOR")
        val gprKey = properties.getProperty("gpr.key") ?: System.getenv("GITHUB_TOKEN")
        if (!gprUser.isNullOrBlank() && !gprKey.isNullOrBlank()) {
            maven {
                url = uri("https://maven.pkg.github.com/compose-miuix-ui/miuix")
                credentials {
                    username = gprUser
                    password = gprKey
                }
            }
        }
    }
}

rootProject.name = "hyperglow"
include(":app")
