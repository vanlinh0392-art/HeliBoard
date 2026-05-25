import com.android.build.api.variant.ApplicationVariant
import org.gradle.api.Project
import java.io.File

fun Project.gitOriginUrl(): String? {
    fun resolveGitDir(): File? {
        val gitEntry = rootDir.resolve(".git")
        if (gitEntry.isDirectory) return gitEntry
        if (!gitEntry.isFile) return null
        val gitDirRef = gitEntry.readText().lineSequence()
            .firstOrNull { it.startsWith("gitdir:") }
            ?.substringAfter("gitdir:")
            ?.trim()
            .orEmpty()
        if (gitDirRef.isBlank()) return null
        return rootDir.resolve(gitDirRef).normalize()
    }

    return runCatching {
        val configFile = resolveGitDir()?.resolve("config") ?: return@runCatching null
        var insideOriginBlock = false
        for (line in configFile.readLines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("[remote ")) {
                insideOriginBlock = trimmed == "[remote \"origin\"]"
                continue
            }
            if (insideOriginBlock && trimmed.startsWith("url =")) {
                return@runCatching trimmed.substringAfter("=").trim().takeIf { it.isNotBlank() }
            }
        }
        null
    }.getOrNull()
}

fun parseGitHubRepo(url: String?): Pair<String, String> {
    if (url.isNullOrBlank()) return "" to ""
    val cleanedUrl = url.removeSuffix(".git")
    val match = Regex("""github\.com[:/]([^/]+)/([^/]+)$""").find(cleanedUrl) ?: return "" to ""
    return match.groupValues[1] to match.groupValues[2]
}

val detectedReleaseRepo = parseGitHubRepo(project.gitOriginUrl())
val githubReleaseRepoOwner = providers.gradleProperty("githubReleaseRepoOwner").orNull ?: detectedReleaseRepo.first
val githubReleaseRepoName = providers.gradleProperty("githubReleaseRepoName").orNull ?: detectedReleaseRepo.second
val githubReleaseRepoUrl = if (githubReleaseRepoOwner.isNotBlank() && githubReleaseRepoName.isNotBlank()) {
    "https://github.com/$githubReleaseRepoOwner/$githubReleaseRepoName/releases/latest"
} else {
    ""
}
val telegramBotToken = providers.gradleProperty("telegramBotToken")
    .orElse(providers.environmentVariable("TELEGRAM_BOT_TOKEN"))
    .orNull
    .orEmpty()

plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.serialization") version "2.2.21"
    kotlin("plugin.compose") version "2.2.21"
}

android {
    compileSdk = 35

    bundle {
        language {
            enableSplit = false
        }
    }

    defaultConfig {
        applicationId = "helium314.keyboard"
        minSdk = 21
        targetSdk = 35
        versionCode = 3611
        versionName = "3.6.8"
        buildConfigField(
            "String",
            "TELEGRAM_BOT_TOKEN",
            "\"$telegramBotToken\""
        )
        buildConfigField("String", "GITHUB_RELEASE_REPO_OWNER", "\"$githubReleaseRepoOwner\"")
        buildConfigField("String", "GITHUB_RELEASE_REPO_NAME", "\"$githubReleaseRepoName\"")
        buildConfigField("String", "GITHUB_RELEASES_URL", "\"$githubReleaseRepoUrl\"")
        ndk {
            abiFilters.clear()
            abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64"))
        }
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = false
            isDebuggable = false
            isJniDebuggable = false
        }
        create("releaseDebug") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".debug"
        }
        create("nouserlib") { // same as release, but does not allow the user to provide a library
            isMinifyEnabled = true
            isShrinkResources = false
            isDebuggable = false
            isJniDebuggable = false
        }
        debug {
            isMinifyEnabled = false
            isJniDebuggable = false
            applicationIdSuffix = ".debug"
        }
        create("runTests") { // build variant for running tests on CI that skips tests known to fail
            isMinifyEnabled = false
            isJniDebuggable = false
        }
        create("debugNoMinify") { // for faster builds in IDE
            isDebuggable = true
            isMinifyEnabled = false
            isJniDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".debug"
        }
        base.archivesBaseName = "HeliBoard_" + defaultConfig.versionName
        // got a little too big for GitHub after some dependency upgrades, so we remove the largest dictionary
        androidComponents.onVariants { variant: ApplicationVariant ->
            if (variant.buildType == "debug") {
                variant.androidResources.ignoreAssetsPatterns = listOf("main_ro.dict")
                variant.proguardFiles = emptyList()
                //noinspection ProguardAndroidTxtUsage we intentionally use the "normal" file here
                variant.proguardFiles.add(project.layout.buildDirectory.file(getDefaultProguardFile("proguard-android.txt").absolutePath))
                variant.proguardFiles.add(project.layout.buildDirectory.file(project.buildFile.parent + "/proguard-rules.pro"))
            }
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
        compose = true
    }

    externalNativeBuild {
        ndkBuild {
            path = File("src/main/jni/Android.mk")
        }
    }
    ndkVersion = "28.0.13004108"

    packaging {
        jniLibs {
            // shrinks APK by 3 MB, zipped size unchanged
            useLegacyPackaging = true
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_21.toString()
    }

    // see https://github.com/Helium314/HeliBoard/issues/477
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    namespace = "helium314.keyboard.latin"
    lint {
        abortOnError = true
        disable += "MissingTranslation"
    }
}

dependencies {
    // androidx
    implementation("androidx.core:core-ktx:1.16.0") // 1.17 requires SDK 36
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.autofill:autofill:1.3.0")
    implementation("androidx.viewpager2:viewpager2:1.1.0")

    // kotlin
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    // compose
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    // newer than 2025.11.01 contains androidx.compose.material:material-android:1.10.0, which requires minSdk 23
    // maybe it's possible to use tools:overrideLibrary="androidx.compose.material" as it's not used explicitly, but probably this is just going to crash
    implementation(platform("androidx.compose:compose-bom:2025.11.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.navigation:navigation-compose:2.9.6")
    implementation("sh.calvin.reorderable:reorderable:2.4.3") // for easier re-ordering, todo: check 3.0.0
    implementation("com.github.skydoves:colorpicker-compose:1.1.3") // for user-defined colors
    
    // coil for sticker image loading
    implementation("io.coil-kt:coil-compose:2.5.0")
    implementation("io.coil-kt:coil-gif:2.5.0")
    
    // icons
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.documentfile:documentfile:1.0.1")
    
    // network
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // test
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.17.0")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:runner:1.6.2")
    testImplementation("androidx.test:core:1.6.1")
}

tasks.matching { it.name.startsWith("buildNdkBuild") }.configureEach {
    doNotTrackState("NDK build emits transient .tmp object files that can disappear before Gradle snapshots outputs on Windows.")
}
