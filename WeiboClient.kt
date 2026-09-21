package com.tjh.weibotask

import android.os.SystemClock
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.random.Random

data class WeiboTopic(
    val id: String,
    val name: String,
    val done: Boolean,
    val scheme: String
)

data class CheckinOutcome(
    val status: String,
    val riskScore: Int,
    val errorCode: String,
    val pauseReason: String,
    val consecutiveFailures: Int,
    val summary: JSONObject
)

/**
 * 一次发帖的结果（v0.19.15）。
 *
 * [status] 的取值与签到保持一致的含义，方便上层复用同一套中文翻译：
 *  - `SUCCESS`        发出去了，进度 +1
 *  - `USER_REQUIRED`  微博要求验证身份 —— 只能人工处理，**不推进进度**
 *  - `PAUSED`         被限流 / 明确拒绝（403、429、访问频繁）—— 暂停后**重试同一条**
 *  - `DUPLICATE`      微博判定「重复内容」—— 重试没意义，**跳过这条**换下一个超话
 *  - `FAILED`         其它失败（网络、未知返回）—— 累计失败次数，超上限才跳过
 *
 * ★ [message] 必须带上微博返回的**原话**。
 *   发帖接口是本机无法验证的部分，一旦微博改了接口或换了说法，
 *   这句原话是唯一的定位依据 —— 简化成「发帖失败」等于把线索丢掉。
 */
data class PostOutcome(
    val status: String,
    val errorCode: String,
    val message: String,
    val topic: String
)

/**
 * 风控类异常。
 *
 * [noRetry] 为 true 表示「微博已经把话说死了，重试没有意义」——
 * 典型是 HTTP 403 / 429：微博直接拒绝了这次请求。
 * 继续按 3 次重试去打，只会让这个账号陷得更深（v19.3 对验证页就是同样的处理）。
 */
class RiskException(message: String, val event: String, val noRetry: Boolean = false) : Exception(message)

object WeiboClient {
    /**
     * 判定依据的输出口。
     *
     * ★★ 为什么用回调而不是直接写 Prefs —— 这是一条**结构性约束**，不是风格问题：
     *   WeiboClient 是纯函数式的网络层，账号状态只能从传入的 [WeiboAccount] 读写。
     *   一旦它在任何地方直接读全局状态，多账号就会开始互相污染，
     *   v13「只签到一个」、v16「签 5 个就停」两个 bug 都是这么来的。
     *   诊断留痕同样不能破例 —— 所以由 AgentService 在启动时把出口挂上。
     */
    var traceSink: (String) -> Unit = {}

    private const val BASE = "https://m.weibo.cn"
    private const val FOLLOW_SUPER = "100803_-_followsuper"
    private const val LIST_REFERER = BASE + "/p/index?containerid=" + FOLLOW_SUPER
    private const val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"

    /**
     * 首屏游标。微博「我的超话」列表的 since_id 不是数字，而是一段 JSON 游标：
     *   {"manage":"","follow":"1022:10080xxxx","page":1}
     * 不带游标虽然通常也能拿到第一页，但条目数会被限制；
     * 拿不到任何超话时，用它再要一次首屏。
     */
    private const val FIRST_CURSOR = "{\"manage\":\"\",\"follow\":\"\",\"page\":1}"

    /**
     * 单个账号的时间预算 —— 按**待签数量**给，不再固定 5 分钟。
     *
     * ★ 固定 5 分钟 + 墙钟计时，是「账号 2 有 4 个超话被『超时未执行』跳过」的根因：
     *  1. 5 个超话的小账号和 34 个超话的大账号拿一样的时间，小账号也会被砍；
     *  2. 计时用 System.currentTimeMillis()（墙钟）。手机息屏、App 被系统冻结时墙钟照走，
     *     等 App 被唤醒，预算已经被「睡」掉了 —— 于是第一个超话刚签完就判定超时，
     *     剩下的全被跳过，而且状态还报成成功。
     *
     * 现在改成：按待签数量给，并用 uptimeMillis() 计时（不含深度睡眠）。
     *
     * ★★★ v19.19 必须同步调大 —— 这是「改了间隔不改预算」的连锁陷阱：
     *
     *   旧系数是「每个超话 25 秒余量」，那是配着 1.5~3 秒的间隔算出来的。
     *   现在间隔拉到 20~45 秒（均值 32.5 秒），单个超话实际要 ~34.5 秒。
     *   如果预算还用 25 秒/个，后果是**每一个账号都会在签一半时被判超时**，
     *   剩下的超话全部走「超时未执行」这条路 ——
     *   用户会从「被风控拦住」变成「每天固定漏签几个」，后者更隐蔽、更难发现。
     *
     *   所以系数必须跟着间隔一起走：**每个超话 45 秒**（32.5 秒间隔 + 网络往返 +
     *   偶尔的失败重试余量）。上限由 10 分钟提到 40 分钟，配合下面的硬停。
     *
     *   ★ 教训：**凡是「等待时长」被调大，所有用「数量 × 时间」算出来的预算都要重算一遍。**
     *     预算不会自己跟着变，它只会安静地变得不够用。
     */
    private fun accountBudgetMs(pendingCount: Int): Long =
        (pendingCount * 45_000L + 120_000L).coerceIn(3 * 60 * 1000L, 40 * 60 * 1000L)

    /**
     * 墙钟兜底：预算按「清醒时间」算，但万一被系统反复冻结，也要有个绝对上限。
     *
     * ★ v19.19：由 20 分钟提到 60 分钟。
     *   30 个超话按 34.5 秒算需要 18.2 分钟，34 个需要 20.6 分钟 —— 旧值刚好卡在边界，
     *   超话多的账号会被硬停砍掉尾巴。60 分钟留足余量，仍然是个有效上限。
     */
    private const val HARD_STOP_MS = 60 * 60 * 1000L

    /**
     * 补签/续跑轮的独立预算 —— 不跟主循环抢同一个 deadline。
     *
     * ★ v19.19：由 3 分钟提到 25 分钟。
     *   改后每个超话要 ~34.5 秒，旧的 3 分钟只够签 5 个 —— 而 [RETRY_MAX] 是 12。
     *   预算跟不上上限，等于上限写了也没用：超话永远补不完。
     *   25 分钟可覆盖全部 12 个（12 × 34.5s ≈ 7 分钟），留了充足余量。
     */
    private const val RETRY_BUDGET_MS = 25 * 60 * 1000L

    /**
     * 补签/续跑轮的墙钟兜底。
     *
     * ★ v19.19：由 8 分钟提到 40 分钟（必须 ≥ RETRY_BUDGET_MS，否则硬停先生效，
     *   预算就形同虚设）。
     */
    private const val RETRY_HARD_STOP_MS = 40 * 60 * 1000L

    /** 补签/续跑一轮最多处理多少个超话 */
    private const val RETRY_MAX = 12

    /** 设备实际清醒的时间。不含深度睡眠 —— 用它计时，手机睡觉不会吃掉预算。 */
    private fun uptime(): Long = SystemClock.uptimeMillis()

    /** 拉取超话列表的单独预算，避免翻页卡住把整轮时间吃光 */
    private const val LIST_BUDGET_MS = 60 * 1000L

    /**
     * 同一个账号，**相邻两个超话**之间的签到间隔。
     *
     * ★★★ v19.19 新增 —— 这是「微博要求验证身份」最主要的触发点。
     *
     *   旧实现在两个地方都是「整数 1500 + 随机 0~1500 的 delay」，
     *   也就是 1.5~3 秒。这个值是把签到当成了「本地批量操作」在写：
     *   反正本地发个请求只要几十毫秒，那就凑合隔一两秒吧。
     *
     * ★ 这里刻意不写出旧调用形式（写成中文描述）：
     *   检查脚本有一条断言是「旧的秒级 delay 不得出现在源码里」，
     *   如果注释里照抄那个调用，断言会因为注释而误判 —— 变成一条永远失效的假断言。
     *   （同理：凡是「某片段不得出现」这类断言，都要注意别让注释撞上去。）
     *
     *   但它面对的是微博的风控。一个账号有 N 个超话，1.5~3 秒一个，
     *   意味着**半分钟内连续 N 次写操作**。任何真人都不可能这样使用微博 ——
     *   真人会滑一滑、看一眼、划走再回来，超话之间至少隔十几秒。
     *
     *   所以 1.5~3 秒这个节奏本身就是一个强特征。它不是「有点快」，
     *   它是「一眼机器」。这解释了用户的现象：第一天没被拦（特征还没攒够），
     *   连续跑几天之后就开始要求验证身份。
     *
     *   为什么取 20 秒：留出足够的间隔感，同时不至于让一个账号的耗时失控。
     *   10 个超话 ≈ 4~7 分钟；30 个超话 ≈ 11~22 分钟（在 20 分钟硬停之内）。
     */
    private const val TOPIC_GAP_MS = 20000L

    /**
     * 超话间隔的随机抖动。
     *
     * ★ 跨度取 25 秒（20~45 秒），比基值本身还大 —— 这是刻意的：
     *   如果抖动只有几秒，那每天的节奏依然是「一个几乎固定的节拍」，
     *   只是这个节拍从 2 秒挪到了 21 秒而已，特征照样明显。
     *   抖动跨度大于基值，整条时间线才真的没有可被拟合的周期性。
     */
    private const val TOPIC_GAP_JITTER_MS = 25000L

    fun fetchConfig(account: WeiboAccount): JSONObject {
        val json = httpJson(BASE + "/api/config", BASE + "/", account)
        val data = json.optJSONObject("data") ?: return JSONObject()
        // 结构异常（风控页 / 错误页 / 空响应）时不要据此判定登录失效，
        // 否则一次异常响应就会被上层当成「掉线」，进而关掉自动签到
        if (!data.has("login")) return data
        if (!data.optBoolean("login")) account.verified = false
        return data
    }

    /**
     * 对**一个**账号执行一轮签到。
     *
     * 多账号的关键：cookie、风控分、连续失败计数全部来自传入的 [account]，
     * 绝不读全局状态 —— 微博的限流是按账号算的，共享计数会让一个号拖垮所有号。
     *
     * @param onProgress 进度回调。每开始处理一个超话就回调一次，
     *   上层用它刷新通知栏和首页状态 —— 旧版本整轮只显示一句
     *   「正在执行微博签到」，一两分钟不变，用户就会以为卡死了。
     */
    suspend fun runCheckin(account: WeiboAccount, onProgress: (String) -> Unit = {}): CheckinOutcome {
        // 墙钟兜底：时间预算用「清醒时间」算（见 accountBudgetMs），
        // 但万一被系统反复冻结，也要有个绝对上限，不能让一轮永远跑不完。
        val hardStop = System.currentTimeMillis() + HARD_STOP_MS
        // ★ 第一个请求就可能撞上验证拦截（微博会把 /api/config 也拦掉）。
        //   这里必须自己接住并翻译成「需要验证」，否则异常会一路冒到 AgentService，
        //   用户看到的只是一句「执行失败：微博要求验证身份」—— 不知道要去哪点验证。
        val config = try {
            fetchConfig(account)
        } catch (error: RiskException) {
            return if (error.event == "CAPTCHA") {
                CheckinOutcome(
                    "USER_REQUIRED", addRisk(account, 50), "CAPTCHA",
                    "微博要求验证身份，请点首页「去微博完成验证」",
                    account.consecutiveFailures, JSONObject()
                )
            } else {
                CheckinOutcome(
                    "RETRY", account.riskScore, error.event,
                    error.message ?: "请求异常", account.consecutiveFailures, JSONObject()
                )
            }
        }
        if (!config.has("login")) {
            // 拿不到登录态：网络抖动或微博返回结构异常，不能误判成掉线
            return CheckinOutcome(
                "RETRY", account.riskScore, "CONFIG_UNKNOWN",
                "无法获取微博登录态", account.consecutiveFailures, JSONObject()
            )
        }
        if (!config.optBoolean("login")) {
            account.verified = false
            val risk = addRisk(account, 40)
            return CheckinOutcome("USER_REQUIRED", risk, "LOGIN_ANOMALY", "微博登录已失效", account.consecutiveFailures, JSONObject())
        }

        // 不再每轮刷新 st：checkinTopic() 内部只在真的报验签错误时才去取新的
        val st = config.optString("st")

        // ★ 每一轮都从「干净」的状态开始。
        //   旧代码直接继承上一轮的 consecutiveFailures，只要上一轮停在 3，
        //   本轮第一个超话一失败就立刻 >=3 直接收工 —— 表现出来就是「只签到一个」。
        account.consecutiveFailures = 0
        if (account.riskScore > 0) account.riskScore = account.riskScore / 2

        report(onProgress, "正在读取超话列表…")
        // 被判定为「未关注」而跳过的超话名。记下来写进明细 ——
        // 用户报「取消关注了还在列表里」时，这一行能立刻分清两种情况：
        //   (a) 它出现在这里 → 程序已正确排除（说明修复生效）；
        //   (b) 它不在这里、却出现在「共 N 个超话」里 → 微博仍在返回「签到」按钮，
        //       那是微博服务端的数据没刷新，不是程序的问题。
        val excluded = mutableListOf<String>()
        val topics = fetchTopics(account, excluded)
        val pending = topics.filter { !it.done && it.scheme.isNotBlank() }
        val summary = JSONObject()
            .put("total", topics.size)
            .put("selected", pending.size)
            .put("success", 0)
            .put("already", 0)
            .put("failed", 0)
            .put("retried", 0)
            .put("skipped", topics.size - pending.size)
            // ★ 微博回「请先加入超话」的数量 —— 这些超话已经取消关注了。
            //   单列出来，既不混进「失败」，也不混进「跳过」，否则数字对不上。
            .put("unfollowed", 0)
            // ★ 单独记一份「微博列表里本来就标着已签到」的数量。
            //   旧版只报「共 30 个超话，待签 3」，用户看到 30 和 3 对不上，
            //   第一反应是「漏签了 27 个」—— 其实那 27 个是微博自己标了今日已签，
            //   程序故意不去重复请求。这个数字必须显式报出来，否则永远是个疑点。
            .put("doneInList", topics.size - pending.size)
            // ★「本轮没来得及签」的数量。它跟「微博已标已签」是两回事：
            //   前者是程序的问题（必须补上），后者是本来就不用签（不该补）。
            //   旧实现把两者混在 skipped 里，界面上完全看不出来有超话被漏掉。
            .put("unfinished", 0)
            // ★ 被判定为「未关注」而跳过的超话名（最多记 6 个，避免明细爆炸）。
            //   用户报「取消关注了还在执行列表里」时，靠它区分「程序没排除」和「微博没刷新」。
            .put("excluded", excluded.take(6).joinToString("、"))
            .put("excludedCount", excluded.size)
            .put("detail", "")

        if (pending.isEmpty()) {
            account.consecutiveFailures = 0
            return CheckinOutcome("SUCCESS", account.riskScore, "", "", 0, summary)
        }

        report(onProgress, "共 " + pending.size + " 个超话待签，开始执行…")

        // ★ 预算要等拿到「待签数量」之后才算：小账号不该被大账号的固定预算拖累
        val deadline = uptime() + accountBudgetMs(pending.size)

        var consecutive = 0
        // 只统计「真正的风控信号」的连续次数，普通失败不计入（详见下方失败分支的注释）
        var anomalyStreak = 0
        val failedTopics = mutableListOf<WeiboTopic>()
        // 本轮没来得及尝试的超话。不能直接丢掉 —— 交给下面的补签/续跑轮。
        val unfinishedTopics = mutableListOf<WeiboTopic>()
        for ((index, topic) in pending.withIndex()) {
            // ★ 时间不够时不能直接放弃：把剩余的超话交给补签/续跑轮（它有自己的预算）。
            //   旧实现只记一句「超时未执行」就 break，结果账号 2 那 4 个超话本轮彻底没人管，
            //   而且最终状态还是 SUCCESS —— 用户看到「上次结果：成功」以为全签上了。
            if (uptime() > deadline || System.currentTimeMillis() > hardStop) {
                val remainTopics = pending.drop(index)
                unfinishedTopics.addAll(remainTopics)
                summary.put("unfinished", summary.optInt("unfinished") + remainTopics.size)
                summary.put("skipped", summary.optInt("skipped") + remainTopics.size)
                appendDetail(summary, "剩余 " + remainTopics.size + " 个超话", "本轮时间不够，已转补签")
                break
            }

            report(onProgress, "正在签到 " + (index + 1) + "/" + pending.size + "：" + topic.name)

            var finalMessage = ""
            var succeeded = false
            var alreadySigned = false
            // 微博回「请先加入超话」→ 这个超话已经取消关注了。不重试、不算失败。
            var unfollowedTopic = false
            // 本超话是否收到过「真正的风控信号」。
            // ★ 必须按**超话**计数，不能按「尝试次数」计 ——
            //   一个超话会重试 3 次，按次数算的话 3 个超话就能触发熔断，比原来还糟。
            var topicAnomaly = false

            for (attempt in 0..2) {
                try {
                    val result = checkinTopic(account, topic, st)
                    finalMessage = result.optString("message")
                    val state = result.optString("status")
                    if (state == "success" || state == "already") {
                        succeeded = true
                        alreadySigned = state == "already"
                        break
                    }
                    // ★ 已取消关注：重试没有意义，直接跳出这一项（省下 2 次请求）
                    if (state == "unfollowed") {
                        unfollowedTopic = true
                        break
                    }
                    val event = classifyRisk(finalMessage)
                    if (event == "CAPTCHA") {
                        // 验证码只能人工处理，立刻停下，但把已完成的统计带回去。
                        // ★ 必须立刻停：账号已经被判「行为异常」，继续发请求只会让它陷得更深。
                        appendDetail(summary, topic.name, "需要人工验证，已停止")
                        account.consecutiveFailures = consecutive
                        return CheckinOutcome(
                            "USER_REQUIRED", addRisk(account, 50), "CAPTCHA",
                            "微博要求验证身份，请点首页「去微博完成验证」",
                            consecutive, summary
                        )
                    }
                    // ★ 只有「请求异常 / 访问频繁 / 系统繁忙」这类**真正的风控信号**才算数。
                    //   普通的签到失败（链接失效、接口返回失败）一律不计 ——
                    //   否则几个本来就签不上的超话就能把整批任务拖停。
                    if (event == "REQUEST_ANOMALY") topicAnomaly = true
                } catch (error: RiskException) {
                    if (error.event == "CAPTCHA") {
                        appendDetail(summary, topic.name, "需要人工验证，已停止")
                        account.consecutiveFailures = consecutive
                        return CheckinOutcome(
                            "USER_REQUIRED", addRisk(account, 50), "CAPTCHA",
                            "微博要求验证身份，请点首页「去微博完成验证」",
                            consecutive, summary
                        )
                    }
                    if (error.event == "REQUEST_ANOMALY") topicAnomaly = true
                    finalMessage = error.message ?: "请求异常"
                    // ★ 微博明确拒绝（403/429）→ 立刻跳出重试。
                    //   重试的用意是「网络抖了一下再试一次」，而 403 是**服务端主动拒绝**，
                    //   再试两次不但注定失败，还等于在风控名单上多记两笔。
                    if (error.noRetry) break
                } catch (error: Exception) {
                    finalMessage = error.message ?: "签到失败"
                }

                if (attempt < 2) delay(1000L + Random.nextLong(1500L))
            }

            if (succeeded) {
                if (alreadySigned) {
                    summary.put("already", summary.optInt("already") + 1)
                    appendDetail(summary, topic.name, "已签")
                } else {
                    summary.put("success", summary.optInt("success") + 1)
                    appendDetail(summary, topic.name, "成功")
                }
                consecutive = 0
                anomalyStreak = 0
                account.consecutiveFailures = 0
            } else if (unfollowedTopic) {
                // ★ 已取消关注：不算失败、不进补签队列、不累计连续失败。
                //   它跟「签不上」是两回事 —— 报成失败会让用户以为签到出了问题，
                //   还会把失败计数推高（进而误判风控）。
                summary.put("unfollowed", summary.optInt("unfollowed") + 1)
                appendDetail(summary, topic.name, "已取消关注，跳过")
                consecutive = 0
                anomalyStreak = 0
                account.consecutiveFailures = 0
            } else {
                // ★ 这里原来有一条 `consecutive >= 5 → 整个任务 PAUSED` 的判定。
                //   后果很严重：只要连着 5 个超话没签上，剩下的**一个都不会再试**，
                //   而且 PAUSED 会把下次执行推到 6 小时后 —— 用户看到的就是
                //   「签到 5 个就停了，后面的全没了」。
                //   现在普通失败只记录、不熔断，保证每个超话都有机会被尝试。
                summary.put("failed", summary.optInt("failed") + 1)
                appendDetail(summary, topic.name, "失败(" + failureLabel(finalMessage) + ")")
                failedTopics.add(topic)
                consecutive += 1
                account.consecutiveFailures = consecutive

                if (topicAnomaly) {
                    // 连续 8 个超话都被微博明确回「请求异常」才熔断 ——
                    // 这时再硬刷下去只会把账号推进更重的风控
                    anomalyStreak += 1
                    val risk = addRisk(account, 8)
                    if (anomalyStreak >= 8 || risk >= 90) {
                        val remain = pending.size - index - 1
                        if (remain > 0) {
                            summary.put("skipped", summary.optInt("skipped") + remain)
                            appendDetail(summary, "剩余 " + remain + " 个超话", "风控跳过")
                        }
                        account.consecutiveFailures = 0
                        return CheckinOutcome(
                            "PAUSED", risk, "RISK_SCORE",
                            "微博连续提示请求异常 " + anomalyStreak + " 个超话" +
                                (if (remain > 0) "，剩余 " + remain + " 个已跳过" else ""),
                            0, summary
                        )
                    }
                } else {
                    // 普通失败（链接失效、接口报错等）不累加风控计数，也不该拖累后面的超话
                    anomalyStreak = 0
                }
            }

            if (index < pending.size - 1) {
                // ★ 这里原来每次都会额外调一次 fetchConfig() 去刷新 st ——
                //   4 个超话就白多 4 次网络请求，既慢又更容易触发风控。
                //   而 checkinTopic() 内部本来就会在真的报验签错误时按需刷新，
                //   所以这个调用是纯浪费，直接删掉。
                //
                // ★★★ v19.19：超话间隔由 1.5~3 秒拉长到 20~45 秒。
                //
                //   这是整个签到流程里**最像机器**的一处，也是用户遇到
                //   「需要人工处理：微博要求验证身份」的直接原因。
                //
                //   旧值 1.5~3 秒意味着：一个账号如果有十几个超话，
                //   半分钟之内就朝微博连发了十几次签到请求。真人做不到这件事 ——
                //   真人在超话之间会滑一滑、看一眼，间隔至少是十几秒到几分钟。
                //
                //   为什么用 20~45 秒这个跨度（而不是固定 30 秒）：
                //   固定间隔本身也是特征。25 秒的随机跨度让每次的节奏都不同，
                //   比「每天都是 30.0 秒」难识别得多。
                //
                //   代价：一个账号签 10 个超话，会从原来的 ~30 秒变成 ~5 分钟。
                //   这个代价是值得的 —— 慢一点没人看，被风控拦住才是真麻烦。
                delay(TOPIC_GAP_MS + Random.nextLong(TOPIC_GAP_JITTER_MS))
            }
        }

        // ★ 补签 + 续跑合成一轮：
        //   - failedTopics：主循环里尝试过但失败的超话（微博经常对密集请求回一次「请求异常」，
        //     隔十几秒重来往往就成了）
        //   - unfinishedTopics：主循环里**没来得及尝试**的超话（时间不够被中断）
        //   两者都必须处理，否则那 4 个超话就是真的漏签了。
        //   ★ 这一轮用**独立预算**，不再跟主循环共用一个 deadline ——
        //     旧实现的条件是 `System.currentTimeMillis() < deadline`，
        //     主循环把时间吃光之后这一轮根本进不来，等于没有兜底。
        //   只做一轮，不做无限重试，避免被判定成风控行为。
        val carryOver = failedTopics + unfinishedTopics
        if (carryOver.isNotEmpty()) {
            val retryDeadline = uptime() + RETRY_BUDGET_MS
            val retryHardStop = System.currentTimeMillis() + RETRY_HARD_STOP_MS
            // 冷却 8~15 秒：够散掉瞬时限流，又不至于让用户干等
            report(onProgress, "有 " + carryOver.size + " 个超话没完成，稍后自动补签…")
            delay(8000L + Random.nextLong(7000L))

            for ((retryIndex, topic) in carryOver.take(RETRY_MAX).withIndex()) {
                if (uptime() > retryDeadline || System.currentTimeMillis() > retryHardStop) break

                // 没尝试过的（续跑）之前既没算成功也没算失败，只是「没来得及」，
                // 所以它的计数要从 unfinished 里挪走，而不是从 failed 里减。
                val wasAttempted = !unfinishedTopics.contains(topic)
                report(
                    onProgress,
                    (if (wasAttempted) "补签 " else "续签 ") +
                        (retryIndex + 1) + "/" + carryOver.size + "：" + topic.name
                )

                var retryMessage = ""
                var retryState = ""

                for (attempt in 0..1) {
                    try {
                        val result = checkinTopic(account, topic, st)
                        retryMessage = result.optString("message")
                        retryState = result.optString("status")
                        if (retryState == "success" || retryState == "already") break
                    } catch (error: RiskException) {
                        if (error.event == "CAPTCHA") {
                            // 验证码只能人工处理，立刻停下
                            appendDetail(summary, topic.name, "需要人工验证，已停止")
                            account.consecutiveFailures = 0
                            return CheckinOutcome(
                                "USER_REQUIRED", addRisk(account, 50), "CAPTCHA",
                                "微博要求验证身份，请点首页「去微博完成验证」",
                                0, summary
                            )
                        }
                        retryMessage = error.message ?: "请求异常"
                        // 同上：403/429 是服务端明确拒绝，补签轮也没必要再打第二次
                        if (error.noRetry) break
                    } catch (error: Exception) {
                        retryMessage = error.message ?: "签到失败"
                    }
                    if (attempt < 1) delay(1000L + Random.nextLong(1500L))
                }

                if (retryState == "success" || retryState == "already") {
                    // 把之前记的「失败」改成「重试成功 / 重试已签」，并把计数挪过来
                    if (wasAttempted) {
                        summary.put("failed", (summary.optInt("failed") - 1).coerceAtLeast(0))
                    } else {
                        summary.put("unfinished", (summary.optInt("unfinished") - 1).coerceAtLeast(0))
                    }
                    summary.put("retried", summary.optInt("retried") + 1)
                    if (retryState == "already") {
                        summary.put("already", summary.optInt("already") + 1)
                        replaceDetail(summary, topic.name, if (wasAttempted) "重试已签" else "补签已签")
                    } else {
                        summary.put("success", summary.optInt("success") + 1)
                        replaceDetail(summary, topic.name, if (wasAttempted) "重试成功" else "补签成功")
                    }
                    account.consecutiveFailures = 0
                } else {
                    if (wasAttempted) {
                        replaceDetail(summary, topic.name, "重试仍失败")
                    } else {
                        // 续跑也失败了：这时才正式把它算作失败（并写清原因）
                        summary.put("unfinished", (summary.optInt("unfinished") - 1).coerceAtLeast(0))
                        summary.put("failed", summary.optInt("failed") + 1)
                        replaceDetail(summary, topic.name, "失败(" + failureLabel(retryMessage) + ")")
                    }
                    account.consecutiveFailures = summary.optInt("failed")
                    if (classifyRisk(retryMessage) == "CAPTCHA") {
                        account.consecutiveFailures = 0
                        return CheckinOutcome(
                            "USER_REQUIRED", addRisk(account, 50), "CAPTCHA",
                            "微博要求验证身份，请点首页「去微博完成验证」",
                            0, summary
                        )
                    }
                }

                // ★ v19.19：补签轮的间隔同样由 1.5~3 秒拉长到 20~45 秒。
                //   补签是「被微博拒绝之后再来一次」，如果还是密集重试，
                //   等于在风控模型面前连续确认「这个账号确实在自动刷」。
                delay(TOPIC_GAP_MS + Random.nextLong(TOPIC_GAP_JITTER_MS))
            }
        }

        // ★ 还有没跑完的超话时，不能报「成功」。
        //   旧实现无论跳过多少都返回 SUCCESS，首页显示「上次结果：SUCCESS」，
        //   用户就以为全签上了 —— 这正是「看起来成功其实没签」的坑。
        val unfinishedLeft = summary.optInt("unfinished")
        if (unfinishedLeft > 0) {
            return CheckinOutcome(
                "PARTIAL", account.riskScore, "TIME_UP",
                "还有 " + unfinishedLeft + " 个超话没签完（时间不够），下次会自动重试",
                account.consecutiveFailures, summary
            )
        }

        return CheckinOutcome("SUCCESS", account.riskScore, "", "", account.consecutiveFailures, summary)
    }

    /** 进度回调：任何异常都不能影响签到主流程 */
    private fun report(onProgress: (String) -> Unit, text: String) {
        try {
            onProgress(text)
        } catch (_: Exception) {
        }
    }

    /**
     * 拉取「我的超话」全量列表。
     *
     * 旧实现的两个问题：
     *  1. 只在首屏没有条目时才不管游标；游标推进判断过严，容易只拿到第一页；
     *  2. 解析依赖固定的 card_group/buttons 层级，微博一旦改结构就静默少拿。
     * 现在按游标翻页，并且用「连续两页没有新增」作为终止条件。
     *
     * @param excluded 出参：被判定为「未关注」而跳过的超话名（供排查用）
     */
    private suspend fun fetchTopics(
        account: WeiboAccount,
        excluded: MutableList<String>
    ): List<WeiboTopic> {
        val output = mutableListOf<WeiboTopic>()
        val seen = mutableSetOf<String>()
        var cursor = ""
        var triedFirstCursor = false
        var emptyStreak = 0

        // 翻页也要有预算，避免接口异常时把整轮时间耗在拉列表上
        val listDeadline = System.currentTimeMillis() + LIST_BUDGET_MS

        var page = 0
        while (page < 50) {
            page++
            if (System.currentTimeMillis() > listDeadline) break

            val url = StringBuilder(BASE + "/api/container/getIndex?containerid=" + FOLLOW_SUPER)
            if (cursor.isNotBlank()) url.append("&since_id=").append(URLEncoder.encode(cursor, "UTF-8"))

            val payload = try {
                httpJson(url.toString(), LIST_REFERER, account)
            } catch (_: Exception) {
                null
            } ?: break
            val data = payload.optJSONObject("data") ?: break
            val cards = data.optJSONArray("cards") ?: JSONArray()

            val before = output.size
            collectTopics(cards, output, seen, excluded)
            val added = output.size - before

            // 首屏一条都没解析出来：换成官方首屏游标再试一次（只试一次）
            if (output.isEmpty() && cursor.isBlank() && !triedFirstCursor) {
                triedFirstCursor = true
                cursor = FIRST_CURSOR
                continue
            }

            val next = data.optJSONObject("cardlistInfo")?.optString("since_id").orEmpty()
            if (next.isBlank() || next == cursor) break

            if (added == 0) {
                emptyStreak++
                if (emptyStreak >= 2) break
            } else {
                emptyStreak = 0
            }

            cursor = next
            delay(300L + Random.nextLong(400L))
        }
        return output
    }

    /**
     * 递归遍历整棵卡片树，收集**已关注**的超话。
     *
     * 兼容两种已知结构：
     *  - m.weibo.cn 网页版：cards[].card_group[].{title_sub, buttons:[{name:"签到", scheme}]}
     *  - 原生 App 接口：cards[].card_group[].{card_type:8, title_sub, scheme}
     *
     * ★ 这个函数原来会把「取消关注后仍留在推荐位 / 最近浏览里的超话」也收进来，
     *   根因是**兜底分支只看「有没有 title + scheme」，不看「是不是真的超话卡片」**。
     *   而 `title` 是分组容器节点也有的字段（「我的超话」「超话推荐」「查看更多」都是），
     *   再叠加「任意深度都收」，于是：
     *     1. 分组标题被当成一个「超话」，去签到当然签不了，一直挂在失败明细里；
     *     2. 取消关注的超话从「已关注」分组消失后，仍留在推荐位 —— 照旧被收进来执行。
     *   这同时解释了「用户说只关注了 4 个，程序却报 30 个」。
     *
     * 现在的判据只有一条：**卡片上必须真的有「签到 / 已签到」按钮**。
     *  - 未关注的超话，按钮是「关注」→ 不会有签到按钮 → 收不进来；
     *  - 分组容器节点根本没有 buttons 数组 → 收不进来。
     * 顺带把「未关注」的超话名字记进 [excluded]，出问题时能一眼看出是被排除了还是被微博误报。
     */
    private fun collectTopics(
        node: Any?,
        output: MutableList<WeiboTopic>,
        seen: MutableSet<String>,
        excluded: MutableList<String>,
        depth: Int = 0
    ) {
        if (depth > 12) return
        when (node) {
            is JSONArray -> {
                for (i in 0 until node.length()) collectTopics(node.opt(i), output, seen, excluded, depth + 1)
            }
            is JSONObject -> {
                val name = node.optString("title_sub").ifBlank { node.optString("title") }
                if (name.isNotBlank()) {
                    var scheme = ""
                    var done = false
                    // 卡片上有「关注」按钮 → 这个超话没关注
                    var unfollowed = false

                    val buttons = node.optJSONArray("buttons")
                    if (buttons != null) {
                        for (i in 0 until buttons.length()) {
                            val button = buttons.optJSONObject(i) ?: continue
                            val buttonName = button.optString("name").trim()
                            // 「已签到」也包含「签到」两个字，所以要先排除掉
                            if (buttonName.contains("签到") && !buttonName.contains("已签")) {
                                val candidate = button.optString("scheme")
                                if (candidate.isNotBlank()) scheme = candidate
                            }
                            if (buttonName.contains("已签") || buttonName.contains("明日再来")) done = true
                            // ★「已关注」也包含「关注」两个字，必须先排除掉
                            if (buttonName.contains("关注") && !buttonName.contains("已关注")) unfollowed = true
                        }
                    }

                    // 少数卡片把签到链接直接挂在卡片上（没有 buttons 数组）。
                    // ★ 这里必须再加一道「这是超话卡片」的校验：
                    //   容器 / 分组节点只有 title，没有 itemid / oid / desc1。
                    //   旧写法只要 scheme 里出现 container/button 就收 ——
                    //   于是「超话推荐」「查看更多」这类分组标题也被当成超话去签到了。
                    if (scheme.isBlank()) {
                        val direct = node.optString("scheme")
                        val looksLikeTopic = node.optString("itemid").isNotBlank() ||
                            node.optString("oid").isNotBlank() ||
                            node.optString("desc1").isNotBlank()
                        if (looksLikeTopic && (direct.contains("container/button") ||
                                direct.contains("checkin") || direct.contains("active_fcheckin"))) {
                            scheme = direct
                        }
                    }

                    if (scheme.isNotBlank() || done) {
                        val key = topicKey(node, name, scheme)
                        if (seen.add(key)) output.add(WeiboTopic(key, name, done, scheme))
                    } else if (unfollowed) {
                        // 是超话卡片、但按钮是「关注」→ 未关注，不该签。记下来供排查。
                        excluded.add(name)
                    }
                }

                // 继续往下走，覆盖 card_group / cards 以及其它任意嵌套
                val keys = node.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (key == "buttons") continue
                    val child = node.opt(key)
                    if (child is JSONObject || child is JSONArray) {
                        collectTopics(child, output, seen, excluded, depth + 1)
                    }
                }
            }
        }
    }

    /**
     * 去重键。
     *
     * ★ 这里是「只签到一个超话」的根因所在：
     *   旧代码用 node.optString("oid").ifBlank { node.optString("id", name) } 当键，
     *   而 m.weibo.cn 卡片上的 id/oid 往往是**整张卡片共用**的，不是每个超话一个。
     *   于是 seen.add(id) 只有第一个超话成功，后面的全被判成「重复」丢掉，
     *   pending 只剩 1 条 —— 表现就是「只签到一个」。
     *
     * 现在优先用签到链接里的 containerid（每个超话唯一），
     * 其次 itemid，最后才退回超话名。
     */
    private fun topicKey(node: JSONObject, name: String, scheme: String): String {
        val containerId = extractParam(scheme, "containerid")
        if (containerId.isNotBlank()) return containerId
        val itemId = node.optString("itemid").ifBlank { node.optString("oid") }
        if (itemId.isNotBlank()) return itemId
        return "name:" + name
    }

    private fun extractParam(url: String, key: String): String {
        val marker = key + "="
        val index = url.indexOf(marker)
        if (index < 0) return ""
        val rest = url.substring(index + marker.length)
        val end = rest.indexOfFirst { it == '&' || it == '#' }
        return (if (end < 0) rest else rest.substring(0, end)).trim()
    }

    private fun appendDetail(summary: JSONObject, name: String, result: String) {
        val line = name + "：" + result
        val current = summary.optString("detail")
        val merged = if (current.isBlank()) line else current + "；" + line
        summary.put("detail", if (merged.length > 400) merged.take(400) + "…" else merged)
    }

    /**
     * 把某个超话的结果**整行**改写成 to。
     * 补签成功后用它把「失败(原因)」就地改成「重试成功」，而不是再追加一行，
     * 这样列表里一个超话只会出现一次结果。
     *
     * 之所以按整行替换而不是按「失败」两字替换：v16 起失败原因会写进明细
     * （例如「超话C：失败(请求异常)」），只匹配「失败」会漏掉后面的括号内容。
     */
    private fun replaceDetail(summary: JSONObject, name: String, to: String) {
        val current = summary.optString("detail")
        if (current.isBlank()) {
            appendDetail(summary, name, to)
            return
        }
        val prefix = name + "："
        var replaced = false
        val lines = current.split("；").map { line ->
            if (!replaced && line.startsWith(prefix)) {
                replaced = true
                prefix + to
            } else {
                line
            }
        }
        if (replaced) {
            summary.put("detail", lines.joinToString("；"))
        } else {
            // 极端情况下（详情被 400 字截断）退化成追加，保证结果不丢
            appendDetail(summary, name, to)
        }
    }

    /**
     * 失败原因摘要。
     * 旧版明细只写「失败」两个字，事后完全看不出为什么失败 ——
     * 是链接失效、接口报错，还是被风控，全都无法区分。
     */
    private fun failureLabel(message: String): String {
        val clean = message.replace('\n', ' ').replace('；', ',').trim()
        if (clean.isBlank()) return "无返回"
        return if (clean.length > 16) clean.take(16) + "…" else clean
    }

    private fun checkinTopic(account: WeiboAccount, topic: WeiboTopic, st: String): JSONObject {
        val url = if (topic.scheme.startsWith("http")) topic.scheme else BASE + topic.scheme

        // ★ 参考实现（网页版接口）只用 Cookie 直接 GET，并不需要 st。
        //   旧代码无条件往签到链接后面拼 st=，一旦这个 st 过期就会返回验签失败，
        //   而下一轮又未必能刷到新 st，于是后面的超话全部签不动。
        //   现在先按不带 st 的方式请求，只有真的报验签错误时才补一次带 st 的重试。
        var payload = httpJson(url, LIST_REFERER, account)
        if (isStError(payload)) {
            val freshRaw = try {
                fetchConfig(account).optString("st")
            } catch (_: Exception) {
                ""
            }
            val fresh = freshRaw.ifBlank { st }
            if (fresh.isNotBlank()) {
                val withSt = url + (if (url.contains("?")) "&" else "?") + "st=" + URLEncoder.encode(fresh, "UTF-8")
                payload = httpJson(withSt, LIST_REFERER, account)
            }
        }

        val data = payload.optJSONObject("data") ?: JSONObject()
        // ★ 微博把提示文案放在三个不同的位置，且**不是每次都填 data.msg**。
        //   只读 data.msg 的话，一个「今日已签到」的响应可能取到空串 ——
        //   空串落进下面的分支就会被当成「签到成功」，于是明明早就签过了还报「新签 1」。
        //   这里把所有已知位置都翻一遍，最后再退到原始 JSON 全文匹配。
        val message = data.optString("msg")
            .ifBlank { data.optString("tipMessage") }
            .ifBlank { payload.optString("msg") }
            .ifBlank { payload.optString("message") }
            .ifBlank { data.optString("message") }
        val code = payload.optString("code").ifBlank { data.optString("code") }
        val ok = payload.optInt("ok")

        // ★★ 判据按「证据强度」排序，不再按原来的顺序 —— 这是「报成功其实没签上」的根因。
        //
        //   旧顺序：① 文案含「已签」 ② 文案含「未加入」 ③ ok==1 就算成功。
        //   问题出在 ③：只要微博回 ok=1 而**没有任何文案**，就直接判成功。
        //   而「今天已经签过了」的响应恰恰经常不带文案（只有 code 或只有 ok）——
        //   于是它掉进 ③，被算成一次「新签到」，界面显示「新签 1」，用户打开微博一看没签上。
        //
        //   现在：任何**否定性证据**（已签 / 未加入 / 已结束）优先于 ok==1。
        //   宁可把一次真签到误判成「已签」（用户看到「已签过」，去微博一看确实签上了，
        //   没有损失），也不能把「已签过」误报成「新签成功」（用户以为签了，实际没有）。

        // ★ 判定依据留痕：用户报「显示成功但没签上」时，这一行能直接看出
        //   微博到底把提示放在了哪个字段、程序据此判成了什么 —— 由 traceSink 送出去落盘。
        val trace = "[" + topic.name + "] ok=" + ok + " code=" + code +
            " msg=" + message.ifBlank { "(空)" } +
            " dataKeys=" + data.keys().asSequence().joinToString(",").take(80)

        // ⓪ ★★★ 风控优先于一切 —— 必须排在「已签 / 未关注 / 成功」**之前**。
        //
        //   真实案例（v0.19.17 的留痕，用户截图原文）：
        //     [张奕然] ok=1 code= msg=你最近的行为存在异常，请先验证身份后再进行操作。
        //              => 判成功（无文案，凭 ok/code）
        //   微博**明确在要求人工验证**，程序却报了「签到成功」——
        //   用户打开微博一看，一条都没签上。
        //
        //   根因：风控检测原来只在 httpJson 里做，而且条件是「响应**不是** JSON」。
        //   微博这次是**在 JSON 里**回的话术（dataKeys=scheme,msg,result,button），
        //   于是完全没被检测到，直接落到 ③ 被 `ok==1` 判成了成功。
        //
        //   现在把同一张词表接进判定链：命中即刻抛 CAPTCHA → 上层立刻停手。
        //   ★ 复用 detectVerification，不另写一份判断 —— 检测与分类**必须共用同一张词表**，
        //     否则迟早不一致（v19.3 的「验证页漏判」就是这么错的）。
        //   ★ 命中就抛异常、不再往下走：账号已被判「行为异常」，
        //     继续发请求只会让它陷得更深（v19.3 的原则：撞上验证 = 当轮 0 次额外请求）。
        val riskHint = detectVerification(message)
        if (riskHint.isNotBlank()) {
            trace(trace + " => 风控（命中「" + riskHint + "」）")
            throw RiskException(
                "微博要求验证身份（" + riskHint + "）：" + message,
                "CAPTCHA"
            )
        }

        // ① 文案里出现「今日已签到 / 明天再来 / 已结束」这类话 —— 最硬的证据
        if (isAlreadyMessage(message) || code == "382004") {
            trace(trace + " => 已签")
            return JSONObject().put("status", "already").put("message", message.ifBlank { "今日已签到" })
        }
        // ② 微博明确回「请先加入超话」这类话 → 这个超话已经取消关注了。
        //    它不是网络问题、不是风控、也不是「签不上」，重试一万次结果都一样。
        if (isUnfollowedMessage(message)) {
            trace(trace + " => 未关注")
            return JSONObject().put("status", "unfollowed").put("message", message.ifBlank { "请先加入超话" })
        }
        // ③ ★★ 只有在**确实没有任何文案**时，ok==1 才能兜底当成功。
        //
        //   这里必须真的检查 message.isBlank()。原注释声称「响应里完全没有文案 ——
        //   这时 ok==1 才能当成功」，但代码里**漏掉了这个条件**，
        //   于是「微博说了话、只是程序不认识」的情况全被判成了成功。
        //   漏掉的那个条件，就是 v0.19.17「报成功其实没签上」的**直接原因**。
        if (message.isBlank() && (ok == 1 || code == "100000" || code == "382010")) {
            trace(trace + " => 判成功（确实无文案，凭 ok/code 兜底）")
            return JSONObject().put("status", "success").put("message", "签到成功")
        }
        // ④ 兜底：所有位置都没文案时，去原始 JSON 全文里找一次「已签」。
        //    微博偶尔把提示塞在别的字段里（例如 data.buttons[].name = "已签到"），
        //    这时只有全文匹配能找到它。放在最后是因为全文匹配有误判风险，
        //    不该优先于精确字段 —— 但比「什么都没找到就报成功」安全得多。
        val raw = payload.toString()
        if (isAlreadyMessage(raw)) {
            trace(trace + " => 已签（全文匹配命中）")
            return JSONObject().put("status", "already").put("message", "今日已签到（原话：" + raw.take(120) + "）")
        }
        // ⑤ ★★ 走到这里，说明微博**说了话、但程序认不出来** —— 绝不能报成功。
        //
        //   原实现会掉回 ③ 报成功，这是最坏的一种错：用户以为签上了，实际没有，
        //   而且要等到自己打开微博才发现（可能已经漏签一整天）。
        //   现在报失败，并把**微博原话**带进明细 —— 下一轮照着原话补词表即可，不用再猜。
        trace(trace + " => 失败（微博有文案但无法识别）")
        return JSONObject().put("status", "failed")
            .put("message", message.ifBlank { "签到失败（微博未返回任何提示）" })
    }

    /** 把一行判定依据送出去；出口没挂上时静默丢弃，绝不影响签到主流程 */
    private fun trace(line: String) {
        try {
            traceSink(line)
        } catch (_: Exception) {
        }
    }

    /**
     * 微博说「这个超话今天已经签过了」的几种说法 —— **必须覆盖真实的文案**。
     *
     * ★ 为什么这张词表要单独抽出来：判定「已签」还是「新签」直接决定界面上的数字。
     *   漏掉一种说法，用户就会看到「新签 1」而实际一条都没签上 —— 这次的 bug 就是它。
     *   已知 / 可能出现的说法（签到接口 + 卡片按钮文案）：
     *     「今日已签到」「已经签到」「已签到，明天再来」「明日再来」
     *     「已签」「签到已完成」「今天已经签到过了」
     *     「该超话今日签到已结束」「签到已结束」
     */
    private fun isAlreadyMessage(message: String): Boolean {
        if (message.isBlank()) return false
        val keywords = listOf(
            "已签", "明日再来", "明天再来", "已经签到",
            "签到已结束", "签到结束", "已完成签到", "签到过了", "今日签到已"
        )
        for (keyword in keywords) {
            if (message.contains(keyword)) return true
        }
        return false
    }

    /**
     * 微博提示「这个超话你没加入」的几种说法。
     *
     * 为什么值得单独识别：用户取消关注某个超话后，如果微博那边数据还没刷新，
     * 列表接口仍会把它带回来。这时签到会被拒，而旧代码只能记一句「失败(请先加入超话)」——
     * 看起来像是签到出了故障，实际是「这个超话已经不用签了」。
     * 识别出来之后：不算失败、不重试、明细直接写「已取消关注，跳过」。
     */
    private fun isUnfollowedMessage(message: String): Boolean {
        if (message.isBlank()) return false
        return message.contains("请先加入") ||
            message.contains("未加入") ||
            message.contains("没有加入") ||
            message.contains("请先关注") ||
            message.contains("未关注") ||
            message.contains("不是超话成员")
    }

    private fun isStError(payload: JSONObject): Boolean {
        return payload.optString("errno") == "100015" ||
            payload.optString("msg").contains("验签") ||
            payload.optJSONObject("data")?.optString("msg")?.contains("验签") == true
    }

    private fun classifyRisk(message: String): String {
        val lower = message.lowercase()
        // ★ 用与拦截页检测**同一张词表**，避免两处不一致 ——
        //   旧实现这里是独立的一份判断，只认「验证码 / 安全验证 / captcha」，
        //   而微博真正用的文案是「请先验证身份」「行为存在异常」，于是全都漏判。
        if (detectVerification(message).isNotBlank() || lower.contains("captcha") ||
            lower.contains("风控")) {
            // 「访问频繁 / 系统繁忙 / 请求异常」属于限流，不是要人工验证的验证码。
            // 前者等一会儿就好，后者必须用户去点验证 —— 处理方式完全不同。
            val isThrottle = lower.contains("访问频繁") || lower.contains("请求异常") ||
                lower.contains("系统繁忙") || lower.contains("操作过于频繁") ||
                lower.contains("风控")
            return if (isThrottle) "REQUEST_ANOMALY" else "CAPTCHA"
        }
        return ""
    }

    private fun addRisk(account: WeiboAccount, delta: Int): Int {
        val value = (account.riskScore + delta).coerceIn(0, 100)
        account.riskScore = value
        return value
    }

    private fun httpJson(urlText: String, referer: String, account: WeiboAccount): JSONObject {
        val connection = URL(urlText).openConnection() as HttpURLConnection
        // ★ disconnect() 必须放在 finally 里 —— 这是「账号 2 卡了 5 分钟」最合理的解释。
        //   旧代码是顺序执行到 disconnect()，只要中间任何一步抛异常
        //   （读超时、连接超时、HTTP 非 2xx 走 errorStream、解析失败……）
        //   就**永远不会**执行到那一行，连接被一直挂在连接池里。
        //   前台服务是常驻的（维护循环一天都不退），一轮跑几十次请求，
        //   泄漏的连接越积越多，后面的请求就会莫名卡上几分钟。
        try {
            connection.requestMethod = "GET"
            // ★ 旧值是 20s/20s。移动网络下一次请求真卡住就是 40 秒，
            //   一个超话试 3 次 = 2 分钟，4 个超话就是 8 分钟 —— 用户看到的就是「卡死」。
            //   这些接口正常都在 1 秒内返回，8s/12s 已经非常宽松。
            connection.connectTimeout = 8000
            connection.readTimeout = 12000
            connection.setRequestProperty("Accept", "application/json, text/plain, */*")
            connection.setRequestProperty("User-Agent", UA)
            connection.setRequestProperty("Referer", referer)
            connection.setRequestProperty("X-Requested-With", "XMLHttpRequest")
            connection.setRequestProperty("MWeibo-Pwa", "1")
            if (account.cookie.isNotBlank()) connection.setRequestProperty("Cookie", account.cookie)

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            // 用 use{} 保证响应流一定被关掉，否则连接无法复用（也就会一直占着）
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            updateCookies(connection, account)

            // ★ 403 / 429 是微博**明确拒绝**这次请求，不是「网络抖了一下」。
            //   真实案例（v0.19.9 的测试报告）：某个超话连续 3 次都回 403，
            //   白白多打了 2 次请求 —— 而 403 恰恰说明「它不想让你打」。
            //   标成 noRetry，让上层的重试循环立刻跳出这一项。
            if (code == 403 || code == 429) {
                throw RiskException("微博请求异常 HTTP $code", "REQUEST_ANOMALY", noRetry = true)
            }
            if (code !in 200..299) throw IllegalStateException("微博 HTTP $code")

            if (text.isBlank()) return JSONObject()

            val trimmed = text.trimStart()
            val looksLikeJson = trimmed.startsWith("{") || trimmed.startsWith("[")

            if (!looksLikeJson) {
                // ★ 微博的「行为异常」拦截页是一张 **HTML 页面**，不是 JSON。
                //   截图上的原文是「请先验证身份」「你最近的行为存在异常」——
                //   旧代码只认「验证码」和「安全验证」两个词，**两个都匹配不上**，
                //   于是这张页面被当成普通解析失败，每个超话还要重试 3 次。
                //   30 个超话 = 90 次请求，而账号已经被风控了 —— 只会越陷越深。
                //
                //   ★ 只在「响应不是 JSON」时才做关键词匹配，是为了避免误判：
                //     超话列表里如果有名字带「验证」的超话，在 JSON 上做全文匹配会误命中，
                //     整批任务会被无辜停掉。
                val hint = detectVerification(text)
                throw RiskException(
                    if (hint.isNotBlank()) "微博要求验证身份（" + hint + "）"
                    else "微博返回了非 JSON 页面，疑似风控拦截",
                    if (hint.isNotBlank()) "CAPTCHA" else "REQUEST_ANOMALY"
                )
            }

            return try {
                JSONObject(text)
            } catch (error: Exception) {
                // 看着像 JSON 却解析不了（被截断 / 网关错误页）→ 计入熔断，不当成验证
                throw RiskException(
                    "微博返回内容无法解析（" + text.take(60).replace('\n', ' ') + "）",
                    "REQUEST_ANOMALY"
                )
            }
        } finally {
            try {
                connection.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * 检测微博的「请先验证身份」拦截页，返回命中的关键词（没命中返回空串）。
     *
     * ★ 这里的词表必须覆盖微博**实际用的文案**，不能凭想象写。
     *   已知的几种说法（都来自真实页面 / 接口返回）：
     *     「你最近的行为存在异常，请先验证身份后再进行操作」
     *     「请点击下方按钮完成验证」
     *     「请输入验证码」「安全验证」「访问频繁」「系统繁忙」
     *   漏掉任何一个，那一类拦截就会被当成普通失败 ——
     *   程序不但签不上，还会继续重试，把账号推得更深。
     */
    private fun detectVerification(text: String): String {
        if (text.isBlank()) return ""
        val keywords = listOf(
            "请先验证身份", "验证身份", "请先验证", "完成验证", "身份验证",
            "行为存在异常", "行为异常", "账号异常", "异常行为",
            "验证码", "安全验证", "滑动验证", "短信验证",
            "访问频繁", "系统繁忙", "操作过于频繁", "请求异常"
        )
        for (keyword in keywords) {
            if (text.contains(keyword)) return keyword
        }
        return ""
    }

    // ==================================================================
    // 超话发帖（v0.20.0）—— 发进超话社区，不再发普通微博
    //
    // ★★ 为什么整个换掉旧实现（v0.19.15 及以前）：
    //   旧版走 m.weibo.cn/api/statuses/update —— 那是**发普通微博**的接口，
    //   只是把 #超话名[超话]# 拼在正文末尾。帖子只会出现在个人主页/时间线里，
    //   不会出现在超话社区里 —— 用户要求「在超话里发帖」，差的正是这一步。
    //   已核实：m.weibo.cn 整个 H5 前端（composer/page 等全部 JS 包）里
    //   **没有任何**超话社区发帖接口 —— 这不是参数写错，是压根没有这条路。
    //
    //   超话社区内的发帖走 App 接口 api.weibo.cn/2/statuses/send：
    //     content=正文 & extparam=超话containerid & gsid=会话凭证
    //   其中 gsid 的值就是 m.weibo.cn 登录 Cookie 里的 SUB ——
    //   所以不需要用户重新登录、也不需要抓包，现有登录态直接可用。
    //
    // ★ 为什么发帖只发 1 条 / 一轮：发帖是比签到**高得多**的风险动作。
    //   一次连发 5 条 = 同一个账号在几分钟内连续 5 次发布行为，
    //   这是微博判定「机器刷屏」最典型的形态。所以由外层控制成
    //   「每轮只发一条，隔 N 分钟再来」，把行为拉成一条稀疏的时间线。
    // ==================================================================

    private const val APP_SEND_URL = "https://api.weibo.cn/2/statuses/send"

    /** App 接口的 UA：必须像微博客户端，不能沿用浏览器 UA */
    private const val APP_UA = "Weibo/10530 Android/14 (Xiaomi/2.20.0)"

    /**
     * 一个目标超话：从用户粘贴的超话链接里解析出来。
     *
     * @param containerId 超话 containerid，形如 100808 + 32 位十六进制
     * @param name 超话名（链接里带了就解析出来，仅用于显示）
     */
    data class SuperTopicRef(val containerId: String, val name: String) {
        val display: String
            get() = if (name.isNotBlank()) name else "超话…" + containerId.takeLast(10)
    }

    /**
     * 从用户粘贴的一行里解析超话。
     *
     * 支持的写法（核心是能找到 100808 开头的 containerid）：
     *   https://m.weibo.cn/p/index?containerid=100808…_-_superindex_-_超话名
     *   https://weibo.com/p/100808…/super_index
     *   https://weibo.com/page/100808…
     *   也可以只贴 containerid 本身
     *
     * @return 解析不出 containerid 时返回 null（上层据此报「链接不对」）
     */
    fun parseSuperTopicRef(entry: String): SuperTopicRef? {
        val clean = entry.trim()
        if (clean.isBlank()) return null
        val idMatch = Regex("100808[0-9a-zA-Z]{20,40}").find(clean) ?: return null
        val id = idMatch.value
        // 超话名：m.weibo.cn 的超话链接 containerid 里带 `_-_superindex_-_名字`；
        // 其它来源的链接没有名字，就只留 id（不影响发帖，只影响显示）。
        val name = Regex("[0-9a-zA-Z]{30,45}_-_superindex_-_([^&\\s]+)").find(clean)
            ?.groupValues?.get(1)
            ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() }
            .orEmpty().trim()
        return SuperTopicRef(id, name)
    }

    /** 从 Cookie 串里取一个值；找不到返回空串 */
    private fun cookieValue(cookieText: String, name: String): String {
        for (part in cookieText.split(";")) {
            val item = part.trim()
            val index = item.indexOf('=')
            if (index <= 0) continue
            if (item.substring(0, index).trim() == name) {
                return item.substring(index + 1).trim()
            }
        }
        return ""
    }

    /**
     * 对**一个**账号在 [topic] 这个超话社区里发**一条**帖子。
     *
     * ★★ v0.20.0：发帖从「发普通微博 + 话题标记」改为「发进超话社区」。
     *   正文原样发布，不再追加任何 #话题# 标记。
     */
    suspend fun runPost(
        account: WeiboAccount,
        topic: SuperTopicRef,
        body: String,
        onProgress: (String) -> Unit = {}
    ): PostOutcome {
        report(onProgress, "准备发帖到超话「" + topic.display + "」…")

        // ★ 第一个请求就可能撞上验证拦截（微博会连 /api/config 一起拦）。
        //   必须自己接住并翻译成「需要验证」，否则用户只看到一句英文报错，不知道去哪点。
        //   这一请求顺带用 Set-Cookie 刷新账号的 SUB（gsid 的来源），一举两得。
        val config = try {
            fetchConfig(account)
        } catch (error: RiskException) {
            return if (error.event == "CAPTCHA") {
                PostOutcome(
                    "USER_REQUIRED", "CAPTCHA",
                    "微博要求验证身份，请点首页「去微博完成验证」", topic.display
                )
            } else {
                PostOutcome("PAUSED", error.event, error.message ?: "请求异常", topic.display)
            }
        }
        if (!config.has("login")) {
            return PostOutcome("FAILED", "CONFIG_UNKNOWN", "无法获取微博登录态（网络或接口异常）", topic.display)
        }
        if (!config.optBoolean("login")) {
            account.verified = false
            return PostOutcome("USER_REQUIRED", "LOGIN_ANOMALY", "微博登录已失效，请重新登录", topic.display)
        }

        // ★ gsid 就是 m.weibo.cn 登录 Cookie 里的 SUB 值 —— App 接口拿它当会话凭证。
        //   fetchConfig 刚请求过、Cookie 已刷新，从里面取即可。
        val gsid = cookieValue(account.cookie, "SUB")
        if (gsid.isBlank()) {
            account.verified = false
            return PostOutcome("USER_REQUIRED", "LOGIN_ANOMALY", "登录凭证（SUB Cookie）缺失，请重新登录", topic.display)
        }

        report(onProgress, "正在超话「" + topic.display + "」内发帖…")

        return try {
            val result = sendToSuperTopic(account, topic, body.trim(), gsid)
            when (result.optString("status")) {
                "success" -> PostOutcome("SUCCESS", "", "已发到超话「" + topic.display + "」", topic.display)
                "login" -> {
                    account.verified = false
                    PostOutcome(
                        "USER_REQUIRED", "LOGIN_ANOMALY",
                        "登录已失效（App 接口返回：" + result.optString("message") + "），请重新登录",
                        topic.display
                    )
                }
                // 重复内容：重试没意义，跳过这条换下一个超话
                "duplicate" -> PostOutcome(
                    "DUPLICATE", "DUPLICATE_CONTENT",
                    "超话「" + topic.display + "」被微博判为重复内容，已跳过（原话：" +
                        result.optString("message").ifBlank { "无" } + "）", topic.display
                )
                else -> PostOutcome(
                    "FAILED", "POST_FAILED",
                    "发到超话「" + topic.display + "」失败，微博原话：" +
                        result.optString("message").ifBlank { "没返回原因" }, topic.display
                )
            }
        } catch (error: RiskException) {
            if (error.event == "CAPTCHA") {
                PostOutcome(
                    "USER_REQUIRED", "CAPTCHA",
                    "发「" + topic.display + "」时微博要求验证身份，请点首页「去微博完成验证」", topic.display
                )
            } else {
                // 403 / 429 / 访问频繁 —— 微博明确拒绝，暂停后重试同一条
                PostOutcome("PAUSED", error.event, "发「" + topic.display + "」被微博拒绝：" + (error.message ?: "请求异常"), topic.display)
            }
        } catch (error: Exception) {
            PostOutcome("FAILED", "POST_ERROR", "发「" + topic.display + "」出错：" + (error.message ?: "未知错误"), topic.display)
        }
    }

    /**
     * 调 App 接口把帖子发进超话社区。
     *
     * ★ 依据（对照多个开源实现 + 抓包资料验证过的参数集）：
     *   POST https://api.weibo.cn/2/statuses/send
     *     content   正文
     *     extparam  超话 containerid（100808…）
     *     gsid      会话凭证 —— 值就是 m.weibo.cn Cookie 里的 SUB
     *     c=android / from / s  客户端标识（c 必带，from/s 可为空）
     *   成功时返回体里带新帖的 id；失败时是 {"errno":…, "errmsg":"微博的原话"}。
     */
    private fun sendToSuperTopic(
        account: WeiboAccount,
        topic: SuperTopicRef,
        content: String,
        gsid: String
    ): JSONObject {
        val form = StringBuilder()
            .append("content=").append(URLEncoder.encode(content, "UTF-8"))
            .append("&extparam=").append(URLEncoder.encode(topic.containerId, "UTF-8"))
            .append("&c=android")
            .append("&from=")
            .append("&s=")
            .append("&gsid=").append(URLEncoder.encode(gsid, "UTF-8"))
        val payload = appPost(APP_SEND_URL, form.toString())

        // ★ 判定依据留痕（与签到同原则）：App 接口是本机无法验证的部分，
        //   一旦微博改了参数或说法，这行日志是唯一线索。
        trace(
            "[发帖:" + topic.display + "] errno=" + payload.opt("errno") +
                " errmsg=" + payload.optString("errmsg").ifBlank { "(空)" } +
                " keys=" + payload.keys().asSequence().joinToString(",").take(80)
        )

        // ① 成功：返回体里有新帖的 id，且没有 errno
        if (!payload.has("errno") && payload.optLong("id", -1L) > 0L) {
            return JSONObject().put("status", "success")
        }
        val message = payload.optString("errmsg")
            .ifBlank { payload.optString("msg") }
            .ifBlank { payload.optString("error") }
            .ifBlank { payload.optJSONObject("data")?.optString("msg").orEmpty() }

        // ② 风控优先于一切（与签到共用同一张词表、同一条原则：命中就停手）
        val hint = detectVerification(message)
        if (hint.isNotBlank()) {
            val event = classifyRisk(message).ifBlank { "REQUEST_ANOMALY" }
            throw RiskException("微博发帖被拦（" + hint + "）：" + message, event)
        }
        // ③ 登录态失效：gsid 过期/被顶掉，重试没有意义，必须重新登录
        val errno = payload.optString("errno").ifBlank { payload.optString("error_code") }
        if (errno == "-100" || errno == "21332" || errno == "21327" || errno == "21315" ||
            message.contains("登录") || message.contains("身份")
        ) {
            return JSONObject().put("status", "login")
                .put("message", message.ifBlank { "登录已失效（errno=" + errno + "）" })
        }
        // ④ 重复内容：重试一万次结果都一样
        if (isDuplicateMessage(message)) {
            return JSONObject().put("status", "duplicate").put("message", message)
        }
        // ⑤ 其它失败：带上原话，让用户和下一轮照着排查
        return JSONObject().put("status", "failed")
            .put("message", message.ifBlank { "errno=" + errno })
    }

    /**
     * 发一条到 App 接口（POST）。
     *
     * ★ 与 [httpPost] 的区别：api.weibo.cn 不吃 Cookie，认证全靠表单里的 gsid；
     *   User-Agent 也要用微博客户端的 —— 浏览器 UA 打 App 接口是明显的缝合特征。
     *   403/429 判不可重试、非 JSON 走风控词表，这些保护与 httpPost 保持一致。
     */
    private fun appPost(urlText: String, form: String): JSONObject {
        val connection = URL(urlText).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 8000
            connection.readTimeout = 12000
            connection.setRequestProperty("Accept", "application/json, text/plain, */*")
            connection.setRequestProperty("User-Agent", APP_UA)
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")

            val bytes = form.toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()

            // 403 / 429 = 微博**明确拒绝**，不是「网络抖了一下」（与 httpPost 同原则）
            if (code == 403 || code == 429) {
                throw RiskException("微博请求异常 HTTP $code", "REQUEST_ANOMALY", noRetry = true)
            }
            if (code !in 200..299) throw IllegalStateException("微博 HTTP $code")
            if (text.isBlank()) throw IllegalStateException("微博返回了空响应")

            val trimmed = text.trimStart()
            if (!(trimmed.startsWith("{") || trimmed.startsWith("["))) {
                val hint = detectVerification(text)
                throw RiskException(
                    if (hint.isNotBlank()) "微博要求验证身份（" + hint + "）"
                    else "微博返回了非 JSON 页面（" + text.take(60).replace('\n', ' ') + "）",
                    if (hint.isNotBlank()) "CAPTCHA" else "REQUEST_ANOMALY"
                )
            }
            return try {
                JSONObject(text)
            } catch (_: Exception) {
                throw RiskException(
                    "微博返回内容无法解析（" + text.take(60).replace('\n', ' ') + "）",
                    "REQUEST_ANOMALY"
                )
            }
        } finally {
            try {
                connection.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * 微博说「这条发过了」的几种说法。
     *
     * ★ 必须单独识别：重复内容重试一万次结果都一样，
     *   旧签到逻辑里「访问频繁」那种等待重试的处理方式在这里完全不适用。
     */
    private fun isDuplicateMessage(message: String): Boolean {
        if (message.isBlank()) return false
        return message.contains("重复") || message.contains("相同的内容") ||
            message.contains("已发布过") || message.contains("请勿发布") ||
            message.contains("内容相似")
    }

    /**
     * 把响应里的 Set-Cookie 合并回**这个账号**。
     * 多账号下这一步尤其关键：绝不能写到全局，否则 B 号的请求会污染 A 号的登录态。
     */
    private fun updateCookies(connection: HttpURLConnection, account: WeiboAccount) {
        val values = linkedMapOf<String, String>()
        account.cookie.split(";").forEach { part ->
            val item = part.trim()
            val index = item.indexOf('=')
            if (index > 0) values[item.substring(0, index).trim()] = item.substring(index + 1).trim()
        }

        for ((header, headerValues) in connection.headerFields) {
            if (header == null || !header.equals("Set-Cookie", ignoreCase = true)) continue
            for (raw in headerValues) {
                val pair = raw.substringBefore(';').trim()
                val index = pair.indexOf('=')
                if (index <= 0) continue
                val name = pair.substring(0, index).trim()
                val value = pair.substring(index + 1).trim()
                if (value.isNotEmpty()) values[name] = value
            }
        }

        if (values.isNotEmpty()) {
            account.cookie = values.entries.joinToString("; ") { "${it.key}=${it.value}" }
        }
        val alf = values["ALF"]?.toLongOrNull()
        if (alf != null && alf > 0L) account.expiryAt = alf * 1000L
    }
}
