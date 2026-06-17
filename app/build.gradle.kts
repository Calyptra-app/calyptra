plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

// Release signing credentials come from env vars (CI) or ~/.gradle/gradle.properties (local).
// There is deliberately NO hardcoded fallback: a release built without these fails loudly
// (see the taskGraph check at the bottom) rather than silently signing with a weak default.
val releaseStorePassword: String? = System.getenv("KEYSTORE_PASSWORD") ?: (findProperty("KEYSTORE_PASSWORD") as String?)
val releaseKeyPassword: String? = System.getenv("KEY_PASSWORD") ?: (findProperty("KEY_PASSWORD") as String?)
val releaseStoreFilePath: String = System.getenv("KEYSTORE_FILE")
    ?: (findProperty("KEYSTORE_FILE") as String?)
    ?: "${System.getProperty("user.home")}/keystores/calyptra-release.keystore"
val releaseKeyAlias: String = System.getenv("KEY_ALIAS") ?: (findProperty("KEY_ALIAS") as String?) ?: "calyptra"

android {
    namespace = "com.calyptra.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.calyptra.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 7
        versionName = "1.4.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            // keytool -genkey -v -keystore ~/keystores/calyptra-release.keystore -alias calyptra -keyalg RSA -keysize 2048 -validity 10000
            // Keystore lives outside the repo on purpose; never commit it.
            storeFile = file(releaseStoreFilePath)
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
        buildConfig = true // exposes VERSION_NAME (used for the blocklist User-Agent)
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    sourceSets {
        // Exported Room schemas (app/schemas) feed MigrationTestHelper in androidTest.
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("com.google.android.material:material:1.11.0")
    
    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")

    // Room
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    
    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.7")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.room:room-testing:$roomVersion")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.02.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// Fail fast if a release is built without real signing credentials, instead of
// silently signing with a weak default. Only fires for release packaging tasks,
// so debug builds and IDE sync are unaffected.
gradle.taskGraph.whenReady {
    val buildingRelease = allTasks.any { task ->
        val n = task.name
        (n.startsWith("assemble") || n.startsWith("bundle") || n.startsWith("package")) && n.contains("Release")
    }
    if (buildingRelease && (releaseStorePassword.isNullOrEmpty() || releaseKeyPassword.isNullOrEmpty())) {
        throw GradleException(
            "Release signing requires KEYSTORE_PASSWORD and KEY_PASSWORD (env vars or gradle properties). " +
                "Refusing to sign a release with a missing or weak default password."
        )
    }
}
