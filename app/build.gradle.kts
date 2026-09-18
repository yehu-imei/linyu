import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.hualala.linyu"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.hualala.linyu"
        minSdk = 26
        targetSdk = 36
        versionCode = 13
        versionName = "3.0.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // 只打 arm64 的 native 库。
        // ML Kit 的 libbarhopper_v3.so 每个 ABI 各存一份，而且在 APK 里是不压缩存储的，
        // x86/x86_64 两份合计 11.5MB，只有模拟器用得到，真机上永远不会加载。
        // ⚠️ 代价：纯 32 位的老机型装不上（minSdk 26 起这类机器已经很少）。
        ndk {
            abiFilters += "arm64-v8a"
        }

        // 界面只有中文。不限定的话 androidx / material 自带的几十种语言翻译
        // 全都会进 resources.arsc
        resourceConfigurations += listOf("zh", "en")
    }

    signingConfigs {
        create("release") {
            val props = Properties().apply {
                load(rootProject.file("local.properties").inputStream())
            }
            storeFile = file(props.getProperty("KEYSTORE_FILE", "../hualala.jks"))
            storePassword = props.getProperty("KEYSTORE_PASSWORD", "")
            keyAlias = props.getProperty("KEY_ALIAS", "")
            keyPassword = props.getProperty("KEY_PASSWORD", "")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material)
    // material-icons-extended 已移除：全项目只用到它里面 2 个图标（扫码、账单），
    // 而 R8 并没有把它裁掉——release 包里实测仍残留 10660 个图标类。
    // 那两个图标已换成项目自己的 vector drawable（ic_qr_code_scanner / ic_receipt_long）。
    // material-icons-core 仍由 material3 传递进来，Icons.Default.* 照常可用。
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Network
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp.logging)
    implementation(libs.mqtt.paho)
    implementation(libs.mqtt.android)
    implementation(libs.security.crypto)

    // 扫码
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.mlkit.barcode)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    debugImplementation(libs.androidx.ui.tooling)
}
