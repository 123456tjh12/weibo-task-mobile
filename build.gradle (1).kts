plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 固定签名：让每次构建出来的包都用同一把钥匙。
//
// 背景：CI 运行器是一次性的，Gradle 每次都会重新生成 ~/.android/debug.keystore，
// 于是每个包的签名都不一样，装新版必须先卸载旧版（微博登录态也会一起丢）。
// 只要在 CI 里注入 KEYSTORE_* 环境变量，下面就会自动启用固定签名，
// 之后就能直接覆盖安装，不再需要卸载。
//
// 未配置这些环境变量时，一切照旧（走默认 debug 签名），不会影响现有构建。
val keystorePath = System.getenv("KEYSTORE_PATH").orEmpty()
val hasFixedKeystore = keystorePath.isNotBlank() && file(keystorePath).exists()

android {
    namespace = "com.tjh.weibotask"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tjh.weibotask"
        minSdk = 26
        targetSdk = 34
        versionCode = 12
        versionName = "0.12.0"
    }

    signingConfigs {
        if (hasFixedKeystore) {
            create("fixed") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasFixedKeystore) {
                signingConfig = signingConfigs.getByName("fixed")
            }
        }
        debug {
            // debug 包也用同一把钥匙，这样 debug 包之间可以互相覆盖安装
            if (hasFixedKeystore) {
                signingConfig = signingConfigs.getByName("fixed")
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
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
