package com.rakshapal.library

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaDrm
import android.net.Uri
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private val LIBRARY_URL           = "https://rakshapal-singh-library-ded2e.web.app"
    private val PERMISSION_REQUEST_CODE = 1001
    private val WIDEVINE_UUID         = UUID(-0x121074568629b532L, -0x5c37d8232ae2de13L)

    // Coroutine scope tied to Activity lifetime
    private val updateScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // ── 1. Start 15-min background checker (WorkManager) ──────────────
        schedulePeriodicCheck(this)

        // ── 2. Blue-screen button wiring ───────────────────────────────────
        findViewById<android.widget.Button>(R.id.btnHome).setOnClickListener {
            openInCustomTab(LIBRARY_URL)
        }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnExit)
            .setOnClickListener {
                finishAffinity()
                android.os.Process.killProcess(android.os.Process.myPid())
            }

        // ── 3. On-open check: runs every time the app is launched ──────────
        checkForUpdateOnOpen()

        // ── 4. Normal deep-link / permission flow ──────────────────────────
        handleDeepLink(intent)

        if (intent.data == null) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
                openInCustomTab(LIBRARY_URL)
            } else {
                requestPermissions(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                    PERMISSION_REQUEST_CODE
                )
            }
        }
    }

    // ── On-open update check ───────────────────────────────────────────────

    private fun checkForUpdateOnOpen() {
        updateScope.launch {
            // Case A: Background worker already downloaded an update → prompt immediately
            val prefs = getSharedPreferences("update_prefs", Context.MODE_PRIVATE)
            if (prefs.getBoolean("update_ready", false)) {
                val apkFile = File(
                    getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                    UpdateConfig.APK_FILE_NAME
                )
                if (apkFile.exists()) {
                    val savedVersion = prefs.getString("update_version", "") ?: ""
                    prefs.edit().putBoolean("update_ready", false).apply()
                    bringAppToFront()
                    promptInstall(this@MainActivity, savedVersion)
                    return@launch
                }
            }

            // Case B: No pre-downloaded APK → check GitHub API now
            if (!isNetworkAvailable()) return@launch

            val latestVersion = fetchLatestVersionName() ?: return@launch
            val currentVersion = try {
                packageManager.getPackageInfo(packageName, 0).versionName
            } catch (e: PackageManager.NameNotFoundException) { return@launch }

            if (!isNewer(latestVersion, currentVersion)) {
                Log.d(UpdateConfig.TAG, "App is up to date ($currentVersion)")
                return@launch
            }

            Log.d(UpdateConfig.TAG, "Update $latestVersion available — downloading…")
            val success = downloadApkSilently(this@MainActivity)
            if (success) {
                // Bring our Activity to the front (closes/hides CCT overlay)
                bringAppToFront()
                promptInstall(this@MainActivity, latestVersion)
            }
        }
    }

    /**
     * Brings MainActivity to the foreground so it sits on top of any
     * Chrome Custom Tab that was open.  The CCT is a separate task, so
     * moving our task to front effectively pushes it behind us.
     */
    private fun bringAppToFront() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE)
                as android.net.ConnectivityManager
        return cm.activeNetworkInfo?.isConnected == true
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    override fun onDestroy() {
        super.onDestroy()
        updateScope.cancel()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    @Deprecated("Overridden to disable back navigation")
    override fun onBackPressed() {
        // Intentionally swallow — user should not close the CCT accidentally
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                openInCustomTab(LIBRARY_URL)
            } else {
                Toast.makeText(
                    this,
                    "Location permission is required to use this app.\nPlease allow it to continue.",
                    Toast.LENGTH_LONG
                ).show()
                android.os.Handler(mainLooper).postDelayed({
                    requestPermissions(
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                        PERMISSION_REQUEST_CODE
                    )
                }, 3000)
            }
        }
    }

    // ── Chrome Custom Tab ──────────────────────────────────────────────────

    private fun openInCustomTab(url: String) {
        val customTabsIntent = CustomTabsIntent.Builder()
            .setShowTitle(true)
            .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
            .build()

        customTabsIntent.intent.apply {
            setPackage("com.android.chrome")
            addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
        }

        try {
            customTabsIntent.launchUrl(this, Uri.parse(url))
        } catch (e: Exception) {
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            browserIntent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
            startActivity(browserIntent)
        }
    }

    // ── Deep link handler ──────────────────────────────────────────────────

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun handleDeepLink(intent: Intent) {
        val data: Uri = intent.data ?: return
        if (data.scheme != "mylibraryapp") return

        when (data.host) {
            "wifi" -> {
                val ssid = data.getQueryParameter("ssid") ?: return
                val pass = data.getQueryParameter("pass") ?: return
                connectToLibraryWifi(ssid, pass)
            }
            "forgetwifi" -> forgetLibraryWifi()
            "getdeviceid" -> {
                val callbackUrl = data.getQueryParameter("callback")
                handleGetDeviceId(callbackUrl)
            }
        }
    }

    // ── MediaDrm device ID ─────────────────────────────────────────────────

    private fun handleGetDeviceId(callbackUrl: String?) {
        try {
            val mediaDrm    = MediaDrm(WIDEVINE_UUID)
            val deviceIdBytes = mediaDrm.getPropertyByteArray(MediaDrm.PROPERTY_DEVICE_UNIQUE_ID)
            mediaDrm.close()
            val deviceIdHex = deviceIdBytes.joinToString("") { "%02x".format(it) }
            Log.d("MediaDrm", "Device ID extracted")

            if (!callbackUrl.isNullOrBlank()) {
                val returnUri = Uri.parse(callbackUrl).buildUpon()
                    .appendQueryParameter("drm_id", deviceIdHex).build().toString()
                openInCustomTab(returnUri)
            } else {
                openInCustomTab(LIBRARY_URL)
            }
        } catch (e: Exception) {
            Log.e("MediaDrm", "Failed: ${e.message}")
            Toast.makeText(this, "Device verification failed. Please try again.",
                Toast.LENGTH_LONG).show()
            if (!callbackUrl.isNullOrBlank()) {
                val errorUri = Uri.parse(callbackUrl).buildUpon()
                    .appendQueryParameter("drm_error", "extraction_failed").build().toString()
                openInCustomTab(errorUri)
            } else {
                openInCustomTab(LIBRARY_URL)
            }
        }
    }

    // ── Wi-Fi helpers ──────────────────────────────────────────────────────

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun connectToLibraryWifi(ssid: String, pass: String) {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this,
                "Location permission was revoked. Please allow it in Settings.",
                Toast.LENGTH_LONG).show()
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                PERMISSION_REQUEST_CODE)
            return
        }

        val suggestion = WifiNetworkSuggestion.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(pass)
            .setIsAppInteractionRequired(true)
            .setPriority(999)
            .build()

        val wifiManager = applicationContext
            .getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiManager.disconnect()
        wifiManager.removeNetworkSuggestions(wifiManager.networkSuggestions)
        val status = wifiManager.addNetworkSuggestions(listOf(suggestion))

        Toast.makeText(
            this,
            if (status == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS)
                "Connecting to Library Wi-Fi…\nYou will be connected shortly."
            else
                "Could not connect. Please check Wi-Fi settings.",
            Toast.LENGTH_LONG
        ).show()
        openInCustomTab(LIBRARY_URL)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun forgetLibraryWifi() {
        val wifiManager = applicationContext
            .getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiManager.removeNetworkSuggestions(wifiManager.networkSuggestions)
        Toast.makeText(this, "Disconnected from Library Wi-Fi.\nMembership ended.",
            Toast.LENGTH_LONG).show()
        openInCustomTab(LIBRARY_URL)
    }
}
