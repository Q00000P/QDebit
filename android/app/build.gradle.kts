plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Постоянный ключ подписи — из окружения (мак: ~/.zshenv от tools/signing-setup.sh; CI: Secrets репо).
// Нет переменных — обычный debug-ключ этой машины.
val qdKeystore = System.getenv("QDEBIT_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }

android {
    namespace = "org.qdebit.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.qdebit.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 174
        versionName = "1.7.4"
    }

    signingConfigs {
        if (qdKeystore != null) create("qdebit") {
            storeFile = qdKeystore
            storePassword = System.getenv("QDEBIT_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("QDEBIT_KEY_ALIAS") ?: "qdebit"
            keyPassword = System.getenv("QDEBIT_KEY_PASSWORD") ?: System.getenv("QDEBIT_KEYSTORE_PASSWORD")
        }
    }

    buildTypes {
        debug {
            if (qdKeystore != null) signingConfig = signingConfigs.getByName("qdebit")
        }
        release {
            isMinifyEnabled = false
            if (qdKeystore != null) signingConfig = signingConfigs.getByName("qdebit")
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(bom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    testImplementation("junit:junit:4.13.2")
}
