// app/build.gradle.kts
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.skipad"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.skipad"
        minSdk = 24          // 兼容 Android 7.0+
        targetSdk = 34
        versionCode = 11
        versionName = "1.4.1"
        testInstrumentationRunner = "com.example.skipad.UiPreviewInstrumentation"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 个人自用项目，release 直接复用 debug 签名，省去 keystore 配置与管理
            signingConfig = signingConfigs.getByName("debug")
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
