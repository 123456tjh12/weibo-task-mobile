package com.tjh.weibotask

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        Prefs.init(context)
        if (Prefs.scheduleEnabled) {
            LocalScheduler.scheduleNext(context)
            val serviceIntent = Intent(context, AgentService::class.java).apply {
                action = AgentService.ACTION_START_SCHEDULED
            }
            androidx.core.content.ContextCompat.startForegroundService(context, serviceIntent)
        }
    }
}
