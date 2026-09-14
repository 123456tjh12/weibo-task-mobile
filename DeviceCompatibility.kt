package com.tjh.weibotask

import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

object DeviceCompatibility {
    fun showGuide(activity: Activity) {
        val brand = Build.MANUFACTURER.lowercase()
        val guide = when {
            brand.contains("xiaomi") || brand.contains("redmi") -> "小米 / Redmi：允许自启动，省电策略设为无限制，并允许锁屏后台运行。"
            brand.contains("huawei") || brand.contains("honor") -> "华为 / 荣耀：应用启动管理改为手动管理，允许自启动、关联启动和后台活动。"
            brand.contains("oppo") || brand.contains("oneplus") || brand.contains("realme") -> "OPPO / OnePlus / realme：允许自动启动、后台运行和关联启动，并关闭电池优化。"
            brand.contains("vivo") || brand.contains("iqoo") -> "vivo / iQOO：允许后台高耗电、自启动和关联启动。"
            brand.contains("samsung") -> "三星：从“休眠应用”中移除星签，并允许后台使用。"
            brand.contains("asus") -> "华硕：允许自启动、后台运行和电池无限制。"
            brand.contains("meizu") -> "魅族：允许自启动、后台运行和锁屏运行。"
            else -> "请允许星签自启动、后台运行和锁屏运行，并关闭电池优化。"
        }
        AlertDialog.Builder(activity)
            .setTitle("Android 后台兼容设置")
            .setMessage(guide + "\n\n还需要允许“闹钟和提醒”，否则定时任务可能延迟。")
            .setPositiveButton("打开应用设置") { _, _ -> openAppDetails(activity) }
            .setNeutralButton("品牌自启动设置") { _, _ -> openBrandAutoStart(activity) }
            .setNegativeButton("电池设置") { _, _ -> openBatterySettings(activity) }
            .show()
    }

    fun openExactAlarmSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = context.getSystemService(AlarmManager::class.java)
            if (!alarmManager.canScheduleExactAlarms()) {
                try {
                    context.startActivity(
                        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                            .setData(Uri.parse("package:" + context.packageName))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (_: Exception) {
                    openAppDetails(context)
                }
            }
        }
    }

    private fun openAppDetails(context: Context) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:" + context.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
            context.startActivity(
                Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun openBatterySettings(context: Context) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:" + context.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
            context.startActivity(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun openBrandAutoStart(context: Context) {
        val brand = Build.MANUFACTURER.lowercase()
        val components = when {
            brand.contains("xiaomi") || brand.contains("redmi") -> listOf(
                ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
            )
            brand.contains("huawei") || brand.contains("honor") -> listOf(
                ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity")
            )
            brand.contains("oppo") || brand.contains("oneplus") || brand.contains("realme") -> listOf(
                ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity")
            )
            brand.contains("vivo") || brand.contains("iqoo") -> listOf(
                ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
                ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")
            )
            brand.contains("samsung") -> listOf(
                ComponentName("com.samsung.android.lool", "com.samsung.android.lool.activity.SleepingAppsActivity"),
                ComponentName("com.samsung.android.sm", "com.samsung.android.sm.ui.appsmanagement.activity.AutoStartActivity")
            )
            else -> emptyList()
        }
        for (component in components) {
            try {
                context.startActivity(
                    Intent().setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                return
            } catch (_: Exception) {
            }
        }
        openAppDetails(context)
    }
}
