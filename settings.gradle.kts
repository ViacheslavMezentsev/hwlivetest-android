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

rootProject.name = "hwlivetest-android"

include(":app")
include(":usb")
include(":stlink")
include(":fx2la")
include(":recorder")
include(":telemetry")
include(":diag")
include(":firmware")
