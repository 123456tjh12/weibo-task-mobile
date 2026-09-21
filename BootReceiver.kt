package com.tjh.weibotask

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        try {
            Prefs.init(context)
            if (!Prefs.scheduleEnabled) return
            // 重启/应用更新会清掉 AlarmManager 里的登记，先恢复闹钟；
            // 服务若被 Android 14+ 拒绝，闹钟链仍然存在，不会一起断掉。
            LocalScheduler.scheduleNext(context)
            val serviceIntent = Intent(context, AgentService::class.java).apply {
                action = AgentService.ACTION_START_SCHEDULED
            }
            try {
                androidx.core.content.ContextCompat.startForegroundService(context, serviceIntent)
            } catch (error: Exception) {
                Prefs.lastRunStatus = "BLOCKED"
                Prefs.lastRunMessage = "开机后系统阻止后台服务启动（" +
                    error.javaClass.simpleName + "），已保留系统闹钟"
            }
        } catch (_: Exception) {
            // Receiver 不能崩；用户解锁后打开 App 时 ensureScheduled 会再次自愈。
        }
    }
}
