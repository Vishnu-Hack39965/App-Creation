package com.rakshapal.library

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.util.Date

// ═══════════════════════════════════════════════════════════════════
// SurvivalExpiryAlarmReceiver
// ────────────────────────────
// Replaces the always-on periodic SurvivalUpdateWorker polling with a
// one-time calculation done the moment the deep link fires, plus exactly
// 3 alarms clustered around the calculated expiry moment:
//
//   (I)   expiry time − 3 hours
//   (II)  exact expiry time
//   (III) expiry time + 3 hours
//
// Each firing does exactly what SurvivalUpdateWorker.doWork() did: re-read
// + recompute + re-save the survival countdown via UserDataManager, which
// also wipes wifi credentials once the value drops below -1.
//
// Declared in AndroidManifest.xml (not registered at runtime), so it fires
// even if the app process was closed — as long as the user has not
// force-stopped the app (a system-level restriction; force-stopping
// suspends ALL of an app's alarms until it's opened again — this is an
// OS guarantee no app, including this one, can bypass).
// ═══════════════════════════════════════════════════════════════════

object SurvivalExpiryConfig {
    const val PREFS             = "survival_expiry_prefs"
    const val KEY_EXPIRE_MILLIS = "expire_millis"
    const val TAG               = "SurvivalExpiry"

    const val REQ_CODE_MINUS_3H = 3001
    const val REQ_CODE_EXACT    = 3002
    const val REQ_CODE_PLUS_3H  = 3003

    const val THREE_HOURS_MS = 3 * 60 * 60 * 1000L
    const val ONE_DAY_MS     = 24 * 60 * 60 * 1000L
}

class SurvivalExpiryAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val label = intent.getStringExtra("label") ?: "unknown"
        val appContext = context.applicationContext
        Log.d(SurvivalExpiryConfig.TAG, "Expiry alarm fired [$label] — updating survival time.")

        try {
            if (!UserDataManager.filesExist(appContext)) {
                Log.d(SurvivalExpiryConfig.TAG, "[$label] No user files found — skipping (nothing to update).")
                return
            }
            val updated = UserDataManager.readAndUpdateSurvivalTime(appContext)
            Log.d(SurvivalExpiryConfig.TAG, "[$label] Survival time updated: $updated days remaining.")
        } catch (e: Exception) {
            // UserDataManager.readAndUpdateSurvivalTime already catches its own
            // errors internally and returns 0.0, but this outer catch ensures
            // that even an unexpected failure (e.g. filesystem/IO issue) is
            // logged with its reason rather than crashing the receiver silently.
            Log.e(SurvivalExpiryConfig.TAG, "[$label] Failed to update survival time: ${e.message}", e)
        }
    }
}

/**
 * Calculates the exact expiry date/time from the moment the deep link fires
 * with a given `survivalDays` value, saves it, and schedules the 3 alarms
 * around it.
 *
 * This mirrors UserDataManager's own expiry rule exactly: starting at
 * `survivalDays` remaining and counting straight down by elapsed days, the
 * value crosses below -1 (the point where wifi credentials get wiped) once
 * (survivalDays + 1) days have elapsed since the deep link fired. So:
 *
 *     expireAtMillis = deepLinkFiredAtMillis + (survivalDays + 1) days
 *
 * Example: survivalDays = 28 fired "now" → expiry = now + 29 days.
 */
fun calculateAndScheduleExpiry(context: Context, survivalDays: Double, deepLinkFiredAtMillis: Long) {
    try {
        val expireAtMillis = deepLinkFiredAtMillis +
            ((survivalDays + 1.0) * SurvivalExpiryConfig.ONE_DAY_MS).toLong()

        context.getSharedPreferences(SurvivalExpiryConfig.PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(SurvivalExpiryConfig.KEY_EXPIRE_MILLIS, expireAtMillis)
            .apply()

        Log.d(
            SurvivalExpiryConfig.TAG,
            "survivalDays=$survivalDays firedAt=${Date(deepLinkFiredAtMillis)} → expireAt=${Date(expireAtMillis)}"
        )
        scheduleExpiryAlarms(context, expireAtMillis)
    } catch (e: Exception) {
        // If this fails, NONE of the 3 expiry alarms get scheduled — that's
        // a serious silent failure mode (the app would look fine but the
        // whole expiry mechanism would just never run), so it's logged loudly.
        Log.e(SurvivalExpiryConfig.TAG, "FAILED to calculate/schedule expiry for survivalDays=$survivalDays: ${e.message}", e)
    }
}

/** Schedules the 3 alarms (−3h / exact / +3h) around a given expiry timestamp. */
fun scheduleExpiryAlarms(context: Context, expireAtMillis: Long) {
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    scheduleExpiryAlarm(
        context, alarmManager,
        expireAtMillis - SurvivalExpiryConfig.THREE_HOURS_MS,
        SurvivalExpiryConfig.REQ_CODE_MINUS_3H, "expire-3h"
    )
    scheduleExpiryAlarm(
        context, alarmManager,
        expireAtMillis,
        SurvivalExpiryConfig.REQ_CODE_EXACT, "expire-exact"
    )
    scheduleExpiryAlarm(
        context, alarmManager,
        expireAtMillis + SurvivalExpiryConfig.THREE_HOURS_MS,
        SurvivalExpiryConfig.REQ_CODE_PLUS_3H, "expire+3h"
    )
}

/**
 * AlarmManager alarms are cleared on reboot. Call this from BootReceiver to
 * re-arm whichever of the 3 alarms haven't already passed, using the expiry
 * timestamp saved by calculateAndScheduleExpiry(). No-op if no deep link has
 * ever fired, or if the whole expiry window is already in the past.
 */
fun rescheduleExpiryAlarmsIfNeeded(context: Context) {
    val prefs = context.getSharedPreferences(SurvivalExpiryConfig.PREFS, Context.MODE_PRIVATE)
    val expireAtMillis = prefs.getLong(SurvivalExpiryConfig.KEY_EXPIRE_MILLIS, -1L)
    if (expireAtMillis <= 0L) {
        Log.d(SurvivalExpiryConfig.TAG, "No saved expiry timestamp — nothing to reschedule (deep link never fired).")
        return
    }

    val lastAlarmMillis = expireAtMillis + SurvivalExpiryConfig.THREE_HOURS_MS
    if (lastAlarmMillis <= System.currentTimeMillis()) {
        Log.d(SurvivalExpiryConfig.TAG, "Saved expiry window already fully passed — nothing to reschedule.")
        return
    }
    scheduleExpiryAlarms(context, expireAtMillis)
}

private fun scheduleExpiryAlarm(
    context: Context,
    alarmManager: AlarmManager,
    triggerAtMillis: Long,
    requestCode: Int,
    label: String
) {
    if (triggerAtMillis <= System.currentTimeMillis()) {
        Log.d(SurvivalExpiryConfig.TAG, "Skipping [$label] — already in the past (${Date(triggerAtMillis)}).")
        return
    }

    val intent = Intent(context, SurvivalExpiryAlarmReceiver::class.java).putExtra("label", label)
    val pendingIntent = PendingIntent.getBroadcast(
        context, requestCode, intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    try {
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
        if (canExact) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            Log.d(SurvivalExpiryConfig.TAG, "[$label] exact alarm set for ${Date(triggerAtMillis)} (requestCode=$requestCode).")
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            Log.w(SurvivalExpiryConfig.TAG, "[$label] exact-alarm permission NOT granted — inexact fallback for ${Date(triggerAtMillis)}.")
        }
    } catch (e: SecurityException) {
        Log.e(SurvivalExpiryConfig.TAG, "[$label] SecurityException scheduling alarm — exact-alarm permission likely revoked: ${e.message}", e)
    } catch (e: Exception) {
        Log.e(SurvivalExpiryConfig.TAG, "[$label] Unexpected error scheduling alarm: ${e.message}", e)
    }
}
