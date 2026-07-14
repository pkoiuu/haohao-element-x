/*
 * HaohaoChat v26.06.5: 推送服务恢复 Worker
 *
 * 功能: Android 15+ FGS 超时后，通过 WorkManager expedited 重新启动 PushForegroundService
 *
 * 背景:
 * Android 15 (API 35) 对 dataSync 类型的前台服务施加 6 小时超时限制。
 * 超时后系统调用 onTimeout()，服务被强制停止。
 * 此时不能直接重新 startForegroundService()，需要通过 WorkManager expedited
 * 获取短时间的前台执行权限来重启服务。
 */

package io.element.android.libraries.pushproviders.unifiedpush

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class PushServiceRecoveryWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    companion object {
        private const val LOG_TAG = "PushRecoveryWorker"
    }

    override suspend fun doWork(): Result {
        Log.i(LOG_TAG, "doWork: attempting to restart PushForegroundService")

        return try {
            val serviceIntent = Intent(applicationContext, PushForegroundService::class.java)
            // 从 SharedPreferences 恢复 topic
            val prefs = applicationContext.getSharedPreferences("push_foreground_prefs", Context.MODE_PRIVATE)
            val topic = prefs.getString("saved_topic", null)
            if (topic != null) {
                serviceIntent.putExtra("topic", topic)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                applicationContext.startForegroundService(serviceIntent)
            } else {
                applicationContext.startService(serviceIntent)
            }

            Log.i(LOG_TAG, "✅ PushForegroundService restart initiated")
            Result.success()
        } catch (e: Exception) {
            Log.e(LOG_TAG, "❌ Failed to restart PushForegroundService", e)
            // 短延迟后重试
            Result.retry()
        }
    }
}
