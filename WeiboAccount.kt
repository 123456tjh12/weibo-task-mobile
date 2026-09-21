package com.tjh.weibotask

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 一个微博账号。
 *
 * ★ 为什么风控状态必须按账号存：
 *   微博的限流是**按账号**算的。A 号被限流不代表 B 号也被限流。
 *   如果几个号共用一份 riskScore / consecutiveFailures，
 *   一个号被风控就会把其它号的计数一起推高，导致全部签不上 ——
 *   这正是 v13「只签到一个」和 v16「签 5 个就停」那类 bug 的同一个根源：
 *   把本不该共享的状态共享了。
 */
class WeiboAccount {
    var id: String = ""
    var label: String = ""
    var cookie: String = ""
    var verified: Boolean = false
    var expiryAt: Long = 0L
    var lastCheckAt: Long = 0L
    var lastCheckMessage: String = ""
    var enabled: Boolean = true

    /** 该账号自己的风控分（0~100），不与其他账号共享 */
    var riskScore: Int = 0

    /** 该账号自己的连续失败计数 */
    var consecutiveFailures: Int = 0

    /**
     * 列表里的固定序号。
     *
     * ★ 为什么要单独存一个数字，而不是每次按位置现算：
     *   用户看到的是「账号 1 / 账号 2 / …… / 账号 8」。如果序号是按下标算的，
     *   那么删掉或停用中间某个账号（例如账号 6）之后，后面的账号会整体前移 ——
     *   账号 7 变成「账号 6」、账号 8 变成「账号 7」。
     *   而签到结果、诊断报告、通知栏里到处都在用 [label]，一错位就对不上号，
     *   用户报「哪个账号没签到」时会指到另一个账号上。
     *   → 序号一旦分配就跟着账号走（成员字段一起存盘），删号空出来的号不再复用。
     */
    var order: Int = 0

    /**
     * 微博昵称（登录后自动填）。
     *
     * ★ 它跟 [label] 是**两个东西**，必须分开存：
     *   昵称会变、会重复（同一个人几个小号可能重名），
     *   而用户在签到结果里认账号靠的是「账号 7」这个序号。
     *   旧实现直接 `label = nickname`，昵称一重名，结果列表里就出现两行一模一样的名字。
     */
    var nickname: String = ""

    /** 显示名：有昵称就带上，没有就只用序号 —— 序号永远在最前面，保证可辨认 */
    val displayName: String
        get() {
            val name = label.ifBlank { if (order > 0) "账号 " + order else "" }
            return if (name.isBlank()) nickname.ifBlank { "未命名账号" } else name
        }

    var lastRunAt: Long = 0L
    var lastRunStatus: String = ""
    var lastRunMessage: String = ""

    /**
     * 这个账号最近一次被「排进批次」的日期（yyyy-MM-dd）。
     *
     * ★ 账号多起来之后（10 个以上），同一时刻把所有账号都跑一遍，
     *   会在同一个 IP 上形成一次密集请求 —— 这是最容易触发风控的形态。
     *   所以改成**分批错峰**：每轮只跑一小批，跑过的账号记下日期，
     *   下一批 20 分钟后再来，几轮跑完。
     *   这个字段就是「今天轮到过它没有」的依据。
     */
    var lastRunDate: String = ""

    /**
     * 这个账号今天以「超话没签完」（PARTIAL）结束过几次，以及这个计数属于哪一天。
     *
     * ★ 为什么必须限次：
     *   分批之后，「今天还没轮到的账号」优先跑。如果一个账号**永远**签不完
     *   （某个超话链接失效、或微博那边就是不认），不设上限的话它会被
     *   每 20 分钟捞出来重跑一轮，一直到零点 —— 既签不上，又白白往微博送请求，
     *   而用户这次的诉求恰恰是「降低风险」。
     *   所以达到 [MAX_PARTIAL_RETRIES] 次之后当天不再碰它，等第二天。
     */
    var partialRetryDate: String = ""
    var partialRetryCount: Int = 0

    // ------------------------------------------------------------------
    // 发帖进度（v0.19.15）
    //
    // ★ 与签到进度**完全分开**：签到漏一轮只是少签一天，
    //   发帖如果计数串了，同一个账号可能在一天里被重复发同样的内容 ——
    //   那正是微博判定「恶意刷屏」的典型形态。
    // ------------------------------------------------------------------

    /** 这个发帖进度属于哪一天（yyyy-MM-dd） */
    var postDate: String = ""

    /** 今天已经发出去几条（也是下一条要用名单里的第几个超话） */
    var postIndex: Int = 0

    /**
     * 当前这一条连续失败了多少次。
     *
     * ★ 必须有上限：同一个超话如果永远发不上去（内容被拒、超话名写错），
     *   不限次就会每隔一个间隔重试一次、一直到零点 ——
     *   既发不出去，又白白往微博送请求。达到 [MAX_POST_FAILURES] 就跳过这条往下走。
     */
    var postFailCount: Int = 0

    var postStatus: String = ""
    var postMessage: String = ""

    /** 今天是否还需要发帖 */
    fun postPending(today: String, perAccount: Int): Boolean =
        postDate != today || postIndex < perAccount

    /**
     * 当天第一次用到时把进度清零。
     *
     * ★ 为什么不能只判断 `postDate != today`：跨天之后 postIndex 仍是昨天的数字，
     *   不重置的话新的一天一开始就被当成「已经发完了」。
     */
    fun rollPostDate(today: String) {
        if (postDate != today) {
            postDate = today
            postIndex = 0
            postFailCount = 0
        }
    }

    /** 是否处于「可以拿去签到」的状态 */
    val loggedIn: Boolean
        get() = verified && cookie.contains("SUB=")

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("label", label)
        .put("order", order)
        .put("nickname", nickname)
        .put("cookie", cookie)
        .put("verified", verified)
        .put("expiryAt", expiryAt)
        .put("lastCheckAt", lastCheckAt)
        .put("lastCheckMessage", lastCheckMessage)
        .put("enabled", enabled)
        .put("riskScore", riskScore)
        .put("consecutiveFailures", consecutiveFailures)
        .put("lastRunAt", lastRunAt)
        .put("lastRunStatus", lastRunStatus)
        .put("lastRunMessage", lastRunMessage)
        .put("lastRunDate", lastRunDate)
        .put("partialRetryDate", partialRetryDate)
        .put("partialRetryCount", partialRetryCount)
        .put("postDate", postDate)
        .put("postIndex", postIndex)
        .put("postFailCount", postFailCount)
        .put("postStatus", postStatus)
        .put("postMessage", postMessage)

    companion object {
        /**
         * 一个账号一天最多可以「以没签完结束」几次。
         *
         * 计数在每次结束时 +1（含第一次正常执行），所以 3 次 = 正常跑 1 次 + 补跑 2 次。
         * 到 3 之后当天不再碰它 —— 再跑也是同样的结果，只是白送请求给微博风控。
         */
        const val MAX_PARTIAL_RETRIES = 3

        /**
         * 同一条帖子最多连续失败几次就跳过。
         *
         * 3 次 = 第一次正常尝试 + 两个间隔后的重试。再往后基本可以确定
         * 是「这条本身发不出去」（超话名不对、内容被拒），继续重试只是白送请求。
         */
        const val MAX_POST_FAILURES = 3

        fun fromJson(source: JSONObject): WeiboAccount {
            val account = WeiboAccount()
            account.id = source.optString("id").ifBlank { UUID.randomUUID().toString() }
            account.label = source.optString("label")
            // 老数据没有 order → 0，由 AccountStore.load() 统一补号（从名字里解析）
            account.order = source.optInt("order")
            account.nickname = source.optString("nickname")
            account.cookie = source.optString("cookie")
            account.verified = source.optBoolean("verified")
            account.expiryAt = source.optLong("expiryAt")
            account.lastCheckAt = source.optLong("lastCheckAt")
            account.lastCheckMessage = source.optString("lastCheckMessage")
            // 默认启用：老数据里没有这个字段时不能让账号变成「停用」而静默不签到
            account.enabled = source.optBoolean("enabled", true)
            account.riskScore = source.optInt("riskScore")
            account.consecutiveFailures = source.optInt("consecutiveFailures")
            account.lastRunAt = source.optLong("lastRunAt")
            account.lastRunStatus = source.optString("lastRunStatus")
            account.lastRunMessage = source.optString("lastRunMessage")
            // 老数据没有这个字段 → 空串，等于「今天还没轮到」，会被排进第一批
            account.lastRunDate = source.optString("lastRunDate")
            account.partialRetryDate = source.optString("partialRetryDate")
            account.partialRetryCount = source.optInt("partialRetryCount")
            // 老数据没有这些字段 → 空串 / 0，等价于「今天还没发过」，会被排进第一批
            account.postDate = source.optString("postDate")
            account.postIndex = source.optInt("postIndex")
            account.postFailCount = source.optInt("postFailCount")
            account.postStatus = source.optString("postStatus")
            account.postMessage = source.optString("postMessage")
            return account
        }
    }
}

/**
 * 账号列表的唯一数据源。
 *
 * 存在 EncryptedSharedPreferences 里（整个 JSON 串一起加密），
 * 进程内用单例缓存，任何改动都要调 [save] 落盘。
 */
object AccountStore {
    private val accounts = mutableListOf<WeiboAccount>()
    private var loaded = false

    fun init(context: Context) {
        if (loaded) return
        Prefs.init(context)
        load()
        // ★ 老数据（v19.14 及以前）没有 order，可能还有两个账号都叫「账号 7」。
        //   补号也在 load 之后统一做一次，和上面的名字补齐是同一趟 ——
        //   分成两处写，迟早出现「补了名字没补号」的半截状态。
        if (normalizeOrdering()) save()
        loaded = true
    }

    /**
     * 补齐 / 修正账号序号，并让 [WeiboAccount.label] 与 [WeiboAccount.order] 一致。
     *
     * 规则：
     *  - 已经有 order 的账号：**保持不动**（这是「序号跟账号走」的关键，
     *    中途删号也不会让后面的账号集体改名）；
     *  - 老数据没有 order：先试着从名字「账号 N」里把 N 解析出来，
     *    这样升级上来的一刻，界面上显示的名字和以前**完全一样**，用户不会看到跳动；
     *  - 解析不出来（用户自己改过名）或号被占用了：分配当前最大号 +1；
     *  - 最后统一把 label 写成「账号 N」。
     *
     * 重复号的处理是必须的：旧实现的 [nextLabel] 是按 `accounts.size + 1` 算的，
     * 删掉中间某个账号后新加的账号会**撞上已有名字** —— 界面上出现两个「账号 7」，
     * 而签到结果只写 label，用户根本分不清是哪一行。
     *
     * @return 是否真的改动了什么（调用方据此决定要不要落盘）
     */
    private fun normalizeOrdering(): Boolean {
        if (accounts.isEmpty()) return false
        var changed = false

        // 第一趟：已经有序号的先占坑（顺带把重号记下来，等会儿重新分配）
        val used = mutableSetOf<Int>()
        for (account in accounts) {
            val current = account.order
            if (current > 0 && used.add(current)) {
                if (account.label != "账号 " + current) {
                    account.label = "账号 " + current
                    changed = true
                }
            } else if (current > 0) {
                // 撞号：先清掉，交给第二趟重新分配
                account.order = 0
                changed = true
            }
        }

        // 第二趟：给没号的账号补号
        var next = (used.maxOrNull() ?: 0) + 1
        for (account in accounts) {
            if (account.order > 0) continue
            val parsed = parseOrderFromLabel(account.label)
            // 只有「名字里的号没被别人占用」时才沿用 —— 否则会出现重号
            val assigned = if (parsed > 0 && used.add(parsed)) parsed else {
                while (!used.add(next)) next++
                next
            }
            account.order = assigned
            account.label = "账号 " + assigned
            changed = true
        }
        return changed
    }

    /** 从「账号 7」这类名字里解析出 7；解析不出来返回 0 */
    private fun parseOrderFromLabel(label: String): Int {
        val trimmed = label.trim()
        if (!trimmed.startsWith("账号")) return 0
        return trimmed.removePrefix("账号").trim().toIntOrNull() ?: 0
    }

    private fun load() {
        accounts.clear()

        val raw = Prefs.accountsJson
        if (raw.isNotBlank()) {
            try {
                val array = JSONArray(raw)
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    accounts.add(WeiboAccount.fromJson(item))
                }
            } catch (_: Exception) {
                // JSON 损坏时不能直接崩，退回下面的迁移/空列表逻辑
                accounts.clear()
            }
        }

        // ★ 从 v16 及以前升级上来时，老版本只有一份全局 Cookie。
        //   这里做一次性迁移 —— 用户不需要重新登录，超话列表也不会丢。
        if (accounts.isEmpty() && Prefs.cookieHeader.isNotBlank()) {
            val migrated = WeiboAccount()
            migrated.id = "account-1"
            migrated.label = "账号 1"
            migrated.cookie = Prefs.cookieHeader
            migrated.verified = Prefs.weiboVerified
            migrated.expiryAt = Prefs.cookieExpiryAt
            migrated.lastCheckAt = Prefs.lastLoginCheckAt
            migrated.lastCheckMessage = Prefs.lastLoginCheckMessage
            migrated.riskScore = Prefs.riskScore
            migrated.consecutiveFailures = Prefs.consecutiveFailures
            accounts.add(migrated)
            save()
        }
    }

    fun save() {
        val array = JSONArray()
        for (account in accounts) array.put(account.toJson())
        Prefs.accountsJson = array.toString()
    }

    /**
     * 全部账号，**按序号排序**。
     *
     * ★ 排序放在这里，而不是指望「列表里的先后恰好就是对的」：
     *   签到是分批跑的、结果按批次顺序拼出来的。如果顺序跟着内部列表走，
     *   用户看到的账号 1、2、3 可能已经乱掉了 —— 报告也就没法对照。
     */
    fun all(): List<WeiboAccount> = accounts.sortedBy { it.order }

    fun enabled(): List<WeiboAccount> = accounts.filter { it.enabled }

    /** 可以真正拿去签到的账号：已启用且登录态有效 */
    fun ready(): List<WeiboAccount> = accounts.filter { it.enabled && it.loggedIn }

    /**
     * 今天还没轮到过的可签到账号（分批错峰用）。
     *
     * 保持列表顺序，这样批次是稳定的：第 1 批永远是账号 1~3，
     * 用户能预期「先签哪几个」。
     */
    fun notRunToday(today: String): List<WeiboAccount> =
        accounts.filter { it.enabled && it.loggedIn && it.lastRunDate != today }

    /** 今天已经轮到过的可签到账号数（首页用来显示进度） */
    fun doneToday(today: String): Int =
        accounts.count { it.enabled && it.loggedIn && it.lastRunDate == today }

    /**
     * 今天还需要「补跑」的账号：上一轮结束时还有超话没签完（PARTIAL），且补跑次数没用完。
     *
     * ★ 为什么单独拎出来：
     *   分批之后，「今天还没轮到的账号」优先跑。如果某个账号上一轮没签完，
     *   它会被记进 `lastRunDate`，于是**永远排在后面**，当天再也不会被选中 ——
     *   界面上写着「会自动补签」，实际却没人管它。这个方法就是补上这一环。
     */
    fun partialPending(today: String): List<WeiboAccount> =
        accounts.filter {
            it.enabled && it.loggedIn &&
                it.lastRunStatus == "PARTIAL" &&
                it.partialRetryDate == today &&
                it.partialRetryCount < WeiboAccount.MAX_PARTIAL_RETRIES
        }

    /**
     * 今天还需要发帖的账号（已启用 + 已登录 + 还没发够 [perAccount] 条）。
     *
     * ★ 判据必须**逐个账号**看，跟签到的 notRunToday 是同一个道理：
     *   分批执行时一个全局标记会被第一批就置成「今天做过了」，剩下的账号就永远排不上。
     */
    fun postPending(today: String, perAccount: Int): List<WeiboAccount> =
        accounts.filter { it.enabled && it.loggedIn && it.postPending(today, perAccount) }

    /** 今天已经发出去几条（只有一个账号在发，所以就是那一个账号的进度） */
    fun postDoneToday(today: String): Int =
        postAccount()?.let { if (it.postDate == today) it.postIndex.coerceAtLeast(0) else 0 } ?: 0

    /**
     * 发帖用的那**一个**账号。
     *
     * ★★ 只返回一个，绝不返回列表 —— 这是硬边界（见 Prefs.postAccountId 的说明）。
     *   没指定或指定的账号已经不可用时，退回「第一个已登录的」，
     *   而不是「所有已登录的」。
     */
    fun postAccount(): WeiboAccount? {
        val picked = find(Prefs.postAccountId)
        if (picked != null && picked.enabled && picked.loggedIn) return picked
        return ready().firstOrNull()
    }

    /**
     * 今天的目标总条数。
     *
     * ★ 是 `perAccount` 而不是 `账号数 × perAccount` —— 只有一个账号在发。
     */
    fun postTargetToday(perAccount: Int): Int = if (postAccount() == null) 0 else perAccount

    fun find(id: String): WeiboAccount? = accounts.firstOrNull { it.id == id }

    fun add(): WeiboAccount {
        val account = WeiboAccount()
        account.id = UUID.randomUUID().toString()
        // ★ 序号取「历史最大号 +1」，**不复用被删掉 / 停用掉的号**。
        //   所以删掉账号 6 之后，新加的账号是「账号 9」而不是又一个「账号 7」——
        //   序号一旦指向某个账号就一直指向它，不会因为删号而整体错位。
        //
        // ★ 下界再兜一层 `accounts.size`：万一某个账号的 order 还是 0
        //   （老数据尚未补号 / 异常中断），只取 max 会算出「1」，
        //   和已有的「账号 1」撞名 —— 界面上出现两行同样的名字，结果就对不上号了。
        val maxOrder = accounts.maxOfOrNull { it.order } ?: 0
        account.order = maxOf(maxOrder, accounts.size) + 1
        account.label = "账号 " + account.order
        accounts.add(account)
        save()
        return account
    }

    fun remove(id: String) {
        accounts.removeAll { it.id == id }
        save()
    }

    /** 界面要渲染列表时用：一个账号都没有就先建一个空的 */
    fun ensureAtLeastOne(): WeiboAccount {
        if (accounts.isEmpty()) return add()
        return accounts[0]
    }
}
