/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2022-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 *
 * 修改说明: 将 runBlocking 改为协程异步调用，消除主线程阻塞（原代码有 3 个 runBlocking 导致帧跳过）
 */

package io.element.android.x.initializer

import android.content.Context
import android.system.Os
import androidx.startup.Initializer
import io.element.android.features.rageshake.api.logs.createWriteToFilesConfiguration
import io.element.android.libraries.architecture.bindings
import io.element.android.libraries.featureflag.api.FeatureFlags
import io.element.android.libraries.matrix.api.tracing.TracingConfiguration
import io.element.android.x.di.AppBindings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber

private const val ELEMENT_X_TARGET = "elementx"

class PlatformInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        val appBindings = context.bindings<AppBindings>()
        val tracingService = appBindings.tracingService()
        val platformService = appBindings.platformService()
        val bugReporter = appBindings.bugReporter()
        Timber.plant(tracingService.createTimberTree(ELEMENT_X_TARGET))
        val preferencesStore = appBindings.preferencesStore()
        val featureFlagService = appBindings.featureFlagService()

        // 使用异步协程替代 runBlocking，避免阻塞主线程导致帧跳过
        CoroutineScope(Dispatchers.IO).launch {
            val logLevel = preferencesStore.getTracingLogLevelFlow().first()
            val writesToLogcat = featureFlagService.isFeatureEnabled(FeatureFlags.PrintLogsToLogcat)
            val traceLogPacks = preferencesStore.getTracingLogPacksFlow().first()

            val tracingConfiguration = TracingConfiguration(
                writesToLogcat = writesToLogcat,
                writesToFilesConfiguration = bugReporter.createWriteToFilesConfiguration(),
                logLevel = logLevel,
                extraTargets = listOf(ELEMENT_X_TARGET),
                traceLogPacks = traceLogPacks,
                sdkSentryDsn = appBindings.sentrySdkDsn()?.value?.takeIf { it.isNotBlank() },
            )

            // 切回主线程更新 UI 相关配置
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                bugReporter.setCurrentTracingLogLevel(logLevel.name)
                platformService.init(tracingConfiguration)
            }
        }

        // Also set env variable for rust back trace
        Os.setenv("RUST_BACKTRACE", "1", true)
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = mutableListOf()
}
