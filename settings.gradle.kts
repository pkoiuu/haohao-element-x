/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2022-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

pluginManagement {
    repositories {
        includeBuild("plugins")
        gradlePluginPortal()
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven {
            url = uri("https://www.jitpack.io")
            content {
                includeModule("com.github.matrix-org", "matrix-analytics-events")
                includeModule("com.github.philburk", "jsyn")
            }
        }
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
        // HaohaoChat: 移除 repo1.maven.org（与 mavenCentral() 完全重复）和 flatDir（指向不存在的目录）
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "HaohaoChat"
include(":app")
include(":appnav")
include(":appconfig")
include(":appicon:element")
// HaohaoChat: FOSS 构建跳过 enterprise appicon 模块
if (File(rootDir, "enterprise/README.md").exists()) {
    include(":appicon:enterprise")
}
// HaohaoChat: 构建 APK 时跳过纯测试模块，减少 configuration 阶段开销
if (!gradle.startParameter.taskNames.any { it.contains("test", ignoreCase = true) || it.contains("check", ignoreCase = true) }) {
    include(":tests:detekt-rules")
    include(":tests:konsist")
    include(":tests:uitests")
}
include(":tests:testutils")
include(":annotations")
include(":codegen")

fun includeProjects(directory: File, path: String, maxDepth: Int = 1, excludes: Set<String> = emptySet()) {
    directory.listFiles().orEmpty().also { it.sort() }.forEach { file ->
        if (file.isDirectory) {
            val newPath = "$path:${file.name}"
            val buildFile = File(file, "build.gradle.kts")
            if (buildFile.exists()) {
                if (newPath !in excludes) {
                    include(newPath)
                    logger.lifecycle("Included project: $newPath")
                } else {
                    logger.lifecycle("Excluded project: $newPath (not needed for this build)")
                }
            } else if (maxDepth > 0) {
                includeProjects(file, newPath, maxDepth - 1, excludes)
            }
        }
    }
}

// HaohaoChat: FOSS 构建排除未使用模块，减少 configuration 阶段开销
// - firebase push provider: gplay flavor 已移除，不再需要
// - analytics providers (posthog/sentry): FOSS analytics 已禁用
// - analytics:impl: FOSS 使用 noop 替代
val moduleExcludes = if (!gradle.startParameter.taskNames.any { it.contains("test", ignoreCase = true) || it.contains("check", ignoreCase = true) }) {
    setOf(
        ":libraries:pushproviders:firebase",
        ":services:analyticsproviders:posthog",
        ":services:analyticsproviders:sentry",
        ":services:analytics:impl",
    )
} else {
    emptySet()
}

includeProjects(File(rootDir, "enterprise"), ":enterprise", maxDepth = 2, excludes = moduleExcludes)
includeProjects(File(rootDir, "features"), ":features", excludes = moduleExcludes)
includeProjects(File(rootDir, "libraries"), ":libraries", excludes = moduleExcludes)
includeProjects(File(rootDir, "services"), ":services", excludes = moduleExcludes)

// Uncomment to include the compound-android module as a local dependency so you can work on it locally.
// You will also need to clone it in the specified folder.
// includeBuild("checkouts/compound-android") {
//    dependencySubstitution {
//        // substitute remote dependency with local module
//        substitute(module("io.element.android:compound-android")).using(project(":compound"))
//    }
// }
