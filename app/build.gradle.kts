import java.time.Instant
import java.time.temporal.ChronoUnit
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Сведения о сборке для окна «О программе»: коммит, время сборки (UTC), номер запуска CI.
val gitSha: String = System.getenv("GITHUB_SHA")?.take(8) ?: runCatching {
    providers.exec {
        commandLine("git", "rev-parse", "--short=8", "HEAD")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim()
}.getOrDefault("").ifEmpty { "unknown" }
val buildTime: String = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()
val ciRun: Int? = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()

android {
    namespace = "io.github.viacheslavmezentsev.hwlivetest"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "io.github.viacheslavmezentsev.hwlivetest"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        // Номер запуска GitHub Actions растёт с каждой сборкой CI: по нему видно, что APK новый.
        versionCode = ciRun ?: 1
        versionName = "0.2.0"
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
        buildConfigField("String", "BUILD_TIME", "\"$buildTime\"")
        buildConfigField("String", "BUILD_ORIGIN", "\"${if (ciRun != null) "ci-$ciRun" else "local"}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":usb"))
    implementation(project(":stlink"))
    implementation(project(":fx2la"))
    implementation(project(":recorder"))
    implementation(project(":telemetry"))
    implementation(project(":diag"))
    implementation(project(":firmware"))
    testImplementation(libs.junit)
}
