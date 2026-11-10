plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ck.orbiteq"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ck.orbiteq"
        minSdk = 28
        targetSdk = 34
        // every CI build gets a higher number, so each APK installs as an update
        val run = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
        versionCode = 100 + (run ?: 0)
        versionName = if (run != null) "2.0 (build $run)" else "2.0"
    }

    // One permanent signing key (kept in GitHub secrets) so new APKs install over the old one.
    // Without it, builds fall back to the machine's random debug key.
    val keyFile = file(System.getenv("ORBIT_KEYSTORE") ?: "none")
    val keyPass = System.getenv("ORBIT_KEY_PASS")
    signingConfigs {
        if (keyFile.exists() && !keyPass.isNullOrEmpty()) {
            create("orbit") {
                storeFile = keyFile
                storePassword = keyPass
                keyAlias = "orbiteq"
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
        debug {
            signingConfigs.findByName("orbit")?.let { signingConfig = it }
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/INDEX.LIST",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            )
        }
    }

    testOptions {
        unitTests.all {
            it.testLogging {
                events("passed", "failed", "skipped")
                showStandardStreams = true
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            }
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.media:media:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.1")

    // YouTube
    implementation("com.github.teamnewpipe:NewPipeExtractor:v0.26.5")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.0.4")

    testImplementation("junit:junit:4.13.2")
}
