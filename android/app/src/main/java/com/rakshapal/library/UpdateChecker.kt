package com.rakshapal.library

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
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

object UpdateConfig {
    const val GITHUB_API_URL   = "https://api.github.com/repos/Vishnu-Hack39965/App-Creation/releases/latest"
    const val APK_DOWNLOAD_URL = "https://github.com/Vishnu-Hack39965/App-Creation/releases/latest/download/app-debug.apk"
    const val APK_FILE_NAME    = "update.apk"
    const val WORK_NAME        = "periodic_update_check"
    const val TAG              = "AutoUpdate"
    const val PREFS            = "update_prefs"
    const val KEY_READY        = "update_ready"
    const val KEY_VERSION      = "update_version"
    const val KEY_DIALOG_SHOWN = "update_dialog_shown"   // tracks whether dialog is currently active
}

// ═══════════════════════════════════════════════════════════════════
// Background UpdateWorker  — runs every 15 min when network available
// Downloads APK silently; marks update_ready in SharedPrefs.
// Does NOT clear update_ready after download — that only happens
// when the user actually installs (i.e. dialog button tapped) or
// after a fresh install overrides the version check.
// ═══════════════════════════════════════════════════════════════════
class UpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        Log.d(UpdateConfig.TAG, "Background check running…")
        val latestVersion  = fetchLatestVersionName() ?: return Result.retry()
        val currentVersion = try {
            applicationContext.packageManager
                .getPackageInfo(applicationContext.packageName, 0).versionName
        } catch (e: PackageManager.NameNotFoundException) { return Result.retry() }

        if (!isNewer(latestVersion, currentVersion)) {
            Log.d(UpdateConfig.TAG, "Already on latest ($currentVersion)")
            // Clean up any stale ready-flag if somehow version already matches
            applicationContext.getSharedPreferences(UpdateConfig.PREFS, Context.MODE_PRIVATE)
                .edit().remove(UpdateConfig.KEY_READY).remove(UpdateConfig.KEY_VERSION).apply()
            return Result.success()
        }

        Log.d(UpdateConfig.TAG, "New version $latestVersion — downloading…")
        val success = downloadApkSilently(applicationContext)
        if (success) {
            applicationContext.getSharedPreferences(UpdateConfig.PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(UpdateConfig.KEY_READY, true)
                .putString(UpdateConfig.KEY_VERSION, latestVersion)
                .apply()
            Log.d(UpdateConfig.TAG, "Download done — update_ready=true")
        }
        return Result.success()
    }
}

// ═══════════════════════════════════════════════════════════════════
// Version helpers
// ═══════════════════════════════════════════════════════════════════
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

// ═══════════════════════════════════════════════════════════════════
// Download
// ═══════════════════════════════════════════════════════════════════
suspend fun downloadApkSilently(context: Context): Boolean = withContext(Dispatchers.IO) {
    try {
        val apkFile = getApkFile(context)
        if (apkFile.exists()) apkFile.delete()
        val conn = URL(UpdateConfig.APK_DOWNLOAD_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout    = 60_000
        conn.connect()
        val input  = conn.inputStream
        val output = FileOutputStream(apkFile)
        input.copyTo(output)
        output.flush(); output.close(); input.close()
        conn.disconnect()
        Log.d(UpdateConfig.TAG, "APK at ${apkFile.absolutePath}")
        true
    } catch (e: Exception) {
        Log.e(UpdateConfig.TAG, "Download failed: ${e.message}")
        false
    }
}

fun getApkFile(context: Context): File =
    File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), UpdateConfig.APK_FILE_NAME)

// ═══════════════════════════════════════════════════════════════════
// promptInstall
// FIX (f): dialog persists across app restarts because update_ready
//           stays true until the user actually taps "Install".
//           We only clear KEY_READY after they tap the button.
// FIX (e): we bring MainActivity to front BEFORE showing the dialog
//           so it appears over the CCT properly.
// FIX (d): FileObserver watches the apk file — the instant it lands
//           on disk the dialog fires on the main thread.
// ═══════════════════════════════════════════════════════════════════
fun promptInstall(context: Context, versionName: String = "") {
    val apkFile = getApkFile(context)
    if (!apkFile.exists()) {
        Log.w(UpdateConfig.TAG, "promptInstall: APK file not found — cannot show dialog")
        return
    }

    // FIX: REQUEST_INSTALL_PACKAGES is only requested here — at the moment
    // the user is actually about to install — not at app startup. If the
    // permission hasn't been granted yet, open the Settings screen now and
    // bail. MainActivity.onResume() will call checkForUpdateOnOpen() again
    // after the user returns from Settings, which will re-call promptInstall()
    // and proceed to the dialog if the permission was granted.
    if (!PermissionManager.canInstallPackages(context)) {
        Log.d(UpdateConfig.TAG, "promptInstall: REQUEST_INSTALL_PACKAGES not granted — requesting now before showing dialog.")
        if (context is android.app.Activity) {
            PermissionManager.requestInstallPackagesPermission(context)
        } else {
            Log.e(UpdateConfig.TAG, "promptInstall: context is not an Activity — cannot request install permission.")
        }
        return
    }

    val displayVersion = if (versionName.isNotBlank()) versionName else "a new version"
    val message = "Rakshapal Library $displayVersion is available.\n\nPlease install it to continue using the app."

    val dialog = AlertDialog.Builder(context)
        .setTitle("🔔 Update Required")
        .setMessage(message)
        .setPositiveButton("Install Now") { _, _ ->
            // Only clear the flag when user actually taps Install
            context.getSharedPreferences(UpdateConfig.PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(UpdateConfig.KEY_READY)
                .remove(UpdateConfig.KEY_VERSION)
                .apply()
            installApk(context, apkFile)
        }
        .setCancelable(false)   // dialog cannot be dismissed without tapping Install
        .create()

    // Block back button as well
    dialog.setOnKeyListener { _, keyCode, _ -> keyCode == KeyEvent.KEYCODE_BACK }
    dialog.show()
    Log.d(UpdateConfig.TAG, "Install dialog shown for version $displayVersion")
}

// ═══════════════════════════════════════════════════════════════════
// startApkFileWatcher  (FIX d)
// Watches the APK download directory. The moment the file appears
// (FileObserver.CLOSE_WRITE) it fires promptInstall on main thread.
// Call this once from MainActivity.onCreate().
// ═══════════════════════════════════════════════════════════════════
private var fileObserver: FileObserver? = null

fun startApkFileWatcher(context: Context) {
    val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return
    val handler = Handler(Looper.getMainLooper())

    @Suppress("DEPRECATION")
    fileObserver = object : FileObserver(dir.absolutePath, CLOSE_WRITE) {
        override fun onEvent(event: Int, path: String?) {
            if (path == UpdateConfig.APK_FILE_NAME) {
                Log.d(UpdateConfig.TAG, "FileObserver: APK write complete — posting dialog")
                val prefs = context.getSharedPreferences(UpdateConfig.PREFS, Context.MODE_PRIVATE)
                val version = prefs.getString(UpdateConfig.KEY_VERSION, "") ?: ""
                handler.post { promptInstall(context, version) }
            }
        }
    }
    fileObserver?.startWatching()
    Log.d(UpdateConfig.TAG, "APK file watcher started on ${dir.absolutePath}")
}

fun stopApkFileWatcher() {
    fileObserver?.stopWatching()
    fileObserver = null
}

// ═══════════════════════════════════════════════════════════════════
// installApk
// ═══════════════════════════════════════════════════════════════════
fun installApk(context: Context, apkFile: File) {
    val uri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
    } else {
        @Suppress("DEPRECATION") Uri.fromFile(apkFile)
    }
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(intent)
}

// ═══════════════════════════════════════════════════════════════════
// schedulePeriodicCheck
// ═══════════════════════════════════════════════════════════════════
fun schedulePeriodicCheck(context: Context) {
    val request = PeriodicWorkRequestBuilder<UpdateWorker>(15, TimeUnit.MINUTES)
        .setConstraints(
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        ).build()
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        UpdateConfig.WORK_NAME,
        ExistingPeriodicWorkPolicy.KEEP,
        request
    )
    Log.d(UpdateConfig.TAG, "Periodic update check scheduled (every 15 min)")
}
