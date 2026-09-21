package com.tjh.weibotask

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object Prefs {
    private const val FILE_NAME = "weibo_task_secure"

    /**
     * 加密存储不可用时的降级文件名。
     *
     * ★ 一旦降级，就**一直**用它。
     *   否则会出现「这次用普通存储登录了账号 → 下次 keystore 恢复正常、
     *   程序又去读加密文件 → 账号又丢一次」这种来回丢数据的情况，
     *   比一次丢干净难排查得多。所以降级状态要持久化，而不是只看当次能不能加密。
     */
    private const val FALLBACK_FILE_NAME = "weibo_task_fallback"
    private const val KEY_DEGRADED = "storage_degraded"
    private const val KEY_DEGRADED_REASON = "storage_degraded_reason"

    private lateinit var prefs: SharedPreferences

    /**
     * 存储初始化是否出过问题；空串表示一切正常。
     *
     * ★ 为什么必须记下来并显示：加密存储失败时 App 会退到普通存储继续跑，
     *   但用户**必须知道**这件事（账号可能已被重置、且数据不再加密）。
     *   首页横幅和诊断报告都会读它 —— 否则用户只会看到「账号莫名其妙没了」。
     */
    @Volatile
    var storageError: String = ""
        private set

    /**
     * ★ 这里必须**永不抛异常**。
     *
     *   `init()` 有十几个调用点（MainActivity / AgentService / 两个 Receiver /
     *   LocalScheduler 九处 / WeiboLoginActivity / WeiboAccount），**全都没有 try**。
     *   `EncryptedSharedPreferences.create()` 在「系统 keystore 异常、加密文件损坏、
     *   从备份恢复后对不上」时会抛异常 —— 真抛出去的表现就是**一打开就闪退**，
     *   界面上看不到任何原因，属于最难排查的一类故障。
     *
     *   所以这里分四步退让：正常 → 删坏文件重建 → 退到普通存储 → 至少能打开。
     *   最坏的结果是「账号要重新登录」，而不是「App 完全打不开」。
     */
    fun init(context: Context) {
        if (::prefs.isInitialized) return

        // ① 上次已经降级过 → 继续用同一个存储，保证数据前后一致
        val fallback = context.getSharedPreferences(FALLBACK_FILE_NAME, Context.MODE_PRIVATE)
        if (fallback.getBoolean(KEY_DEGRADED, false)) {
            storageError = fallback.getString(KEY_DEGRADED_REASON, "").orEmpty()
                .ifBlank { "加密存储不可用" }
            prefs = fallback
            return
        }

        // ② 正常路径
        var reason = ""
        val first = try {
            createEncrypted(context)
        } catch (error: Exception) {
            reason = error.javaClass.simpleName
            null
        }
        if (first != null) {
            prefs = first
            return
        }

        // ③ 第一次失败：最常见的原因是加密文件本身坏了（升级中断、数据被部分清理）。
        //    删掉重建通常就好了 —— 代价是账号要重新登录，但比「打不开」好得多。
        try {
            context.deleteSharedPreferences(FILE_NAME)
        } catch (_: Exception) {
        }
        val second = try {
            createEncrypted(context)
        } catch (error: Exception) {
            reason = reason + "；重建后仍失败：" + error.javaClass.simpleName
            null
        }
        if (second != null) {
            prefs = second
            return
        }

        // ④ 两次都失败（例如系统 keystore 整个不可用）→ 退到普通存储。
        //    代价是账号信息不再加密（但依然只存在本机），首页横幅会明确告诉用户。
        storageError = reason + "；已降级为普通存储"
        fallback.edit()
            .putBoolean(KEY_DEGRADED, true)
            .putString(KEY_DEGRADED_REASON, storageError)
            .apply()
        prefs = fallback
    }

    private fun createEncrypted(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    var cloudUrl: String
        get() = prefs.getString("cloud_url", "") ?: ""
        set(value) = prefs.edit().putString("cloud_url", value).apply()

    var deviceToken: String
        get() = prefs.getString("device_token", "") ?: ""
        set(value) = prefs.edit().putString("device_token", value).apply()

    var deviceId: String
        get() = prefs.getString("device_id", "") ?: ""
        set(value) = prefs.edit().putString("device_id", value).apply()

    val paired: Boolean
        get() = deviceToken.isNotBlank()

    var cookieHeader: String
        get() = prefs.getString("weibo_cookie", "") ?: ""
        set(value) = prefs.edit().putString("weibo_cookie", value).apply()

    /**
     * 多账号列表（整段 JSON 一起加密）。
     * 下面是 v16 及以前的单账号字段，保留它们只为了**一次性迁移**，
     * 迁移完成后所有读写都走 accountsJson。
     */
    var accountsJson: String
        get() = prefs.getString("accounts_json", "") ?: ""
        set(value) = prefs.edit().putString("accounts_json", value).apply()

    var weiboVerified: Boolean
        get() = prefs.getBoolean("weibo_verified", false)
        set(value) = prefs.edit().putBoolean("weibo_verified", value).apply()

    var lastLoginCheckAt: Long
        get() = prefs.getLong("last_login_check_at", 0L)
        set(value) = prefs.edit().putLong("last_login_check_at", value).apply()

    var lastLoginCheckMessage: String
        get() = prefs.getString("last_login_check_message", "") ?: ""
        set(value) = prefs.edit().putString("last_login_check_message", value).apply()

    var cookieExpiryAt: Long
        get() = prefs.getLong("cookie_expiry_at", 0L)
        set(value) = prefs.edit().putLong("cookie_expiry_at", value).apply()

    var riskScore: Int
        get() = prefs.getInt("risk_score", 0)
        set(value) = prefs.edit().putInt("risk_score", value.coerceIn(0, 100)).apply()

    var consecutiveFailures: Int
        get() = prefs.getInt("consecutive_failures", 0)
        set(value) = prefs.edit().putInt("consecutive_failures", value.coerceAtLeast(0)).apply()

    var scheduleEnabled: Boolean
        get() = prefs.getBoolean("schedule_enabled", false)
        set(value) = prefs.edit().putBoolean("schedule_enabled", value).apply()

    /**
     * 自动签到开关**最近一次变化**的时间。
     *
     * ★ 为什么必须记：诊断报告里出现「设定时间已保存、但开关是关的」这种组合时，
     *   完全无法判断到底是用户自己关的、还是程序某条路径把它关掉了 ——
     *   而这决定了接下来该修界面还是修逻辑，猜错就是白改一轮。
     *   有了这个时间戳，报告里能直接写出「什么时候被关的、关了多久」。
     */
    var scheduleEnabledChangedAt: Long
        get() = prefs.getLong("schedule_enabled_changed_at", 0L)
        set(value) = prefs.edit().putLong("schedule_enabled_changed_at", value).apply()

    /**
     * 自动签到开关**是被谁改的**（一句中文说明，直接显示给用户看）。
     *
     * ★ 所有改动开关的地方都必须经过 `MainActivity.setScheduleEnabled()` 写入原因，
     *   否则这个字段就是空的 —— 空值本身就是「有未知路径在改开关」的信号。
     */
    var scheduleEnabledChangedBy: String
        get() = prefs.getString("schedule_enabled_changed_by", "") ?: ""
        set(value) = prefs.edit().putString("schedule_enabled_changed_by", value).apply()

    var baseHour: Int
        get() = prefs.getInt("base_hour", 8)
        set(value) = prefs.edit().putInt("base_hour", value.coerceIn(0, 23)).apply()

    var baseMinute: Int
        get() = prefs.getInt("base_minute", 5)
        set(value) = prefs.edit().putInt("base_minute", value.coerceIn(0, 59)).apply()

    var startDate: String
        get() = prefs.getString("start_date", "") ?: ""
        set(value) = prefs.edit().putString("start_date", value).apply()

    var skipDate: String
        get() = prefs.getString("skip_date", "") ?: ""
        set(value) = prefs.edit().putString("skip_date", value).apply()

    /**
     * 风控 / 验证码等场景的「限时暂停」截止时间。
     * 旧实现遇到这些情况直接把 scheduleEnabled 置 false 并取消闹钟，
     * 等于一次偶发风控就永久废掉自动签到。改为暂停一段时间后自动重试。
     */
    var pausedUntil: Long
        get() = prefs.getLong("paused_until", 0L)
        set(value) = prefs.edit().putLong("paused_until", value).apply()

    var nextRunAt: Long
        get() = prefs.getLong("next_run_at", 0L)
        set(value) = prefs.edit().putLong("next_run_at", value).apply()

    /**
     * 闹钟**最近一次真正触发**的时间。
     *
     * ★ 为什么需要它：用户报「到点没执行」时，有两种完全不同的原因 ——
     *   (a) 闹钟根本没触发（被系统省电策略丢弃 / 应用被强制停止）；
     *   (b) 闹钟触发了，但后台启动前台服务被系统拒绝。
     *   以前这两种情况在界面上长得一模一样，只能靠猜。
     *   有了这个时间戳，「闹钟有没有响过」一眼可辨：
     *     首页显示「闹钟最近触发：XX:XX」→ 说明 (a) 不成立，问题在 (b) 或执行环节；
     *     这个时间停在昨天 → 闹钟压根没响，要去做后台白名单设置。
     */
    var lastAlarmAt: Long
        get() = prefs.getLong("last_alarm_at", 0L)
        set(value) = prefs.edit().putLong("last_alarm_at", value).apply()

    /**
     * 后台服务的心跳时间（维护循环每一轮刷新一次）。
     *
     * ★ 这是「到点会不会执行」的分水岭：
     *   服务活着 → 维护循环会发现「到点了」并直接执行，**根本不依赖闹钟**；
     *   服务死了 → 只能靠闹钟，而闹钟在国产 ROM 的省电策略下很容易被丢弃。
     *   以前首页完全看不出服务是死是活，用户只能反复猜「为什么没签」。
     */
    var serviceAliveAt: Long
        get() = prefs.getLong("service_alive_at", 0L)
        set(value) = prefs.edit().putLong("service_alive_at", value).apply()

    /**
     * 是否有账号正卡在微博的「请先验证身份」上。
     *
     * ★ 为什么单独用一个标记，而不是让界面去猜 `lastRunMessage` 里有没有「验证」两个字：
     *   这是**需要用户动手**的状态（去微博点一次验证），必须稳定地显示出来。
     *   靠字符串匹配来判断，微博改一次文案、或文案被 400 字截断，提示就消失了 ——
     *   用户又会回到「设了时间却没签，也不知道为什么」的状态。
     *
     * 置 true：某次签到收到验证拦截。
     * 置 false：某次签到正常完成（说明用户已经验证过了）。
     */
    var needsVerify: Boolean
        get() = prefs.getBoolean("needs_verify", false)
        set(value) = prefs.edit().putBoolean("needs_verify", value).apply()

    /** 最近一次成功登记的闹钟档位：alarm_clock / exact / inexact / failed。 */
    var lastAlarmMode: String
        get() = prefs.getString("last_alarm_mode", "") ?: ""
        set(value) = prefs.edit().putString("last_alarm_mode", value).apply()

    /** 闹钟登记失败或降级时的最后一个异常名，供首页诊断。 */
    var lastAlarmError: String
        get() = prefs.getString("last_alarm_error", "") ?: ""
        set(value) = prefs.edit().putString("last_alarm_error", value).apply()

    /** Receiver 已调用 startForegroundService 的时间；不等于服务已经真正启动。 */
    var lastAlarmAcceptedAt: Long
        get() = prefs.getLong("last_alarm_accepted_at", 0L)
        set(value) = prefs.edit().putLong("last_alarm_accepted_at", value).apply()

    /** AgentService.onStartCommand 最近一次真正执行的时间。 */
    var lastServiceStartAt: Long
        get() = prefs.getLong("last_service_start_at", 0L)
        set(value) = prefs.edit().putLong("last_service_start_at", value).apply()

    /** 等待阶段的短时唤醒保护截止时间；0 表示当前未保护。 */
    var standbyProtectedUntil: Long
        get() = prefs.getLong("standby_protected_until", 0L)
        set(value) = prefs.edit().putLong("standby_protected_until", value).apply()

    /**
     * 短时唤醒保护**最近一次真正申请成功**的时刻（只增不清，纯诊断用）。
     *
     * ★ 为什么不能只看 [standbyProtectedUntil]：它会被 `executeOnce` 开头和
     *   `releaseStandbyWakeLock()` 清零，所以报告里经常显示「无记录」。
     *   那个值回答的是「**此刻**是否受保护」，而这个字段回答的是
     *   「**到底有没有保护过**」—— 排查「到点不执行」时需要的正是后者。
     */
    var standbyAcquiredAt: Long
        get() = prefs.getLong("standby_acquired_at", 0L)
        set(value) = prefs.edit().putLong("standby_acquired_at", value).apply()

    /** 唤醒锁申请失败的原因；空字符串表示没有失败过。 */
    var standbyError: String
        get() = prefs.getString("standby_error", "") ?: ""
        set(value) = prefs.edit().putString("standby_error", value).apply()

    /**
     * 看门狗闹钟的下一次触发时间（**与 nextRunAt 完全独立**）。
     *
     * ★ 单独存一份，绝不能用它覆盖 `nextRunAt` —— v19.4 就是因为看门狗写进了 nextRunAt，
     *   导致维护循环一直以为「下次执行还早」，任务永远不开始。
     */
    var lastWatchdogAt: Long
        get() = prefs.getLong("last_watchdog_at", 0L)
        set(value) = prefs.edit().putLong("last_watchdog_at", value).apply()

    var lastRunDate: String
        get() = prefs.getString("last_run_date", "") ?: ""
        set(value) = prefs.edit().putString("last_run_date", value).apply()

    var lastRunStatus: String
        get() = prefs.getString("last_run_status", "") ?: ""
        set(value) = prefs.edit().putString("last_run_status", value).apply()

    var lastRunAt: Long
        get() = prefs.getLong("last_run_at", 0L)
        set(value) = prefs.edit().putLong("last_run_at", value).apply()

    var lastRunMessage: String
        get() = prefs.getString("last_run_message", "") ?: ""
        set(value) = prefs.edit().putString("last_run_message", value).apply()

    /**
     * 当前是否正在执行签到。
     * 首页用它把「正在执行：…」和「上次执行：…」区分开 ——
     * 否则一轮签到跑一两分钟，界面一直显示旧结果，用户会以为没反应。
     */
    var running: Boolean
        get() = prefs.getBoolean("running", false)
        set(value) = prefs.edit().putBoolean("running", value).apply()

    // ------------------------------------------------------------------
    // 自动发帖（v0.19.15）
    //
    // ★ 发帖是**独立于签到**的一条链路：它比签到危险得多（会被微博判定为刷屏），
    //   所以配置、进度、开关全部单独存，不与签到的任何字段共用。
    // ------------------------------------------------------------------

    /**
     * 自动发帖开关。
     *
     * ★ 默认**关闭**：发帖是明确的高风险行为，只有用户主动填好超话链接和正文、
     *   并亲手打开开关之后才会执行。绝不能因为「装了新版」就默默开始发帖。
     */
    var postEnabled: Boolean
        get() = prefs.getBoolean("post_enabled", false)
        set(value) = prefs.edit().putBoolean("post_enabled", value).apply()

    /**
     * 用**哪一个**账号发帖（存账号 id；空串 = 自动取第一个已登录的账号）。
     *
     * ★★ 这是硬边界，不是可选项：发帖只走这一个账号。
     *   一旦允许「每个账号都自动发」，就变成「一个人看起来像很多人」——
     *   超话的发帖量 / 热度 / 排名是给别人看的（品牌方、榜单、别的粉丝、逛超话的路人），
     *   他们都会当真。所以这里存的是**单个 id**，不是列表。
     */
    var postAccountId: String
        get() = prefs.getString("post_account_id", "") ?: ""
        set(value) = prefs.edit().putString("post_account_id", value).apply()

    /**
     * 超话链接名单，一行一个（v0.20.0：从「超话名」改为「超话页链接」，
     *   新发帖接口需要从链接里解析 containerid）。
     *
     * ★ 用「用户自己填的名单」而不是「我的超话」列表，是有意的：
     *   列表要靠翻页接口去拉，多一次请求就多一分风控风险；
     *   而且用户往往只想发固定的几个超话，名单明确也更可控。
     */
    var postTopicsText: String
        get() = prefs.getString("post_topics_text", "") ?: ""
        set(value) = prefs.edit().putString("post_topics_text", value).apply()

    /**
     * 帖子正文。
     *
     * 多段之间用**单独一行 `---`** 分隔；没有分隔符时整段算一条，
     * 所有帖子共用这段文字（靠结尾的超话话题区分，避免被判重复内容）。
     */
    var postContentText: String
        get() = prefs.getString("post_content_text", "") ?: ""
        set(value) = prefs.edit().putString("post_content_text", value).apply()

    /** 每个账号每天发几条（默认 5）。 */
    var postPerAccount: Int
        get() = prefs.getInt("post_per_account", 5)
        set(value) = prefs.edit().putInt("post_per_account", value.coerceIn(1, 20)).apply()

    /**
     * 两条帖子之间的间隔（分钟）。
     *
     * ★ 默认值取 20 而不是更短：发帖是最容易触发风控的动作，
     *   宁可慢。用户说「间隔可以加大」时把它调大即可（上限 240 分钟）。
     */
    var postIntervalMinutes: Int
        get() = prefs.getInt("post_interval_minutes", 20)
        set(value) = prefs.edit().putInt("post_interval_minutes", value.coerceIn(5, 240)).apply()

    var postLastRunAt: Long
        get() = prefs.getLong("post_last_run_at", 0L)
        set(value) = prefs.edit().putLong("post_last_run_at", value).apply()

    var postLastStatus: String
        get() = prefs.getString("post_last_status", "") ?: ""
        set(value) = prefs.edit().putString("post_last_status", value).apply()

    /**
     * 最近一次发帖的结果明细。
     *
     * ★ 这里会原样带上微博返回的报文（ok=0 时的 msg）。
     *   发帖接口是本机无法验证的部分，出问题时这句原话是唯一的定位依据 ——
     *   绝不能把它简化成「发帖失败」四个字。
     */
    var postLastMessage: String
        get() = prefs.getString("post_last_message", "") ?: ""
        set(value) = prefs.edit().putString("post_last_message", value).apply()

    /**
     * 最近一次「签到结果判定」的原始依据（微博返回的报文字段）。
     *
     * ★★ 为什么值得专门存下来 —— 这是 v19.16 那个 bug 的直接教训：
     *   「报成功其实没签上」这类问题，靠读代码猜不出来（微博把提示文案放在
     *   data.msg / payload.msg / data.tipMessage / buttons[].name 等好几个地方，
     *   不同账号、不同超话命中的位置还不一样）。
     *   唯一的定位办法是**把原始报文摊开看**。
     *   所以每判一个超话，就把「取了哪几个字段、取到什么、最后判成什么」记一行，
     *   用户点「复制诊断报告」就能原样发过来 —— 一轮就能定位，不用来回猜。
     *
     * 只保留最近 5 条，避免加密存储被撑大（写入有性能代价）。
     */
    var checkinRawTrace: String
        get() = prefs.getString("checkin_raw_trace", "") ?: ""
        set(value) = prefs.edit().putString("checkin_raw_trace", value.take(2000)).apply()

    /** 追加一行判定依据（保留最近 5 行） */
    fun appendCheckinTrace(line: String) {
        try {
            val existing = checkinRawTrace.lineSequence().filter { it.isNotBlank() }.toList()
            val merged = (existing + line).takeLast(5)
            checkinRawTrace = merged.joinToString("\n")
        } catch (_: Exception) {
            // 诊断信息绝不能影响签到主流程
        }
    }

    val weiboLoggedIn: Boolean
        get() = weiboVerified && cookieHeader.contains("SUB=")
}
