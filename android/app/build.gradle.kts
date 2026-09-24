import java.util.Properties

plugins {
    id("com.android.application")
}

// 如需发布签名包：在 android/ 目录下新建 keystore.properties，内容为
//   storeFile=luomic.jks
//   storePassword=xxxx
//   keyAlias=luomic
//   keyPassword=xxxx
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "com.luomic.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.luomic.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        resourceConfigurations += listOf("zh", "en")
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (keystorePropsFile.exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = false
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module")
    }

    lint {
        abortOnError = false
    }
}

// 输出文件名固定为 luo-mic-<buildType>.apk，方便在文件夹里一眼认出。
//
// ⚠️ 历史坑：这里原来用的是 android.applicationVariants + BaseVariantOutputImpl，
//    那种写法在 AGP 8.x 的 Kotlin DSL 里会直接编译失败（内部 API 已移除/改签名）。
//    现在改为「官方 Variant API + 反射设置文件名」：
//      · androidComponents / onVariants 是官方稳定 API
//      · outputFileName 用反射设置，即使某个 AGP 版本改了实现类也不会让脚本编译不过，
//        最多是文件名没改成（APK 依然会正常产出）
androidComponents {
    onVariants { variant ->
        val buildType = variant.buildType ?: "release"
        variant.outputs.forEach { output ->
            // AGP 8.x 的做法：getOutputFileName() 返回一个 Property<String>，
            // 改它即可。注意没有 setOutputFileName 方法（实测确认过），
            // 所以不能靠 setter 反射 —— 那是上一版失效的原因。
            try {
                val prop = output.javaClass.getMethod("getOutputFileName").invoke(output)
                prop.javaClass.getMethod("set", Any::class.java).invoke(prop, "luo-mic-$buildType.apk")
            } catch (e: Exception) {
                logger.lifecycle("luo mic: 未能重命名 APK（${e.javaClass.simpleName}），用默认文件名")
            }
        }
    }
}

