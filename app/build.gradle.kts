import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// 从 local.properties 读取 API key（local.properties 已在 .gitignore 中，不会提交）
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val dashscopeApiKey: String = localProps.getProperty("DASHSCOPE_API_KEY", "")
val githubOwner: String = localProps.getProperty("GITHUB_OWNER", "")
val githubRepo: String = localProps.getProperty("GITHUB_REPO", "")
// 金标语料库独立仓库（可与 APK 仓库不同），格式 "owner/repo"，分支默认 main
val corpusRepo: String = localProps.getProperty("CORPUS_REPO", "")
val corpusBranch: String = localProps.getProperty("CORPUS_BRANCH", "main")

// 版本号：默认值；CI 通过 -PversionName / -PversionCode 注入实际值
val ciVersionName: String = (project.findProperty("versionName") as String?) ?: "1.0.0"
val ciVersionCode: Int = ((project.findProperty("versionCode") as String?)?.toIntOrNull()) ?: 1

android {
    namespace = "com.xingshu.helper"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.xingshu.helper"
        minSdk = 26
        targetSdk = 34
        versionCode = ciVersionCode
        versionName = ciVersionName

        // 注入到 BuildConfig，运行时读取
        buildConfigField("String", "DASHSCOPE_API_KEY", "\"$dashscopeApiKey\"")
        buildConfigField("String", "GITHUB_OWNER", "\"$githubOwner\"")
        buildConfigField("String", "GITHUB_REPO", "\"$githubRepo\"")
        buildConfigField("String", "CORPUS_REPO", "\"$corpusRepo\"")
        buildConfigField("String", "CORPUS_BRANCH", "\"$corpusBranch\"")
    }

    signingConfigs {
        create("release") {
            val storeFilePath = project.findProperty("releaseStoreFile") as String?
            if (storeFilePath != null) {
                storeFile = file(storeFilePath)
                storePassword = project.findProperty("releaseStorePassword") as String?
                keyAlias = project.findProperty("releaseKeyAlias") as String?
                keyPassword = project.findProperty("releaseKeyPassword") as String?
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (project.hasProperty("releaseStoreFile")) {
                signingConfig = signingConfigs.getByName("release")
            }
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
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.05.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.0")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.savedstate:savedstate:1.2.1")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
