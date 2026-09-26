import org.gradle.api.tasks.testing.Test

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Room 导出 schema JSON：MigrationTestHelper 靠这些文件建旧版库并校验迁移结果�?
// 没有它就没有任何东西能证明「迁移后的库 == 当前实体定义」�?
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// Kotlin 2.x writes Android unit-test classes to tmp/kotlin-classes; keep the
// AGP test task's class directory explicit so JUnit can discover Kotlin tests.
tasks.withType<Test>().configureEach {
    if (name == "testDebugUnitTest") {
        val kotlinTestClasses = layout.buildDirectory.dir("tmp/kotlin-classes/debugUnitTest")
        // 放在最前，避免�?AGP 默认（不包含 Kotlin 2.x 输出目录）的条目遮蔽
        classpath = files(kotlinTestClasses) + classpath
        // 测试凭据通过环境变量注入，源码零硬编�?
        environment("NF_TEST_FAKE_TOKEN", "unit-test-placeholder-key")
        testClassesDirs = files(
            kotlinTestClasses,
            layout.buildDirectory.dir("intermediates/javac/debugUnitTest/classes")
        )
    }
}

android {
    namespace = "com.novelforge.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.novelforge.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 153
        versionName = "0.2.0-beta54"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    buildTypes {
        release {
            // 保持关闭：Room / kotlinx.serialization / OkHttp 的反射面很大�?
            // 一旦开 minify 就要逐个�?keep 规则，收益远小于风险�?
            // release �?debug 的差别因此只剩「可调试标记」和资源收缩�?
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 没配签名的话 assembleRelease 产出的是 unsigned APK，装不上�?
            // 这里�?debug keystore 签，好处是「发出去的包一定能装、能覆盖升级」�?
            // 注意：debug keystore 的口令是公开的（android），任何人都能签�?
            // 一个能覆盖安装的包 —�?这只适合自测分发。要公开发布必须换成
            // 自己�?keystore，并把口令放�?local.properties 或环境变量�?
            signingConfig = signingConfigs.getByName("debug")
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

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    // 迁移测试要读 assets 里的历史 schema（app/schemas/<数据库类�?/<版本>.json�?
    sourceSets {
        getByName("androidTest") {
            assets.srcDir("$projectDir/schemas")
        }
    }

    applicationVariants.all {
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                "NovelForge-v${defaultConfig.versionName}-${defaultConfig.versionCode}-$name.apk"
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.bundles.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.bundles.room)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.turbine)
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.bundles.testing)
    // 迁移测试要用 MigrationTestHelper 真正建一�?v1 库再跑迁�?
    androidTestImplementation("androidx.room:room-testing:2.7.0")
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

