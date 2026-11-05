import java.util.Properties
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest

plugins {
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Explicit local distribution only. Normal tests/PRs never read private configuration.
val distribution = providers.gradleProperty("robstoreDistribution").orNull
val privateSigning = Properties()
if (distribution != null) {
    require(distribution in listOf("release", "dev")) { "Unknown distribution channel" }
    require(gradle.startParameter.projectProperties["robstoreDistribution"] == distribution) {
        "Distribution requires an explicit command-line -ProbstoreDistribution option"
    }
    require(System.getenv("CI") != "true" && System.getenv("GITHUB_ACTIONS") != "true") {
        "Persistent distribution is local only"
    }
    require(!gradle.startParameter.isConfigurationCacheRequested && !gradle.startParameter.isBuildScan &&
        gradle.startParameter.logLevel != org.gradle.api.logging.LogLevel.DEBUG) {
        "Distribution forbids configuration cache, build scans and debug logging"
    }
    require(gradle.startParameter.taskNames == listOf(if (distribution == "release") ":app:assembleRelease" else ":app:assembleDebug")) {
        "Distribution requires exactly the explicit :app:assembleRelease or :app:assembleDebug task"
    }
    val config = System.getenv("ROBSTORE_SIGNING_FILE")?.let { file(it).canonicalFile }
    require(config != null && config.isFile && !config.toPath().startsWith(rootDir.canonicalFile.toPath())) {
        "ROBSTORE_SIGNING_FILE must point to a private file outside the repository"
    }
    config.reader(Charsets.UTF_8).use { privateSigning.load(it) }
    fun setting(name: String): String = privateSigning.getProperty("$distribution.$name")
        ?.takeIf { it.isNotBlank() } ?: error("Missing private distribution setting: $name")
    val storePath = setting("storeFile")
    val store = file(storePath).canonicalFile
    require(File(storePath).isAbsolute && store.isFile && !store.toPath().startsWith(rootDir.canonicalFile.toPath())) {
        "Keystore must exist outside the repository"
    }
    val expected = setting("certificateSha256").lowercase()
    val other = privateSigning.getProperty(if (distribution == "release") "dev.certificateSha256" else "release.certificateSha256", "").lowercase()
    require(expected.matches(Regex("[a-f0-9]{64}")) && other.matches(Regex("[a-f0-9]{64}")) && expected != other) {
        "Two distinct public certificate fingerprints are required"
    }
    val actual = try {
        val ks = KeyStore.getInstance("PKCS12")
        store.inputStream().use { ks.load(it, setting("storePassword").toCharArray()) }
        val alias = setting("keyAlias")
        require(ks.isKeyEntry(alias))
        ks.getKey(alias, setting("keyPassword").toCharArray())
        MessageDigest.getInstance("SHA-256").digest(ks.getCertificate(alias).encoded)
            .joinToString("") { "%02x".format(it) }
    } catch (_: Exception) {
        error("Private signing configuration could not be validated; details suppressed")
    }
    require(actual == expected) { "Unexpected distribution certificate" }
}

android {
    namespace = "es.robertodorado.robstore"
    compileSdk = 36

    defaultConfig {
        applicationId = "es.robertodorado.robstore"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-DEV"
        }
        release {
            isMinifyEnabled = false
        }
    }

    if (distribution != null) {
        val persistent = signingConfigs.create("persistentDistribution") {
            fun setting(name: String) = privateSigning.getProperty("$distribution.$name")
            storeFile = file(setting("storeFile"))
            storePassword = setting("storePassword")
            keyAlias = setting("keyAlias")
            keyPassword = setting("keyPassword")
            storeType = "PKCS12"
            enableV1Signing = false
            enableV2Signing = true
        }
        buildTypes.getByName(if (distribution == "release") "release" else "debug") {
            signingConfig = persistent
            isDebuggable = false
        }
        afterEvaluate {
            check(buildTypes.getByName(if (distribution == "release") "release" else "debug").signingConfig === persistent) {
                "Distribution signing configuration was overridden"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.lifecycle.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    testImplementation(libs.kotlinx.coroutines.test)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.icons)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.compose.preview)
    debugImplementation(libs.androidx.compose.tooling)
    testImplementation(libs.androidx.compose.ui.test)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}

// Robolectric's Android 36 runtime requires Java 21; app compilation remains Java 17.
val testJavaToolchains = extensions.getByType<org.gradle.jvm.toolchain.JavaToolchainService>()
tasks.withType<Test>().configureEach {
    javaLauncher.set(testJavaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    })
}
