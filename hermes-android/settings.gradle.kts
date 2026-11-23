pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Termux's terminal-emulator and terminal-view libraries (Apache 2.0) are published here.
        maven("https://jitpack.io") { content { includeGroup("com.github.termux.termux-app") } }
    }
}
rootProject.name = "HermesMobile"
include(":app")
