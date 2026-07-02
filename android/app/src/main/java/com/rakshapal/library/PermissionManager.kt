package com.rakshapal.library

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast

// ═══════════════════════════════════════════════════════════════════
// PermissionManager
// ─────────────────
// Generalizes the "keep asking until granted" pattern that used to be
// hard-coded only for ACCESS_FINE_LOCATION, to EVERY permission the app
// needs. Two kinds are handled:
//
//  • Runtime (dangerous) permissions — requested via requestPermissions():
//      - ACCESS_FINE_LOCATION
//      - POST_NOTIFICATIONS        (API 33+ only)
//
//  • Special-access permissions — only grantable via a Settings screen,
//    no requestPermissions() callback exists for these:
//      - REQUEST_INSTALL_PACKAGES  ("Install unknown apps", needed for
//                                    the silent APK auto-update flow)
//      - SCHEDULE_EXACT_ALARM      ("Alarms & reminders", API 31+, needed
//                                    for WifiAlarmReceiver / expiry alarms)
//
// ensureAllPermissions() checks the list in order and stops at the first
// missing one — shows an explanatory Toast, then requests it (or opens
// the right Settings screen for special ones). It's re-invoked after
// every grant/denial (see handlePermissionResult / onResume in
// MainActivity), so the prompt keeps re-appearing — permission by
// permission — until everything required is granted, mirroring the
// original location-only loop.
// ═══════════════════════════════════════════════════════════════════
object PermissionManager {

    private const val TAG = "PermissionManager"
    const val REQUEST_CODE_BASE = 2000

    // Order matters: location first (needed for wifi connect), same as before.
    private fun runtimePermissions(): List<String> {
        val list = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return list
    }

    private fun friendlyName(permission: String): String = when (permission) {
        Manifest.permission.ACCESS_FINE_LOCATION -> "Location"
        Manifest.permission.POST_NOTIFICATIONS    -> "Notifications"
        else -> permission.substringAfterLast(".")
    }

    /**
     * Call from onCreate() and onResume(). Walks runtime permissions first,
     * then the SCHEDULE_EXACT_ALARM special-access permission, and acts on
     * the first one missing. Safe to call repeatedly — no-op once all granted.
     *
     * NOTE: REQUEST_INSTALL_PACKAGES ("Install unknown apps") is intentionally
     * NOT checked here. It is only requested at the moment the user taps
     * "Install Now" in the update dialog (see promptInstall() in
     * UpdateChecker.kt). Asking for it at every startup is unnecessarily
     * intrusive and contradicts standard Android UX expectations.
     */
    fun ensureAllPermissions(activity: Activity) {
        for (permission in runtimePermissions()) {
            if (activity.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                requestRuntimePermission(activity, permission)
                return
            }
        }

        if (!canScheduleExactAlarms(activity)) {
            requestExactAlarmPermission(activity)
            return
        }
        // All required startup permissions granted — nothing to do.
    }

    // ── Runtime permission request/response ──────────────────────────────

    private fun requestRuntimePermission(activity: Activity, permission: String) {
        Toast.makeText(
            activity,
            "🔐 ${friendlyName(permission)} permission is required.\nPlease allow it.",
            Toast.LENGTH_LONG
        ).show()
        activity.requestPermissions(
            arrayOf(permission),
            REQUEST_CODE_BASE + runtimePermissions().indexOf(permission)
        )
    }

    /**
     * Call from Activity.onRequestPermissionsResult(). Returns true if this
     * requestCode belonged to PermissionManager (caller can skip its own
     * handling in that case).
     */
    fun handlePermissionResult(activity: Activity, requestCode: Int, grantResults: IntArray): Boolean {
        val perms = runtimePermissions()
        val index = requestCode - REQUEST_CODE_BASE
        if (index !in perms.indices) return false

        val permission = perms[index]
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(activity, "✅ ${friendlyName(permission)} granted.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(
                activity,
                "❌ ${friendlyName(permission)} denied.\nThis app cannot work correctly without it.\nPlease allow it.",
                Toast.LENGTH_LONG
            ).show()
        }
        // Re-run the full check regardless of outcome: on grant this advances
        // to the next missing permission; on denial it re-asks the same one —
        // exactly the loop that previously existed only for Location.
        Handler(Looper.getMainLooper()).postDelayed({ ensureAllPermissions(activity) }, 1500)
        return true
    }

    // ── Special-access permissions (Settings-screen only) ────────────────

    internal fun canInstallPackages(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    internal fun requestInstallPackagesPermission(activity: Activity) {
        Toast.makeText(
            activity,
            "📦 \"Install unknown apps\" permission is required for auto-updates.\nPlease allow it on the next screen.",
            Toast.LENGTH_LONG
        ).show()
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${activity.packageName}")
            )
            activity.startActivity(intent)
            // No callback exists for this one — MainActivity.onResume() re-checks
            // it (and everything else) the moment the user comes back from Settings.
        } catch (e: Exception) {
            // Some OEM builds / emulators don't have this Settings screen — if we
            // can't even open it, say so explicitly instead of leaving the user
            // stuck on a permission that silently never gets requested.
            Log.e(TAG, "Could not open 'Install unknown apps' settings: ${e.message}", e)
            Toast.makeText(
                activity,
                "⚠️ Couldn't open the permission screen automatically.\nPlease enable \"Install unknown apps\" for this app manually in system Settings.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun canScheduleExactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return am.canScheduleExactAlarms()
    }

    private fun requestExactAlarmPermission(activity: Activity) {
        Toast.makeText(
            activity,
            "⏰ \"Alarms & reminders\" permission is required for exact-time tasks.\nPlease allow it on the next screen.",
            Toast.LENGTH_LONG
        ).show()
        try {
            val intent = Intent(
                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                Uri.parse("package:${activity.packageName}")
            )
            activity.startActivity(intent)
            // Also re-checked in onResume() when the user returns.
        } catch (e: Exception) {
            Log.e(TAG, "Could not open 'Alarms & reminders' settings: ${e.message}", e)
            Toast.makeText(
                activity,
                "⚠️ Couldn't open the permission screen automatically.\nPlease enable \"Alarms & reminders\" for this app manually in system Settings.",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
