package com.rakshapal.library

import android.content.Context
import android.util.Log
import androidx.work.*
import java.util.concurrent.TimeUnit

// ═══════════════════════════════════════════════════════════════════
// SurvivalUpdateWorker
// ─────────────────────
// RETAINED IN CODE but NO LONGER scheduled periodically (previously ran
// every 3h, then every 15min). The underlying logic is still actively
// used in two places:
//   1. OfflineDashboardActivity — calls UserDataManager.readAndUpdateSurvivalTime()
//      directly every time that screen opens, to refresh the countdown.
//   2. SurvivalExpiryAlarmReceiver.kt — calls the same UserDataManager
//      logic, but only at the 3 calculated expiry-window alarm times
//      instead of polling.
// This CoroutineWorker class itself is kept for compatibility / manual
// triggering, but scheduleSurvivalWorker() below is intentionally NOT
// called from MainActivity or BootReceiver anymore.
// ═══════════════════════════════════════════════════════════════════
class SurvivalUpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        return try {
            if (!UserDataManager.filesExist(applicationContext)) {
                Log.d("SurvivalWorker", "No user files found — skipping update.")
                return Result.success()
            }
            val updated = UserDataManager.readAndUpdateSurvivalTime(applicationContext)
            Log.d("SurvivalWorker", "Survival time updated: $updated days remaining.")
            Result.success()
        } catch (e: Exception) {
            Log.e("SurvivalWorker", "doWork failed: ${e.message}", e)
            Result.retry()
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// NOTE: WifiRemovalWorker (30-min polling) has been replaced by
// WifiAlarmReceiver.kt, which uses AlarmManager to fire at the exact
// clock times 6:00 AM / 6:00 PM instead of polling every 30 minutes.
// See scheduleWifiAlarms() in WifiAlarmReceiver.kt.
// ═══════════════════════════════════════════════════════════════════
// Scheduling helpers
// ═══════════════════════════════════════════════════════════════════

// DEPRECATED / UNUSED: kept only so SurvivalUpdateWorker can still be
// manually re-enabled as a periodic worker later if ever needed. Not
// called from anywhere in the app anymore — see class comment above.
fun scheduleSurvivalWorker(context: Context) {
    val request = PeriodicWorkRequestBuilder<SurvivalUpdateWorker>(15, TimeUnit.MINUTES)
        .build()
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        "survival_update_worker",
        ExistingPeriodicWorkPolicy.KEEP,
        request
    )
    Log.d("BackgroundWorkers", "SurvivalUpdateWorker scheduled (every 15min).")
}
