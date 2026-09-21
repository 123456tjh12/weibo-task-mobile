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
    /**
     * 各品牌的**具体路径**。
     *
     * ★ 为什么不再只写一句「允许自启动、后台运行」：
     *   国产 ROM 把这三项分散在三个互不相邻的设置页里，只说名字用户根本找不到，
     *   结果就是「做过一部分」—— 而少做的那一项恰好就是决定性的一项。
     *   这里直接把「设置 → 具体哪一层 → 具体哪一项」写出来。
     */
    private fun brandSteps(brand: String): String = when {
        brand.contains("oppo") || brand.contains("oneplus") || brand.contains("realme") ->
            "ColorOS / realme UI / OxygenOS（OPPO、一加、realme）需要开满 4 项：\n" +
                "① 设置 → 电池 → 应用耗电管理 → 星签 → 允许「后台运行」\n" +
                "② 设置 → 应用 → 自启动 → 星签 → 打开（这是最关键的一项）\n" +
                "③ 设置 → 应用 → 应用管理 → 星签 → 电池 → 允许「后台活动」\n" +
                "④ 设置 → 通知与状态栏 → 应用通知 → 星签 → 允许通知（否则前台服务会被降级）\n" +
                "最后：在「最近任务」里下拉星签的卡片，点锁头图标锁定，避免被一键清理掉。"
        brand.contains("vivo") || brand.contains("iqoo") ->
            "OriginOS / Funtouch（vivo、iQOO）需要开满 3 项：\n" +
                "① 设置 → 电池 → 后台高耗电 → 星签 → 允许\n" +
                "② i管家 → 应用管理 → 权限管理 → 自启动 → 星签 → 打开\n" +
                "③ 设置 → 应用与权限 → 应用管理 → 星签 → 权限 → 单项权限设置 → 允许「后台运行」\n" +
                "最后：在「最近任务」里下拉星签的卡片锁定，并在 i管家 → 电池 → 后台运行管理里确认没被清理。"
        brand.contains("xiaomi") || brand.contains("redmi") ->
            "MIUI / HyperOS（小米、Redmi）：\n" +
                "① 设置 → 应用设置 → 应用管理 → 星签 → 省电策略 → 无限制\n" +
                "② 同一页 → 自启动 → 打开\n" +
                "③ 同一页 → 权限管理 → 显示在其他应用上层 → 允许\n" +
                "④ 最近任务里下拉星签卡片锁定。"
        brand.contains("huawei") || brand.contains("honor") ->
            "EMUI / MagicOS（华为、荣耀）：\n" +
                "① 设置 → 应用 → 应用启动管理 → 星签 → 改为「手动管理」\n" +
                "② 手动管理里把「自启动」「关联启动」「后台活动」三项全部打开\n" +
                "③ 设置 → 电池 → 更多电池设置 → 关闭「休眠时始终保持网络连接」的限制"
        brand.contains("samsung") ->
            "三星：设置 → 电池 → 后台使用限制 → 从「休眠应用」中移除星签，并关闭「自动运行优化」。"
        brand.contains("asus") ->
            "华硕：设置 → 电池 → 星签 → 允许自启动、后台运行，电池设为「无限制」。"
        brand.contains("meizu") ->
            "魅族：设置 → 应用管理 → 星签 → 权限 → 允许自启动、后台运行和锁屏运行。"
        else ->
            "请在系统设置里找到星签，允许「自启动」「后台运行」「锁屏运行」，并把电池设为不优化。"
    }

    fun showGuide(activity: Activity) {
        val brand = Build.MANUFACTURER.lowercase()
        val guide = brandSteps(brand)
        AlertDialog.Builder(activity)
            .setTitle("Android 后台兼容设置")
            .setMessage(
                guide +
                    "\n\n以上每一项都要开 —— 少开一项就可能出现「放后台不执行、打开 App 才执行」。" +
                    "\n\n另外还要允许「闹钟和提醒」权限，否则定时任务会被系统延迟到下次亮屏。" +
                    "\n\n状态栏出现一个小闹钟图标是正常的：那是本应用登记的定时任务，" +
                    "它正是为了让系统不敢丢掉这个任务。" +
                    "\n\n做完之后回到首页，看「后台服务」这一行 —— " +
                    "如果显示「待命中」就说明服务已经不会被冻结了。"
            )
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
