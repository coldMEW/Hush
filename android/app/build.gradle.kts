import java.util.Properties

plugins { id("com.android.application") }

// Release signing is read from a properties file kept OUTSIDE the repository
// (default ~/.gradle/hush-signing.properties, or the path in HUSH_SIGNING).
// Keys: storeFile, storePassword, keyAlias, keyPassword. Without it, release builds are unsigned.
val signingFile = file(System.getenv("HUSH_SIGNING") ?: "${System.getProperty("user.home")}/.gradle/hush-signing.properties")
val signing = Properties().apply { if (signingFile.exists()) signingFile.inputStream().use { load(it) } }

android {
    namespace = "io.coldmew.hush"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.coldmew.hush"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "1.0.1"
    }

    signingConfigs {
        if (signing.containsKey("storeFile")) create("release") {
            storeFile = file(signing.getProperty("storeFile"))
            storePassword = signing.getProperty("storePassword")
            keyAlias = signing.getProperty("keyAlias")
            keyPassword = signing.getProperty("keyPassword")
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        disable += setOf("GradleDependency", "OldTargetApi", "IconMissingDensityFolder", "IconLauncherShape")
    }

    testOptions { unitTests.isReturnDefaultValues = true }

    dependenciesInfo {
        // No Google-encrypted dependency blob in the APK: keeps it reproducible for F-Droid-style checks.
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    testImplementation("junit:junit:4.13.2")
}
