/*
 * HaohaoChat v26.06.5: 设备重启后自动启动推送服务
 *
 * v26.06.5 改进:
 * - 使用 WorkManager expedited 替代直接 startForegroundService
 * - Android 15+ (API 35+) BOOT_COMPLETED 不能直接启动 dataSync/specialUse FGS
 * - 必须通过 WorkManager expedited 获取短时间前台执行权限
 *
 * v26.06.4 原始实现:
 * - 直接调用 PushForegroundService.start(context)
 * - 在 Android 15+ 上会抛出 ForegroundServiceStartNotAllowedException
 */

package io.element.android.libraries.pushproviders.unifiedpush

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val LOG_TAG = "PushBootReceiver"
        private const val PREFS_NAME = "push_foreground_prefs"
        private const val PREF_KEY_TOPIC = "saved_topic"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            intent.action == "android.intent.action.QUERY_PACKAGE_RESTART"
        ) {
            Log.i(LOG_TAG, "Boot/restart received, checking push service")

            // 从 SharedPreferences 检查是否有已注册的 topic
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val savedTopic = prefs.getString(PREF_KEY_TOPIC, null)

            if (savedTopic != null) {
                Log.i(LOG_TAG, "Found saved topic=$savedTopic, scheduling service start via WorkManager")
                // HaohaoChat v26.06.5: 使用 WorkManager expedited 启动服务
                // Android 15+ BOOT_COMPLETED 不能直接启动 FGS
                scheduleServiceStart(context)
            } else {
                Log.i(LOG_TAG, "No saved topic, not starting service")
            }
        }
    }

    /**
     * 通过 WorkManager expedited 安排服务启动
     *
     * expedited worker 拥有短时间的前台执行权限，
     * 可以在 Android 15+ 上合法地启动前台服务。
     */
    private fun scheduleServiceStart(context: Context) {
        try {
            val request = OneTimeWorkRequestBuilder<PushServiceRecoveryWorker>()
                .setInitialDelay(2, TimeUnit.SECONDS) // 短延迟等待系统就绪
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "push_service_boot_start",
                ExistingWorkPolicy.REPLACE,
                request
            )
            Log.i(LOG_TAG, "✅ Scheduled PushServiceRecoveryWorker for boot start")
        } catch (e: Exception) {
            Log.e(LOG_TAG, "❌ Failed to schedule service start, trying direct start", e)
            // Fallback: 尝试直接启动 (Android 14 及以下可以)
            try {
                PushForegroundService.start(context)
            } catch (e2: Exception) {
                Log.e(LOG_TAG, "Direct start also failed", e2)
            }
        }
    }
}
