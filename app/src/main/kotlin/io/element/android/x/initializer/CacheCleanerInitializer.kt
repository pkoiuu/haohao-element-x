/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2022-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 *
 * 修改说明: 将缓存清理改为后台线程异步执行，避免阻塞主线程导致启动卡顿
 */

package io.element.android.x.initializer

import android.content.Context
import androidx.startup.Initializer
import io.element.android.features.cachecleaner.impl.CacheCleanerBindings
import io.element.android.libraries.architecture.bindings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class CacheCleanerInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        // 异步执行缓存清理，避免阻塞主线程影响启动速度
        CoroutineScope(Dispatchers.IO).launch {
            try {
                context.bindings<CacheCleanerBindings>().cacheCleaner().clearCache()
            } catch (_: Exception) {
                // 缓存清理失败不影响正常使用
            }
        }
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
