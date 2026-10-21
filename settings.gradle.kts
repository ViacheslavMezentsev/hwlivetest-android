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
        // usb-serial-for-android публикуется только в JitPack; оттуда берётся только эта группа.
        exclusiveContent {
            forRepository { maven("https://jitpack.io") }
            filter { includeGroup("com.github.mik3y") }
        }
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
