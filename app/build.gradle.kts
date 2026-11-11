plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val robolectricRuntime: Configuration by configurations.creating
val robolectricDir = layout.buildDirectory.dir("robolectric-runtime")
val syncRobolectricRuntime = tasks.register<Sync>("syncRobolectricRuntime") {
    from(robolectricRuntime)
    into(robolectricDir)
}

android {
    namespace = "com.daand.drivemusic"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.daand.drivemusic"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Use a real, stable key when the CI secrets are set (see README); otherwise the debug key.
    val keystoreFile = System.getenv("KEYSTORE_FILE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (keystoreFile != null) {
            create("stable") {
                storeFile = keystoreFile
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("stable") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { test ->
            // Use the Android runtime jar Gradle downloaded (cached + retried) instead of letting
            // Robolectric fetch it itself at test time, which is flaky on rate-limited networks.
            test.dependsOn(syncRobolectricRuntime)
            test.systemProperty("robolectric.offline", "true")
            test.systemProperty("robolectric.dependency.dir", robolectricDir.get().asFile.absolutePath)
        }
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.car.app:app:1.4.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1") {
        // Large native runtime only needed for SQLite/graphics tests; we use legacy mode instead.
        exclude(group = "org.robolectric", module = "nativeruntime-dist-compat")
    }
    testImplementation("androidx.test:core:1.6.1")
    // Android 15 runtime used by Robolectric (matches targetSdk 35).
    robolectricRuntime("org.robolectric:android-all-instrumented:15-robolectric-12650502-i7")

    androidTestImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
