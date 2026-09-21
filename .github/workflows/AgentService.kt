package com.tjh.weibotask

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

class AgentService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var running = false
    private var maintenanceJob: Job? = null

    /**
     * 唤醒信号：用来把正在 `delay()` 里睡觉的维护循环立刻叫醒。
     *
     * ★★ 为什么必须有它 —— 这是 v0.19.11 定位到的「必须打开 App 才执行」的真正原因：
     *
     *   维护循环的等待时长是**按当时的 nextRunAt 算出来**的，上限 15 分钟。
     *   用户点「测试自动签到」时，nextRunAt 还停在「明天」，所以循环正睡在一个
     *   接近 15 分钟的 `delay` 里。紧接着任务被排到 **2 分钟后** ——
     *   但循环不知道，它要等这次 delay 睡完（最长 15 分钟）才会重新读时间。
     *
     *   更要命的是这段时间**没有任何唤醒保护**（唤醒锁只在循环体里申请）：
     *   vivo 的后台冻结会把整个进程挂起，于是
     *     · 循环到点也不会醒；
     *     · 系统闹钟的投递也可能被一并推迟。
     *   结果就是「到点什么都不发生，一打开 App 立刻执行」。
     *
     *   有了这个信号，`startMaintenance()` 能在**排程变化的当下**把循环踢醒，
     *   循环随即按新的 nextRunAt 重新计算等待时间并申请唤醒锁 —— 等待窗口被真正保护起来。
     *
     * 用 CONFLATED 是刻意的：只需要「有人叫过我」这一个事实，
     * 连按几次测试按钮不该积压成多次唤醒。
     */
    private val maintenanceWake = Channel<Unit>(Channel.CONFLATED)
    private var wakeLock: PowerManager.WakeLock? = null
    private var standbyWakeLock: PowerManager.WakeLock? = null
    private var foregroundStartError: String = ""

    override fun onCreate() {
        super.onCreate()
        // ★ startForegroundService() 之后系统只给很短时间进入前台状态。
        //   旧顺序先初始化 Android Keystore、解密全部账号 JSON、再启动通知；
        //   冷启动 + 息屏刚唤醒时这些工作可能超过时限，服务会被系统直接杀掉。
        //   现在先占住前台服务资格，再做加密存储初始化。
        createNotificationChannel()
        foregroundStartError = startForegroundNotification("自动签到待命中").orEmpty()
        Prefs.init(this)
        AccountStore.init(this)
        // ★ 把「签到判定依据」的出口挂上（见 WeiboClient.traceSink 的说明）。
        //   网络层不认识 Prefs，由服务在启动时注入 —— 这样多账号的状态隔离规则不被破坏，
        //   同时「显示成功但没签上」这类问题有了可复现的证据链。
        WeiboClient.traceSink = { line ->
            try {
                Prefs.appendCheckinTrace(line)
            } catch (_: Exception) {
            }
        }
        // 服务被重建 = 上一次执行一定已经中断，清掉可能残留的状态。
        Prefs.running = false
        Prefs.standbyProtectedUntil = 0L
        if (foregroundStartError.isNotBlank()) {
            Prefs.lastRunStatus = "FGS_FAILED"
            Prefs.lastRunMessage = "前台服务启动失败：" + foregroundStartError
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 「闹钟触发」只证明广播到了；这个时间证明服务的 onStartCommand 也真的到了。
        // 两者分开记录，才能区分「闹钟没响」和「闹钟响了但服务没起来」。
        try {
            Prefs.lastServiceStartAt = System.currentTimeMillis()
        } catch (_: Exception) {
        }
        when (intent?.action) {
            ACTION_RUN_CHECKIN -> {
                // ★ 手动点「立即签到」时不分批。
                //   分批是为了「每天定时、无人值守」这个场景 —— 那种情况下把 10 个账号
                //   一次性全跑，等于在同一 IP 上打出一波密集请求。
                //   但手动点击是低频、有人值守的：用户就坐在旁边等结果，
                //   只签 3 个然后把服务停掉，剩下 7 个永远不会被跑（尤其是没开自动签到时）。
                //   所以手动 = 跑完全部可签账号，只在账号之间留间隔。
                //   ★ 用参数而不是成员变量：定时触发走的是维护循环里的 requestRun()，
                //     如果写成成员变量，上一次手动点击的 true 会残留下来，把定时任务也变成不分批。
                startForegroundNotification("正在准备签到")
                requestRun(allAccounts = intent?.getBooleanExtra(EXTRA_RUN_ALL, false) ?: false)
            }
            ACTION_START_SCHEDULED -> {
                startForegroundNotification("自动签到运行中")
                if (Prefs.scheduleEnabled) {
                    LocalScheduler.ensureScheduled(this)
                    startMaintenance()
                }
            }
            ACTION_RUN_POST -> {
                startForegroundNotification("正在发超话帖子")
                requestPost(allAccounts = intent?.getBooleanExtra(EXTRA_RUN_ALL, false) ?: false)
            }
            else -> {
                startForegroundNotification("自动签到运行中")
                if (Prefs.scheduleEnabled) startMaintenance()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        maintenanceJob?.cancel()
        scope.cancel()
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
        releaseStandbyWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 用户从「最近任务」里划掉本应用时触发。
     *
     * ★ 为什么要在这里再拉一次服务：部分国产 ROM 在划掉任务卡片时会顺带
     *   「清理」应用进程。服务一旦死掉，到点就只能靠闹钟，而闹钟正是最容易被丢弃的一环。
     *   服务活着时维护循环会自己发现「到点了」，根本不依赖闹钟 ——
     *   所以「让服务活着」比「让闹钟更准」更根本。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (Prefs.scheduleEnabled) {
            try {
                val restart = Intent(applicationContext, AgentService::class.java).apply {
                    action = ACTION_START_SCHEDULED
                }
                ContextCompat.startForegroundService(applicationContext, restart)
            } catch (error: Exception) {
                // 不再静默吞掉：START_STICKY / 闹钟仍会重试，但首页必须能看见失败原因。
                try {
                    Prefs.lastRunStatus = "BLOCKED"
                    Prefs.lastRunMessage = "最近任务划掉后，系统阻止服务重启（" +
                        error.javaClass.simpleName + "）"
                } catch (_: Exception) {
                }
            }
        }
        super.onTaskRemoved(rootIntent)
    }

    private fun requestRun(allAccounts: Boolean = false) {
        if (running) return
        running = true
        scope.launch {
            try {
                executeOnce(allAccounts)
            } finally {
                running = false
                if (!Prefs.scheduleEnabled) {
                    stopSelf()
                } else {
                    LocalScheduler.ensureScheduled(this@AgentService)
                    startMaintenance()
                }
            }
        }
    }

    /** 手动「立即发一条」：只跑发帖，不跑签到。 */
    private fun requestPost(allAccounts: Boolean = false) {
        if (running) return
        running = true
        scope.launch {
            try {
                executePostOnce(allAccounts)
            } finally {
                running = false
                if (!Prefs.scheduleEnabled) {
                    stopSelf()
                } else {
                    LocalScheduler.ensureScheduled(this@AgentService)
                    startMaintenance()
                }
            }
        }
    }

    private suspend fun executePostOnce(allAccounts: Boolean = false) {
        releaseStandbyWakeLock()
        AccountStore.init(this)
        Prefs.running = true
        Prefs.lastRunAt = System.currentTimeMillis()
        Prefs.lastRunMessage = "正在发超话帖子……"
        val todayStr = today()
        try {
            val ready = AccountStore.ready()
            if (ready.isEmpty()) {
                Prefs.postLastStatus = "USER_REQUIRED"
                Prefs.postLastMessage = "还没有已登录的微博账号，没法发帖"
                Prefs.lastRunMessage = Prefs.postLastMessage
                updateNotification("还没有已登录的微博账号")
                return
            }
            val batch = if (allAccounts) ready else ready.take(BATCH_SIZE)
            acquireWakeLock((batch.size * 5 + 10).coerceIn(10, 60))

            val lines = mutableListOf<String>()
            val remaining = runPostPhase(batch, todayStr, lines)
            AccountStore.save()

            // ★ 手动发帖不覆盖「上次签到」的状态码 —— 那是另一条链路的结果，
            //   覆盖了会让首页看不出签到到底成没成。这里只写发帖自己的记录。
            Prefs.lastRunMessage = lines.joinToString("\n").ifBlank { "本轮没有需要发的帖子" }
            if (remaining > 0) {
                if (Prefs.scheduleEnabled) {
                    LocalScheduler.scheduleRetry(this, Prefs.postIntervalMinutes * 60_000L)
                }
            } else if (Prefs.scheduleEnabled) {
                LocalScheduler.scheduleNext(this)
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            Prefs.postLastStatus = "INTERRUPTED"
            Prefs.postLastMessage = "发帖被系统中断（应用在后台被系统关闭）"
            try {
                updateNotification("发帖被系统中断")
            } catch (_: Exception) {
            }
            throw cancelled
        } catch (error: Exception) {
            Prefs.postLastStatus = "FAILED"
            Prefs.postLastMessage = "发帖出错：" + (error.message ?: "未知错误")
            updateNotification(Prefs.postLastMessage)
        } finally {
            Prefs.running = false
            try {
                if (wakeLock?.isHeld == true) wakeLock?.release()
            } catch (_: Exception) {
            }
            wakeLock = null
        }
    }

    /**
     * 发帖阶段：对这一批账号，每个发**一条**帖子。
     *
     * ★★ 为什么一轮只发一条：
     *   一次连发 5 条 = 同一个账号几分钟内连续 5 次发布行为，
     *   这是微博判定「机器刷屏」最典型的形态 —— 比签到密集请求严重得多。
     *   所以拆成「每轮一条 + 间隔 N 分钟」，把发布行为拉成一条稀疏的时间线。
     *   ★ 附带好处：中途被系统冻结也不会丢进度，进度已经逐条落盘了。
     *
     * @return 本轮结束后「今天还需要发帖」的账号数（0 = 都发完了）
     */
    private suspend fun runPostPhase(
        batch: List<WeiboAccount>,
        todayStr: String,
        lines: MutableList<String>
    ): Int {
        if (!Prefs.postEnabled) return 0

        val rawLines = Prefs.postTopicsText.lineSequence()
            .map { it.trim() }.filter { it.isNotBlank() }.toList()
        // ★ 超话名单从「超话名」改成了「超话链接」：新发帖接口要 containerid，
        //   只能从链接里解析（v0.20.0）。能解析的照发，解析不了的单独报出来。
        val topics = rawLines.mapNotNull { WeiboClient.parseSuperTopicRef(it) }
        val bodies = parsePostBodies(Prefs.postContentText)
        val perAccount = Prefs.postPerAccount

        // ★ 配置不全必须明说。开启发帖却什么都没发，用户只会以为程序坏了。
        if (rawLines.isEmpty() || bodies.isEmpty()) {
            Prefs.postLastStatus = "CONFIG_MISSING"
            Prefs.postLastMessage = "自动发帖已开启，但超话链接名单或帖子正文是空的 —— 本轮没有发帖。" +
                "请到首页「4. 自动发帖」里填好再保存。"
            lines.add("发帖：配置不完整（需要至少 1 个超话链接和 1 段正文），本轮跳过")
            return 0
        }
        if (topics.size < rawLines.size) {
            // ★ 不中断：能解析的那几条照发，只把解析不了的行报出来。
            //   否则一行写错会让整轮发帖停提。
            val bad = "发帖：有 " + (rawLines.size - topics.size) + " 行不是有效的超话链接（没找到 containerid），" +
                "已忽略。请整行粘贴超话页地址，例如\n" +
                "https://m.weibo.cn/p/index?containerid=100808…\n或 https://weibo.com/p/100808…/super_index"
            Prefs.postLastMessage = bad
            lines.add(bad)
            if (topics.isEmpty()) return 0
        }

        val results = mutableListOf<String>()
        var anyVerify = false
        var anyPaused = false
        var posted = 0

        for ((index, account) in batch.withIndex()) {
            if (!account.loggedIn) continue
            // 跨天自动清零进度，否则新的一天一开始就被当成「已经发完了」
            account.rollPostDate(todayStr)
            if (account.postIndex >= perAccount) continue

            // 同一批里几个账号背靠背发，密度依然偏高 —— 隔开
            if (index > 0) delay(ACCOUNT_GAP_MS + Random.nextLong(ACCOUNT_GAP_JITTER_MS))

            val topic = topics[account.postIndex % topics.size]
            val body = bodies[account.postIndex % bodies.size]
            val prefix = "[" + account.displayName + "] "
            val outcome = WeiboClient.runPost(account, topic, body) { text ->
                Prefs.lastRunMessage = prefix + text
                updateNotification(prefix + text)
            }

            when (outcome.status) {
                "SUCCESS" -> {
                    account.postIndex += 1
                    account.postFailCount = 0
                    posted += 1
                }
                // 重复内容：重试没意义，跳过这条换下一个超话
                "DUPLICATE" -> {
                    account.postIndex += 1
                    account.postFailCount = 0
                }
                // 被限流 / 明确拒绝：暂停后**重试同一条**，不推进进度
                "PAUSED" -> anyPaused = true
                // 验证只能人工处理，进度保持不动，等用户验证完自动补上
                "USER_REQUIRED" -> {
                    anyVerify = true
                    Prefs.needsVerify = true
                }
                else -> {
                    account.postFailCount += 1
                    // ★ 同一条连续失败到上限就跳过：否则一个写错的超话名
                    //   会让程序每隔一个间隔重试它一整天，白送请求给风控。
                    if (account.postFailCount >= WeiboAccount.MAX_POST_FAILURES) {
                        account.postIndex += 1
                        account.postFailCount = 0
                        outcome.let {
                            results.add(
                                prefix + "「" + topic.display + "」连续失败 " + WeiboAccount.MAX_POST_FAILURES +
                                    " 次，已跳过：" + it.message
                            )
                        }
                    }
                }
            }

            account.postStatus = outcome.status
            account.postMessage = outcome.message
            results.add(prefix + outcome.message)

            // 每发完一个账号就落盘：后面万一进程被杀，进度不会丢
            AccountStore.save()
        }

        Prefs.postLastRunAt = System.currentTimeMillis()
        Prefs.postLastStatus = when {
            anyVerify -> "USER_REQUIRED"
            anyPaused -> "PAUSED"
            posted > 0 -> "SUCCESS"
            results.isEmpty() -> "IDLE"
            else -> "FAILED"
        }
        Prefs.postLastMessage = results.joinToString("\n")
        for (line in results) lines.add(line)

        if (anyVerify || anyPaused) {
            // 发帖撞上风控比签到更严重，暂停一下让微博那边冷静
            Prefs.pausedUntil = System.currentTimeMillis() + PAUSE_DURATION_MS
        }

        return AccountStore.postPending(todayStr, perAccount).size
    }

    /**
     * 帖子正文：多段之间用**单独一行 `---`** 分隔。
     * 没有分隔符时整段算一条 —— 所有帖子共用它，靠结尾的超话话题区分。
     */
    private fun parsePostBodies(raw: String): List<String> =
        raw.split(Regex("(?m)^\\s*-{3,}\\s*$")).map { it.trim() }.filter { it.isNotBlank() }

    private fun startMaintenance() {
        // ★★ 第一步必须**同步**做，不能留给循环里的第一次迭代：
        //   循环很可能正睡在一个接近 15 分钟的 delay 里（见 maintenanceWake 的说明），
        //   而刚排上的任务可能就在 2 分钟后。这段时间不持唤醒锁，进程会被 ROM 冻结，
        //   到点既不执行、循环也不会醒 —— 用户看到的就是「必须打开 App 才执行」。
        //   所以：服务一旦被拉起，就立刻按当前 nextRunAt 决定要不要保护等待窗口。
        try {
            refreshStandbyWakeLock(Prefs.nextRunAt - System.currentTimeMillis())
        } catch (_: Exception) {
        }
        // 第二步：把可能正在睡觉的循环踢醒，让它按新的 nextRunAt 重新算等待时间。
        // 注意顺序 —— 先申请唤醒锁，再叫醒循环；反过来的话循环可能来不及重新申请。
        maintenanceWake.trySend(Unit)

        if (maintenanceJob?.isActive == true) return
        maintenanceJob = scope.launch {
            while (isActive) {
                try {
                    // 心跳只证明协程最近真正获得过 CPU，不能再把它解释成「完全不依赖闹钟」。
                    // Doze 会冻结普通 delay；因此系统闹钟仍是主触发，短时唤醒锁是第二道保险。
                    Prefs.serviceAliveAt = System.currentTimeMillis()
                    if (!Prefs.scheduleEnabled) {
                        releaseStandbyWakeLock()
                        stopSelf()
                        break
                    }
                    val remaining = Prefs.nextRunAt - System.currentTimeMillis()
                    refreshStandbyWakeLock(remaining)
                    if (remaining <= 0L) {
                        releaseStandbyWakeLock()
                        requestRun()
                        delay(60000)
                    } else {
                        // ★ 用 withTimeoutOrNull 而不是裸 delay：排程被改动时（例如用户点了
                        //   「测试自动签到」把任务挪到 2 分钟后），这里能被立刻叫醒并重算，
                        //   不必等这次等待睡完（最长 15 分钟）才反应。
                        withTimeoutOrNull(min(max(remaining, 15000L), 15 * 60 * 1000L)) {
                            maintenanceWake.receive()
                        }
                    }
                } catch (error: Exception) {
                    // 旧实现中，心跳的一次加密写失败就会让 maintenanceJob 永久退出；
                    // 后台从此没人检查时间，直到用户打开 App 重新启动服务。
                    try {
                        Prefs.lastRunStatus = "MAINTENANCE_FAILED"
                        Prefs.lastRunMessage = "后台守护异常，60 秒后重试（" +
                            error.javaClass.simpleName + "）"
                    } catch (_: Exception) {
                    }
                    delay(60000)
                }
            }
        }
    }

    private suspend fun executeOnce(allAccounts: Boolean = false) {
        // 等待阶段的短时唤醒保护到此结束；执行阶段会按本批账号数重新申请 wakeLock。
        releaseStandbyWakeLock()
        AccountStore.init(this)
        Prefs.running = true
        Prefs.lastRunAt = System.currentTimeMillis()
        Prefs.lastRunMessage = "正在准备签到"
        try {
            val accounts = AccountStore.enabled()
            if (accounts.isEmpty()) {
                Prefs.lastRunStatus = "USER_REQUIRED"
                Prefs.lastRunMessage = "还没有添加微博账号，请先在首页添加并登录"
                updateNotification("还没有添加微博账号")
                if (Prefs.scheduleEnabled) LocalScheduler.scheduleNext(this)
                return
            }

            val ready = accounts.filter { it.loggedIn }
            val skipped = accounts.filter { !it.loggedIn }

            if (ready.isEmpty()) {
                Prefs.lastRunStatus = "USER_REQUIRED"
                Prefs.lastRunMessage = "所有账号都未登录，请先完成微博登录"
                updateNotification("所有账号都未登录")
                if (Prefs.scheduleEnabled) LocalScheduler.scheduleNext(this)
                return
            }

            // ★ 分批错峰（账号多时降低风控风险的关键）
            //
            //   把 10 个账号在同一时刻全部跑一遍，会在同一个 IP 上形成一次密集请求 ——
            //   这是最容易触发风控的形态。改成每轮只跑一小批（默认 3 个），
            //   跑过的账号记下日期，下一批 20 分钟后再来，几轮跑完。
            //   10 个账号 = 4 批，大约 1 小时跑完，请求密度降到 1/3 以下。
            //
            //   ★ 手动点「立即签到」时 allAccounts=true，跳过分批 ——
            //     那种场景服务跑完就 stopSelf 了，剩下的批次没人接着跑。
            val todayStr = today()
            val pendingToday = AccountStore.notRunToday(todayStr)
            // ★ 上一轮「没签完」的账号也要重新排进来。
            //   它们已经被记进 lastRunDate，光靠 notRunToday 永远选不到它们 ——
            //   界面上写着「会自动补签」，实际却没人管。这里补上这一环（次数有限，见 partialPending）。
            val partialPending = AccountStore.partialPending(todayStr)
            // 池子按优先级：今天没轮到的 > 没签完要补的
            val pool = pendingToday + partialPending
            val batch = if (allAccounts) {
                ready
            } else {
                // 池子空（今天全都跑完且都没问题）→ 兜底跑一批，不做无意义的空转
                (if (pool.isNotEmpty()) pool else ready).take(BATCH_SIZE)
            }
            val remainingAfterBatch = if (allAccounts) 0 else (pool.size - batch.size).coerceAtLeast(0)

            // 唤醒锁按**这一批**的时长算（不是按全部账号）。
            //
            // ★★ v19.19：系数必须跟着 WeiboClient.accountBudgetMs 一起走。
            //   旧值是 `batch.size * 12 + 10`，对应「单账号最多 10 分钟」。
            //   现在单账号预算上限已提到 40 分钟，唤醒锁却还按 12 分钟/账号申请 ——
            //   后果是签到跑到一半唤醒锁就过期，手机允许休眠，剩下的超话被冻结，
            //   最后以「超时未执行」收尾。用户的感受是「每天都签一半」。
            //
            //   ★ 这是一类容易漏的耦合：**唤醒锁时长是「预算时长的影子」**，
            //     预算改了、影子不会自己跟着改。凡是调整 accountBudgetMs，这里必须同改。
            //
            // ★ 上限由 120 分钟提到 240 分钟：手动「立即签到」时会一次跑完所有账号，
            //   10 个账号按新公式算出来是 410 分钟 —— 截到 240 分钟。
            //   真被截断也只是这一轮剩下的超话进 PARTIAL、下一轮再签，不会丢数据。
            //   （一次 4 小时的唤醒锁在手动触发时是可接受的，因为它只在这段时间内保持 CPU 清醒，
            //     不阻止息屏，且任务结束就释放。）
            acquireWakeLock((batch.size * 45 + 15).coerceIn(30, 240))

            val lines = mutableListOf<String>()
            // 需要人工处理（验证码 / 登录失效）—— 重试不会变好，要限时暂停
            var needsUser = false
            // 只是有槽位没登录 —— 这是要用户手动去登录的，跟风控是两回事
            var anyNotLoggedIn = false
            var anyPaused = false
            var anyPartial = false

            // ★ 逐个账号串行签到。
            //   不做并发：微博的风控是按账号算的，但同一台设备 IP 相同，
            //   并发请求会让几个号同时被判定成异常流量，反而更容易全盘失败。
            for ((index, account) in batch.withIndex()) {
                val prefix = "[" + account.displayName + "] "
                // 批内也要隔开：同一 IP 连着发几个账号的请求，密集度依然偏高
                if (index > 0) delay(ACCOUNT_GAP_MS + Random.nextLong(ACCOUNT_GAP_JITTER_MS))
                updateNotification(prefix + "准备签到（" + (index + 1) + "/" + batch.size + "）")

                // 记录每个账号的耗时。用户报「某个账号超时」时，
                // 这个数字是判断「网络慢」还是「App 被系统冻结」的唯一线索。
                val accountStartedAt = System.currentTimeMillis()

                // 把每个超话的进度实时写回通知栏和首页。
                // 多账号下必须带账号名前缀，否则用户不知道卡在哪个号上。
                val outcome = WeiboClient.runCheckin(account) { text ->
                    Prefs.lastRunMessage = prefix + text
                    updateNotification(prefix + text)
                }

                val elapsed = System.currentTimeMillis() - accountStartedAt
                account.lastRunAt = System.currentTimeMillis()
                account.lastRunStatus = outcome.status
                account.lastRunMessage = buildResultMessage(outcome)
                // ★ 记下「今天轮到过它了」，下一批就不会重复跑它。
                //
                //   ★★ 但「需要人工验证」是例外：**不能**标记。
                //   微博判定「行为异常」后，唯一的出路是用户手动完成一次验证。
                //   如果这里照标记，那么用户验证完之后，这个账号今天再也不会被自动选中 ——
                //   只能靠手动点「立即签到」补，很容易整整一天都漏掉。
                //   不标记的话：30 分钟暂停期一过、用户下次打开 App 时，
                //   ensureScheduled 就会发现「今天还有该跑没跑的账号」并自动补跑。
                //   （不会变成空转：每次补跑只发 1~2 个请求，第一个超话就重新命中验证页并立刻停下，
                //     而且整批结束后会 scheduleNext 排到明天，不依赖用户不开 App。）
                if (outcome.status == "USER_REQUIRED") {
                    account.lastRunDate = ""
                } else {
                    account.lastRunDate = todayStr
                }
                // ★ 「没签完」的账号要单独记账，下一轮会被 partialPending 重新捞出来补跑。
                //   必须限次（见 WeiboAccount.MAX_PARTIAL_RETRIES）：
                //   一个永远签不完的超话会让它每 20 分钟空转一轮，那是白白送请求给风控。
                if (outcome.status == "PARTIAL") {
                    if (account.partialRetryDate != todayStr) {
                        account.partialRetryDate = todayStr
                        account.partialRetryCount = 0
                    }
                    account.partialRetryCount += 1
                } else {
                    // 这一轮签干净了 → 清掉历史欠账，别再把它当「没签完」反复捞
                    account.partialRetryCount = 0
                }

                lines.add(prefix + oneLineSummary(outcome) + "（用时 " + formatDuration(elapsed) + "）")
                val detail = outcome.summary.optString("detail")
                if (detail.isNotBlank()) lines.add(detail)

                when (outcome.status) {
                    "USER_REQUIRED" -> needsUser = true
                    "PAUSED" -> anyPaused = true
                    "PARTIAL" -> anyPartial = true
                }

                // ★ 「需要去微博点一次验证」是一个**要用户动手**的状态，必须显式记下来，
                //   首页才能稳定地提示（而不是让界面去猜文案里有没有「验证」两个字）。
                //   签到正常完成 = 用户已经验证过了 → 清掉提示。
                if (outcome.errorCode == "CAPTCHA") {
                    Prefs.needsVerify = true
                } else if (outcome.status == "SUCCESS" || outcome.status == "PARTIAL") {
                    Prefs.needsVerify = false
                }

                // 每签完一个账号就落盘一次：万一后面某个账号把进程搞崩了，
                // 前面已经签好的结果和刷新过的 Cookie 不会丢
                AccountStore.save()
            }

            // 没登录的账号也要在结果里说清楚，否则用户会以为「少签了一个」
            for (account in skipped) {
                account.lastRunStatus = "USER_REQUIRED"
                account.lastRunMessage = "未登录，已跳过"
                lines.add("[" + account.displayName + "] 未登录，已跳过")
                anyNotLoggedIn = true
            }
            AccountStore.save()

            // ★ 发帖阶段（跟在签到之后）：每账号本轮只发一条，剩下的等下一个间隔。
            val postRemaining = runPostPhase(batch, todayStr, lines)

            // 分批执行时，必须明确告诉用户「还剩几个、什么时候继续」，
            // 否则他只看到 3 个账号的结果，会以为剩下 7 个被漏掉了
            if (remainingAfterBatch > 0) {
                lines.add(
                    "—— 今天还有 " + remainingAfterBatch + " 个账号没跑，" +
                        (BATCH_GAP_MS / 60000L) + " 分钟后自动继续。" +
                        "共 " + ready.size + " 个账号，每批 " + BATCH_SIZE + " 个、分 " +
                        ((ready.size + BATCH_SIZE - 1) / BATCH_SIZE) + " 批跑完 —— " +
                        "这是为了降低风控风险（同一个 IP 上不要瞬间发太多请求）"
                )
            }

            Prefs.lastRunDate = todayStr
            val status = when {
                needsUser -> "USER_REQUIRED"
                // ★ 空槽位没登录 ≠ 需要人工处理。
                //   旧实现把它也报成 USER_REQUIRED，于是「10 个槽位里有 5 个没登录」这种
                //   完全正常的配置，会让每一轮的结果都显示「需要处理」——
                //   功能明明正常，报告却在报警（v0.19.9 的测试报告就是这样）。
                //   现在单独一个状态码，并且不暂停、不影响后续排程。
                anyNotLoggedIn -> "NOT_LOGGED_IN"
                anyPaused -> "PAUSED"
                // 有超话没签完也是「没做完」，不能报成功
                anyPartial -> "PARTIAL"
                else -> "SUCCESS"
            }
            Prefs.lastRunStatus = status
            Prefs.lastRunMessage = lines.joinToString("\n")

            // 第 1 步：决定「要不要暂停」—— 只有风控 / 需要人工处理才暂停
            when (status) {
                "USER_REQUIRED" -> {
                    // ★ 分两种情况，处理方式完全不同：
                    //   验证码 / 登录失效：重试不会变好，但也**不能永久关闭调度**
                    //     （旧实现是 scheduleEnabled=false + cancel()，用户看到「到点完全没执行」），
                    //     所以限时暂停，到期自动重试。
                    //   只是有槽位没登录：这要用户手动去登录，每 30 分钟重试一次毫无意义 ——
                    //     只会白白发请求、平白加重风控。所以**不暂停**，按正常日程走。
                    if (needsUser) {
                        Prefs.pausedUntil = System.currentTimeMillis() + PAUSE_DURATION_MS
                    } else {
                        Prefs.pausedUntil = 0L
                    }
                }
                "PAUSED" -> Prefs.pausedUntil = System.currentTimeMillis() + PAUSE_DURATION_MS
                else -> Prefs.pausedUntil = 0L
            }

            // 第 2 步：决定「下一次什么时候跑」。
            //   优先级：还有账号没轮到 > 有超话没签完 > 按用户设定的每日时间。
            //   ★ 这两件事以前是混在一起的（暂停 = 排到暂停结束），
            //     所以「还有 7 个账号没跑」这种正常情况会被当成暂停处理，节奏全乱。
            if (Prefs.scheduleEnabled) {
                val pauseLeft = (Prefs.pausedUntil - System.currentTimeMillis()).coerceAtLeast(0L)
                when {
                    // 还有账号今天没轮到 → 歇一会儿继续下一批
                    remainingAfterBatch > 0 -> {
                        val gap = pauseLeft + BATCH_GAP_MS + Random.nextLong(BATCH_GAP_JITTER_MS)
                        updateNotification(
                            "本批完成，还有 " + remainingAfterBatch + " 个账号，" +
                                (gap / 60000L) + " 分钟后继续" +
                                (if (pauseLeft > 0L) "（含风控暂停）" else "")
                        )
                        LocalScheduler.scheduleRetry(this, gap)
                    }
                    // ★ 还有账号没发完帖子 → 按**发帖间隔**再来一轮。
                    //   发帖的间隔通常比签到批次间隔大得多（默认 20 分钟，用户可以调到 4 小时），
                    //   这是故意的：发布行为比签到敏感，稀疏才有价值。
                    postRemaining > 0 -> {
                        val gap = pauseLeft + Prefs.postIntervalMinutes * 60_000L
                        updateNotification(
                            "还有 " + postRemaining + " 个账号没发完帖子，" +
                                (gap / 60000L) + " 分钟后继续发下一条"
                        )
                        LocalScheduler.scheduleRetry(this, gap)
                    }
                    // 有超话没签完 → 单独补一轮，不跟风控暂停混在一起
                    status == "PARTIAL" -> {
                        val gap = pauseLeft + PARTIAL_RETRY_DELAY_MS
                        updateNotification("有超话没签完，" + (gap / 60000L) + " 分钟后自动补签")
                        LocalScheduler.scheduleRetry(this, gap)
                    }
                    else -> {
                        when (status) {
                            "SUCCESS" -> updateNotification("全部账号签到完成")
                            "USER_REQUIRED" ->
                                updateNotification(
                                    if (needsUser) "有账号需要人工处理，稍后自动重试"
                                    else "有账号还没登录，请到首页完成登录"
                                )
                            "PAUSED" -> updateNotification("部分账号被风控暂停，稍后自动重试")
                            // 空槽位没登录：已登录的都跑完了，通知里别报成失败
                            "NOT_LOGGED_IN" -> updateNotification("已登录账号签到完成（有账号未登录）")
                            else -> updateNotification("签到失败")
                        }
                        LocalScheduler.scheduleNext(this)
                    }
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            // ★ 协程被取消 ≠ 执行出错，必须单独处理。
            //
            //   最常见的来源是「服务被系统关掉」：onDestroy → scope.cancel()。
            //   国产 ROM（vivo / OPPO 尤其）在后台清理应用时非常容易触发。
            //   旧实现把它当普通异常，界面就报「执行失败：Job was cancelled」——
            //   用户看到英文报错只会以为是程序 bug，而真正该做的是加后台白名单。
            //   （v0.19.7 的诊断报告里抓到的就是这个：上次结果 FAILED / Job was cancelled。）
            Prefs.lastRunStatus = "INTERRUPTED"
            Prefs.lastRunMessage = "执行被系统中断（应用在后台被系统关闭）。" +
                "如果经常出现，请把本应用加入自启动 / 后台运行白名单。"
            try {
                updateNotification("签到被系统中断")
            } catch (_: Exception) {
            }
            // 取消必须继续往上抛，否则协程框架会认为任务「正常结束」。
            throw cancelled
        } catch (error: Exception) {
            Prefs.lastRunStatus = "FAILED"
            Prefs.lastRunMessage = "执行失败：" + (error.message ?: "未知错误")
            updateNotification(Prefs.lastRunMessage)
            if (Prefs.scheduleEnabled) LocalScheduler.scheduleNext(this)
        } finally {
            // 无论正常结束、提前 return 还是抛异常，都要清掉「正在执行」标记，
            // 否则首页会永远停在「正在执行」上
            Prefs.running = false
            try {
                if (wakeLock?.isHeld == true) wakeLock?.release()
            } catch (_: Exception) {
            }
            wakeLock = null
        }
    }

    /**
     * 单个账号的一行汇总，用于多账号结果列表。
     * 明细（哪个超话失败、失败原因）由 summary.detail 另起一行展示。
     */
    private fun oneLineSummary(outcome: CheckinOutcome): String {
        return when (outcome.status) {
            // PARTIAL 也走这一支：它只是「还有几个没签完」，前面的成功/已签数字照样要报
            "SUCCESS", "PARTIAL" -> {
                val total = outcome.summary.optInt("total")
                if (total == 0) {
                    "没有读到任何超话"
                } else {
                    "共 " + total + " 个超话" +
                        (if (outcome.summary.optInt("doneInList") > 0)
                            "（微博已标已签 " + outcome.summary.optInt("doneInList") + " 个，跳过）" else "") +
                        "，新签 " + outcome.summary.optInt("success") +
                        "，已签过 " + outcome.summary.optInt("already") +
                        "，失败 " + outcome.summary.optInt("failed") +
                        // 已取消关注的超话要单独报，否则会被当成「失败」让用户白担心
                        (if (outcome.summary.optInt("unfollowed") > 0)
                            "，已取消关注 " + outcome.summary.optInt("unfollowed") + " 个" else "") +
                        (if (outcome.summary.optInt("retried") > 0)
                            "（已自动补签 " + outcome.summary.optInt("retried") + " 个）" else "") +
                        (if (outcome.summary.optInt("unfinished") > 0)
                            "，还有 " + outcome.summary.optInt("unfinished") + " 个没签完" else "")
                }
            }
            "USER_REQUIRED" -> "需要人工处理：" + outcome.pauseReason.ifBlank { "验证码或登录异常" }
            "PAUSED" -> "已暂停：" + outcome.pauseReason.ifBlank { "风控" }
            else -> "失败：" + outcome.errorCode.ifBlank { outcome.status }
        }
    }

    /** 耗时文案：90 秒以内只显示秒，超过就显示「X 分 Y 秒」 */
    private fun formatDuration(ms: Long): String {
        val seconds = (ms / 1000L).coerceAtLeast(0L)
        return if (seconds < 90L) {
            seconds.toString() + " 秒"
        } else {
            (seconds / 60L).toString() + " 分 " + (seconds % 60L) + " 秒"
        }
    }

    private fun buildResultMessage(outcome: CheckinOutcome): String {
        return when (outcome.status) {
            "SUCCESS", "PARTIAL" -> {
                val total = outcome.summary.optInt("total")
                val selected = outcome.summary.optInt("selected")
                val already = outcome.summary.optInt("already")
                val failed = outcome.summary.optInt("failed")
                val retried = outcome.summary.optInt("retried")
                val unfollowed = outcome.summary.optInt("unfollowed")
                val doneInList = outcome.summary.optInt("doneInList")
                val unfinished = outcome.summary.optInt("unfinished")
                val excluded = outcome.summary.optString("excluded")
                val excludedCount = outcome.summary.optInt("excludedCount")
                val detail = outcome.summary.optString("detail")
                val head = if (total == 0) {
                    "没有读到任何超话（可能是登录态或接口异常）"
                } else {
                    // ★ 数字必须能对上：共 N 个 = 微博已标已签的 + 本次需签的。
                    //   旧文案只说「共 30 个超话，待签 3」，30 和 3 之间的缺口没有任何解释，
                    //   用户会以为漏签了 27 个 —— 所以这里把「跳过多少、为什么跳过」写清楚。
                    "签到完成：共 " + total + " 个超话" +
                        (if (doneInList > 0) "（其中 " + doneInList + " 个微博显示今日已签，未重复请求）" else "") +
                        "，本次需签 " + selected + " 个：新签 " + outcome.summary.optInt("success") +
                        "，已签过 " + already + "，失败 " + failed +
                        // 已取消关注的超话单独报，别让用户把它当成「签到出故障了」
                        (if (unfollowed > 0) "，已取消关注 " + unfollowed + " 个" else "") +
                        (if (retried > 0) "（已自动补签 " + retried + " 个）" else "") +
                        // 没签完必须单独说明，否则用户会把这一轮当成全部完成
                        (if (unfinished > 0) "；还有 " + unfinished + " 个本轮没签完，会自动重试" else "") +
                        // ★ 已取消关注的超话不该被签到。把跳过的写出来，
                        //   否则用户不知道「取消关注了为什么还在列表里」是被修了还是没修。
                        (if (excludedCount > 0) "；已忽略 " + excludedCount + " 个未关注的超话" else "")
                }
                // 明细里再补一行具体名字，用户能直接对照自己的关注列表
                val excludedLine = if (excluded.isNotBlank()) "已忽略（未关注）：" + excluded else ""
                val body = listOf(detail, excludedLine).filter { it.isNotBlank() }.joinToString("\n")
                if (body.isBlank()) head else head + "\n" + body
            }
            "USER_REQUIRED" -> "需要人工处理：" + outcome.pauseReason.ifBlank { "验证码或登录异常" }
            "PAUSED" -> "任务暂停：" + outcome.pauseReason.ifBlank { "风控暂停" }
            else -> "签到失败：" + outcome.errorCode.ifBlank { outcome.status }
        }
    }

    private fun today(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())
    }

    /**
     * 计划时间在 90 分钟内时，给「等待到点」加一层短时唤醒保护。
     *
     * 前台服务并不等于 CPU 会一直运行：息屏进入 Doze 后，普通 coroutine delay 会被冻结。
     * v19.2 错把「服务还活着」当成「到点一定执行」，这正是用户放后台一小时后不跑、
     * 一打开 App（CPU 被唤醒）才立刻执行的原因。
     *
     * 不允许持有一整天：只保护 90 分钟内的日常签到/分批间隔，降低电量影响；
     * 更远的每日时间仍由 setAlarmClock 唤醒，到达 90 分钟窗口后服务会补上这层保护。
     */
    private fun refreshStandbyWakeLock(remainingMs: Long) {
        if (remainingMs <= 0L || remainingMs > STANDBY_WAKE_WINDOW_MS) {
            releaseStandbyWakeLock()
            return
        }
        if (standbyWakeLock?.isHeld == true) return
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        val lock = power.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "WeiboTask::ScheduledWakeLock"
        )
        lock.setReferenceCounted(false)
        try {
            lock.acquire(remainingMs + STANDBY_WAKE_GRACE_MS)
        } catch (error: Exception) {
            // 申请被系统拒绝必须留痕。否则报告里只有一句「无记录」，
            // 分不清「从来没申请过」和「申请了但被系统拒绝」—— 这两种要修的地方完全不同。
            try {
                Prefs.standbyError = "唤醒锁申请失败：" + error.javaClass.simpleName
            } catch (_: Exception) {
            }
            return
        }
        standbyWakeLock = lock
        try {
            val now = System.currentTimeMillis()
            Prefs.standbyProtectedUntil = now + remainingMs + STANDBY_WAKE_GRACE_MS
            // ★ 单独记「最近一次真正申请成功」的时刻。
            //   standbyProtectedUntil 会被 executeOnce 开头和 releaseStandbyWakeLock 清零，
            //   所以报告里经常是「无记录」—— 那个值说明的是「此刻是否受保护」，
            //   而这个字段说明的是「到底有没有保护过」，两者缺一不可。
            Prefs.standbyAcquiredAt = now
            Prefs.standbyError = ""
        } catch (_: Exception) {
        }
    }

    private fun releaseStandbyWakeLock() {
        try {
            if (standbyWakeLock?.isHeld == true) standbyWakeLock?.release()
        } catch (_: Exception) {
        }
        standbyWakeLock = null
        try {
            Prefs.standbyProtectedUntil = 0L
        } catch (_: Exception) {
        }
    }

    private fun acquireWakeLock(minutes: Int) {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WeiboTask::CheckinWakeLock")
        wakeLock?.setReferenceCounted(false)
        // 旧值是固定 10 分钟。多个超话逐个签到时很容易超过，按本批账号数动态计算。
        wakeLock?.acquire(minutes * 60 * 1000L)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "星签后台执行", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    /** 返回 null 表示成功；返回文字表示两种启动方式都失败。 */
    private fun startForegroundNotification(text: String): String? {
        val notification = buildNotification(text)
        var typedError = ""
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
                return null
            } catch (error: Exception) {
                typedError = error.javaClass.simpleName
                // 类型不被系统接受时退回「不声明类型」；但两次都失败时必须留下诊断。
            }
        }
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, 0)
            null
        } catch (error: Exception) {
            listOf(typedError, error.javaClass.simpleName).filter { it.isNotBlank() }.joinToString(" / ")
        }
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("星签")
            .setContentText(text)
            .setContentIntent(pending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        const val ACTION_RUN_CHECKIN = "com.tjh.weibotask.RUN_CHECKIN"
        const val ACTION_START_SCHEDULED = "com.tjh.weibotask.START_SCHEDULED"

        /**
         * 只发帖、不签到。
         *
         * ★ 为什么要有独立入口：发帖接口是本机**无法验证**的部分（没有 JDK/SDK，
         *   也不可能在真机上试）。必须有一个按钮能让用户当场发一条、立刻看到
         *   微博返回的原始结果，否则只能等第二天定时跑完才能知道接口对不对 ——
         *   一轮反馈就是一天。
         */
        const val ACTION_RUN_POST = "com.tjh.weibotask.RUN_POST"

        /**
         * 手动「立即签到」时带上它 → 不分批，一次跑完所有可签账号。
         * 定时自动签到不带它 → 走分批错峰（每批 [BATCH_SIZE] 个）。
         */
        const val EXTRA_RUN_ALL = "com.tjh.weibotask.EXTRA_RUN_ALL"

        /**
         * 风控 / 验证码等临时状态的暂停时长，到期自动重试。
         *
         * ★ 旧值是 6 小时。后果是：一旦触发暂停，当天剩下的超话就**再也不会被签**，
         *   用户第二天才发现「昨天有几个没签上」。
         *   现在改成 30 分钟 —— 足够让微博的限流状态恢复，又不至于把当天剩下的一次性放弃。
         */
        private const val PAUSE_DURATION_MS = 30 * 60 * 1000L

        /**
         * 「有超话没签完」（PARTIAL）时的补签间隔。
         *
         * 它跟风控暂停是两回事：风控要等微博那边冷静下来（30 分钟），
         * 而「时间不够」纯粹是本地预算的问题 —— 只要再给一轮就能签上，
         * 所以间隔短得多，10 分钟后再跑一遍。
         */
        private const val PARTIAL_RETRY_DELAY_MS = 10 * 60 * 1000L

        /**
         * 每批跑几个账号。
         *
         * ★ 为什么要有这个：微博的风控是**按账号**限流的，但同一台手机只有一个 IP。
         *   把 10 个账号在同一分钟里全部跑一遍，等于在同一个 IP 上瞬间发出几十次请求 ——
         *   这不是「10 个账号各自正常签到」，在微博眼里就是一次异常流量。
         *   分成小批、拉长时间，请求密度才能降下来。
         *
         * 取 3 的理由：单批耗时约 3~10 分钟（一个账号最多 10 分钟预算），
         * 20 分钟间隔下不会出现「上一批还没跑完下一批就来了」的重叠。
         */
        private const val BATCH_SIZE = 3

        /**
         * 两批之间的间隔（另加随机抖动，见 BATCH_GAP_JITTER_MS）。
         *
         * ★ v19.19：由 20 分钟拉长到 50 分钟。
         *
         *   批次是「降低单次突发密度」的手段，15 分钟一轮的节奏还是太整齐、太频繁。
         *   拉长到 50 分钟后，10 个账号会摊到 ~3 小时里慢慢跑完，
         *   更接近「一个人断断续续刷微博」的样子。
         */
        private const val BATCH_GAP_MS = 50 * 60 * 1000L

        /**
         * 批次间隔的随机抖动。
         *
         * ★ 固定间隔也是一种「机器特征」：每天都是 50 分 00 秒准时开始下一批，
         *   比随机间隔更容易被识别。加上 0~20 分钟抖动，行为更像人在用手机。
         *
         * ★ v19.19：抖动上限由 5 分钟放宽到 20 分钟，配合拉长后的 BATCH_GAP_MS。
         */
        private const val BATCH_GAP_JITTER_MS = 20 * 60 * 1000L

        /**
         * 同一批内，相邻两个账号之间的间隔。
         *
         * 分批解决了「批与批」的密度，但一批里的 3 个账号如果背靠背连着发，
         * 依然是 3 倍密度的突发流量，所以批内也要隔开。
         *
         * ★★ v19.19：由 8 秒拉长到 45 秒。
         *
         *   旧值 8~15 秒的后果，用户在 v19.18 上遇到了「需要人工处理：微博要求验证身份」。
         *   一个账号进 batch 之后要连着签十几个超话，账号之间的 8 秒根本不构成间隔 ——
         *   在微博眼里，同一台设备 IP 在几分钟内跑完几百次请求，这就是最典型的异常流量。
         */
        private const val ACCOUNT_GAP_MS = 45000L

        /** 账号间隔的抖动（同上，避免固定节奏）。45~105 秒，跨度足够大。 */
        private const val ACCOUNT_GAP_JITTER_MS = 60000L

        // 只在计划时间进入 90 分钟窗口后保持 CPU 清醒，避免整天持锁耗电。
        private const val STANDBY_WAKE_WINDOW_MS = 90 * 60 * 1000L
        private const val STANDBY_WAKE_GRACE_MS = 5 * 60 * 1000L

        private const val CHANNEL_ID = "weibo_task_agent"
        private const val NOTIFICATION_ID = 1001
    }
}
