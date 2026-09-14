package com.tjh.weibotask

import android.Manifest
import android.app.AlarmManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.PowerManager
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.DatePicker
import android.widget.TextView
import android.widget.TimePicker
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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
    private val refreshRunnable = object : Runnable {
        override fun run() {
            updateStatus()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        setContentView(R.layout.activity_main)

        val datePicker = findViewById<DatePicker>(R.id.datePicker)
        val timePicker = findViewById<TimePicker>(R.id.timePicker)
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

        findViewById<Button>(R.id.weiboLoginButton).setOnClickListener {
            startActivity(Intent(this, WeiboLoginActivity::class.java))
        }

        findViewById<Button>(R.id.checkLoginButton).setOnClickListener {
            checkLoginNow()
        }

        findViewById<Button>(R.id.saveScheduleButton).setOnClickListener {
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
            Prefs.scheduleEnabled = true
            // 用户手动重新开启时，清掉风控暂停状态，否则会被顺延到暂停结束时间
            Prefs.pausedUntil = 0L
            requestExactAlarmPermissionIfNeeded()
            LocalScheduler.scheduleNext(this)
            val serviceIntent = Intent(this, AgentService::class.java).apply {
                action = AgentService.ACTION_START_SCHEDULED
            }
            ContextCompat.startForegroundService(this, serviceIntent)
            updateStatus()
            toast("自动签到已开启")
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

        findViewById<Button>(R.id.disableScheduleButton).setOnClickListener {
            Prefs.scheduleEnabled = false
            Prefs.pausedUntil = 0L
            LocalScheduler.cancel(this)
            stopService(Intent(this, AgentService::class.java))
            updateStatus()
            toast("自动签到已关闭")
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

        findViewById<Button>(R.id.runNowButton).setOnClickListener {
            if (!Prefs.weiboLoggedIn) {
                findViewById<TextView>(R.id.logText).text = "请先完成微博登录"
                toast("请先扫码登录微博")
                return@setOnClickListener
            }
            Prefs.lastRunAt = System.currentTimeMillis()
            Prefs.lastRunMessage = "已启动签到任务，正在执行……"
            findViewById<TextView>(R.id.logText).text = Prefs.lastRunMessage
            val intent = Intent(this, AgentService::class.java).apply {
                action = AgentService.ACTION_RUN_CHECKIN
            }
            ContextCompat.startForegroundService(this, intent)
            toast("已开始立即签到")
        }

        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        Prefs.init(this)
        if (Prefs.scheduleEnabled) {
            LocalScheduler.ensureScheduled(this)
            val serviceIntent = Intent(this, AgentService::class.java).apply {
                action = AgentService.ACTION_START_SCHEDULED
            }
            ContextCompat.startForegroundService(this, serviceIntent)
        }
        updateStatus()
        if (Prefs.weiboLoggedIn && System.currentTimeMillis() - Prefs.lastLoginCheckAt > 12L * 60L * 60L * 1000L) {
            checkLoginNow()
        }
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

    private fun updateStatus() {
        findViewById<TextView>(R.id.weiboStatus).text =
            if (Prefs.weiboLoggedIn) "微博已登录" else "微博未登录，请先扫码"

        val checkText = if (Prefs.lastLoginCheckAt > 0L) {
            val time = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(Prefs.lastLoginCheckAt))
            val expiry = if (Prefs.cookieExpiryAt > 0L) {
                "\n会话到期：" + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(Prefs.cookieExpiryAt))
            } else ""
            "最后检查：" + time + "\n" + Prefs.lastLoginCheckMessage.ifBlank { "无结果" } + expiry
        } else {
            "尚未检查登录状态"
        }
        findViewById<TextView>(R.id.loginCheckStatus).text = checkText

        val exactAllowed = LocalScheduler.exactAlarmAllowed(this)
        val power = getSystemService(PowerManager::class.java)
        val batteryAllowed = power.isIgnoringBatteryOptimizations(packageName)

        val scheduleText = StringBuilder()
        if (Prefs.scheduleEnabled) {
            scheduleText.append(LocalScheduler.formatNextRun(this))
            if (Prefs.pausedUntil > System.currentTimeMillis()) {
                scheduleText.append("\n状态：风控暂停中，")
                    .append(SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(Prefs.pausedUntil)))
                    .append(" 后自动重试")
            }
            scheduleText.append("\n上次结果：").append(Prefs.lastRunStatus.ifBlank { "暂无" })
            if (Prefs.lastRunMessage.isNotBlank()) {
                scheduleText.append("\n上次信息：").append(Prefs.lastRunMessage)
            }
        } else {
            scheduleText.append("自动签到未开启")
        }
        scheduleText.append("\n精确闹钟：")
            .append(if (exactAllowed) "已允许" else "未允许（定时会被系统延迟甚至拦截）")
        scheduleText.append("\n电池优化：")
            .append(if (batteryAllowed) "已关闭" else "未关闭（后台可能被冻结）")
        if (!exactAllowed || !batteryAllowed) {
            scheduleText.append("\n⚠ 请点下方「全安卓后台兼容设置」完成配置，否则定时任务可能不执行")
        }
        findViewById<TextView>(R.id.scheduleStatus).text = scheduleText.toString()

        val lastText = if (Prefs.lastRunAt > 0L) {
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(Prefs.lastRunAt))
            "上次执行：" + time + "\n" + Prefs.lastRunMessage.ifBlank { "无结果" }
        } else {
            "等待操作"
        }
        findViewById<TextView>(R.id.logText).text = lastText
    }

    private fun checkLoginNow() {
        if (!Prefs.weiboLoggedIn) {
            findViewById<TextView>(R.id.logText).text = "请先完成微博登录"
            return
        }
        scope.launch {
            findViewById<TextView>(R.id.logText).text = "正在检查微博登录……"
            val result = withContext(Dispatchers.IO) {
                try {
                    val config = WeiboClient.fetchConfig()
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
            Prefs.lastLoginCheckAt = System.currentTimeMillis()
            when (result) {
                LoginCheck.OK -> {
                    Prefs.weiboVerified = true
                    Prefs.lastLoginCheckMessage = "登录正常"
                    findViewById<TextView>(R.id.logText).text = "微博登录正常"
                }
                LoginCheck.EXPIRED -> {
                    Prefs.weiboVerified = false
                    Prefs.lastLoginCheckMessage = "登录已失效，需要重新登录"
                    Prefs.scheduleEnabled = false
                    LocalScheduler.cancel(this@MainActivity)
                    stopService(Intent(this@MainActivity, AgentService::class.java))
                    findViewById<TextView>(R.id.logText).text = "微博登录已失效，请重新扫码"
                }
                LoginCheck.UNKNOWN -> {
                    // 旧实现在这里把 scheduleEnabled 置 false 并取消闹钟，
                    // 导致一次网络抖动就会让自动签到永久停摆，且用户毫无感知。
                    // 现在只记录状态，不动登录标记、也不动调度。
                    Prefs.lastLoginCheckMessage = "登录检查未完成（网络异常），自动签到已保留"
                    findViewById<TextView>(R.id.logText).text = "登录检查未完成，稍后自动重试"
                }
            }
            updateStatus()
        }
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
}

/** 登录检查结果：必须区分「确实掉线」和「这次没查成」 */
private enum class LoginCheck { OK, EXPIRED, UNKNOWN }
