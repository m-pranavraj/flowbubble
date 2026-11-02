import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing is local or CI-only. keystore.properties is gitignored; without it the
// release build still compiles and simply stays unsigned.
val keystoreProps = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { file ->
    Properties().apply { FileInputStream(file).use { load(it) } }
}

android {
    namespace = "com.flowbubble.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.flowbubble.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "1.0.2"
    }

    signingConfigs {
        keystoreProps?.let { props ->
            create("release") {
                storeFile = file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")
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

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    // Deliverables carry the product name and version instead of "app-release.apk", and an
    // unsigned release says so in its name so it can never be mistaken for a deliverable.
    val hasReleaseKey = signingConfigs.findByName("release") != null
    applicationVariants.all {
        val unsignedRelease = buildType.name == "release" && !hasReleaseKey
        outputs.forEach { output ->
            if (output is com.android.build.gradle.internal.api.BaseVariantOutputImpl) {
                val type = if (unsignedRelease) "release-unsigned" else buildType.name
                output.outputFileName = "FlowBubble-${versionName}-${type}.apk"
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:genai-speech-recognition:1.0.0-alpha2")

    // On-device LLM. Google's LiteRT-LM Android SDK, from Google Maven (already in
    // settings.gradle.kts via google()). Pinned, not latest.release, so a build stays reproducible.
    // Its POM drags in kotlinx-coroutines-android 1.9.0, the same version this app already uses, and
    // the AAR's minSdk is 24, below this app's 26. The CPU backend is the one used in code: it needs
    // no <uses-native-library> entries in the manifest, which GPU and NPU both do.
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.16.1")

    testImplementation("junit:junit:4.13.2")
}
