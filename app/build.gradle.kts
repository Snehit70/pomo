plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("org.jlleitschuh.gradle.ktlint")
}

android {
    namespace = "com.pomo"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pomo"
        minSdk = 26
        targetSdk = 34
        versionCode = 65
        versionName = "2.12.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        val gitSha: String =
            System.getenv("CI_COMMIT_SHA")
                ?: providers.exec {
                    commandLine("git", "rev-parse", "--short", "HEAD")
                }.standardOutput.asText.get().trim().ifEmpty { "unknown" }

        val gitBranch: String =
            System.getenv("CI_REF_NAME")
                ?: providers.exec {
                    commandLine("git", "rev-parse", "--abbrev-ref", "HEAD")
                }.standardOutput.asText.get().trim().ifEmpty { "unknown" }

        buildConfigField("String", "BUILD_COMMIT", "\"$gitSha\"")
        buildConfigField("String", "BUILD_BRANCH", "\"$gitBranch\"")
        buildConfigField("String", "BUILD_TIME", "\"${System.currentTimeMillis()}\"")

        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
        }
    }

    flavorDimensions += "environment"
    productFlavors {
        create("dev") {
            dimension = "environment"
        }
        create("prod") {
            dimension = "environment"
        }
    }

    signingConfigs {
        // Released devDebug APKs must always be signed with the same key so that
        // installs update in place instead of forcing an uninstall (data loss).
        // CI provides the stable keystore via env; local builds fall back to the
        // developer's default ~/.android/debug.keystore (env unset = unchanged).
        getByName("debug") {
            val keystorePath = System.getenv("POMO_KEYSTORE_FILE")
            if (!keystorePath.isNullOrBlank()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("POMO_KEYSTORE_PASSWORD") ?: "android"
                keyAlias = System.getenv("POMO_KEY_ALIAS") ?: "androiddebugkey"
                keyPassword = System.getenv("POMO_KEY_PASSWORD") ?: "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        allWarningsAsErrors = true
        freeCompilerArgs = freeCompilerArgs +
            listOf(
                "-Xexplicit-api=strict",
                "-Xjsr305=strict",
            )
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    lint {
        abortOnError = true
        warningsAsErrors = true
        checkReleaseBuilds = true
        disable += setOf("GradleDependency", "OldTargetApi")
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }

    sourceSets.getByName("test").resources.srcDir(rootProject.file("sync-protocol"))
}

ktlint {
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.preference:preference:1.2.1")
    implementation("androidx.navigation:navigation-fragment-ktx:2.7.7")
    implementation("androidx.navigation:navigation-ui-ktx:2.7.7")

    // Jetpack Compose
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("com.patrykandpatrick.vico:compose:1.13.1")
    implementation("com.patrykandpatrick.vico:compose-m3:1.13.1")

    // Networking
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.bouncycastle:bcprov-jdk15to18:1.84")
    implementation("fr.acinq.secp256k1:secp256k1-kmp-jni-android:0.16.0")
    implementation("io.ktor:ktor-server-core:2.3.12")
    implementation("io.ktor:ktor-server-cio:2.3.12")
    implementation("io.ktor:ktor-server-websockets:2.3.12")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // Room
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // Tests
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("androidx.test.ext:junit:1.1.5")
    testImplementation("org.robolectric:robolectric:4.12.2")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("androidx.room:room-testing:2.6.1")
    testImplementation("fr.acinq.secp256k1:secp256k1-kmp-jni-jvm:0.16.0")
}
