/*
 * HaohaoChat v26.06.4: 设备重启后自动启动推送服务
 * 原项目没有 BootReceiver，设备重启后需要用户手动打开 App 才能恢复推送
 */

package io.element.android.libraries.pushproviders.unifiedpush

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

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
                Log.i(LOG_TAG, "Found saved topic=$savedTopic, starting PushForegroundService")
                PushForegroundService.start(context)
            } else {
                Log.i(LOG_TAG, "No saved topic, not starting service")
            }
        }
    }
}
