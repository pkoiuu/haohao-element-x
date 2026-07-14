/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2022-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
plugins {
    `kotlin-dsl`
    `kotlin-dsl-precompiled-script-plugins`
}

repositories {
    mavenCentral()
    google()
}

dependencies {
    implementation("com.android.tools.build:gradle:9.2.1")
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.20")
    implementation("org.jetbrains.kotlinx:kover-gradle-plugin:0.9.8")
    // HaohaoChat: firebase 插件依赖已移除，个人构建无需 nightly 分发
    // implementation(platform("com.google.firebase:firebase-bom:34.14.1"))
    // implementation("com.google.firebase:firebase-appdistribution-gradle:5.2.1")
    implementation(files(libs.javaClass.superclass.protectionDomain.codeSource.location))
    // HaohaoChat: dependency-analysis 插件依赖已移除，已从所有 convention 插件中移除引用
    // implementation("com.autonomousapps:dependency-analysis-gradle-plugin:3.15.0")
    implementation("dev.zacsweers.metro:gradle-plugin:1.1.1")
    implementation("com.google.devtools.ksp:com.google.devtools.ksp.gradle.plugin:2.3.9")
    implementation("org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.3.20")
}
