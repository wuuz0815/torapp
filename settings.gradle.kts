// Datei:  settings.gradle.kts – Projekteinstellungen der Tor-App
// Autor:  Stefan Weinzetl
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
rootProject.name = "TorApp"
include(":app")
