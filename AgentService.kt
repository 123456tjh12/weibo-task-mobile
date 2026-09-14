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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

class AgentService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var running = false
    private var maintenanceJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RUN_CHECKIN -> {
                startForegroundNotification("正在准备签到")
                requestRun()
            }
            ACTION_START_SCHEDULED -> {
                startForegroundNotification("自动签到运行中")
                if (Prefs.scheduleEnabled) {
                    LocalScheduler.ensureScheduled(this)
                    startMaintenance()
                }
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
        wakeLock?.release()
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun requestRun() {
        if (running) return
        running = true
        scope.launch {
            try {
                executeOnce()
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

    private fun startMaintenance() {
        if (maintenanceJob?.isActive == true) return
        maintenanceJob = scope.launch {
            while (isActive) {
                if (!Prefs.scheduleEnabled) {
                    stopSelf()
                    break
                }
                val remaining = Prefs.nextRunAt - System.currentTimeMillis()
                if (remaining <= 0L) {
                    requestRun()
                    delay(60000)
                } else {
                    delay(min(max(remaining, 15000L), 15 * 60 * 1000L))
                }
            }
        }
    }

    private suspend fun executeOnce() {
        acquireWakeLock()
        Prefs.lastRunAt = System.currentTimeMillis()
        Prefs.lastRunMessage = "正在执行微博签到"
        try {
            if (!Prefs.weiboLoggedIn) {
                Prefs.lastRunStatus = "USER_REQUIRED"
                Prefs.lastRunMessage = "需要重新登录微博"
                updateNotification("需要重新登录微博")
                if (Prefs.scheduleEnabled) LocalScheduler.scheduleNext(this)
                return
            }

            updateNotification("正在执行微博签到")
            val outcome = WeiboClient.runCheckin()
            Prefs.lastRunDate = today()
            Prefs.lastRunStatus = outcome.status
            Prefs.lastRunMessage = buildResultMessage(outcome)

            when (outcome.status) {
                "SUCCESS" -> {
                    Prefs.pausedUntil = 0L
                    updateNotification("签到完成")
                    if (Prefs.scheduleEnabled) LocalScheduler.scheduleNext(this)
                }
                "USER_REQUIRED", "PAUSED" -> {
                    // 旧实现：Prefs.scheduleEnabled = false + LocalScheduler.cancel()
                    // 风控、验证码、连续失败都只是临时状态，一旦永久关闭调度，
                    // 用户就会看到「到点完全没执行」，而且界面上几乎没有任何提示。
                    // 改为限时暂停，到期自动重试。
                    Prefs.pausedUntil = System.currentTimeMillis() + PAUSE_DURATION_MS
                    updateNotification(
                        "任务暂停：" + outcome.pauseReason.ifBlank { outcome.errorCode } + "，稍后自动重试"
                    )
                    if (Prefs.scheduleEnabled) LocalScheduler.scheduleNext(this)
                }
                else -> {
                    updateNotification("签到失败：" + outcome.errorCode)
                    if (Prefs.scheduleEnabled) LocalScheduler.scheduleNext(this)
                }
            }
        } catch (error: Exception) {
            Prefs.lastRunStatus = "FAILED"
            Prefs.lastRunMessage = "执行失败：" + (error.message ?: "未知错误")
            updateNotification(Prefs.lastRunMessage)
            if (Prefs.scheduleEnabled) LocalScheduler.scheduleNext(this)
        } finally {
            wakeLock?.release()
            wakeLock = null
        }
    }

    private fun buildResultMessage(outcome: CheckinOutcome): String {
        return when (outcome.status) {
            "SUCCESS" -> "签到完成：成功 " + outcome.summary.optInt("success") + "，已签到 " + outcome.summary.optInt("already") + "，失败 " + outcome.summary.optInt("failed")
            "USER_REQUIRED" -> "需要人工处理：" + outcome.pauseReason.ifBlank { "验证码或登录异常" }
            "PAUSED" -> "任务暂停：" + outcome.pauseReason.ifBlank { "风控暂停" }
            else -> "签到失败：" + outcome.errorCode.ifBlank { outcome.status }
        }
    }

    private fun today(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val power = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WeiboTask::CheckinWakeLock")
            wakeLock?.setReferenceCounted(false)
            wakeLock?.acquire(10 * 60 * 1000L)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "星签后台执行", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundNotification(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
                return
            } catch (_: Exception) {
                // 类型不被系统接受时退回「不声明类型」，避免整个服务起不来
            }
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, 0)
        } catch (_: Exception) {
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
        const val ACTION_STOP = "com.tjh.weibotask.STOP"

        /** 风控 / 验证码等临时状态的暂停时长，到期自动重试 */
        private const val PAUSE_DURATION_MS = 6 * 60 * 60 * 1000L
        private const val CHANNEL_ID = "weibo_task_agent"
        private const val NOTIFICATION_ID = 1001
    }
}
