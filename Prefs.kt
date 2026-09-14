package com.tjh.weibotask

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object Prefs {
    private const val FILE_NAME = "weibo_task_secure"
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        prefs = EncryptedSharedPreferences.create(
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

    val weiboLoggedIn: Boolean
        get() = weiboVerified && cookieHeader.contains("SUB=")
}
