package com.tjh.weibotask

import android.Manifest
import android.app.AlarmManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.PowerManager
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.DatePicker
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.TimePicker
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())

    /** 账号 id -> 该行视图。列表结构变化时重建，状态变化时只改文字。 */
    private val rowViews = mutableMapOf<String, View>()

    /**
     * 本次启动是否已经提示过「闹钟和提醒」权限。
     *
     * ★ 必须只提示一次：这个提示的按钮会跳到系统设置页，
     *   如果每次回到前台都弹，用户点「返回」就又弹一次，会变成退不出去的死循环。
     */
    private var exactAlarmPrompted = false

    /**
     * 本次启动是否已经提示过「自动签到还没开启」。
     *
     * ★ 同样只弹一次：横幅是常驻的，弹窗只是「第一次打开时确保被看见」。
     *   每次都弹会让用户烦，但一次都不弹又可能整轮排查方向都是错的。
     */
    private var schedulePrompted = false

    /**
     * 使用说明是否展开（首页 4 处灰色小字）。
     *
     * ★ 默认收起：对已经会用的人来说，那些说明每次打开都要越过才能看到状态，
     *   是纯噪音；但对第一次用的人又必须能看到。一个开关同时满足两边。
     *   ★ 收起只是「不显示」，不改变任何行为 —— 警示横幅、状态块、按钮全都不受影响。
     */
    private var notesExpanded = false

    /**
     * 状态块的详细部分是否展开。
     *
     * ★ 默认收起：那一块会长到十几行（执行链 + 每轮账号明细），
     *   而每天要看的只有「下次什么时候跑 / 今天签了几个 / 上次成没成」。
     * ★ 但收起**只针对正常时的细节** —— 需要用户动手的告警由 updateStatus()
     *   用 alerts 列表提升到摘要区，不受这个开关影响。
     */
    private var statusExpanded = false

    // 提成字段：保存按钮和「立即开启」弹窗都要读它们当前选中的值
    private lateinit var datePicker: DatePicker
    private lateinit var timePicker: TimePicker

    private val refreshRunnable = object : Runnable {
        override fun run() {
            updateStatus()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        AccountStore.init(this)
        AccountStore.ensureAtLeastOne()
        setContentView(R.layout.activity_main)

        datePicker = findViewById(R.id.datePicker)
        timePicker = findViewById(R.id.timePicker)
        timePicker.setIs24HourView(true)

        datePicker.minDate = System.currentTimeMillis() - 1000L
        val startCalendar = Calendar.getInstance()
        if (Prefs.startDate.isNotBlank()) {
            try { startCalendar.time = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).parse(Prefs.startDate) ?: Date() } catch (_: Exception) {}
        }
        datePicker.updateDate(
            startCalendar.get(Calendar.YEAR),
            startCalendar.get(Calendar.MONTH),
            startCalendar.get(Calendar.DAY_OF_MONTH)
        )

        timePicker.hour = Prefs.baseHour
        timePicker.minute = Prefs.baseMinute

        requestNotificationPermission()

        findViewById<Button>(R.id.addAccountButton).setOnClickListener {
            // 先建一个空账号槽位，再带它的 id 去登录页，
            // 登录成功后 Cookie 会精确写回这个槽位，不会覆盖其它账号
            val account = AccountStore.add()
            renderAccounts()
            updateStatus()
            openLogin(account)
        }

        findViewById<Button>(R.id.checkLoginButton).setOnClickListener {
            checkAllLogins()
        }

        // ★ 「去微博完成验证」：微博判定「你最近的行为存在异常」时会拦下所有请求，
        //   这时唯一的出路是**人工**完成一次验证 —— 程序怎么重试都没用。
        //   而验证必须带着**同一个会话**做（换会话等于白做），
        //   所以这里在应用内用 WebView 打开微博，用户验证完 Cookie 会自动存回。
        findViewById<Button>(R.id.verifyButton).setOnClickListener {
            val accounts = AccountStore.all().filter { it.cookie.isNotBlank() }
            if (accounts.isEmpty()) {
                toast("还没有登录任何微博账号")
                return@setOnClickListener
            }
            if (accounts.size == 1) {
                openVerify(accounts[0])
                return@setOnClickListener
            }
            val labels = accounts.map { it.displayName }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("要验证哪个账号？")
                .setItems(labels) { _, which -> openVerify(accounts[which]) }
                .setNegativeButton("取消", null)
                .show()
        }

        findViewById<Button>(R.id.saveScheduleButton).setOnClickListener {
            enableScheduleFromPickers()
        }

        // ★★ 「测试自动签到」：一键把整条自动链路原样跑一遍。
        //
        //   用户想验证的是「到点程序会不会自己动起来」，而不是「点按钮能不能签到」——
        //   这两件事走的根本不是同一条路（「立即签到」是直接拉起服务，完全不经过闹钟）。
        //   所以必须有一个按钮，让闹钟那条路也能量一次，而且不用去跟日期/时间选择器较劲。
        //
        //   它做三件事：
        //     ① 开启自动签到（不开启的话服务起来后会立刻自己停掉，什么都测不到）
        //     ② 把闹钟登记到 2 分钟后 —— 走 setAlarmClock，和日常定时完全同一条通道
        //     ③ 明确告诉用户「现在可以息屏等」，以及回来之后看哪一行
        findViewById<Button>(R.id.testScheduleButton).setOnClickListener {
            val ready = AccountStore.ready()
            if (ready.isEmpty()) {
                toast("请先添加并登录微博账号")
                return@setOnClickListener
            }
            setScheduleEnabled(true, "点了「测试自动签到」")
            // 清掉风控暂停，否则测试这一轮会被顺延到暂停结束时间
            Prefs.pausedUntil = 0L
            LocalScheduler.scheduleTest(this, TEST_DELAY_MINUTES * 60_000L)
            val serviceIntent = Intent(this, AgentService::class.java).apply {
                action = AgentService.ACTION_START_SCHEDULED
            }
            ContextCompat.startForegroundService(this, serviceIntent)
            updateStatus()

            val dailyTime = String.format(Locale.CHINA, "%02d:%02d", Prefs.baseHour, Prefs.baseMinute)
            AlertDialog.Builder(this)
                .setTitle("已安排 " + TEST_DELAY_MINUTES + " 分钟后自动签到")
                .setMessage(
                    "这次走的是和定时签到完全相同的通道" +
                        "（系统闹钟 → 后台服务 → 签到），所以它能自己跑起来，日常定时就能跑起来。\n\n" +
                        "接下来：\n" +
                        "① 按 Home 键回桌面，或者直接息屏 —— 但不要从「最近任务」里划掉本应用；\n" +
                        "② 等 " + TEST_DELAY_MINUTES + " 分钟，看通知栏有没有出现「星签」的签到进度；\n" +
                        "③ 回来打开本页，看「上次执行」的时间对不对得上。\n\n" +
                        "这 2 分钟里程序会自己保持唤醒，不依赖系统的省电策略。\n" +
                        "如果等完还是什么都没发生，那就是系统把本应用整个冻结了 —— " +
                        "请点下面的「全安卓后台兼容设置」，它会按你的手机品牌给出对应的设置路径，" +
                        "把「自启动」和「后台运行」都打开再测一次。\n" +
                        "（这两项的叫法各家不同：小米叫「自启动 / 省电策略：无限制」，" +
                        "华为叫「应用启动管理 → 手动管理」，vivo 叫「自启动 / 后台高耗电」，" +
                        "OPPO 叫「自启动 / 应用耗电管理」—— 指向的是同一件事。）\n\n" +
                        "你的每日时间（" + dailyTime + "）不受影响，这一轮跑完会自动回到原来的节奏。"
                )
                .setPositiveButton("知道了", null)
                .show()
        }

        findViewById<Button>(R.id.skipTodayButton).setOnClickListener {
            if (!Prefs.scheduleEnabled) {
                toast("请先开启自动签到")
                return@setOnClickListener
            }
            LocalScheduler.skipToday(this)
            val serviceIntent = Intent(this, AgentService::class.java).apply {
                action = AgentService.ACTION_START_SCHEDULED
            }
            ContextCompat.startForegroundService(this, serviceIntent)
            updateStatus()
            toast("今天不签到，已安排到下一天")
        }

        // ★ 加二次确认：这个按钮和「保存并开启自动签到」是上下相邻的两个全宽按钮，
        //   误触一下就会把自动签到整个关掉 —— 而关掉之后程序一个闹钟都不登记，
        //   用户只会看到「到点不执行」，根本想不到是自己点错了。
        //   （v0.19.7 的诊断报告就抓到了这个状态：scheduleEnabled=false，但用户以为开着。）
        findViewById<Button>(R.id.disableScheduleButton).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("确定关闭自动签到？")
                .setMessage(
                    "关闭后程序不会再登记任何闹钟，到点不会自动签到。\n\n" +
                        "如果只是今天不想签，请改用上面的「今日不签到」。"
                )
                .setPositiveButton("确定关闭") { _, _ ->
                    setScheduleEnabled(false, "点了「关闭自动签到」并确认")
                    Prefs.pausedUntil = 0L
                    LocalScheduler.cancel(this)
                    // ★ 正在签到时不立刻 stopService：那会把跑了一半的任务掐断，
                    //   界面报「INTERRUPTED（被系统中断）」，看起来像程序出错 ——
                    //   而 v19.8 那份报告里出现的 INTERRUPTED 正是这么来的。
                    //   服务会在这一轮跑完后自己停（requestRun 的 finally 里有判断）。
                    if (!Prefs.running) {
                        stopService(Intent(this, AgentService::class.java))
                    }
                    updateStatus()
                    toast("自动签到已关闭（不会再自动执行）")
                }
                .setNegativeButton("取消", null)
                .show()
        }

        findViewById<Button>(R.id.compatibilityButton).setOnClickListener {
            DeviceCompatibility.showGuide(this)
        }

        findViewById<Button>(R.id.batteryButton).setOnClickListener {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
                startActivity(intent)
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }

        // ★ 「复制诊断报告」：把「到点到底卡在哪一段」变成一段可以直接粘贴的文本。
        //
        //   用户报「还是不行」时，以前只能靠来回追问（有没有这一行？显示什么？），
        //   一轮就要等半天，而且用户看到的和程序实际状态经常对不上。
        //   这里一次性把所有关键状态拼成文本复制到剪贴板，用户直接粘贴过来即可定位。
        findViewById<Button>(R.id.diagnoseButton).setOnClickListener {
            val report = buildDiagnosticReport()
            copyToClipboard(report)
            AlertDialog.Builder(this)
                .setTitle("诊断报告已复制")
                .setMessage(report)
                .setPositiveButton("知道了", null)
                .show()
        }

        findViewById<Button>(R.id.runNowButton).setOnClickListener {
            val ready = AccountStore.ready()
            if (ready.isEmpty()) {
                findViewById<TextView>(R.id.logText).text = "没有可用的微博账号，请先登录"
                toast("请先添加并登录微博账号")
                return@setOnClickListener
            }
            Prefs.lastRunAt = System.currentTimeMillis()
            // ★ 手动点击 = 明确要求「现在就签」，所以带上 EXTRA_RUN_ALL 跳过分批。
            //   自动定时签到才分批错峰（账号多时避免同一 IP 上打出密集请求）。
            Prefs.lastRunMessage =
                "已启动签到任务（共 " + ready.size + " 个账号），正在执行……"
            findViewById<TextView>(R.id.logText).text = Prefs.lastRunMessage
            val intent = Intent(this, AgentService::class.java).apply {
                action = AgentService.ACTION_RUN_CHECKIN
                putExtra(AgentService.EXTRA_RUN_ALL, true)
            }
            ContextCompat.startForegroundService(this, intent)
            toast("已开始立即签到")
        }

        setupNotesToggle()
        setupStatusToggle()
        setupPostPanel()

        renderAccounts()
        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        Prefs.init(this)
        AccountStore.init(this)

        // ★ 没有「闹钟和提醒」权限时，AlarmManager 会退化成非精确闹钟：
        //   在深度休眠下它可以被推迟到**下次亮屏**才触发 —— 表现出来就是
        //   「放后台不执行，一打开 App 就执行」。这个权限只靠首页那行小字很容易被忽略，
        //   所以在开启自动签到的情况下主动问一次（每次启动只问一次）。
        if (Prefs.scheduleEnabled && !exactAlarmPrompted && !LocalScheduler.exactAlarmAllowed(this)) {
            exactAlarmPrompted = true
            AlertDialog.Builder(this)
                .setTitle("还差一个权限")
                .setMessage(
                    "本应用还没有「闹钟和提醒」权限。\n\n" +
                        "没有它，系统会把定时任务推迟到下一次亮屏才执行 —— " +
                        "也就是「放后台不执行、打开 App 才执行」。\n\n" +
                        "点「去允许」后，把「闹钟和提醒」的开关打开即可。"
                )
                .setPositiveButton("去允许") { _, _ -> requestExactAlarmPermissionIfNeeded() }
                .setNegativeButton("稍后", null)
                .show()
        }

        // ★★ 自动签到没开启时，必须**主动**告诉用户，不能等他自己往下翻。
        //
        //   真实案例（v0.19.7 的诊断报告）：用户把时间设成 18:07、以为已经在自动签到了，
        //   实际 scheduleEnabled 还是 false —— 程序一个闹钟都没登记（「下次自动执行：尚未登记」）。
        //   他连续反馈「到点不执行」，排查方向全被带偏，真正的原因只是「没点保存并开启」。
        //   所以这里打开 App 就直接弹一次，并给一个「立即开启」按钮，一键按当前选择开启。
        if (!Prefs.scheduleEnabled && !schedulePrompted) {
            schedulePrompted = true
            AlertDialog.Builder(this)
                .setTitle("自动签到还没开启")
                .setMessage(
                    "你现在选的是 " +
                        String.format(Locale.CHINA, "%02d:%02d", timePicker.hour, timePicker.minute) +
                        "，但自动签到当前是【关闭】状态。\n\n" +
                        "关闭状态下程序不会登记任何闹钟，到点不会执行 —— " +
                        "这跟后台权限、省电设置都无关。\n\n" +
                        "点「立即开启」就会按上面选的日期和时间开始自动签到。"
                )
                .setPositiveButton("立即开启") { _, _ ->
                    enableScheduleFromPickers("点了弹窗里的「立即开启」")
                }
                .setNegativeButton("稍后", null)
                .show()
        }

        if (Prefs.scheduleEnabled) {
            // ★ 先判断「是不是已经错过了」再重排。
            //   ensureScheduled 会把错过的这一轮改成「1 分钟后执行」，如果这里仍用
            //   ACTION_START_SCHEDULED，用户点开 App 后还要干等一分钟才开始 —— 观感就是「没反应」。
            //   已经错过就直接开跑。
            val overdue = Prefs.nextRunAt > 0L && Prefs.nextRunAt <= System.currentTimeMillis()
            LocalScheduler.ensureScheduled(this)
            val serviceIntent = Intent(this, AgentService::class.java).apply {
                action = if (overdue) AgentService.ACTION_RUN_CHECKIN else AgentService.ACTION_START_SCHEDULED
            }
            ContextCompat.startForegroundService(this, serviceIntent)
        }
        // 从登录页返回时账号状态会变，重建一次行视图
        renderAccounts()
        updateStatus()
        // 有账号超过 12 小时没检查过登录态，就顺手查一遍
        val stale = AccountStore.all().any {
            it.cookie.isNotBlank() &&
                System.currentTimeMillis() - it.lastCheckAt > 12L * 60L * 60L * 1000L
        }
        if (stale) checkAllLogins()
        handler.removeCallbacks(refreshRunnable)
        handler.post(refreshRunnable)
    }

    override fun onPause() {
        handler.removeCallbacks(refreshRunnable)
        super.onPause()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /**
     * 改自动签到开关，并记下「什么时候、因为什么」。
     *
     * ★ 所有改动开关的地方都必须走这里。
     *   起因：诊断报告里反复出现「设定时间已保存、但开关是关的」——
     *   从报告上完全看不出是谁关的，只能猜（是用户点的？还是程序某条路径？）。
     *   记下来之后，报告里会直接写出「19:13:58 由「点了「关闭自动签到」并确认」关闭」，
     *   一眼就能定性，不用再猜。
     */
    private fun setScheduleEnabled(enabled: Boolean, by: String) {
        Prefs.scheduleEnabled = enabled
        Prefs.scheduleEnabledChangedAt = System.currentTimeMillis()
        Prefs.scheduleEnabledChangedBy = by
    }

    /**
     * 按当前日期 / 时间选择器保存设置并开启自动签到。
     *
     * ★ 抽成独立函数的原因：保存按钮和「自动签到还没开启」弹窗里的「立即开启」
     *   必须是**同一段逻辑**。如果只给保存按钮写一份、弹窗里再抄一份，
     *   两边迟早不一致 —— 而这类不一致正是「点了按钮却什么都没发生」的经典来源。
     */
    private fun enableScheduleFromPickers(by: String = "点了「保存并开启自动签到」") {
        Prefs.startDate = String.format(
            Locale.CHINA,
            "%04d-%02d-%02d",
            datePicker.year,
            datePicker.month + 1,
            datePicker.dayOfMonth
        )
        Prefs.skipDate = ""
        Prefs.baseHour = timePicker.hour
        Prefs.baseMinute = timePicker.minute
        setScheduleEnabled(true, by)
        // 用户手动重新开启时，清掉风控暂停状态，否则会被顺延到暂停结束时间
        Prefs.pausedUntil = 0L
        requestExactAlarmPermissionIfNeeded()
        LocalScheduler.scheduleNext(this)
        val serviceIntent = Intent(this, AgentService::class.java).apply {
            action = AgentService.ACTION_START_SCHEDULED
        }
        ContextCompat.startForegroundService(this, serviceIntent)
        updateStatus()

        // ★ 关键提示：如果用户选的时间点**今天已经过了**，程序会把它顺延到明天。
        //   旧实现只 toast 一句「自动签到已开启」，用户会以为今天到点就会跑，
        //   结果等到超时也没动静 —— 这就是「设置时间自动签到过了 30 分钟都没签」的常见来源。
        val picked = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, timePicker.hour)
            set(Calendar.MINUTE, timePicker.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val todayPassed = !picked.after(Calendar.getInstance())
        val nextText = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(Prefs.nextRunAt))
        toast(
            if (todayPassed) {
                "已开启。今天 " + String.format(Locale.CHINA, "%02d:%02d", timePicker.hour, timePicker.minute) +
                    " 已经过了，安排在 " + nextText
            } else {
                "自动签到已开启，下次执行：" + nextText
            }
        )
    }

    /**
     * 首页顶部那行版本号。
     *
     * ★ 为什么值得单独做一行：用户反馈「改了还是不行」时，第一件必须排除的事是
     *   「手机上装的还是旧包」。以前没有任何办法在界面上确认，只能靠来回问，
     *   一问一答就是半天。现在版本号直接写在标题下面。
     */
    /**
     * 「显示 / 隐藏使用说明」总开关。
     *
     * ★ 只控制 4 处**解释性**灰色小字：
     *   introNote（本机运行）、jitterExplainText（随机延迟）、
     *   compatNoteText（后台权限）、reportNoteText（诊断报告用途）。
     *
     * ★ 绝不碰任何**状态**：warningBanner 警示横幅、scheduleStatus 状态块、
     *   所有按钮始终可见。它们的显示规则是「不处理就一定不会执行」，
     *   如果跟着说明一起被藏起来，用户就会重复 v19.8 之前的排查弯路。
     */
    private fun setupNotesToggle() {
        val toggle = findViewById<TextView>(R.id.notesToggle)
        val noteIds = listOf(
            R.id.introNote,
            R.id.jitterExplainText,
            R.id.compatNoteText,
            R.id.reportNoteText,
        )

        fun renderNotes(expanded: Boolean) {
            for (id in noteIds) {
                findViewById<TextView>(id).visibility = if (expanded) View.VISIBLE else View.GONE
            }
            toggle.text = if (expanded) "隐藏使用说明 ▴" else "显示使用说明 ▾"
        }

        renderNotes(notesExpanded)
        toggle.setOnClickListener {
            notesExpanded = !notesExpanded
            renderNotes(notesExpanded)
        }
    }

    /**
     * 状态块的「显示 / 隐藏详细状态」开关。
     *
     * ★ 只切换 [R.id.statusDetail] 的可见性，内容仍由 updateStatus() 每 2 秒刷新，
     *   所以展开状态下时间也会实时更新，不会变成一张死图。
     */
    private fun setupStatusToggle() {
        findViewById<TextView>(R.id.statusToggle).setOnClickListener {
            statusExpanded = !statusExpanded
            updateStatus()
        }
    }

    /**
     * 「4. 自动发帖」面板。
     *
     * ★ 发帖是**高风险动作**，三个约束必须在界面上立住：
     *   ① 默认关闭，且打开开关时当场校验配置（空名单 / 空正文不许开）——
     *      「开了却什么都不发」是最难排查的一类状态；
     *   ② 条数和间隔填非法值时**明确报错**，不能悄悄用默认值，
     *      否则用户以为设了 4 小时，实际还是 20 分钟；
     *   ③ 提供「立即发一条」——发帖接口本机无法验证，必须让用户当场能试一次。
     */
    private fun setupPostPanel() {
        val topicsEdit = findViewById<EditText>(R.id.postTopicsEdit)
        val contentEdit = findViewById<EditText>(R.id.postContentEdit)
        val countEdit = findViewById<EditText>(R.id.postCountEdit)
        val intervalEdit = findViewById<EditText>(R.id.postIntervalEdit)
        val enabledSwitch = findViewById<SwitchCompat>(R.id.postEnabledSwitch)

        topicsEdit.setText(Prefs.postTopicsText)
        contentEdit.setText(Prefs.postContentText)
        countEdit.setText(Prefs.postPerAccount.toString())
        intervalEdit.setText(Prefs.postIntervalMinutes.toString())
        enabledSwitch.isChecked = Prefs.postEnabled

        enabledSwitch.setOnCheckedChangeListener { _, checked ->
            if (!checked) {
                Prefs.postEnabled = false
                updateStatus()
                toast("自动发帖已关闭（签到不受影响）")
                return@setOnCheckedChangeListener
            }
            val topics = topicListOf(topicsEdit.text.toString())
            val body = contentEdit.text.toString().trim()
            if (topics.isEmpty() || body.isBlank()) {
                // ★ 不许「开了但发不出东西」：那会变成用户眼里最莫名其妙的故障
                enabledSwitch.isChecked = false
                AlertDialog.Builder(this)
                    .setTitle("还差配置")
                    .setMessage(
                        "开启自动发帖之前，请先填好这两项：\n\n" +
                            "① 超话链接（至少 1 个，一行一个，直接粘贴超话页地址）\n" +
                            "② 帖子正文\n\n" +
                            "现在是空的 —— 开起来也一条都发不出去。"
                    )
                    .setPositiveButton("知道了", null)
                    .show()
                return@setOnCheckedChangeListener
            }
            Prefs.postEnabled = true
            updateStatus()
            toast(
                "自动发帖已开启：每个账号 " + Prefs.postPerAccount + " 条，" +
                    "每 " + Prefs.postIntervalMinutes + " 分钟发一条"
            )
        }

        findViewById<Button>(R.id.savePostButton).setOnClickListener {
            val count = countEdit.text.toString().trim().toIntOrNull()
            val interval = intervalEdit.text.toString().trim().toIntOrNull()
            if (count == null || count < 1) {
                toast("「每个账号几条」请填 1~20 的数字")
                return@setOnClickListener
            }
            if (interval == null || interval < 5) {
                toast("「间隔（分钟）」请填不小于 5 的数字")
                return@setOnClickListener
            }
            Prefs.postTopicsText = topicsEdit.text.toString()
            Prefs.postContentText = contentEdit.text.toString()
            Prefs.postPerAccount = count.coerceIn(1, 20)
            Prefs.postIntervalMinutes = interval.coerceIn(5, 240)
            updateStatus()
            toast(
                "已保存：每个账号 " + Prefs.postPerAccount + " 条，间隔 " +
                    Prefs.postIntervalMinutes + " 分钟，共 " +
                    topicListOf(Prefs.postTopicsText).size + " 个超话链接"
            )
        }

        findViewById<Button>(R.id.testPostButton).setOnClickListener {
            val ready = AccountStore.ready()
            if (ready.isEmpty()) {
                toast("请先添加并登录微博账号")
                return@setOnClickListener
            }
            // 先落盘再发：用户往往改了内容就直接点测试，不点保存
            Prefs.postTopicsText = topicsEdit.text.toString()
            Prefs.postContentText = contentEdit.text.toString()
            val topics = topicListOf(Prefs.postTopicsText)
            if (topics.isEmpty()) {
                toast("请先填超话链接（一行一个，粘贴超话页地址）")
                return@setOnClickListener
            }
            if (Prefs.postContentText.isBlank()) {
                toast("请先填帖子正文")
                return@setOnClickListener
            }
            Prefs.lastRunAt = System.currentTimeMillis()
            Prefs.lastRunMessage = "正在发一条超话帖子……"
            val intent = Intent(this, AgentService::class.java).apply {
                action = AgentService.ACTION_RUN_POST
                putExtra(AgentService.EXTRA_RUN_ALL, true)
            }
            ContextCompat.startForegroundService(this, intent)
            updateStatus()

            val first = ready.first()
            val index = if (first.postDate == LocalScheduler.todayString()) first.postIndex else 0
            val topicName = topics[index % topics.size].display
            AlertDialog.Builder(this)
                .setTitle("已开始发一条")
                .setMessage(
                    "正在用「" + first.displayName + "」往「" + topicName + "」发一条。\n\n" +
                        "大约十几秒后回来看本页最下面的结果，或者点「复制诊断报告」" +
                        "看里面「--- 发帖 ---」那一段。\n\n" +
                        "★ 如果结果里出现「微博原话」或「HTTP 403」之类的字样，" +
                        "请把那句话原样发给我 —— 发帖接口我这边没法真机验证，" +
                        "那句原话是定位问题的唯一依据。"
                )
                .setPositiveButton("知道了", null)
                .show()
        }
    }

    /** 超话名单解析：一行一个链接，解析出超话引用（与 AgentService 的解析保持一致） */
    private fun topicListOf(raw: String): List<WeiboClient.SuperTopicRef> =
        raw.lineSequence().map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { WeiboClient.parseSuperTopicRef(it) }
            .toList()

    /** 发帖进度与结果（挂在「4. 自动发帖」卡片下面） */
    private fun updatePostStatus() {
        val view = findViewById<TextView>(R.id.postStatus)
        if (!Prefs.postEnabled) {
            view.text = "自动发帖未开启 —— 填好上面的超话链接和正文后，打开开关才会发。"
            return
        }
        val today = LocalScheduler.todayString()
        val perAccount = Prefs.postPerAccount
        val done = AccountStore.postDoneToday(today)
        val target = AccountStore.postTargetToday(perAccount)
        val pending = AccountStore.postPending(today, perAccount).size

        val text = StringBuilder()
        text.append("今日发帖：").append(done).append(" / ").append(target).append(" 条")
        when {
            pending > 0 -> text.append("（还有 ").append(pending).append(" 个账号没发完，每 ")
                .append(Prefs.postIntervalMinutes).append(" 分钟发一条）")
            target > 0 -> text.append("（今天的都发完了）")
        }
        if (Prefs.postLastStatus == "CONFIG_MISSING") {
            text.append("\n⚠ ").append(Prefs.postLastMessage)
        } else if (Prefs.postLastMessage.isNotBlank()) {
            text.append("\n上次发帖：").append(Prefs.postLastMessage)
        }
        view.text = text.toString()
    }

    private fun versionLabel(): String = "版本：" + BuildConfig.VERSION_NAME +
        "（构建号 " + BuildConfig.VERSION_CODE + "）"

    private fun updateStatus() {
        findViewById<TextView>(R.id.versionText).text = versionLabel()

        // 首屏警示横幅：只在「需要用户动手、且不处理就一定不会执行」的状态下出现。
        // 放在标题正下方，不用滚动就能看到 —— 这是这一版最重要的可见性改动。
        val banner = findViewById<TextView>(R.id.warningBanner)
        val bannerText = when {
            // ★ 存储异常排在最前：它是唯一一种会让用户「看到账号莫名消失」的情况，
            //   而且此时签到一定跑不了（没账号可签），优先级高于其它任何提示。
            Prefs.storageError.isNotBlank() ->
                "⚠ 本机存储异常，账号信息可能已重置 —— 请检查下面的账号列表，" +
                    "需要时点「添加微博账号」重新登录。"
            !Prefs.scheduleEnabled ->
                "⚠ 自动签到未开启 —— 你设的时间不会生效，程序不会登记任何闹钟。" +
                    "请点下面的「保存并开启自动签到」。"
            !LocalScheduler.exactAlarmAllowed(this) ->
                "⚠ 缺少「闹钟和提醒」权限，定时会被系统推迟到下次亮屏才执行。"
            !getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName) ->
                "⚠ 电池优化未关闭，后台可能被系统冻结。请点下面「允许后台和锁屏运行」。"
            else -> ""
        }
        banner.visibility = if (bannerText.isBlank()) View.GONE else View.VISIBLE
        banner.text = bannerText

        val accounts = AccountStore.all()
        val readyCount = accounts.count { it.enabled && it.loggedIn }
        findViewById<TextView>(R.id.weiboStatus).text = when {
            accounts.isEmpty() -> "还没有添加微博账号"
            readyCount == 0 -> "共 " + accounts.size + " 个账号，暂时没有可签到的（未登录或已停用）"
            else -> "共 " + accounts.size + " 个账号，" + readyCount + " 个可签到"
        }

        val checkLines = mutableListOf<String>()
        for (account in accounts) {
            if (account.cookie.isBlank()) continue
            val time = if (account.lastCheckAt > 0L) {
                SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(account.lastCheckAt)) + " "
            } else {
                ""
            }
            val expiry = if (account.expiryAt > 0L) {
                "（会话到期 " + SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(account.expiryAt)) + "）"
            } else {
                ""
            }
            checkLines.add(
                account.displayName + "：" + time + account.lastCheckMessage.ifBlank { "尚未检查" } + expiry
            )
        }
        findViewById<TextView>(R.id.loginCheckStatus).text =
            if (checkLines.isEmpty()) "尚未检查登录状态" else checkLines.joinToString("\n")

        refreshAccountRows()

        val exactAllowed = LocalScheduler.exactAlarmAllowed(this)
        val power = getSystemService(PowerManager::class.java)
        val batteryAllowed = power.isIgnoringBatteryOptimizations(packageName)

        // 诊断时间统一用「MM-dd HH:mm:ss」，能区分同一分钟内的先后顺序
        val shortTime = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA)
        // ★ v19.13：状态块拆成「摘要 + 详细」两层。
        //
        //   为什么：这一块会长到十几行（执行链 + 每轮的账号明细），
        //   而每天真正要看的只有三件事 —— 下次什么时候跑、今天签了几个、上次成没成。
        //   其余都是排查用的细节，只在出问题时才需要。
        //
        //   ★★ 硬规则：折叠的只能是「一切正常时的细节」。
        //      凡是「不处理就一定不会执行」的行，一律用 alerts 收集后**提升**到摘要区，
        //      而不是删掉或留在详细里。
        //      （v19.8 的教训：提示藏在页面下方 → 用户连续三轮反馈「到点不执行」，
        //        真正原因只是没点「保存并开启」。）
        val head = StringBuilder()
        val detail = StringBuilder()
        val alerts = mutableListOf<String>()
        if (Prefs.scheduleEnabled) {
            head.append(LocalScheduler.formatNextRun(this))

            // ★ 分批错峰的进度。
            //   账号多起来之后（10 个以上），一轮只跑 3 个，剩下的 20 分钟后继续。
            //   如果界面只显示「上次结果：成功」，用户会以为今天只签了 3 个号、其余被漏掉了 ——
            //   所以这里必须把「今天已经轮到几个 / 一共几个」直接写出来。
            val readyCount = AccountStore.ready().size
            if (readyCount > 0) {
                val doneToday = AccountStore.doneToday(LocalScheduler.todayString())
                head.append("\n今日进度：").append(doneToday).append(" / ").append(readyCount).append(" 个账号")
                head.append(
                    if (doneToday >= readyCount) "（今天已全部轮到）"
                    else "（分批错峰执行中，剩下的会自动继续）"
                )
            }

            // ★ 微博要求验证身份 —— 这是**需要用户动手**的状态，必须醒目地说清「去点哪里」。
            //   光显示「需要处理」用户不知道该干什么，只能干等。
            if (Prefs.needsVerify) {
                alerts.add(
                    "⚠ 微博提示「你最近的行为存在异常」，需要验证身份。" +
                        "请点上方「去微博完成验证」，在页面里验证通过后返回，本应用会自动继续签到。"
                )
            }

            // ★ 到点了却没执行 —— 必须让用户**看见**。
            //   旧实现遇到这种情况只是悄悄把任务顺延到明天，用户唯一的感受就是
            //   「设了时间却没签」，而且永远不知道是闹钟没响还是被系统拦了。
            //   现在只要超过设定时间 10 分钟还没动静，就直接写在这里。
            val overdueMs = System.currentTimeMillis() - Prefs.nextRunAt
            if (Prefs.nextRunAt > 0L && overdueMs > 10 * 60 * 1000L) {
                alerts.add(
                    "⚠ 已超过设定时间 " + (overdueMs / 60000L) +
                        " 分钟仍未执行，请点下方「全安卓后台兼容设置」把本应用加入白名单"
                )
            }

            // ★ v19.2 的「服务活着 = 不依赖闹钟」是错误结论：Doze 会冻结普通 delay。
            //   心跳只证明协程最近拿到过 CPU；真正到点仍以系统闹钟为主，90 分钟内再加短时唤醒保护。
            val serviceAlive = System.currentTimeMillis() - Prefs.serviceAliveAt < 20 * 60 * 1000L
            // ★ 「服务已停止/被冻结」就是「放后台不执行、打开 App 才执行」的直接证据：
            //   服务被系统冻结时，连维护循环都不会跑，只能等闹钟 —— 而国产 ROM 的省电策略
            //   往往连闹钟一起冻结，于是要等用户打开 App 才一起放行。
            //   光显示「已停止」用户不知道该干什么，所以它必须留在摘要区并带上下一步。
            if (!serviceAlive) {
                alerts.add(
                    "⚠ 后台服务已停止或被系统冻结 —— 这就是「放后台不执行、打开 App 才执行」的原因。" +
                        "请点下方「全安卓后台兼容设置」，把本应用加入自启动 / 后台运行白名单。"
                )
            }
            detail.append("后台服务：")
            if (serviceAlive) {
                detail.append("待命中（到点以系统闹钟为主）")
            } else if (Prefs.serviceAliveAt > 0L) {
                detail.append("已停止或被系统冻结（最近心跳 ")
                    .append(SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(Prefs.serviceAliveAt)))
                    .append("）")
            } else {
                detail.append("未启动")
            }
            if (Prefs.standbyProtectedUntil > System.currentTimeMillis()) {
                detail.append("\n短时唤醒保护：到 ")
                    .append(SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(Prefs.standbyProtectedUntil)))
            }

            // 完整自证链：闹钟触发 → 系统接受启动请求 → Service.onStartCommand 真正执行。
            // 三个时间分开记录，下一次不用再猜是卡在哪一段。
            // ★ 这三行属于「排查用细节」：一切正常时用户不需要看到，折进详细区。
            detail.append("\n闹钟最近触发：")
                .append(if (Prefs.lastAlarmAt > 0L) shortTime.format(Date(Prefs.lastAlarmAt)) else "尚未触发过")
            detail.append("\n启动请求已受理：")
                .append(if (Prefs.lastAlarmAcceptedAt > 0L) shortTime.format(Date(Prefs.lastAlarmAcceptedAt)) else "尚无记录")
            detail.append("\n服务最近接令：")
                .append(if (Prefs.lastServiceStartAt > 0L) shortTime.format(Date(Prefs.lastServiceStartAt)) else "尚无记录")
            if (Prefs.pausedUntil > System.currentTimeMillis()) {
                // ★ 暂停的原因不止「风控」一种：验证码、登录失效也会限时暂停。
                //   旧实现一律写「风控暂停中」，用户看到这句就会以为是微博限流，
                //   而那次其实是因为有账号没登录 —— 判断方向完全被带偏。
                val pauseReason = when (Prefs.lastRunStatus) {
                    "USER_REQUIRED" -> "需要人工处理（验证身份或重新登录），暂停中，"
                    "PAUSED" -> "风控暂停中，"
                    else -> "暂停中，"
                }
                // 暂停解释了「为什么现在没在签」，属于用户需要知道的事，留在摘要区
                alerts.add(
                    "状态：" + pauseReason +
                        SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(Prefs.pausedUntil)) +
                        " 后自动重试"
                )
            }
            head.append("\n上次结果：").append(runStatusLabel(Prefs.lastRunStatus))
            // ★ 这一段是「上次信息」的完整明细：每个账号一行汇总 + 每个超话的已签/失败。
            //   它是全块最长的部分，也是折叠收益最大的一处。
            if (Prefs.lastRunMessage.isNotBlank()) {
                detail.append("\n\n上次信息：").append(Prefs.lastRunMessage)
            }
        } else {
            // ★ 旧实现这里只写一句「自动签到未开启」，太像普通说明文字，用户会直接跳过，
            //   然后继续反馈「到点不执行」—— 其实功能根本没开，程序压根不会登记任何闹钟。
            //   必须明确写出「当前状态 + 要做什么」，否则这个最廉价的排查点永远被漏掉。
            //   ★ 这一支**不折叠**：它是「不处理就一定不会执行」的典型。
            head.append("⚠ 自动签到当前是【关闭】状态。")
                .append("\n关闭状态下程序不会登记任何闹钟，到点一定不会执行。")
                .append("\n请在上面选好日期和时间，然后点「保存并开启自动签到」。")
        }
        detail.append("\n精确闹钟权限：")
            .append(if (exactAllowed) "已允许" else "未允许（定时会被系统延迟甚至拦截）")
        detail.append("\n闹钟实际档位：").append(alarmModeLabel(Prefs.lastAlarmMode))
        if (Prefs.lastWatchdogAt > 0L) {
            detail.append("\n看门狗重试：")
                .append(shortTime.format(Date(Prefs.lastWatchdogAt)))
        }
        if (Prefs.lastAlarmError.isNotBlank() && Prefs.lastAlarmMode != "alarm_clock") {
            detail.append("（").append(Prefs.lastAlarmError).append("）")
        }
        detail.append("\n电池优化：")
            .append(if (batteryAllowed) "已关闭" else "未关闭（后台可能被冻结）")
        if (!exactAllowed || !batteryAllowed) {
            alerts.add("⚠ 请点下方「全安卓后台兼容设置」完成配置，否则定时任务可能不执行")
        }

        // 摘要 = 三行状态 + 所有需要用户动手的事（告警统一追加在末尾，最醒目）
        for (alert in alerts) head.append("\n").append(alert)
        findViewById<TextView>(R.id.scheduleStatus).text = head.toString()

        val detailView = findViewById<TextView>(R.id.statusDetail)
        detailView.text = detail.toString()
        detailView.visibility = if (statusExpanded) View.VISIBLE else View.GONE

        val statusToggle = findViewById<TextView>(R.id.statusToggle)
        statusToggle.visibility = if (detail.isEmpty()) View.GONE else View.VISIBLE
        statusToggle.text = if (statusExpanded) "隐藏详细状态 ▴" else "显示详细状态 ▾"

        // 发帖进度单独一块：它跟签到是两条独立链路，混在一起数字永远对不上
        updatePostStatus()

        // 区分「正在执行」和「上次执行」：
        // 旧实现整轮都显示上一轮的结果，签到跑起来后界面毫无变化，像是没反应。
        // 这里再加一层时间兜底 —— 万一进程被杀，「正在执行」标记不会永远挂着。
        // ★ 兜底时长要跟单轮最大耗时匹配：每个账号最多 10 分钟（按待签数量动态给），
        //   5 个账号就可能跑很久，所以放宽到 60 分钟，否则跑到一半界面会误判成「已结束」。
        val runningNow = Prefs.running &&
            System.currentTimeMillis() - Prefs.lastRunAt < 60 * 60 * 1000L
        val lastText = when {
            runningNow -> "正在执行…\n" + Prefs.lastRunMessage.ifBlank { "正在执行微博签到" }
            Prefs.lastRunAt > 0L -> {
                val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(Prefs.lastRunAt))
                "上次执行：" + time + "\n" + Prefs.lastRunMessage.ifBlank { "无结果" }
            }
            else -> "等待操作"
        }
        findViewById<TextView>(R.id.logText).text = lastText
    }

    /** 闹钟档位的中文名。首页和诊断报告共用，避免两处文案不一致。 */
    private fun alarmModeLabel(mode: String): String = when (mode) {
        "alarm_clock" -> "系统闹钟（最可靠）"
        "exact" -> "精确闹钟"
        "inexact" -> "非精确闹钟（可能延迟）"
        "failed" -> "登记失败"
        else -> "尚未登记"
    }

    /**
     * 生成一段可以直接粘贴出去的诊断文本。
     *
     * ★ 为什么需要它：用户反馈「还是不行」时，真正决定性的信息是
     *   「闹钟有没有响过」「服务有没有被冻结」「精确闹钟权限有没有给」——
     *   而这些以前只能靠一问一答去凑，一轮就是半天，而且用户看到的内容
     *   和程序实际状态经常对不上（比如没注意到某一行）。
     *   现在点一下按钮就全部整理好并复制到剪贴板，一次问清。
     */
    private fun buildDiagnosticReport(): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
        fun stamp(value: Long): String = if (value > 0L) formatter.format(Date(value)) else "无记录"

        val accounts = AccountStore.all()
        val ready = accounts.count { it.enabled && it.loggedIn }
        val today = LocalScheduler.todayString()
        val doneToday = try {
            AccountStore.doneToday(today)
        } catch (_: Exception) {
            -1
        }
        val power = getSystemService(PowerManager::class.java)
        val batteryAllowed = power.isIgnoringBatteryOptimizations(packageName)

        val report = StringBuilder()
        report.append("【星签诊断报告】\n")
        report.append("版本：").append(BuildConfig.VERSION_NAME)
            .append("（构建号 ").append(BuildConfig.VERSION_CODE).append("）\n")
        report.append("生成时间：").append(formatter.format(Date())).append("\n")
        report.append("手机：").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append(" / Android ").append(Build.VERSION.RELEASE)
            .append("（API ").append(Build.VERSION.SDK_INT).append("）\n")
        // ★ 存储状态：加密存储失败会静默退到普通存储，表现是「账号莫名其妙没了」。
        //   不写这一行的话，用户只会报「签到不执行」，而真因其实在存储层。
        report.append("存储状态：").append(
            if (Prefs.storageError.isBlank()) {
                "正常（加密存储）"
            } else {
                "【已降级】" + Prefs.storageError + " —— 账号可能需要重新登录"
            }
        ).append("\n")
        report.append("--- 定时开关 ---\n")
        report.append("自动签到：").append(if (Prefs.scheduleEnabled) "已开启" else "【未开启】").append("\n")
        // ★ 开关的「变化时间 + 原因」：用来定性「到底是谁把它关掉的」。
        //   报告里出现「设定时间已保存、但开关是关的」时，这一行能立刻分清是用户点的还是程序干的。
        if (Prefs.scheduleEnabledChangedAt > 0L) {
            report.append("开关最近变化：").append(stamp(Prefs.scheduleEnabledChangedAt))
                .append("（").append(Prefs.scheduleEnabledChangedBy.ifBlank { "原因未记录" }).append("）")
                .append("\n")
            if (!Prefs.scheduleEnabled) {
                report.append("已关闭时长：")
                    .append((System.currentTimeMillis() - Prefs.scheduleEnabledChangedAt) / 60000L)
                    .append(" 分钟\n")
            }
        } else {
            report.append("开关最近变化：无记录\n")
        }
        report.append("设定时间：")
            .append(String.format(Locale.CHINA, "%02d:%02d", Prefs.baseHour, Prefs.baseMinute)).append("\n")
        report.append("下次自动执行：")
            .append(if (Prefs.nextRunAt > 0L) stamp(Prefs.nextRunAt) else "尚未登记").append("\n")
        // ★ 「设定 18:51，却显示明天 19:05」—— 差的 14 分钟是防固定时刻请求的随机抖动。
        //   不写出来，用户只会以为自己设错了，然后又来反馈一轮。
        val jitterNote = LocalScheduler.jitterNote(this)
        if (jitterNote.isNotBlank()) report.append(jitterNote).append("\n")
        report.append("精确闹钟权限：")
            .append(if (LocalScheduler.exactAlarmAllowed(this)) "已允许" else "【未允许】").append("\n")
        report.append("闹钟实际档位：").append(alarmModeLabel(Prefs.lastAlarmMode)).append("\n")
        report.append("闹钟登记错误：").append(Prefs.lastAlarmError.ifBlank { "无" }).append("\n")
        report.append("--- 后台执行链 ---\n")
        report.append("闹钟最近触发：").append(stamp(Prefs.lastAlarmAt)).append("\n")
        report.append("启动请求已受理：").append(stamp(Prefs.lastAlarmAcceptedAt)).append("\n")
        report.append("服务最近接令：").append(stamp(Prefs.lastServiceStartAt)).append("\n")
        report.append("看门狗下次重试：").append(stamp(Prefs.lastWatchdogAt)).append("\n")
        report.append("服务最近心跳：").append(stamp(Prefs.serviceAliveAt)).append("\n")
        // ★ 「唤醒保护最近申请」和「短时唤醒保护到」是两个不同的问题：
        //   前者 = 到底有没有保护过（只增不清）；后者 = 此刻是否还在保护中（会被清零）。
        //   排查「到点不执行」时，如果前者也没有值，就说明等待窗口从来没被保护过 ——
        //   那才是 ROM 能冻结进程、任务到点不执行的直接原因。
        report.append("唤醒保护最近申请：").append(stamp(Prefs.standbyAcquiredAt)).append("\n")
        report.append("短时唤醒保护到：").append(stamp(Prefs.standbyProtectedUntil)).append("\n")
        if (Prefs.standbyError.isNotBlank()) {
            report.append("唤醒锁错误：").append(Prefs.standbyError).append("\n")
        }
        report.append("电池优化：").append(if (batteryAllowed) "已关闭" else "【未关闭】").append("\n")
        report.append("--- 账号与结果 ---\n")
        report.append("账号：共 ").append(accounts.size).append(" 个，可签到 ").append(ready).append(" 个\n")
        // ★★ 账号序号的原文输出。
        //
        //   用户报「删掉账号 6 之后，账号 7 变成账号 6、账号 8 变成账号 7」时，
        //   光看界面分不清是「存盘的号错了」还是「显示时又按下标算了一遍」——
        //   这两者的修法完全不同。把 label 和 order 一起摊开，一眼就能定性：
        //     label 与 order 一致、且号连续 → 是显示层的问题
        //     order 本身就是 6/7          → 是存盘数据的问题
        //   ★ 只列前 12 个，避免账号很多时把报告撑爆。
        if (accounts.isNotEmpty()) {
            report.append("账号序号：")
                .append(accounts.take(12).joinToString("，") {
                    (it.label.ifBlank { "(无名)" }) + "/order=" + it.order
                })
                .append("\n")
        }
        if (doneToday >= 0) {
            report.append("今日进度：").append(doneToday).append(" / ").append(ready).append("\n")
        }
        report.append("暂停截止：").append(stamp(Prefs.pausedUntil)).append("\n")
        report.append("需要人工验证：").append(if (Prefs.needsVerify) "是" else "否").append("\n")
        report.append("上次执行：").append(stamp(Prefs.lastRunAt)).append("\n")
        report.append("上次结果：").append(Prefs.lastRunStatus.ifBlank { "无" })
            .append("（").append(runStatusLabel(Prefs.lastRunStatus)).append("）\n")
        report.append("上次信息：").append(Prefs.lastRunMessage.ifBlank { "无" }).append("\n")
        // ★ 发帖单独一段：发帖接口是本机无法验证的部分，
        //   这段里的「微博原话」是出问题时唯一的定位依据。
        report.append("--- 发帖 ---\n")
        report.append("自动发帖：").append(if (Prefs.postEnabled) "已开启" else "【未开启】").append("\n")
        if (Prefs.postEnabled) {
            report.append("每个账号：").append(Prefs.postPerAccount)
                .append(" 条，间隔 ").append(Prefs.postIntervalMinutes).append(" 分钟\n")
            report.append("超话链接：").append(topicListOf(Prefs.postTopicsText).size).append(" 个\n")
            report.append("正文段数：").append(Prefs.postContentText.trim().let { raw ->
                if (raw.isBlank()) 0 else raw.split(Regex("(?m)^\\s*-{3,}\\s*$")).count { it.isNotBlank() }
            }).append(" 段\n")
            report.append("今日发帖：").append(AccountStore.postDoneToday(today))
                .append(" / ").append(AccountStore.postTargetToday(Prefs.postPerAccount)).append("\n")
        }
        report.append("发帖上次执行：").append(stamp(Prefs.postLastRunAt)).append("\n")
        report.append("发帖上次结果：").append(Prefs.postLastStatus.ifBlank { "无" }).append("\n")
        report.append("发帖上次明细：").append(Prefs.postLastMessage.ifBlank { "无" }).append("\n")
        // ★ 签到判定的原始依据。用户报「显示成功但微博里没签上」时，
        //   这一段是唯一能直接看出「微博到底回了什么」的东西 ——
        //   不用再靠读代码猜它把提示塞在哪个字段里（见 Prefs.checkinRawTrace）。
        report.append("--- 签到判定依据（最近 5 条）---\n")
        report.append(Prefs.checkinRawTrace.ifBlank { "无（本次启动后还没跑过签到）" }).append("\n")
        return report.toString()
    }

    private fun copyToClipboard(text: String) {
        try {
            val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            manager.setPrimaryClip(ClipData.newPlainText("星签诊断报告", text))
        } catch (_: Exception) {
        }
    }

    /**
     * 把内部状态码翻成中文。
     *
     * ★ 之前这里是直接把 Prefs.lastRunStatus 原样打到界面上，
     *   于是用户会看到「USER_REQUIRED」「PARTIAL」这种词 —— 对自用工具来说毫无意义。
     *   特别是 PARTIAL：它表示「有超话没签完」，绝不能让用户误以为全签上了。
     */
    private fun runStatusLabel(status: String): String {
        return when (status) {
            "" -> "暂无"
            "SUCCESS" -> "成功"
            "PARTIAL" -> "部分完成（有超话没签完，会自动补签）"
            "PAUSED" -> "被风控暂停，稍后自动重试"
            "USER_REQUIRED" -> "需要处理（账号未登录，或需要点「去微博完成验证」）"
            // ★ 这个状态码是 v0.19.10 新加的，专门用来区分「有空槽位没登录」和「真的出事了」。
            //
            //   起因是 v0.19.9 的测试报告：账号共 10 个、可签到 5 个，
            //   已登录的 3 个账号全部跑完、今日进度 5/5，
            //   但「上次结果」写的是 USER_REQUIRED（需要处理）——
            //   因为另外 5 个槽位一直没登录，把它们也算进了「需要人工处理」。
            //   结果就是：**功能完全正常，报告却在报警**，用户看到只会以为坏了。
            //
            //   现在未登录槽位单独报，且明确写出「不影响其它账号」。
            "NOT_LOGGED_IN" -> "已登录的账号都签到完成（另有账号未登录，已跳过，不影响其它账号）"
            // ★ 这是「闹钟响了但系统不让后台启动服务」—— 必须给用户一个可执行的下一步，
            //   旧实现直接把 "BLOCKED" 这个内部状态码打到界面上，用户完全看不懂。
            "BLOCKED" -> "被系统拦截（后台启动被拒绝，请允许自启动并关闭电池优化）"
            "ALARM_FAILED" -> "系统闹钟登记失败（请允许「闹钟和提醒」）"
            "FGS_FAILED" -> "前台服务启动失败"
            "MAINTENANCE_FAILED" -> "后台守护异常，正在自动重试"
            // ★ 这不是程序出错，而是「服务跑到一半被系统关掉了」。
            //   旧实现把它当普通异常报成「执行出错：Job was cancelled」，
            //   用户看到的是英文报错，还会以为是程序 bug —— 实际要去做后台白名单设置。
            "INTERRUPTED" -> "被系统中断（应用在后台被系统关闭，请加自启动 / 后台运行白名单）"
            "FAILED" -> "执行出错"
            "RETRY" -> "网络异常，稍后重试"
            else -> status
        }
    }

    /**
     * 逐个检查所有账号的登录态。
     *
     * ★ 与旧版的区别：某个账号掉线**不再关闭整个自动签到**。
     *   多账号场景下，一个号掉线不该拖累其它号继续签到。
     */
    private fun checkAllLogins() {
        val accounts = AccountStore.all()
        if (accounts.isEmpty()) {
            findViewById<TextView>(R.id.logText).text = "还没有添加微博账号"
            return
        }
        scope.launch {
            findViewById<TextView>(R.id.logText).text =
                "正在检查 " + accounts.size + " 个账号的登录状态……"
            val lines = mutableListOf<String>()
            for (account in accounts) {
                val result = withContext(Dispatchers.IO) {
                    try {
                        val config = WeiboClient.fetchConfig(account)
                        when {
                            !config.has("login") -> LoginCheck.UNKNOWN
                            config.optBoolean("login") -> LoginCheck.OK
                            else -> LoginCheck.EXPIRED
                        }
                    } catch (_: Exception) {
                        // 网络异常 / 超时 / 无网，都归为「未知」，不能据此判定掉线
                        LoginCheck.UNKNOWN
                    }
                }
                account.lastCheckAt = System.currentTimeMillis()
                when (result) {
                    LoginCheck.OK -> {
                        account.verified = true
                        account.lastCheckMessage = "登录正常"
                    }
                    LoginCheck.EXPIRED -> {
                        account.verified = false
                        account.lastCheckMessage = "登录已失效，需要重新登录"
                    }
                    LoginCheck.UNKNOWN -> {
                        // 旧实现在这里把 scheduleEnabled 置 false 并取消闹钟，
                        // 导致一次网络抖动就会让自动签到永久停摆，且用户毫无感知。
                        account.lastCheckMessage = "检查未完成（网络异常）"
                    }
                }
                lines.add(account.displayName + "：" + account.lastCheckMessage)
            }
            AccountStore.save()
            findViewById<TextView>(R.id.logText).text = lines.joinToString("\n")
            renderAccounts()
            updateStatus()
        }
    }

    /** 账号列表结构变化（增删/启停/登录返回）时重建所有行 */
    private fun renderAccounts() {
        val container = findViewById<LinearLayout>(R.id.accountList)
        container.removeAllViews()
        rowViews.clear()

        val accounts = AccountStore.all()
        if (accounts.isEmpty()) {
            val empty = TextView(this)
            empty.text = "还没有账号，点下面「添加微博账号」"
            empty.setTextColor(Color.parseColor("#667085"))
            empty.textSize = 13f
            container.addView(empty)
            return
        }

        val inflater = LayoutInflater.from(this)
        for (account in accounts) {
            val row = inflater.inflate(R.layout.item_account, container, false)
            rowViews[account.id] = row

            row.findViewById<Button>(R.id.accountLoginButton).setOnClickListener {
                openLogin(account)
            }
            row.findViewById<Button>(R.id.accountToggleButton).setOnClickListener {
                account.enabled = !account.enabled
                AccountStore.save()
                refreshAccountRows()
                updateStatus()
                toast(account.displayName + (if (account.enabled) " 已启用" else " 已停用"))
            }
            row.findViewById<Button>(R.id.accountRemoveButton).setOnClickListener {
                confirmRemove(account)
            }
            container.addView(row)
        }
        refreshAccountRows()
    }

    /** 只刷新已有行的文字，不重建视图（每 2 秒调一次，重建会打断滚动和点击） */
    private fun refreshAccountRows() {
        for (account in AccountStore.all()) {
            val row = rowViews[account.id] ?: continue

            row.findViewById<TextView>(R.id.accountName).text = account.displayName

            val state = row.findViewById<TextView>(R.id.accountState)
            when {
                !account.enabled -> {
                    state.text = "已停用"
                    state.setTextColor(Color.parseColor("#667085"))
                }
                account.loggedIn -> {
                    state.text = "已登录"
                    state.setTextColor(Color.parseColor("#067647"))
                }
                account.cookie.isNotBlank() -> {
                    state.text = "登录已失效"
                    state.setTextColor(Color.parseColor("#B54708"))
                }
                else -> {
                    state.text = "未登录"
                    state.setTextColor(Color.parseColor("#B54708"))
                }
            }

            val summary = account.lastRunMessage.lineSequence().firstOrNull().orEmpty()
            row.findViewById<TextView>(R.id.accountInfo).text = when {
                summary.isNotBlank() -> summary
                account.cookie.isBlank() -> "尚未登录"
                else -> "尚未签到"
            }

            row.findViewById<Button>(R.id.accountLoginButton).text =
                if (account.loggedIn) "重新登录" else "登录"
            row.findViewById<Button>(R.id.accountToggleButton).text =
                if (account.enabled) "停用" else "启用"
        }
    }

    private fun openLogin(account: WeiboAccount) {
        startActivity(
            Intent(this, WeiboLoginActivity::class.java)
                .putExtra(WeiboLoginActivity.EXTRA_ACCOUNT_ID, account.id)
        )
    }

    /**
     * 打开「微博身份验证」。
     *
     * 与 [openLogin] 的区别：**不清 Cookie**。
     * 微博的「行为异常」验证是按账号 + 会话记的，换一个未登录的会话去验证，
     * 验证的是那个空会话，本应用手里的 Cookie 依然是异常状态 —— 白做一次。
     */
    private fun openVerify(account: WeiboAccount) {
        startActivity(
            Intent(this, WeiboLoginActivity::class.java)
                .putExtra(WeiboLoginActivity.EXTRA_ACCOUNT_ID, account.id)
                .putExtra(WeiboLoginActivity.EXTRA_VERIFY_MODE, true)
        )
        toast("请在页面里完成验证，通过后返回即可")
    }

    private fun confirmRemove(account: WeiboAccount) {
        AlertDialog.Builder(this)
            .setTitle("删除「" + account.displayName + "」？")
            .setMessage("会同时删除该账号的登录信息，之后需要重新扫码。其它账号不受影响。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                AccountStore.remove(account.id)
                renderAccounts()
                updateStatus()
                toast("已删除 " + account.displayName)
            }
            .show()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
    }

    private fun requestExactAlarmPermissionIfNeeded() {
        DeviceCompatibility.openExactAlarmSettings(this)
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        /**
         * 「测试自动签到」的执行延迟。
         *
         * ★ 取 2 分钟：足够用户按 Home 键、息屏、把手机放下，又不用干等太久。
         *   也不宜更短 —— scheduleAt 里有一条「绝不把闹钟登记到过去」的兜底，
         *   1 分钟以内会被抬到 1 分钟，反而不好预期。
         */
        const val TEST_DELAY_MINUTES = 2
    }
}

/** 登录检查结果：必须区分「确实掉线」和「这次没查成」 */
private enum class LoginCheck { OK, EXPIRED, UNKNOWN }
