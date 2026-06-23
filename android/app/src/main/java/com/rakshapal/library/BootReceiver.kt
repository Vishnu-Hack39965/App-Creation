package com.rakshapal.library

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Re-registers the WorkManager periodic update check after every device reboot,
 * so the background checker is never lost even after a restart.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d(UpdateConfig.TAG, "Boot completed — rescheduling update check")
            schedulePeriodicCheck(context)
        }
    }
}
