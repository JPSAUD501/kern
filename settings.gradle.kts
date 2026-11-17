pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "kern"

include(":app", ":engine", ":bench")
project(":app").projectDir = file("android/app")
project(":engine").projectDir = file("android/engine")
project(":bench").projectDir = file("android/bench")
