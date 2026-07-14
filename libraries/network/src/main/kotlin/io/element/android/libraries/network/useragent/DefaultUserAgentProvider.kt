/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 *
 * 修改说明: 简化 User-Agent 格式以兼容 Matrix Authentication Service (MAS)
 * 原格式包含特殊字符导致 MAS 返回 "invalid HTTP header (user-agent)" 错误
 * 这会导致 OAuth token 刷新失败，进而导致 sync 服务进入 offline mode（真离线）
 */

package io.element.android.libraries.network.useragent

import android.os.Build
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.core.meta.BuildMeta
import io.element.android.libraries.matrix.api.SdkMetadata

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DefaultUserAgentProvider(
    private val buildMeta: BuildMeta,
    private val sdkMeta: SdkMetadata,
) : UserAgentProvider {
    private val userAgent: String by lazy { buildUserAgent() }

    override fun provide(): String = userAgent

    /**
     * 创建符合 MAS (Matrix Authentication Service) 要求的 User-Agent
     *
     * ⚠️ 关键：User-Agent 只能包含 ASCII 字符！
     * 任何非 ASCII 字符（如中文）都会导致 MAS 返回：
     *   "invalid HTTP header (user-agent)" → HTTP 400/500 → JSON 解析失败 →
     *   "expected value at line 1 column 1" → client registration failed
     *
     * 错误链路：
     *   中文 UA → MAS 拒绝 → 空 HTML 响应体 → Rust SDK 解析失败 → 登录/注册/OAuth 全部失败
     *
     * 正确格式示例：HaohaoChat/1.0.0 Android/12
     */
    private fun buildUserAgent(): String {
        // 强制使用纯 ASCII，不依赖 buildMeta（可能含中文）
        return "HaohaoChat/1.0.0 Android/${Build.VERSION.RELEASE}"
    }
}
