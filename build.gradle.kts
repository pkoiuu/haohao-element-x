import org.gradle.accessors.dm.LibrariesForLibs

/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2022-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("io.element.android-root")
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.dependencycheck) apply false
    alias(libs.plugins.roborazzi) apply false
    // HaohaoChat: 以下插件已移除以加速构建（个人构建无需代码质量检查）
    // alias(libs.plugins.dependencyanalysis)
    // alias(libs.plugins.detekt)
    // alias(libs.plugins.ktlint)
    // alias(libs.plugins.dependencygraph)
    // alias(libs.plugins.sonarqube)
}

tasks.register<Delete>("clean").configure {
    delete(rootProject.layout.buildDirectory)
}

private val catalog = the<LibrariesForLibs>()

allprojects {
    // HaohaoChat: detekt/ktlint/owasp 插件已移除，个人构建无需代码质量检查
    // 如需恢复，取消以下注释
    // // Detekt
    // apply {
    //     plugin("io.gitlab.arturbosch.detekt")
    // }
    // detekt {
    //     // preconfigure defaults
    //     buildUponDefaultConfig = true
    //     // activate all available (even unstable) rules.
    //     allRules = true
    //     // point to your custom config defining rules to run, overwriting default behavior
    //     config.from(files("$rootDir/tools/detekt/detekt.yml"))
    // }
    // dependencies {
    //     detektPlugins(catalog.detekt.compose.rules)
    //     detektPlugins(project(":tests:detekt-rules"))
    // }
    //
    // tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    //     exclude("io/element/android/tests/konsist/failures/**")
    //
    //     // This file comes from another project and we want to keep it as close to the original as possible
    //     exclude("org/rustls/platformverifier/**")
    // }
    //
    // // KtLint
    // apply {
    //     plugin("org.jlleitschuh.gradle.ktlint")
    // }
    //
    // // See https://github.com/JLLeitschuh/ktlint-gradle#configuration
    // configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
    //     version = catalog.versions.ktlint.get()
    //     android = true
    //     ignoreFailures = false
    //     enableExperimentalRules = true
    //     // display the corresponding rule
    //     verbose = true
    //     reporters {
    //         reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.PLAIN)
    //         // To have XML report for Danger
    //         reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.CHECKSTYLE)
    //     }
    //     val generatedPath = "${layout.buildDirectory.asFile.get()}/generated/"
    //     filter {
    //         exclude { element -> element.file.path.contains(generatedPath) }
    //         exclude("io/element/android/tests/konsist/failures/**")
    //
    //         // This file comes from another project and we want to keep it as close to the original as possible
    //         exclude("**/SafeChildrenTransitionScope.kt")
    //
    //         // This file comes from another project and we want to keep it as close to the original as possible
    //         exclude("org/rustls/platformverifier/**")
    //     }
    // }
    // // Dependency check
    // apply {
    //     plugin("org.owasp.dependencycheck")
    // }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions {
            // Warnings are potential errors, so stop ignoring them
            // This is disabled by default, but the CI will enforce this.
            // You can override by passing `-PallWarningsAsErrors=true` in the command line
            // Or add a line with "allWarningsAsErrors=true" in your ~/.gradle/gradle.properties file
            allWarningsAsErrors = project.properties["allWarningsAsErrors"] == "true"

            // Uncomment to suppress Compose Kotlin compiler compatibility warning
//            freeCompilerArgs.addAll(listOf("-P", "plugin:androidx.compose.compiler.plugins.kotlin:suppressKotlinVersionCompatibilityCheck=true"))

            // Fix compilation warning for annotations
            // See https://youtrack.jetbrains.com/issue/KT-73255/Change-defaulting-rule-for-annotations for more details
            freeCompilerArgs.add("-Xannotation-default-target=first-only")

            // Enable context parameters (experimental in Kotlin 2.3.x)
            freeCompilerArgs.add("-Xcontext-parameters")

            // HaohaoChat: 跳过预发布版本检查，减少编译器不必要的验证开销
            freeCompilerArgs.add("-Xskip-prerelease-check")
        }
    }

    // HaohaoChat: 合并 Test 配置到此 allprojects 块，减少 configuration 遍历
    tasks.withType<Test> {
        maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)

        val isScreenshotTest = project.gradle.startParameter.taskNames.any { it.contains("paparazzi", ignoreCase = true) }
        if (isScreenshotTest) {
            // Increase heap size for screenshot tests
            maxHeapSize = "2g"
            // Record all the languages?
            if (project.hasProperty("allLanguagesNoEnglish")) {
                // Do not record English language
                exclude("ui/*.class")
            } else if (project.hasProperty("allLanguages").not()) {
                // Do not record other languages
                exclude("translations/*.class")
            }
        } else {
            // Disable screenshot tests by default
            exclude("ui/*.class")
            exclude("translations/*.class")
        }
    }
}

// HaohaoChat: dependencyAnalysis 和 sonar 配置已移除以加速构建
// See https://github.com/autonomousapps/dependency-analysis-android-gradle-plugin/wiki/Customizing-plugin-behavior
// dependencyAnalysis {
//     issues {
//         all {
//             onUnusedDependencies {
//                 exclude("com.jakewharton.timber:timber")
//             }
//             onUnusedAnnotationProcessors {}
//             onRedundantPlugins {}
//             onIncorrectConfiguration {}
//         }
//     }
// }

// HaohaoChat: sonar 配置已移除
// To run a sonar analysis:
// Run './gradlew sonar -Dsonar.login=<SONAR_LOGIN>'
// The SONAR_LOGIN is stored in passbolt as Token Sonar Cloud Bma
// Sonar result can be found here: https://sonarcloud.io/project/overview?id=element-x-android
// sonar {
//     properties {
//         property("sonar.projectName", "element-x-android")
//         property("sonar.projectKey", "element-x-android")
//         property("sonar.host.url", "https://sonarcloud.io")
//         property("sonar.projectVersion", "1.0")
//         property("sonar.sourceEncoding", "UTF-8")
//         property("sonar.links.homepage", "https://github.com/element-hq/element-x-android/")
//         property("sonar.links.ci", "https://github.com/element-hq/element-x-android/actions")
//         property("sonar.links.scm", "https://github.com/element-hq/element-x-android/")
//         property("sonar.links.issue", "https://github.com/element-hq/element-x-android/issues")
//         property("sonar.organization", "element-hq")
//         property("sonar.login", if (project.hasProperty("SONAR_LOGIN")) project.property("SONAR_LOGIN")!! else "invalid")
//         property("sonar.exclusions", "**/BugReporterMultipartBody.java")
//     }
// }

// HaohaoChat: 第二个 allprojects 块已合并到上方，此处删除以减少 configuration 遍历

// Register quality check tasks.
// HaohaoChat: 清理已移除插件的引用（detekt/ktlintCheck 已移除，gplay flavor 已移除）
tasks.register("runQualityChecks") {
    dependsOn(":tests:konsist:testDebugUnitTest")
    project.subprojects {
        tasks.findByPath("$path:lintDebug")?.let { dependsOn(it) }
    }
    dependsOn("checkDocs")
    // Make sure all checks run even if some fail
    gradle.startParameter.isContinueOnFailure = true
}

// Register Markdown documentation check task.
tasks.register("checkDocs", Exec::class.java) {
    inputs.files("./*.md", "docs/**/*.md")
    commandLine("python3", "tools/docs/generate_toc.py", "--verify", *inputs.files.map { it.path }.toTypedArray())
}

// Register Markdown documentation TOC generation task.
tasks.register("generateDocsToc", Exec::class.java) {
    inputs.files("./*.md", "docs/**/*.md")
    commandLine("python3", "tools/docs/generate_toc.py", *inputs.files.map { it.path }.toTypedArray())
}

// HaohaoChat: 截图清理任务仅在截图测试时注册，APK 构建时跳过以减少配置开销
// 原配置会为每个子项目注册 2 个任务 + 6 次 findByName 调用（共 ~200 个任务 + ~600 次调用）
if (gradle.startParameter.taskNames.any {
    it.contains("record", ignoreCase = true) ||
    it.contains("paparazzi", ignoreCase = true) ||
    it.contains("roborazzi", ignoreCase = true)
}) {
    // Make sure to delete old screenshots before recording new ones
    subprojects {
        val snapshotsDir = File("${project.projectDir}/src/test/snapshots")
        val removeOldScreenshotsTask = tasks.register("removeOldSnapshots") {
            onlyIf { snapshotsDir.exists() }
            doFirst {
                println("Delete previous screenshots located at $snapshotsDir\n")
                snapshotsDir.deleteRecursively()
            }
        }
        tasks.findByName("recordPaparazzi")?.dependsOn(removeOldScreenshotsTask)
        tasks.findByName("recordPaparazziDebug")?.dependsOn(removeOldScreenshotsTask)
        tasks.findByName("recordPaparazziRelease")?.dependsOn(removeOldScreenshotsTask)
    }

    // Make sure to delete old snapshot before recording new ones
    subprojects {
        val screenshotsDir = File("${project.projectDir}/screenshots")
        val removeOldScreenshotsTask = tasks.register("removeOldScreenshots") {
            onlyIf { screenshotsDir.exists() }
            doFirst {
                println("Delete previous screenshots located at $screenshotsDir\n")
                screenshotsDir.deleteRecursively()
            }
        }
        tasks.findByName("recordRoborazzi")?.dependsOn(removeOldScreenshotsTask)
        tasks.findByName("recordRoborazziDebug")?.dependsOn(removeOldScreenshotsTask)
        tasks.findByName("recordRoborazziRelease")?.dependsOn(removeOldScreenshotsTask)
    }
}

subprojects {
    // HaohaoChat: 全局禁用所有 lint 任务执行，个人构建无需 lint 检查
    // 比在 Lint DSL 中设置 abortOnError=false 更彻底，完全跳过 lint 任务配置和执行
    tasks.matching { it.name.startsWith("lint") }.configureEach {
        enabled = false
    }

    // HaohaoChat: Compose compiler reports/metrics 仅对已应用 Compose 插件的模块生效
    // 避免对非 Compose 模块（如 jvm-library, android-library）执行无意义的 configureEach 回调
    plugins.withId("org.jetbrains.kotlin.plugin.compose") {
        tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
            compilerOptions {
                if (project.findProperty("composeCompilerReports") == "true") {
                    freeCompilerArgs.addAll(
                        listOf(
                            "-P",
                            "plugin:androidx.compose.compiler.plugins.kotlin:reportsDestination=" +
                                "${project.layout.buildDirectory.asFile.get().absolutePath}/compose_compiler"
                        )
                    )
                }
                if (project.findProperty("composeCompilerMetrics") == "true") {
                    freeCompilerArgs.addAll(
                        listOf(
                            "-P",
                            "plugin:androidx.compose.compiler.plugins.kotlin:metricsDestination=" +
                                "${project.layout.buildDirectory.asFile.get().absolutePath}/compose_compiler"
                        )
                    )
                }
            }
        }
    }
}
