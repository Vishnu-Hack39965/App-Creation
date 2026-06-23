package com.rakshapal.library

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

// ─────────────────────────────────────────────────────────────────────────────
//  Constants shared across all update-related classes
// ─────────────────────────────────────────────────────────────────────────────
object UpdateConfig {
    const val GITHUB_API_URL =
        "https://api.github.com/repos/Vishnu-Hack39965/App-Creation/releases/latest"
    const val APK_DOWNLOAD_URL =
        "https://github.com/Vishnu-Hack39965/App-Creation/releases/latest/download/app-debug.apk"
    const val APK_FILE_NAME   = "update.apk"
    const val WORK_NAME       = "periodic_update_check"
    const val TAG             = "AutoUpdate"
}

// ─────────────────────────────────────────────────────────────────────────────
//  WorkManager worker — runs every 15 minutes in the background
// ─────────────────────────────────────────────────────────────────────────────
class UpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        Log.d(UpdateConfig.TAG, "Background check running…")
        val latestVersion = fetchLatestVersionName() ?: return Result.retry()

        val currentVersion = applicationContext.packageManager
            .getPackageInfo(applicationContext.packageName, 0).versionName

        if (isNewer(latestVersion, currentVersion)) {
            Log.d(UpdateConfig.TAG, "New version $latestVersion found, downloading silently…")
            val success = downloadApkSilently(applicationContext)
            if (success) {
                // Store flag so MainActivity shows install prompt next open
                applicationContext
                    .getSharedPreferences("update_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("update_ready", true)
                    .putString("update_version", latestVersion)
                    .apply()
            }
        } else {
            Log.d(UpdateConfig.TAG, "Already on latest ($currentVersion)")
        }
        return Result.success()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Suspend helpers — network & disk (safe to call from any coroutine scope)
// ─────────────────────────────────────────────────────────────────────────────

/** Fetch the tag_name of the latest GitHub release, e.g. "v1.0.42" */
suspend fun fetchLatestVersionName(): String? = withContext(Dispatchers.IO) {
    try {
        val conn = URL(UpdateConfig.GITHUB_API_URL).openConnection() as HttpURLConnection
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.connectTimeout = 10_000
        conn.readTimeout    = 10_000
        val json = conn.inputStream.bufferedReader().readText()
        conn.disconnect()
        JSONObject(json).getString("tag_name").removePrefix("v")
    } catch (e: Exception) {
        Log.e(UpdateConfig.TAG, "Version fetch failed: ${e.message}")
        null
    }
}

/**
 * Returns true when [remote] is strictly newer than [current].
 * Compares semver-style segments (1.0.5 > 1.0.4).
 */
fun isNewer(remote: String, current: String): Boolean {
    val r = remote.split(".").mapNotNull { it.toIntOrNull() }
    val c = current.split(".").mapNotNull { it.toIntOrNull() }
    val len = maxOf(r.size, c.size)
    for (i in 0 until len) {
        val rv = r.getOrElse(i) { 0 }
        val cv = c.getOrElse(i) { 0 }
        if (rv > cv) return true
        if (rv < cv) return false
    }
    return false
}

/**
 * Downloads the latest APK into the app's private cache directory.
 * Deletes any previously downloaded APK first → only 1 file ever exists.
 * Returns true on success.
 */
suspend fun downloadApkSilently(context: Context): Boolean = withContext(Dispatchers.IO) {
    try {
        val apkFile = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            UpdateConfig.APK_FILE_NAME)

        // Delete old downloaded APK before downloading new one
        if (apkFile.exists()) {
            apkFile.delete()
            Log.d(UpdateConfig.TAG, "Old APK deleted")
        }

        val conn = URL(UpdateConfig.APK_DOWNLOAD_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout    = 60_000
        conn.connect()

        val input  = conn.inputStream
        val output = FileOutputStream(apkFile)
        input.copyTo(output)
        output.flush(); output.close(); input.close()
        conn.disconnect()

        Log.d(UpdateConfig.TAG, "APK downloaded to ${apkFile.absolutePath}")
        true
    } catch (e: Exception) {
        Log.e(UpdateConfig.TAG, "Download failed: ${e.message}")
        false
    }
}

/**
 * Shows a FORCE-INSTALL dialog — no cancel button, no back press, no outside dismiss.
 * The only exit is tapping Install, which opens the system installer.
 * [versionName] is the new version string shown in the dialog (e.g. "1.0.43").
 * Call only from the UI thread.
 */
fun promptInstall(context: Context, versionName: String = "") {
    val apkFile = File(
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
        UpdateConfig.APK_FILE_NAME
    )
    if (!apkFile.exists()) return

    val displayVersion = if (versionName.isNotBlank()) versionName else "a new version"
    val message = "Rakshapal Library $displayVersion is available.\n\nPlease install it to continue using the app."

    val dialog = AlertDialog.Builder(context)
        .setTitle("Update Required")
        .setMessage(message)
        .setPositiveButton("Install") { _, _ ->
            installApk(context, apkFile)
        }
        // No negative button — no "Cancel" or "Later"
        .setCancelable(false)   // blocks back press + outside tap
        .create()

    // Extra safety: also swallow the back key inside the dialog
    dialog.setOnKeyListener { _, keyCode, _ ->
        keyCode == android.view.KeyEvent.KEYCODE_BACK   // true = consumed, do nothing
    }

    dialog.show()
}

/** Triggers the system APK installer */
fun installApk(context: Context, apkFile: File) {
    val uri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
    } else {
        Uri.fromFile(apkFile)
    }

    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(intent)
}

/** Schedules (or re-schedules) the 15-minute periodic background check */
fun schedulePeriodicCheck(context: Context) {
    val request = PeriodicWorkRequestBuilder<UpdateWorker>(15, TimeUnit.MINUTES)
        .setConstraints(
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
        )
        .build()

    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        UpdateConfig.WORK_NAME,
        ExistingPeriodicWorkPolicy.KEEP,   // Don't reset timer if already scheduled
        request
    )
    Log.d(UpdateConfig.TAG, "Periodic update check scheduled (every 15 min)")
}
