plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.mm.wxcj"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "dev.mm.wxcj"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 32
        versionName = "3.11"

        ndk {
            // 微信与 DexKit 实际只跑在 ARM 上；排除 x86 可显著减小 APK 体积。
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 省去自建 keystore：直接复用 debug 签名，产物可直接安装。
            // 想用自己的签名，见 README「自定义签名」一节。
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.jdk.get())
        targetCompatibility = JavaVersion.toVersion(libs.versions.jdk.get())
    }

    kotlinOptions {
        jvmTarget = libs.versions.jdk.get()
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        // DexKit / Kotlin 依赖自带的许可证文件，去重避免打包冲突
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // 解压而非页对齐压缩 .so：Xposed 模块被加载到宿主进程时，
        // System.loadLibrary("dexkit") 才找得到文件。
        jniLibs {
            useLegacyPackaging = true
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // 由宿主 Xposed 框架提供，不打包进 APK
    compileOnly(libs.xposed.api)
    // 运行时 dex 反混淆：用于在微信各版本里定位被混淆的类/方法
    implementation(libs.dexkit)
}
