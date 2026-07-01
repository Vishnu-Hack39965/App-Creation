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
// Each firing removes wifi suggestions AND re-schedules the next alarm.
//
// Declared in AndroidManifest.xml (not registered at runtime), so the
// system wakes the app process for this broadcast even if the app was
// closed/swiped away — as long as the user has not force-stopped the
// app (a system-level restriction no app can override; force-stopping
// suspends ALL of an app's scheduled alarms/broadcasts until it is
// opened again).
// ═══════════════════════════════════════════════════════════════════
class WifiAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        Log.d("WifiAlarm", "Alarm fired — removing wifi suggestions.")
        try {
            val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiManager.removeNetworkSuggestions(wifiManager.networkSuggestions)
            Log.d("WifiAlarm", "Wifi suggestions removed successfully.")
        } catch (e: Exception) {
            // Logged with reason rather than swallowed — a silent failure here
            // would mean suggestions are never removed and no one would know why.
            Log.e("WifiAlarm", "Failed to remove wifi suggestions: ${e.message}", e)
        }

        // Re-arm for the next occurrence (this alarm does not auto-repeat
        // exactly, so we schedule the next one every time it fires).
        try {
            scheduleWifiAlarms(appContext)
        } catch (e: Exception) {
            Log.e("WifiAlarm", "Failed to re-arm next wifi alarm(s): ${e.message}", e)
        }
    }
}

/**
 * Schedules two exact alarms: the next 6:00 AM and the next 6:00 PM.
 * Call this once at app start / boot; each alarm reschedules itself
 * (and its sibling) when it fires, so this single call keeps the
 * cycle going indefinitely.
 */
fun scheduleWifiAlarms(context: Context) {
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    scheduleNextAlarm(context, alarmManager, hour = 6, minute = 0, requestCode = 1001)  // 6:00 AM
    scheduleNextAlarm(context, alarmManager, hour = 18, minute = 0, requestCode = 1002) // 6:00 PM
}

private fun scheduleNextAlarm(
    context: Context,
    alarmManager: AlarmManager,
    hour: Int,
    minute: Int,
    requestCode: Int
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
            // User hasn't granted "Alarms & reminders" — fall back to inexact,
            // and log clearly WHY so this doesn't look like an untraceable bug.
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.timeInMillis, pendingIntent)
            Log.w("WifiAlarm", "Exact-alarm permission NOT granted — used inexact fallback for $hour:$minute (${next.time}).")
        } else {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.timeInMillis, pendingIntent)
            Log.d("WifiAlarm", "Exact alarm set for ${next.time} (requestCode=$requestCode).")
        }
    } catch (e: SecurityException) {
        Log.e("WifiAlarm", "SecurityException scheduling alarm for $hour:$minute — exact-alarm permission likely revoked: ${e.message}", e)
    } catch (e: Exception) {
        Log.e("WifiAlarm", "Unexpected error scheduling alarm for $hour:$minute: ${e.message}", e)
    }
}
