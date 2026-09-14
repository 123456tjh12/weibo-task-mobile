package com.tjh.weibotask

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

class RiskException(message: String, val event: String) : Exception(message)

object WeiboClient {
    private const val BASE = "https://m.weibo.cn"
    private const val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"

    fun fetchConfig(): JSONObject {
        val json = httpJson(BASE + "/api/config", BASE + "/")
        val data = json.optJSONObject("data") ?: return JSONObject()
        // 结构异常（风控页 / 错误页 / 空响应）时不要据此判定登录失效，
        // 否则一次异常响应就会被上层当成「掉线」，进而关掉自动签到
        if (!data.has("login")) return data
        if (!data.optBoolean("login")) Prefs.weiboVerified = false
        return data
    }

    suspend fun runCheckin(): CheckinOutcome {
        val config = fetchConfig()
        if (!config.has("login")) {
            // 拿不到登录态：网络抖动或微博返回结构异常，不能误判成掉线
            return CheckinOutcome(
                "RETRY", Prefs.riskScore, "CONFIG_UNKNOWN",
                "无法获取微博登录态", Prefs.consecutiveFailures, JSONObject()
            )
        }
        if (!config.optBoolean("login")) {
            Prefs.weiboVerified = false
            val risk = addRisk(40)
            return CheckinOutcome("USER_REQUIRED", risk, "LOGIN_ANOMALY", "微博登录已失效", Prefs.consecutiveFailures, JSONObject())
        }

        var st = config.optString("st")
        val topics = fetchTopics()
        val pending = topics.filter { !it.done && it.scheme.isNotBlank() }
        val summary = JSONObject()
            .put("total", topics.size)
            .put("selected", pending.size)
            .put("success", 0)
            .put("already", 0)
            .put("failed", 0)
            .put("skipped", topics.size - pending.size)

        if (pending.isEmpty()) {
            Prefs.consecutiveFailures = 0
            return CheckinOutcome("SUCCESS", Prefs.riskScore, "", "", 0, summary)
        }

        var consecutive = Prefs.consecutiveFailures
        for ((index, topic) in pending.withIndex()) {
            var finalMessage = ""
            var succeeded = false
            for (attempt in 0..2) {
                try {
                    val result = checkinTopic(topic, st)
                    finalMessage = result.optString("message")
                    if (result.optString("status") == "success" || result.optString("status") == "already") {
                        succeeded = true
                        summary.put("success", summary.optInt("success") + if (result.optString("status") == "success") 1 else 0)
                        summary.put("already", summary.optInt("already") + if (result.optString("status") == "already") 1 else 0)
                        break
                    }
                    val event = classifyRisk(finalMessage)
                    if (event == "CAPTCHA") {
                        val risk = addRisk(50)
                        return CheckinOutcome("USER_REQUIRED", risk, "CAPTCHA", finalMessage, consecutive, summary)
                    }
                    if (event == "REQUEST_ANOMALY") {
                        val risk = addRisk(20)
                        if (risk >= 70) return CheckinOutcome("PAUSED", risk, "RISK_SCORE", finalMessage, consecutive, summary)
                    }
                } catch (error: RiskException) {
                    if (error.event == "CAPTCHA") {
                        val risk = addRisk(50)
                        return CheckinOutcome("USER_REQUIRED", risk, "CAPTCHA", error.message ?: "需要验证码", consecutive, summary)
                    }
                    finalMessage = error.message ?: "请求异常"
                } catch (error: Exception) {
                    finalMessage = error.message ?: "签到失败"
                }

                if (attempt < 2) delay(5000L + Random.nextLong(10000L))
            }

            if (!succeeded) {
                summary.put("failed", summary.optInt("failed") + 1)
                consecutive += 1
                Prefs.consecutiveFailures = consecutive
                if (consecutive >= 3) {
                    val risk = addRisk(20)
                    return CheckinOutcome("PAUSED", risk, "CONSECUTIVE_FAILURE", "连续失败达到 3 次", consecutive, summary)
                }
            } else {
                consecutive = 0
                Prefs.consecutiveFailures = 0
            }

            if (index < pending.size - 1) {
                st = fetchConfig().optString("st", st)
                delay(30000L + Random.nextLong(60000L))
            }
        }

        return CheckinOutcome("SUCCESS", Prefs.riskScore, "", "", Prefs.consecutiveFailures, summary)
    }

    private suspend fun fetchTopics(): List<WeiboTopic> {
        val output = mutableListOf<WeiboTopic>()
        val seen = mutableSetOf<String>()
        var sinceId = ""
        for (page in 0 until 50) {
            val url = StringBuilder(BASE + "/api/container/getIndex?containerid=100803_-_followsuper")
            if (sinceId.isNotBlank()) url.append("&since_id=").append(URLEncoder.encode(sinceId, "UTF-8"))
            val payload = httpJson(url.toString(), BASE + "/p/index?containerid=100803_-_followsuper")
            val data = payload.optJSONObject("data") ?: break
            val cards = data.optJSONArray("cards") ?: JSONArray()
            collectTopics(cards, output, seen)
            val next = data.optJSONObject("cardlistInfo")?.optString("since_id").orEmpty()
            if (next.isBlank() || next == sinceId) break
            sinceId = next
            delay(500)
        }
        return output
    }

    private fun collectTopics(node: Any?, output: MutableList<WeiboTopic>, seen: MutableSet<String>) {
        when (node) {
            is JSONArray -> for (i in 0 until node.length()) collectTopics(node.opt(i), output, seen)
            is JSONObject -> {
                val group = node.optJSONArray("card_group")
                if (group != null) collectTopics(group, output, seen)
                val name = node.optString("title_sub").ifBlank { node.optString("title") }
                val buttons = node.optJSONArray("buttons")
                if (name.isNotBlank() && buttons != null) {
                    var scheme = ""
                    var done = false
                    for (i in 0 until buttons.length()) {
                        val button = buttons.optJSONObject(i) ?: continue
                        val buttonName = button.optString("name")
                        if (buttonName == "签到") scheme = button.optString("scheme")
                        if (buttonName.contains("已签") || buttonName.contains("明日再来")) done = true
                    }
                    val id = node.optString("oid").ifBlank { node.optString("id", name) }
                    if (seen.add(id)) output.add(WeiboTopic(id, name, done, scheme))
                }
                val cards = node.optJSONArray("cards")
                if (cards != null) collectTopics(cards, output, seen)
            }
        }
    }

    private fun checkinTopic(topic: WeiboTopic, st: String): JSONObject {
        val url = if (topic.scheme.startsWith("http")) topic.scheme else BASE + topic.scheme
        val withSt = if (url.contains("st=")) url else url + (if (url.contains("?")) "&" else "?") + "st=" + URLEncoder.encode(st, "UTF-8")
        var payload = httpJson(withSt, BASE + "/p/index?containerid=100803_-_followsuper")
        if (isStError(payload)) {
            val fresh = fetchConfig().optString("st")
            payload = httpJson(
                url + (if (url.contains("?")) "&" else "?") + "st=" + URLEncoder.encode(fresh, "UTF-8"),
                BASE + "/p/index?containerid=100803_-_followsuper"
            )
        }
        val data = payload.optJSONObject("data") ?: JSONObject()
        val message = data.optString("msg").ifBlank { data.optString("tipMessage").ifBlank { payload.optString("msg") } }
        val code = payload.optString("code").ifBlank { data.optString("code") }
        return when {
            payload.optInt("ok") == 1 || code == "100000" || code == "382010" ->
                JSONObject().put("status", "success").put("message", message.ifBlank { "签到成功" })
            code == "382004" || message.contains("已签") || message.contains("明日再来") ->
                JSONObject().put("status", "already").put("message", message.ifBlank { "今日已签到" })
            else -> JSONObject().put("status", "failed").put("message", message.ifBlank { "签到失败" })
        }
    }

    private fun isStError(payload: JSONObject): Boolean {
        return payload.optString("errno") == "100015" ||
            payload.optString("msg").contains("验签") ||
            payload.optJSONObject("data")?.optString("msg")?.contains("验签") == true
    }

    private fun classifyRisk(message: String): String {
        val lower = message.lowercase()
        if (lower.contains("验证码") || lower.contains("安全验证") || lower.contains("captcha")) return "CAPTCHA"
        if (lower.contains("访问频繁") || lower.contains("请求异常") || lower.contains("系统繁忙") || lower.contains("风控")) return "REQUEST_ANOMALY"
        return ""
    }

    private fun addRisk(delta: Int): Int {
        val value = (Prefs.riskScore + delta).coerceIn(0, 100)
        Prefs.riskScore = value
        return value
    }

    private fun httpJson(urlText: String, referer: String): JSONObject {
        val connection = URL(urlText).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 20000
        connection.readTimeout = 20000
        connection.setRequestProperty("Accept", "application/json, text/plain, */*")
        connection.setRequestProperty("User-Agent", UA)
        connection.setRequestProperty("Referer", referer)
        connection.setRequestProperty("X-Requested-With", "XMLHttpRequest")
        connection.setRequestProperty("MWeibo-Pwa", "1")
        if (Prefs.cookieHeader.isNotBlank()) connection.setRequestProperty("Cookie", Prefs.cookieHeader)

        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        updateCookies(connection)
        connection.disconnect()

        if (code == 403 || code == 429) throw RiskException("微博请求异常 HTTP $code", "REQUEST_ANOMALY")
        if (code !in 200..299) throw IllegalStateException("微博 HTTP $code")
        if (text.contains("验证码") || text.contains("安全验证")) throw RiskException("检测到验证码", "CAPTCHA")
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }

    private fun updateCookies(connection: HttpURLConnection) {
        val values = linkedMapOf<String, String>()
        Prefs.cookieHeader.split(";").forEach { part ->
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
            Prefs.cookieHeader = values.entries.joinToString("; ") { "${it.key}=${it.value}" }
        }
        val alf = values["ALF"]?.toLongOrNull()
        if (alf != null && alf > 0L) Prefs.cookieExpiryAt = alf * 1000L
    }
}
