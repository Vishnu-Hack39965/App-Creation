package com.rakshapal.library

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import java.util.Calendar

// ═══════════════════════════════════════════════════════════════════
// WifiAlarmReceiver — fires at exact clock times (6:00 AM / 6:00 PM)
// via AlarmManager instead of polling every 30 min with WorkManager.
//
// BUG FIX: previously each firing manually re-scheduled the *next*
// one-shot alarm itself. If a single firing was ever missed/killed by
// the OS/OEM (Doze, battery optimization, etc.) the whole daily cycle
// died silently forever, since nothing else would ever re-arm it.
//
// Now these are true DAILY-REPEATING alarms (AlarmManager.INTERVAL_DAY),
// so the OS itself keeps repeating them — no manual re-arming needed on
// each fire. On top of that, ensureWifiAlarmsScheduled() is called at
// every app startup (and on boot) to check whether each alarm is still
// actually armed; if either was revoked (reboot without reschedule,
// killed by OS/OEM, permission revoked, etc.) it's reset with the same
// daily-repeat schedule at the same clock time.
//
// Declared in AndroidManifest.xml (not registered at runtime), so the
// system wakes the app process for this broadcast even if the app was
// closed/swiped away — as long as the user has not force-stopped the
// app (a system-level restriction no app can override; force-stopping
// suspends ALL of an app's scheduled alarms/broadcasts until it is
// opened again).
// ═══════════════════════════════════════════════════════════════════

object WifiAlarmConfig {
    const val REQ_CODE_6AM = 1001
    const val REQ_CODE_6PM = 1002
    const val PREFS        = "wifi_alarm_prefs"
    const val KEY_NEXT_6AM = "next_trigger_6am"
    const val KEY_NEXT_6PM = "next_trigger_6pm"
    const val TAG          = "WifiAlarm"
}

class WifiAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        Log.d(WifiAlarmConfig.TAG, "Alarm fired — removing wifi suggestions.")
        try {
            val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiManager.removeNetworkSuggestions(wifiManager.networkSuggestions)
            Log.d(WifiAlarmConfig.TAG, "Wifi suggestions removed successfully.")
        } catch (e: Exception) {
            // Logged with reason rather than swallowed — a silent failure here
            // would mean suggestions are never removed and no one would know why.
            Log.e(WifiAlarmConfig.TAG, "Failed to remove wifi suggestions: ${e.message}", e)
        }

        // These are now daily-repeating alarms, so the OS re-arms them on
        // its own — no manual reschedule needed here. We advance the stored
        // "expected next trigger" bookkeeping by exactly INTERVAL_DAY so the
        // startup health check stays accurate after the alarm fires. Without
        // this update the stored value would remain in the past and every
        // subsequent startup would incorrectly think the alarms are missing.
        try {
            val prefs = appContext.getSharedPreferences(WifiAlarmConfig.PREFS, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            // Advance whichever key(s) have slipped into the past by one day each.
            for (key in listOf(WifiAlarmConfig.KEY_NEXT_6AM, WifiAlarmConfig.KEY_NEXT_6PM)) {
                var next = prefs.getLong(key, -1L)
                if (next <= now) {
                    // Step it forward day-by-day until it's in the future, to
                    // handle the rare case where multiple days were missed (e.g.
                    // app was force-stopped for several days then reopened).
                    while (next <= now) next += AlarmManager.INTERVAL_DAY
                    prefs.edit().putLong(key, next).apply()
                    Log.d(WifiAlarmConfig.TAG, "Advanced prefs key $key to ${java.util.Date(next)} after alarm fired.")
                }
            }
        } catch (e: Exception) {
            Log.e(WifiAlarmConfig.TAG, "Failed to advance next-trigger prefs after alarm fire: ${e.message}", e)
        }

        // Belt-and-braces self-heal: re-confirm both alarms are still armed
        // (the OS should keep repeating them, but this catches any edge case
        // where the repeat was silently dropped by the system).
        try {
            ensureWifiAlarmsScheduled(appContext)
        } catch (e: Exception) {
            Log.e(WifiAlarmConfig.TAG, "Failed post-fire self-check of wifi alarms: ${e.message}", e)
        }
    }
}

/**
 * Startup/boot entry point. Checked at every app startup (per requirement):
 * for each of the two daily alarms (6:00 AM / 6:00 PM), checks whether it
 * is still actually armed. If yes → leaves it alone (does nothing). If it
 * was revoked/removed → resets it with the same daily-repeat schedule at
 * the same clock time.
 */
fun ensureWifiAlarmsScheduled(context: Context) {
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val prefs = context.getSharedPreferences(WifiAlarmConfig.PREFS, Context.MODE_PRIVATE)

    ensureSingleWifiAlarm(context, alarmManager, prefs, hour = 6, minute = 0,
        requestCode = WifiAlarmConfig.REQ_CODE_6AM, prefsKey = WifiAlarmConfig.KEY_NEXT_6AM)
    ensureSingleWifiAlarm(context, alarmManager, prefs, hour = 18, minute = 0,
        requestCode = WifiAlarmConfig.REQ_CODE_6PM, prefsKey = WifiAlarmConfig.KEY_NEXT_6PM)
}

private fun ensureSingleWifiAlarm(
    context: Context,
    alarmManager: AlarmManager,
    prefs: android.content.SharedPreferences,
    hour: Int,
    minute: Int,
    requestCode: Int,
    prefsKey: String
) {
    val intent = Intent(context, WifiAlarmReceiver::class.java)
    val existing = PendingIntent.getBroadcast(
        context, requestCode, intent,
        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
    )
    val recordedNextTrigger = prefs.getLong(prefsKey, -1L)
    val now = System.currentTimeMillis()

    // "Armed" means: the PendingIntent is still registered with the system
    // AND its last known intended trigger time hasn't silently slipped into
    // the past (which would mean it should have fired — and re-armed itself
    // via INTERVAL_DAY — but for some reason didn't; a sign it was revoked).
    val isArmed = existing != null && recordedNextTrigger > now

    if (isArmed) {
        Log.d(WifiAlarmConfig.TAG, "$hour:$minute alarm already armed (next=${Date(recordedNextTrigger)}) — no action needed.")
        return
    }

    Log.w(WifiAlarmConfig.TAG, "$hour:$minute alarm is missing/revoked (existed=${existing != null}, recordedNextTrigger=${if (recordedNextTrigger > 0) Date(recordedNextTrigger) else "none"}) — resetting with daily repeat.")
    setDailyRepeatingWifiAlarm(context, alarmManager, prefs, hour, minute, requestCode, prefsKey)
}

/** Sets (or replaces) a single daily-repeating alarm at the given clock time. */
private fun setDailyRepeatingWifiAlarm(
    context: Context,
    alarmManager: AlarmManager,
    prefs: android.content.SharedPreferences,
    hour: Int,
    minute: Int,
    requestCode: Int,
    prefsKey: String
) {
    val next = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        // If that time already passed today, push to tomorrow
        if (timeInMillis <= System.currentTimeMillis()) {
            add(Calendar.DAY_OF_YEAR, 1)
        }
    }

    val intent = Intent(context, WifiAlarmReceiver::class.java)
    val pendingIntent = PendingIntent.getBroadcast(
        context, requestCode, intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    // canScheduleExactAlarms() can still theoretically race with a
    // SecurityException on some OEM builds — wrapped so a scheduling
    // failure is logged with its exact reason instead of crashing the
    // receiver/app or silently dropping the alarm.
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            // User hasn't granted "Alarms & reminders" — fall back to an
            // inexact daily repeat, and log clearly WHY.
            alarmManager.setInexactRepeating(
                AlarmManager.RTC_WAKEUP, next.timeInMillis, AlarmManager.INTERVAL_DAY, pendingIntent
            )
            Log.w(WifiAlarmConfig.TAG, "Exact-alarm permission NOT granted — used inexact daily repeat for $hour:$minute (first fire ${next.time}).")
        } else {
            // setRepeating with exact semantics isn't offered directly by the
            // platform for daily intervals in a battery-friendly way, but
            // setRepeating() at RTC_WAKEUP with INTERVAL_DAY is the standard
            // "repeat every day at this clock time" primitive and is what's
            // requested here (daily repeat at 6am/6pm).
            alarmManager.setRepeating(
                AlarmManager.RTC_WAKEUP, next.timeInMillis, AlarmManager.INTERVAL_DAY, pendingIntent
            )
            Log.d(WifiAlarmConfig.TAG, "Daily-repeating alarm set for ${next.time}, every 24h (requestCode=$requestCode).")
        }
        prefs.edit().putLong(prefsKey, next.timeInMillis).apply()
    } catch (e: SecurityException) {
        Log.e(WifiAlarmConfig.TAG, "SecurityException scheduling alarm for $hour:$minute — exact-alarm permission likely revoked: ${e.message}", e)
    } catch (e: Exception) {
        Log.e(WifiAlarmConfig.TAG, "Unexpected error scheduling alarm for $hour:$minute: ${e.message}", e)
    }
}
