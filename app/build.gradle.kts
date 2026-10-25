import java.time.Instant
import java.time.temporal.ChronoUnit
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Сведения о сборке для окна «О программе»: коммит, время сборки (UTC), номер запуска CI.
val gitSha: String = System.getenv("GITHUB_SHA")?.take(8) ?: runCatching {
    providers.exec {
        commandLine("git", "rev-parse", "--short=8", "HEAD")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim()
}.getOrDefault("").ifEmpty { "unknown" }
val buildInstant: Instant = Instant.now().truncatedTo(ChronoUnit.SECONDS)
val buildTime: String = buildInstant.toString()
// versionCode — минуты от 2026-01-01 UTC: растёт с каждой сборкой, и локальной, и в CI, поэтому
// любой более новый APK ставится поверх старого без удаления (данные приложения сохраняются).
val versionMinutes: Int = ((buildInstant.epochSecond - Instant.parse("2026-01-01T00:00:00Z").epochSecond) / 60).toInt()
val ciRun: Int? = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
// versionName: последнее число увеличивается с каждой сборкой, отданной на установку (AGENTS.md).
val appVersion = "0.3.27"

android {
    namespace = "io.github.viacheslavmezentsev.hwlivetest"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "io.github.viacheslavmezentsev.hwlivetest"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = versionMinutes
        versionName = appVersion
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
        compose = true
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
    // Интерфейс на Jetpack Compose (Material 3): решение владельца 22.10.2026, docs/roadmap.md.
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.activity.compose)
    testImplementation(libs.junit)
}

// Имя APK с версией: hwlivetest-<версия>-debug.apk — файлы разных сборок не путаются.
base {
    archivesName.set("hwlivetest-$appVersion")
}
