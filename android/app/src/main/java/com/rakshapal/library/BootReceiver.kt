package com.rakshapal.library

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Re-registers all WorkManager periodic workers after every device reboot.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d(UpdateConfig.TAG, "Boot completed — rescheduling all workers")
            schedulePeriodicCheck(context)
            scheduleSurvivalWorker(context)
            scheduleWifiRemovalWorker(context)
        }
    }
}
