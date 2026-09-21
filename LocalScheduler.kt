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

    /**
     * 看门狗闹钟的 request code。
     *
     * ★ 必须与主闹钟**完全分开**，而且**绝不能去写 `Prefs.nextRunAt`**。
     *   v19.4 犯过一个致命错误：闹钟广播里用 `scheduleRetry()` 排重试，
     *   而 `scheduleRetry` 会把 `nextRunAt` 写成「15 分钟后」——
     *   于是维护循环看到「下次执行在 15 分钟后」，就一直等；
     *   15 分钟后再被看门狗推一次，再等 15 分钟……**任务永远不会真正开始**。
     *   表现就是「不点开 App 就永远不执行」。
     *   看门狗的职责只有一个：**保证闹钟链不断**，它不参与业务时间。
     */
    private const val WATCHDOG_REQUEST_CODE = 2003
    private const val RETRY_DELAY_MS = 15 * 60 * 1000L

    /**
     * 主闹钟之后多久挂「备用闹钟」。
     *
     * ★ 为什么要在主闹钟之外再挂一个：主闹钟走的是 `setAlarmClock()`，
     *   备用闹钟走 `setExactAndAllowWhileIdle()` —— 在 AlarmManager 里是两条不同的通道。
     *   国产 ROM 的省电策略经常只吞掉其中一条（尤其是「闹钟和提醒」权限没给全时），
     *   多挂一条就多一次机会。备用闹钟到点时业务时间已经逾期，服务会直接开跑。
     */
    private const val WATCHDOG_BACKUP_DELAY_MS = 20 * 60 * 1000L

    /**
     * 已经逾期之后，还继续用看门狗补试多久。
     *
     * ★ 必须有界：否则「到点但一直跑不成」（例如服务被系统反复拒绝）会让看门狗
     *   每 15 分钟醒一次、无限重排，白白耗电。超过这个窗口就不再续期。
     */
    const val WATCHDOG_CHAIN_WINDOW_MS = 2 * 60 * 60 * 1000L

    /**
     * 目标时间距现在不足这个间隔时，**不再**加 0~30 分钟的随机抖动。
     *
     * ★ 存在的唯一理由是「当场测试」：用户把时间设在 1 小时以内，就是想立刻看到
     *   自动签到跑起来。日常排程不会把时间设成「5 分钟后」，所以这个判据很干净 ——
     *   它不会削弱平时的防固定时刻请求能力（每天的签到时间通常离设置时刻还有十几个小时）。
     */
    private const val JITTER_MIN_GAP_MS = 60 * 60 * 1000L

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)

    /** 看门狗闹钟的标记：Receiver 靠它区分「业务闹钟」和「看门狗」。 */
    const val EXTRA_WATCHDOG = "com.tjh.weibotask.EXTRA_WATCHDOG"

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

        val base = baseRunAt(context)

        // 抖动 0~30 分钟，避免每天固定时刻请求。
        //
        // ★ 但「当场测试」必须跳过抖动：用户把时间设在 1 小时以内，只可能是想现在
        //   验证「自动签到到底会不会自己跑起来」（日常排程不会把时间设成「5 分钟后」）。
        //   这时如果还加最多 30 分钟抖动，用户设 19:35、实际排到 20:05，
        //   等十几分钟没动静就会判定「自动签到是坏的」—— 而他其实只是还没到点。
        //   所以：目标时间距现在不足 1 小时 → 不加抖动，设几点就几点跑。
        val trigger = if (base - nowMs > JITTER_MIN_GAP_MS) {
            base + Random.nextInt(0, 31) * 60_000L
        } else {
            base
        }
        scheduleAt(context, trigger)
    }

    /**
     * 用户设定的时间点对应的**下一次**执行时刻 —— **不含**随机抖动。
     *
     * ★ 为什么要把这个单独抽出来：排程时会加 0~30 分钟随机抖动（防止每天固定时刻请求），
     *   于是「设定时间 18:51」和「下次自动执行 19:05」会同时出现在界面上，差 14 分钟。
     *   用户看到两个对不上的数字，第一反应是「我是不是设错了」——
     *   而程序其实完全正常。有了这个基准值，界面就能写成
     *   「19:05（= 设定时间 18:51 + 14 分钟随机延迟）」，一眼就懂。
     *
     * 旧实现在这里有两个致命问题（已修，注释保留作为教训）：
     *  1. 把「开始日期」当成基准日。开始日期是用户当初选的一次性日期，之后永不更新，
     *     所以从第二天起基准日就是过去时间，算出来的 runAt 永远落在过去。
     *  2. 时间不在未来时只 add 一天，过去一天之后还是过去。
     * 结果：闹钟被登记到一个已经过去的时间点，系统会立刻触发，触发后又算出同样的过去时间，
     * 形成「立刻重排」的死循环；如果这一轮里前台服务启动失败，整条链路就直接断掉。
     * 现在改为：基准日取 max(今天, 开始日期)，时间点不在未来就逐日顺延到未来为止。
     */
    fun baseRunAt(context: Context): Long {
        Prefs.init(context)
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
        return candidate.timeInMillis
    }

    /**
     * 测试模式：把**下一次**自动签到安排在 [delayMs] 之后。
     *
     * ★ 它与日常定时走的是**完全相同**的一条通道 —— 同一个 PendingIntent、
     *   同一个 setAlarmClock 闹钟、同一个 AgentService。
     *   所以「这一轮能自己跑起来」就等于「日常定时也能跑起来」，
     *   这正是用户想验证的东西。（手动点「立即签到」走的是另一条路：直接拉起服务，
     *   根本不经过闹钟，验证不了定时链路。）
     *
     * ★ 它**不改** startDate / baseHour / baseMinute：测试只是插一次性的执行，
     *   用户辛苦设好的每日时间不该被覆盖。这一轮跑完之后，AgentService 会照常
     *   按每日时间 scheduleNext()，自动回到原来的节奏。
     */
    fun scheduleTest(context: Context, delayMs: Long = 2 * 60 * 1000L) {
        Prefs.init(context)
        scheduleAt(context, System.currentTimeMillis() + delayMs)
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
            return
        }

        // ★ 到这里说明「登记的执行时间已经过去了，但任务没跑」——
        //   也就是闹钟被系统丢了、或者应用被强制停止过。
        //
        //   旧实现这里直接调 scheduleNext()，等于**把错过的这一轮静默推到明天**：
        //   用户看到「到点没执行」，一点开 App，程序悄悄把任务挪走了，
        //   当天再也不会补上，而且界面上没有任何提示。
        //
        //   现在改成：只要今天还有该跑没跑的账号，就登记成「立刻执行」，
        //   交给这次启动的服务补跑。确实没活了，才顺延到下一个时间点。
        if (hasPendingWork(context)) {
            scheduleAt(context, System.currentTimeMillis())
        } else {
            scheduleNext(context)
        }
    }

    /**
     * 今天是否还有「该跑但没跑」的账号。
     *
     * 判据是**逐个账号**看，不是看一个全局日期标记 ——
     * 分批错峰下一轮只跑 3 个账号，全局标记跑完第一批就被置成「今天跑过了」，
     * 用它判断会让剩下的批次被当成「没活了」而丢掉。
     */
    private fun hasPendingWork(context: Context): Boolean {
        if (Prefs.pausedUntil > System.currentTimeMillis()) return false
        // ★ 刚跑过就先别补。否则 runCheckin 抛异常时（异常路径上 lastRunDate 不会被写），
        //   ensureScheduled 会被 onResume 反复触发，每分钟拉起一次任务 —— 变成空转，
        //   白白往微博发请求。10 分钟的间隔足够让一次真实执行跑完。
        if (System.currentTimeMillis() - Prefs.lastRunAt < 10 * 60 * 1000L) return false
        return try {
            AccountStore.init(context)
            val today = todayString()
            AccountStore.notRunToday(today).isNotEmpty() ||
                AccountStore.partialPending(today).isNotEmpty() ||
                // ★ 发帖也要算「还有活」：否则签到一跑完，这里就判定「今天没事了」
                //   → 排到明天 → 当天的发帖链直接断掉（一天只发了 1 条）。
                (Prefs.postEnabled && AccountStore.postPending(today, Prefs.postPerAccount).isNotEmpty())
        } catch (_: Exception) {
            // 读账号列表失败时不要谎报「有活」，否则会陷入反复重排
            false
        }
    }

    /** 紧急重试：只用于「业务时间」的重新登记（下一批 / 补签），会写 nextRunAt。 */
    fun scheduleRetry(context: Context, delayMs: Long = RETRY_DELAY_MS) {
        Prefs.init(context)
        scheduleAt(context, System.currentTimeMillis() + delayMs)
    }

    /**
     * 看门狗：保证闹钟链不断，**不参与业务时间**（绝不写 `nextRunAt`）。
     *
     * 两个来源：
     *   ① [armBackupWatchdog]：每次登记主闹钟时顺手挂在「业务时间 + 20 分钟」——
     *      主闹钟走 setAlarmClock、它走 setExactAndAllowWhileIdle，是两条通道，
     *      国产 ROM 常常只吞掉其中一条。
     *   ② [CheckinAlarmReceiver]：闹钟触发时按「是否逾期且未超容错窗口」续期。
     *
     * 它解决的是这个问题：闹钟是一次性的，如果某次触发后服务没能把下一批排上
     * （被系统拦截、进程被杀、冷启动崩掉），整条链就断了 —— 直到第二天才会再响。
     * 有了看门狗，最多 15 分钟后就会再醒一次。
     *
     * ★ 它必须走**独立的 request code**，否则会把主闹钟顶掉；
     *   而且它**不能写 nextRunAt**，否则维护循环会以为「下次执行还早」而一直等（v19.4 的致命 bug）。
     */
    fun scheduleWatchdog(context: Context, delayMs: Long = RETRY_DELAY_MS) {
        Prefs.init(context)
        val trigger = System.currentTimeMillis() + delayMs
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = watchdogPendingIntent(context)
        try {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, intent)
            Prefs.lastWatchdogAt = trigger
            return
        } catch (_: Exception) {
        }
        try {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, intent)
            Prefs.lastWatchdogAt = trigger
        } catch (_: Exception) {
        }
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
        try {
            alarmManager.cancel(watchdogPendingIntent(context))
        } catch (_: Exception) {
        }
        Prefs.nextRunAt = 0L
        Prefs.lastWatchdogAt = 0L
    }

    fun exactAlarmAllowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return alarmManager.canScheduleExactAlarms()
    }

    /**
     * 首页那行「下次自动执行」。
     *
     * ★ 必须带上「还有多久」。原因：排程时会加 0~30 分钟的随机抖动（避免每天固定时刻
     *   打请求，降低风控风险），所以用户设 19:30 实际可能排到 19:52。
     *   只显示一个绝对时间，用户等到 19:35 就会以为「坏了」—— 而他根本不知道该等到什么时候。
     *   加上倒计时之后，「还有 32 分钟」一眼就知道现在还没到，不用瞎等。
     */
    fun formatNextRun(context: Context): String {
        Prefs.init(context)
        if (!Prefs.scheduleEnabled || Prefs.nextRunAt <= 0L) return "自动签到未开启"
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
        val remainMs = Prefs.nextRunAt - System.currentTimeMillis()
        val remain = when {
            remainMs <= 0L -> "（已到时间，正在补跑）"
            remainMs < 60 * 60 * 1000L -> "（还有 " + (remainMs / 60000L).coerceAtLeast(1L) + " 分钟）"
            else -> "（还有 " + (remainMs / 3600000L) + " 小时 " +
                ((remainMs % 3600000L) / 60000L) + " 分钟）"
        }
        val text = StringBuilder()
        text.append("下次自动执行：").append(formatter.format(Date(Prefs.nextRunAt))).append(remain)
        val note = jitterNote(context)
        if (note.isNotBlank()) text.append("\n").append(note)
        return text.toString()
    }

    /**
     * 「为什么显示的时间和设定时间不一样」的说明。
     *
     * ★ 真实案例（v0.19.9 的测试报告）：报告里同时出现
     *     设定时间：18:51
     *     下次自动执行：2026-09-19 19:05:00
     *   两个数字差 14 分钟，用户第一反应是「我是不是设错了」——
     *   其实那 14 分钟就是防固定时刻请求的随机抖动，程序完全正常。
     *   把它写出来，这一类疑问就永远不会变成一次反馈。
     *
     * 只在确实存在抖动时才输出（0~30 分钟），平时不占版面；
     * 测试跑（下次执行在几分钟后、而基准在明天）算出来的差值会是负数，也会被这条过滤掉。
     */
    fun jitterNote(context: Context): String {
        Prefs.init(context)
        if (!Prefs.scheduleEnabled || Prefs.nextRunAt <= 0L) return ""
        val base = baseRunAt(context)
        val deltaMinutes = (Prefs.nextRunAt - base) / 60_000L
        if (deltaMinutes !in 0L..30L) return ""
        return "= 设定时间 " + SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(base)) +
            " 再加 " + deltaMinutes + " 分钟随机延迟（防止每天固定时刻发请求，属正常）"
    }

    private fun scheduleAt(context: Context, triggerAt: Long) {
        // 兜底：绝不把闹钟登记到过去的时间点，否则会立刻触发并反复重排。
        val now = System.currentTimeMillis()
        val safeTrigger = if (triggerAt <= now + 5_000L) now + 60_000L else triggerAt
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = pendingIntent(context)
        val errors = mutableListOf<String>()

        // ★ 不再用 canScheduleExactAlarms() 把 setAlarmClock 和 exact 两档一起跳过。
        //   真实系统/ROM 的授权状态并不总和这个查询一致，最可靠的判据是「实际调用成功没有」。
        //   每一档都亲自尝试，失败才降级；并把最后真正用到的档位写进首页。
        try {
            alarmManager.setAlarmClock(
                AlarmManager.AlarmClockInfo(safeTrigger, showIntent(context)),
                pendingIntent
            )
            recordScheduled(context, safeTrigger, "alarm_clock", "")
            return
        } catch (error: Exception) {
            errors.add("alarm_clock:" + error.javaClass.simpleName)
        }

        try {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, safeTrigger, pendingIntent)
            recordScheduled(context, safeTrigger, "exact", errors.joinToString(" | "))
            return
        } catch (error: Exception) {
            errors.add("exact:" + error.javaClass.simpleName)
        }

        try {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, safeTrigger, pendingIntent)
            recordScheduled(context, safeTrigger, "inexact", errors.joinToString(" | "))
        } catch (error: Exception) {
            // ★ 只有真正登记成功后才能写 nextRunAt。旧代码先写时间再登记，三档全失败时
            //   首页仍显示「下次自动执行」，实际上系统里根本没有闹钟。
            Prefs.nextRunAt = 0L
            Prefs.lastAlarmMode = "failed"
            Prefs.lastAlarmError = (errors + ("inexact:" + error.javaClass.simpleName)).joinToString(" | ")
            Prefs.lastRunStatus = "ALARM_FAILED"
            Prefs.lastRunMessage = "系统未能登记自动签到闹钟，请允许「闹钟和提醒」"
        }
    }

    private fun recordScheduled(context: Context, triggerAt: Long, mode: String, error: String) {
        Prefs.nextRunAt = triggerAt
        Prefs.lastAlarmMode = mode
        Prefs.lastAlarmError = error
        armBackupWatchdog(context, triggerAt)
    }

    /**
     * 在业务时间之后 [WATCHDOG_BACKUP_DELAY_MS] 挂一个备用闹钟。
     *
     * ★ 关键：它**只**决定「什么时候再醒一次看看」，绝不改 `nextRunAt`。
     *   到点后它发现业务时间已经逾期，服务会按正常流程开跑；
     *   如果那时候已经跑完了（nextRunAt 被推到明天），它就安静地不再续期。
     *   这样既多了一次机会，又不会像 v19.4 那样把业务时间往后推、导致任务永远不开始。
     */
    private fun armBackupWatchdog(context: Context, triggerAt: Long) {
        val delay = (triggerAt - System.currentTimeMillis()) + WATCHDOG_BACKUP_DELAY_MS
        scheduleWatchdog(context, if (delay < 60_000L) 60_000L else delay)
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

    /** 看门狗专用 PendingIntent：request code 独立 + 带 [EXTRA_WATCHDOG] 标记，便于 Receiver 区分。 */
    private fun watchdogPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, CheckinAlarmReceiver::class.java)
            .putExtra(EXTRA_WATCHDOG, true)
        return PendingIntent.getBroadcast(
            context,
            WATCHDOG_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * setAlarmClock() 要求的「展示用」PendingIntent —— 用户点状态栏的闹钟图标时打开本应用。
     *
     * ★ 必须用 getActivity（打开界面），不能拿广播的 PendingIntent 顶替：
     *   那样点闹钟图标只会发一次广播，用户会觉得「点了没反应」。
     */
    private fun showIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
        return PendingIntent.getActivity(
            context,
            REQUEST_CODE + 1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
