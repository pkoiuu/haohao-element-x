/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2022-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

/**
 * This will generate the plugin "io.element.jvm-library", used in pure JVM libraries.
 */
import extension.setupKover
import org.gradle.accessors.dm.LibrariesForLibs

val libs = the<LibrariesForLibs>()
plugins {
    id("org.jetbrains.kotlin.jvm")
    // HaohaoChat: dependency-analysis 插件已移除以加速构建
    // id("com.autonomousapps.dependency-analysis")
    // HaohaoChat: lint 插件已移除，个人构建无需 JVM 模块 lint 检查
    // id("com.android.lint")
}

kotlin {
    jvmToolchain {
        languageVersion = Versions.javaLanguageVersion
    }
}

setupKover()
