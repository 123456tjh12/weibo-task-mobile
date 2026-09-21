package com.tjh.weibotask

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
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

        // ★ 心跳：记下「闹钟真的响过」。
        //   用户报「到点没执行」时，这是唯一能区分两种原因的东西 ——
        //   闹钟没响（被系统省电策略丢弃）vs 闹钟响了但后台启动服务被拒。
        //   没有它，两种情况在界面上长得一模一样，只能靠猜。
        try {
            Prefs.lastAlarmAt = System.currentTimeMillis()
        } catch (_: Exception) {
        }

        // ★★ 这里**绝对不能**再调用 scheduleNext() / scheduleRetry()。
        //
        //   v19.4 犯过一个致命错误：闹钟广播里调 scheduleRetry()，而它会把 nextRunAt
        //   写成「15 分钟后」。维护循环于是看到「下次执行还早」就一直等；
        //   15 分钟后看门狗再推一次、再等 15 分钟……**任务永远不会真正开始**。
        //   表现就是「不点开 App 就永远不执行」。
        //
        //   现在：看门狗只负责「保证闹钟链不断」，走独立 request code，**不碰 nextRunAt**；
        //   业务时间（nextRunAt）只由 executeOnce 结束时的 scheduleRetry/scheduleNext 决定。
        val now = System.currentTimeMillis()
        val isWatchdog = intent?.getBooleanExtra(LocalScheduler.EXTRA_WATCHDOG, false) == true
        // 业务时间已经到了（或已过期）→ 现在就该跑。
        val due = Prefs.nextRunAt > 0L && Prefs.nextRunAt <= now

        // 看门狗续期规则（两条都必须有界，否则会变成每 15 分钟无限重排、白白耗电）：
        //   ① 主闹钟触发 → 补挂一次。万一这一轮跑不成（服务被拒、进程被杀），还有下一次机会。
        //   ② 看门狗自己触发 → 只在「已经逾期，且还在 2 小时容错窗口内」时续期。
        //
        //   ★ 关键：一旦这一轮真的跑成功，`nextRunAt` 会被 executeOnce 推到明天，
        //     `due` 立刻变回 false，链子自然结束 —— 不需要额外的计数器。
        val overdueMs = if (Prefs.nextRunAt > 0L) now - Prefs.nextRunAt else Long.MIN_VALUE
        val withinChain = due && overdueMs <= LocalScheduler.WATCHDOG_CHAIN_WINDOW_MS
        if (!isWatchdog || withinChain) {
            try {
                LocalScheduler.scheduleWatchdog(context)
            } catch (_: Exception) {
            }
        }

        // 广播到服务 onStartCommand 之间也可能再次休眠；用带超时的短锁只桥接冷启动，
        // 不长期持有，不需要手工释放（90 秒后系统自动释放）。
        try {
            val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            bridgeWakeLock = power.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "WeiboTask::AlarmBridgeWakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire(90_000L)
            }
        } catch (_: Exception) {
        }

        val serviceIntent = Intent(context, AgentService::class.java).apply {
            // 到点了 → 直接开跑；还没到点（看门狗提前醒）→ 只把服务拉起来待命，
            // 由维护循环在正确时间触发，避免把分批间隔打乱。
            action = if (due) AgentService.ACTION_RUN_CHECKIN else AgentService.ACTION_START_SCHEDULED
        }
        try {
            ContextCompat.startForegroundService(context, serviceIntent)
            // 这只证明系统接受了启动请求；AgentService.onStartCommand 另记 lastServiceStartAt，
            // 两个时间能区分「请求被接受」和「服务真的启动」。
            Prefs.lastAlarmAcceptedAt = System.currentTimeMillis()
        } catch (error: Exception) {
            Prefs.lastRunStatus = "BLOCKED"
            Prefs.lastRunMessage = "系统阻止了后台执行（" + error.javaClass.simpleName +
                "）。15 分钟后看门狗会再试；请允许「闹钟和提醒」并关闭电池优化。"
            Prefs.lastAlarmError = "start_service:" + error.javaClass.simpleName
        }
    }

    companion object {
        // 保持强引用直到 90 秒超时；否则局部 WakeLock 可能在服务冷启动前被回收。
        private var bridgeWakeLock: PowerManager.WakeLock? = null
    }
}
