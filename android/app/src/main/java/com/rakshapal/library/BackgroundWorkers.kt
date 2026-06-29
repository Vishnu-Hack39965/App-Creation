package com.rakshapal.library

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.work.*
import java.util.Calendar
import java.util.concurrent.TimeUnit

// ═══════════════════════════════════════════════════════════════════
// SurvivalUpdateWorker — runs every 3 hours
// Reads user files, recalculates remaining survival time, re-saves.
// ═══════════════════════════════════════════════════════════════════
class SurvivalUpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        if (!UserDataManager.filesExist(applicationContext)) {
            Log.d("SurvivalWorker", "No user files found — skipping update.")
            return Result.success()
        }
        val updated = UserDataManager.readAndUpdateSurvivalTime(applicationContext)
        Log.d("SurvivalWorker", "Survival time updated: $updated days remaining.")
        return Result.success()
    }
}

// ═══════════════════════════════════════════════════════════════════
// WifiRemovalWorker — runs at 6am and 6pm, removes suggestions only
// ═══════════════════════════════════════════════════════════════════
class WifiRemovalWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    @RequiresApi(Build.VERSION_CODES.Q)
    override suspend fun doWork(): Result {
        val cal  = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val min  = cal.get(Calendar.MINUTE)
        val totalMin = hour * 60 + min
        // Only remove during closed windows: 6:00–7:00 AM or 6:00–6:30 PM
        val inClosedWindow = totalMin in 360..419 || totalMin in 1080..1109
        if (inClosedWindow) {
            try {
                val wifiManager = applicationContext
                    .getSystemService(Context.WIFI_SERVICE) as WifiManager
                wifiManager.removeNetworkSuggestions(wifiManager.networkSuggestions)
                Log.d("WifiRemoval", "Wifi suggestions removed during closed window ($hour:$min).")
            } catch (e: Exception) {
                Log.e("WifiRemoval", "Error removing suggestions: ${e.message}")
            }
        } else {
            Log.d("WifiRemoval", "Not in closed window ($hour:$min) — no action.")
        }
        return Result.success()
    }
}

// ═══════════════════════════════════════════════════════════════════
// Scheduling helpers
// ═══════════════════════════════════════════════════════════════════

fun scheduleSurvivalWorker(context: Context) {
    val request = PeriodicWorkRequestBuilder<SurvivalUpdateWorker>(3, TimeUnit.HOURS)
        .build()
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        "survival_update_worker",
        ExistingPeriodicWorkPolicy.KEEP,
        request
    )
    Log.d("BackgroundWorkers", "SurvivalUpdateWorker scheduled (every 3h).")
}

fun scheduleWifiRemovalWorker(context: Context) {
    // Run every 30 minutes — the worker itself checks if it's the right window
    val request = PeriodicWorkRequestBuilder<WifiRemovalWorker>(30, TimeUnit.MINUTES)
        .build()
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        "wifi_removal_worker",
        ExistingPeriodicWorkPolicy.KEEP,
        request
    )
    Log.d("BackgroundWorkers", "WifiRemovalWorker scheduled (every 30min, active at 6am/6pm).")
}
