/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 *
 * 修改说明:
 * APPLICATION_NAME 必须设为纯英文字符串
 * 原值为空字符串 ""，导致 fallback 到 R.string.app_name（中文名"Haohao聊天"）
 * 中文名进入 User-Agent HTTP 头导致 MAS 返回 "invalid HTTP header (user-agent)" 错误
 * 进而导致登录/注册/OAuth 全部失败（client registration failed）
 */

package io.element.android.appconfig

object ApplicationConfig {
    /**
     * Application name used in the UI for string. If empty, the value is taken from the resources `R.string.app_name`.
     * Note that this value is not used for the launcher icon.
     *
     * ⚠️ 必须使用纯英文字符！此值会被用作 HTTP User-Agent 头。
     * 如果包含中文或特殊字符，MAS (Matrix Authentication Service) 会拒绝请求，
     * 导致 client registration failed / OAuth 登录失败 / 真离线 等一系列问题。
     */
    const val APPLICATION_NAME: String = "HaohaoChat"

    /**
     * Used in the strings to reference the Element client.
     * Cannot be empty.
     * For Element, the value is "Element".
     */
    const val PRODUCTION_APPLICATION_NAME: String = "HaohaoChat"

    /**
     * Used in the strings to reference the Element Desktop client, for instance Element Web.
     * Cannot be empty.
     */
    const val DESKTOP_APPLICATION_NAME: String = "HaohaoChat"
}
