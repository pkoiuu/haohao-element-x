/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2022-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:Suppress("UnstableApiUsage")

import com.android.build.api.variant.FilterConfiguration.FilterType.ABI
import com.android.build.gradle.internal.tasks.factory.dependsOn
import com.android.build.gradle.tasks.GenerateBuildConfig
import config.BuildTimeConfig
import extension.GitBranchNameValueSource
import extension.GitRevisionValueSource
import extension.allEnterpriseImpl
import extension.allFeaturesImpl
import extension.allLibrariesImpl
import extension.allServicesImpl
import extension.buildConfigFieldStr
import extension.locales
import extension.setupDependencyInjection
import extension.testCommonDependencies
import java.util.Locale

plugins {
    id("io.element.android-compose-application")
    // HaohaoChat: firebase 插件已移除，个人构建无需 nightly 分发
    // id(libs.plugins.firebaseAppDistribution.get().pluginId)
    id("kotlin-parcelize")
    // HaohaoChat: licensee 插件已移除，个人构建无需许可证检查
    // alias(libs.plugins.licensee)
    alias(libs.plugins.kotlin.serialization)
    // To be able to update the firebase.xml files, uncomment and build the project
    // alias(libs.plugins.gms.google.services)
}

android {
    namespace = "io.element.android.x"

    defaultConfig {
        applicationId = BuildTimeConfig.APPLICATION_ID
        targetSdk = Versions.TARGET_SDK
        versionCode = Versions.VERSION_CODE
        versionName = Versions.VERSION_NAME

        // HaohaoChat: 仅构建 arm64-v8a，个人设备无需其他 ABI
        // 如需模拟器调试，在 splits.abi.include 中添加 "x86_64"
        // ndk.abiFilters 不设置，由 splits.abi 控制

        // Ref: https://developer.android.com/studio/build/configure-apk-splits.html#configure-abi-split
        splits {
            // Configures multiple APKs based on ABI.
            abi {
                val buildingAppBundle = gradle.startParameter.taskNames.any { it.contains("bundle") }

                isEnable = !buildingAppBundle
                reset()

                if (!buildingAppBundle) {
                    // HaohaoChat: 仅构建 arm64-v8a，不生成 universal APK
                    include("arm64-v8a")
                    isUniversalApk = false
                }
            }
        }

        androidResources {
            // HaohaoChat: 仅保留 en + zh-rCN 两种语言资源
            localeFilters += locales
        }
    }

    signingConfigs {
        getByName("debug") {
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            storeFile = file("./signature/debug.keystore")
            storePassword = "android"
        }
        // HaohaoChat: nightly signing config 已移除，个人构建无需 nightly 分发
        // register("nightly") { ... }
    }

    val baseAppName = BuildTimeConfig.APPLICATION_NAME
    val buildType = if (isEnterpriseBuild) "Enterprise" else "FOSS"
    logger.warnInBox("Building ${defaultConfig.applicationId} ($baseAppName) [$buildType]")

    buildTypes {
        val oAuthRedirectSchemeBase = BuildTimeConfig.METADATA_HOST_REVERSED ?: "io.element.android"
        getByName("debug") {
            resValue("string", "app_name", "$baseAppName dbg")
            resValue(
                "string",
                "login_redirect_scheme",
                "$oAuthRedirectSchemeBase.debug",
            )
            applicationIdSuffix = ".debug"
            signingConfig = signingConfigs.getByName("debug")
        }

        getByName("release") {
            resValue("string", "app_name", baseAppName)
            resValue(
                "string",
                "login_redirect_scheme",
                oAuthRedirectSchemeBase,
            )
            signingConfig = signingConfigs.getByName("debug")

            // R8/minify 已关闭：个人使用无需代码压缩和混淆，构建时间从 ~1h 降至 ~5min
            // 如需恢复，将下面的注释放开并删除 isMinifyEnabled = false
            optimization {
                enable = false
            }
        }

        // HaohaoChat: nightly build type 已移除，个人构建无需 nightly 分发
        // register("nightly") { ... }
    }

    buildFeatures {
        buildConfig = true
        resValues = true
    }
    // HaohaoChat: 移除 gplay flavor，个人构建仅使用 fdroid
    // variant 从 4 减到 2（fdroid debug + fdroid release），配置阶段减半
    flavorDimensions += "store"
    productFlavors {
        create("fdroid") {
            dimension = "store"
            isDefault = true
            buildConfigFieldStr("SHORT_FLAVOR_DESCRIPTION", "F")
            buildConfigFieldStr("FLAVOR_DESCRIPTION", "FDroid")
        }
    }

    packaging {
        resources.pickFirsts += setOf(
            "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
        )

        jniLibs {
            useLegacyPackaging = project.findProperty("useLegacyPackaging")?.toString()?.toBoolean()
        }
    }
}

androidComponents {
    // HaohaoChat: 仅构建 arm64-v8a，简化 versionCode 映射
    val abiVersionCodes = mapOf(
        "arm64-v8a" to 2,
    )

    onVariants { variant ->
        // Assigns a different version code for each output APK
        // other than the universal APK.
        variant.outputs.forEach { output ->
            val name = output.filters.find { it.filterType == ABI }?.identifier

            // Stores the value of abiCodes that is associated with the ABI for this variant.
            val abiCode = abiVersionCodes[name] ?: 0
            // Assigns the new version code to output.versionCode, which changes the version code
            // for only the output APK, not for the variant itself.
            output.versionCode.set((output.versionCode.orNull ?: 0) * 10 + abiCode)
        }
    }

    // HaohaoChat: licensee 相关代码已移除
    // val reportingExtension: ReportingExtension = project.extensions.getByType(ReportingExtension::class.java)
    // configureLicensesTasks(reportingExtension)
}

setupDependencyInjection()

dependencies {
    allLibrariesImpl()
    allServicesImpl()
    if (isEnterpriseBuild) {
        allEnterpriseImpl(project)
        // HaohaoChat: 使用字符串引用避免类型安全访问器在模块不存在时编译报错
        implementation(project(":appicon:enterprise"))
    } else {
        implementation(projects.features.enterprise.implFoss)
        implementation(projects.appicon.element)
    }
    allFeaturesImpl(project)
    implementation(projects.features.migration.api)
    implementation(projects.appnav)
    implementation(projects.appconfig)
    implementation(projects.libraries.uiStrings)
    implementation(projects.services.analytics.compose)

    // HaohaoChat: gplay flavor 已移除，firebase push provider 不再需要
    // if (ModulesConfig.pushProvidersConfig.includeFirebase) {
    //     "gplayImplementation"(projects.libraries.pushproviders.firebase)
    // }
    if (ModulesConfig.pushProvidersConfig.includeUnifiedPush) {
        implementation(projects.libraries.pushproviders.unifiedpush)
    }

    implementation(libs.appyx.core)
    implementation(libs.androidx.splash)
    implementation(libs.androidx.core)
    implementation(libs.androidx.corektx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.startup)
    implementation(libs.androidx.preference)
    implementation(libs.coil)

    implementation(platform(libs.network.okhttp.bom))
    implementation(libs.network.okhttp.logging)
    implementation(libs.serialization.json)

    implementation(libs.matrix.emojibase.bindings)

    // HaohaoChat: test 依赖仅在 test 模块存在时引入
    // 构建 APK 时跳过 test 目录，这些依赖不会被解析
    if (gradle.startParameter.taskNames.any { it.contains("test", ignoreCase = true) || it.contains("check", ignoreCase = true) }) {
        testCommonDependencies(libs)
        testImplementation(project(":libraries:matrix:test"))
        testImplementation(project(":services:toolbox:test"))
    }
}

// HaohaoChat: GenerateBuildConfig 使用 lazy provider，允许 Gradle 缓存任务输出
tasks.withType<GenerateBuildConfig>().configureEach {
    // 使用 Provider 延迟计算，仅在 BuildConfig 需要重新生成时才执行 git 命令
    val gitRevisionProvider = providers.of(GitRevisionValueSource::class.java) {}
    val gitBranchNameProvider = providers.of(GitBranchNameValueSource::class.java) {}
    android.defaultConfig.buildConfigFieldStr("GIT_REVISION", gitRevisionProvider.get())
    android.defaultConfig.buildConfigFieldStr("GIT_BRANCH_NAME", gitBranchNameProvider.get())
}

// HaohaoChat: licensee 配置和 configureLicensesTasks 已移除，个人构建无需许可证检查
// licensee {
//     allow("Apache-2.0")
//     allow("MIT")
//     ...
// }
// fun Project.configureLicensesTasks(...) { ... }

configurations.all {
    resolutionStrategy {
        dependencySubstitution {
            val tink = libs.google.tink.get()
            substitute(module("com.google.crypto.tink:tink")).using(module("${tink.group}:${tink.name}:${tink.version}"))
        }
    }
}
