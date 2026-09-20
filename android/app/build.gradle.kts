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

// 输出文件名固定为 luo-mic-<version>.apk，方便拷贝到手机
android.applicationVariants.all {
    outputs.all {
        val out = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
        out.outputFileName = "luo-mic-${name}-${versionName}.apk"
    }
}
