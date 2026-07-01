package com.rakshapal.library

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Re-arms all background scheduling after every device reboot (AlarmManager
 * alarms and, on some OEM skins, WorkManager's own alarms too, do not
 * survive a reboot on their own).
 *
 * Each reschedule call is wrapped individually so that one failing does
 * NOT silently prevent the others from running — every outcome (success or
 * failure, and why) is logged explicitly. This receiver never fails
 * silently: a crash here would mean NONE of the background mechanisms
 * re-arm after reboot, which is why every step is isolated.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        Log.d(UpdateConfig.TAG, "Boot completed — rescheduling all background work")

        runCatchingLogged("schedulePeriodicCheck (auto-update, every 15min)") {
            schedulePeriodicCheck(context)
        }
        runCatchingLogged("ensureWifiAlarmsScheduled (daily-repeating 6am/6pm alarms)") {
            // AlarmManager alarms (including repeating ones) don't survive
            // reboot — this checks whether they're still armed and, if
            // revoked, re-arms them with the same daily-repeat schedule.
            ensureWifiAlarmsScheduled(context)
        }
        runCatchingLogged("verifyAndRepairExpiryAlarms (survival expiry 3-shot alarms)") {
            // AlarmManager alarms don't survive reboot — recalculate the
            // canonical expiry moment from source data and re-arm whichever
            // of the 3 expiry alarms are missing/mismatched.
            verifyAndRepairExpiryAlarms(context)
        }

        // NOTE: SurvivalUpdateWorker is deliberately NOT rescheduled here —
        // it no longer runs periodically. See MainActivity.onCreate() comment.
    }

    private inline fun runCatchingLogged(taskName: String, block: () -> Unit) {
        try {
            block()
            Log.d(UpdateConfig.TAG, "[$taskName] rescheduled OK on boot.")
        } catch (e: Exception) {
            // Logged with full reason instead of failing silently — a boot-time
            // scheduling failure otherwise leaves the user with no background
            // work running at all, with no visible symptom until it's too late.
            Log.e(UpdateConfig.TAG, "[$taskName] FAILED on boot: ${e.message}", e)
        }
    }
}
