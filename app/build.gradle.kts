plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.agent.app"
    compileSdk = 33

    defaultConfig {
        applicationId = "com.agent.app"
        minSdk = 26
        targetSdk = 28
        versionCode = 28
        versionName = "0.14.3"
    }

    signingConfigs {
        create("release") {
            // Signing secrets come from local.properties (NOT committed) or env vars.
            val lpFile = rootProject.file("local.properties")
            val lpMap: Map<String, String> = if (lpFile.exists())
                lpFile.readLines().mapNotNull {
                    val i = it.indexOf('=')
                    if (i > 0 && !it.startsWith("#")) it.substring(0, i).trim() to it.substring(i + 1).trim() else null
                }.toMap()
            else emptyMap()
            fun secret(key: String, env: String): String =
                lpMap[key] ?: System.getenv(env) ?: ""
            storeFile = secret("keystore.file", "KEYSTORE_FILE").takeIf { it.isNotEmpty() }?.let { file(it) }
                ?: rootProject.file("release.jks").takeIf { it.exists() }
            storePassword = secret("keystore.password", "KEYSTORE_PASSWORD")
            keyAlias = secret("keystore.alias", "KEYSTORE_ALIAS").ifEmpty { "agentkey" }
            keyPassword = secret("keystore.keyPassword", "KEYSTORE_KEY_PASSWORD")
            enableV1Signing = true
            enableV2Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.5"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.activity:activity-compose:1.7.2")
    implementation(platform("androidx.compose:compose-bom:2023.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.6.0")
    implementation("androidx.datastore:datastore-preferences:1.0.0")
    implementation("com.squareup.okhttp3:okhttp:4.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.work:work-runtime-ktx:2.8.1")
}
