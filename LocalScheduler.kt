package com.tjh.weibotask

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.random.Random

object LocalScheduler {
    private const val REQUEST_CODE = 2001
    private const val RETRY_DELAY_MS = 15 * 60 * 1000L
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)

    fun todayString(): String = dateFormat.format(Date())

    /**
     * 计算并登记下一次执行时间。
     *
     * 旧实现有两个致命问题：
     *  1. 把「开始日期」当成基准日。开始日期是用户当初选的一次性日期，之后永不更新，
     *     所以从第二天起基准日就是过去时间，算出来的 runAt 永远落在过去。
     *  2. 时间不在未来时只 add 一天，过去一天之后还是过去。
     *
     * 结果：闹钟被登记到一个已经过去的时间点，系统会立刻触发，
     * 触发后又算出同样的过去时间，形成「立刻重排」的死循环；
     * 如果这一轮里前台服务启动失败，整条链路就直接断掉，再也不会有下一次。
     *
     * 现在改为：基准日取 max(今天, 开始日期)，时间点不在未来就逐日顺延到未来为止。
     */
    fun scheduleNext(context: Context) {
        Prefs.init(context)

        // 暂停期内不排常规时间，等暂停结束再重试一次
        val nowMs = System.currentTimeMillis()
        if (Prefs.pausedUntil > nowMs) {
            scheduleAt(context, Prefs.pausedUntil + Random.nextInt(0, 31) * 60_000L)
            return
        }

        val now = Calendar.getInstance()
        val startDate = parseDate(Prefs.startDate.ifBlank { todayString() })

        val candidate = Calendar.getInstance().apply {
            time = if (startDate.after(now)) startDate.time else now.time
            set(Calendar.HOUR_OF_DAY, Prefs.baseHour)
            set(Calendar.MINUTE, Prefs.baseMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        var guard = 0
        while (!candidate.after(now) && guard++ < 400) {
            candidate.add(Calendar.DAY_OF_YEAR, 1)
        }

        // 用户指定「今天跳过」时，顺延一天
        if (Prefs.skipDate.isNotBlank() && dateFormat.format(candidate.time) == Prefs.skipDate) {
            candidate.add(Calendar.DAY_OF_YEAR, 1)
        }

        // 抖动 0~30 分钟，避免每天固定时刻请求
        candidate.add(Calendar.MINUTE, Random.nextInt(0, 31))
        scheduleAt(context, candidate.timeInMillis)
    }

    fun ensureScheduled(context: Context) {
        Prefs.init(context)
        if (!Prefs.scheduleEnabled) return
        val existing = Prefs.nextRunAt
        // 只要还在未来就原样重新登记（scheduleAt 是幂等的）。
        // 这里不能加「余量阈值再重算」，否则「30 秒后就要执行」的任务
        // 会被 onResume 顺手推到明天。
        if (existing > System.currentTimeMillis()) {
            scheduleAt(context, existing)
        } else {
            scheduleNext(context)
        }
    }

    /** 紧急重试：用于前台服务被系统拦截等可恢复的失败场景 */
    fun scheduleRetry(context: Context, delayMs: Long = RETRY_DELAY_MS) {
        Prefs.init(context)
        scheduleAt(context, System.currentTimeMillis() + delayMs)
    }

    fun skipToday(context: Context) {
        Prefs.init(context)
        Prefs.skipDate = todayString()
        cancel(context)
        if (Prefs.scheduleEnabled) scheduleNext(context)
    }

    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(pendingIntent(context))
        Prefs.nextRunAt = 0L
    }

    fun exactAlarmAllowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return alarmManager.canScheduleExactAlarms()
    }

    fun formatNextRun(context: Context): String {
        Prefs.init(context)
        if (!Prefs.scheduleEnabled || Prefs.nextRunAt <= 0L) return "自动签到未开启"
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
        return "下次自动执行：" + formatter.format(Date(Prefs.nextRunAt))
    }

    private fun scheduleAt(context: Context, triggerAt: Long) {
        // 兜底：绝不把闹钟登记到过去的时间点，否则会立刻触发并反复重排
        val now = System.currentTimeMillis()
        val safeTrigger = if (triggerAt <= now + 5_000L) now + 60_000L else triggerAt

        Prefs.nextRunAt = safeTrigger
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = pendingIntent(context)

        if (exactAlarmAllowed(context)) {
            try {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, safeTrigger, pendingIntent)
                return
            } catch (_: SecurityException) {
                // 权限被系统收回，落到下面的非精确分支
            } catch (_: Exception) {
            }
        }
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, safeTrigger, pendingIntent)
    }

    private fun parseDate(value: String): Calendar {
        val calendar = Calendar.getInstance()
        try {
            calendar.time = dateFormat.parse(value) ?: Date()
        } catch (_: Exception) {
            calendar.time = Date()
        }
        return calendar
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, CheckinAlarmReceiver::class.java)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
