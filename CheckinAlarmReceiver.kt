package com.tjh.weibotask

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class CheckinAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        try {
            Prefs.init(context)
        } catch (_: Exception) {
            // 加密存储损坏（EncryptedSharedPreferences 已知会抛 AEADBadTagException）
            // 时不能让 receiver 崩掉，否则后面什么都做不了
            return
        }
        if (!Prefs.scheduleEnabled) return

        // ★ 关键：闹钟是一次性的，而旧代码把「重排下一次」放在 AgentService 里。
        //   一旦 Service 因为任何原因没起来，闹钟就再也不会被登记 —— 整条链路永久中断。
        //   所以这里先无条件把下一次闹钟排好，保证链路自愈。
        try {
            LocalScheduler.scheduleNext(context)
        } catch (_: Exception) {
        }

        val serviceIntent = Intent(context, AgentService::class.java).apply {
            action = AgentService.ACTION_RUN_CHECKIN
        }
        try {
            ContextCompat.startForegroundService(context, serviceIntent)
        } catch (error: Exception) {
            // Android 12+ 禁止后台启动前台服务，豁免清单里只认「精确闹钟」，
            // 非精确闹钟（setAndAllowWhileIdle）不豁免，会抛
            // ForegroundServiceStartNotAllowedException。
            Prefs.lastRunStatus = "BLOCKED"
            Prefs.lastRunMessage = "系统阻止了后台执行（" + error.javaClass.simpleName +
                "）。请到系统设置里允许「闹钟和提醒」，并关闭本应用的电池优化。"
            try {
                // 15 分钟后重试，而不是就此放弃
                LocalScheduler.scheduleRetry(context)
            } catch (_: Exception) {
            }
        }
    }
}
