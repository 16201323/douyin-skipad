import java.net.URI

// app/build.gradle.kts
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.skipad"
    compileSdk = 34

    buildFeatures { buildConfig = true }

    defaultConfig {
        applicationId = "com.example.skipad"
        minSdk = 24          // 兼容 Android 7.0+
        targetSdk = 34
        versionCode = 17
        versionName = "1.5.5"
        val staticOrigin = providers.gradleProperty("staticUpdateBaseUrl").orElse("").get().trim().trimEnd('/')
        require(staticOrigin.isEmpty() || runCatching {
            val uri = URI(staticOrigin)
            uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawAuthority == uri.host && uri.rawPath.isEmpty() && uri.query == null && uri.fragment == null
        }.getOrDefault(false)) { "staticUpdateBaseUrl must be an HTTPS origin without credentials, path or query" }
        buildConfigField("String", "STATIC_UPDATE_ORIGIN", "\"$staticOrigin\"")
        testInstrumentationRunner = "com.example.skipad.UiPreviewInstrumentation"
    }

    val ciSigningFile = System.getenv("SKIPAD_SIGNING_STORE_FILE")
    if (!ciSigningFile.isNullOrBlank()) signingConfigs.create("ciRelease") {
        storeFile = file(ciSigningFile)
        storePassword = System.getenv("SKIPAD_SIGNING_STORE_PASSWORD") ?: "android"
        keyAlias = System.getenv("SKIPAD_SIGNING_KEY_ALIAS") ?: "androiddebugkey"
        keyPassword = System.getenv("SKIPAD_SIGNING_KEY_PASSWORD") ?: "android"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 本地沿用现有密钥；CI 通过私密配置恢复同一密钥，禁止换签名发布。
            signingConfig = signingConfigs.getByName(if (ciSigningFile.isNullOrBlank()) "debug" else "ciRelease")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
            it.testLogging.exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }

    // APK 输出文件命名：统一用项目名 douyin-skipad，便于识别与分发
    // （用户要求：apk 名称更名为项目名称）release → douyin-skipad-release.apk，debug → douyin-skipad-debug.apk
    applicationVariants.all {
        val variant = this
        outputs.all {
            val outputImpl = this as com.android.build.gradle.internal.api.ApkVariantOutputImpl
            outputImpl.outputFileName = "douyin-skipad-${variant.buildType.name}.apk"
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    // AppCompat（用于 AppCompatActivity）
    implementation("androidx.appcompat:appcompat:1.7.0")
    // 图片裁剪库（CanHub 维护的 uCrop 活跃分支）：悬浮窗图片模式的自由框选裁剪
    implementation("com.vanniktech:android-image-cropper:4.7.0")
}
