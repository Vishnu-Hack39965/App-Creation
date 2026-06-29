package com.rakshapal.library

import android.Manifest
import android.animation.AnimatorInflater
import android.animation.ObjectAnimator
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
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.cardview.widget.CardView
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private val LIBRARY_URL             = "https://rakshapal-singh-library-ded2e.web.app"
    private val PERMISSION_REQUEST_CODE = 1001
    private val WIDEVINE_UUID           = UUID(-0x121074568629b532L, -0x5c37d8232ae2de13L)
    private val updateScope             = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var floatAnimator: ObjectAnimator? = null

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Schedule all background workers
        schedulePeriodicCheck(this)
        scheduleSurvivalWorker(this)
        scheduleWifiRemovalWorker(this)

        // FIX (d): watch for APK landing on disk — show dialog immediately
        startApkFileWatcher(this)

        runEntranceAnimations()

        // ── Button wiring ──────────────────────────────────────────────────
        findViewById<Button>(R.id.btnHome).setOnClickListener { view ->
            animateButtonPress(view) { openHomePage() }
        }
        findViewById<Button>(R.id.btnDashboard).setOnClickListener { view ->
            animateButtonPress(view) {
                startActivity(Intent(this, OfflineDashboardActivity::class.java))
            }
        }
        findViewById<Button>(R.id.btnExit).setOnClickListener { view ->
            animateButtonPress(view) {
                finishAffinity()
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }

        // FIX (c) & (f): check on every open whether a pending update exists
        checkForUpdateOnOpen()

        handleDeepLink(intent)

        // FIX (b): request location — keep asking until granted
        if (intent.data == null) {
            ensureLocationPermissionThenOpen()
        }
    }

    // ── FIX (b): keep asking until location is granted, never open without it ──

    private fun ensureLocationPermissionThenOpen() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            openHomePage()
        } else {
            // Show a Toast explaining why, then request again
            Toast.makeText(
                this,
                "📍 Location permission is required to connect to Wi-Fi.\nPlease allow it.",
                Toast.LENGTH_LONG
            ).show()
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                PERMISSION_REQUEST_CODE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Granted — proceed to open home
                openHomePage()
            } else {
                // FIX (b): denied — show reason and ask AGAIN immediately (loop)
                Toast.makeText(
                    this,
                    "❌ Permission denied.\nThis app cannot work without Location permission.\nPlease allow it.",
                    Toast.LENGTH_LONG
                ).show()
                Handler(mainLooper).postDelayed({
                    // Re-request — will keep appearing until user grants it
                    requestPermissions(
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                        PERMISSION_REQUEST_CODE
                    )
                }, 2500)
            }
        }
    }

    // ── Open home page (CCT) ───────────────────────────────────────────────

    private fun openHomePage() {
        openInCustomTab(LIBRARY_URL)
    }

    // ── Entrance Animations ────────────────────────────────────────────────

    private fun runEntranceAnimations() {
        val card    = findViewById<CardView>(R.id.mainCard)
        val icon    = findViewById<ImageView>(R.id.appIcon)
        val appName = findViewById<TextView>(R.id.appName)
        val divider = findViewById<View>(R.id.divider)
        val version = findViewById<TextView>(R.id.appVersion)
        val btnDash = findViewById<Button>(R.id.btnDashboard)
        val btnHome = findViewById<Button>(R.id.btnHome)
        val btnExit = findViewById<Button>(R.id.btnExit)

        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: PackageManager.NameNotFoundException) { "—" }
        version.text = "Version $versionName"

        val cardAnim    = AnimationUtils.loadAnimation(this, R.anim.card_slide_up)
        val iconAnim    = AnimationUtils.loadAnimation(this, R.anim.icon_bounce)
        val titleAnim   = AnimationUtils.loadAnimation(this, R.anim.title_fade_in)
        val buttonsAnim = AnimationUtils.loadAnimation(this, R.anim.buttons_fade_in)

        card.visibility = View.VISIBLE
        card.startAnimation(cardAnim)
        Handler(Looper.getMainLooper()).postDelayed({
            icon.visibility = View.VISIBLE; icon.startAnimation(iconAnim)
        }, 200)
        Handler(Looper.getMainLooper()).postDelayed({
            appName.visibility = View.VISIBLE; divider.visibility = View.VISIBLE
            version.visibility = View.VISIBLE
            appName.startAnimation(titleAnim); divider.startAnimation(titleAnim)
            version.startAnimation(titleAnim)
        }, 350)
        Handler(Looper.getMainLooper()).postDelayed({
            btnDash.visibility = View.VISIBLE; btnHome.visibility = View.VISIBLE
            btnExit.visibility = View.VISIBLE
            btnDash.startAnimation(buttonsAnim); btnHome.startAnimation(buttonsAnim)
            btnExit.startAnimation(buttonsAnim)
        }, 700)
        Handler(Looper.getMainLooper()).postDelayed({
            floatAnimator = AnimatorInflater
                .loadAnimator(this, R.animator.card_float) as ObjectAnimator
            floatAnimator?.target = card; floatAnimator?.start()
        }, 1300)
    }

    private fun animateButtonPress(view: View, action: () -> Unit) {
        view.animate().scaleX(0.93f).scaleY(0.93f).setDuration(80).withEndAction {
            view.animate().scaleX(1f).scaleY(1f).setDuration(100).withEndAction { action() }.start()
        }.start()
    }

    // ── Chrome Custom Tab ──────────────────────────────────────────────────

    private fun openInCustomTab(url: String) {
        val cti = CustomTabsIntent.Builder()
            .setShowTitle(true)
            .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
            .build()
        cti.intent.apply {
            setPackage("com.android.chrome")
            addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
        }
        try {
            cti.launchUrl(this, Uri.parse(url))
        } catch (e: Exception) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY))
        }
    }

    // FIX (e): bring MainActivity to front so dialog shows OVER the CCT
    private fun closeCctAndBringToFront() {
        try {
            startActivity(Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            })
        } catch (e: Exception) { Log.e("CCT", "reorder failed: ${e.message}") }

        try {
            startActivity(Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            Handler(Looper.getMainLooper()).postDelayed({
                packageManager.getLaunchIntentForPackage(packageName)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }?.also { startActivity(it) }
            }, 300)
        } catch (e: Exception) { Log.e("CCT", "home+reopen failed: ${e.message}") }
    }

    // ── FIX (c) & (f): Check for pending update on EVERY open ────────────
    // KEY_READY stays true across app restarts until user taps "Install Now".
    // So even after closing and reopening the app the dialog reappears.

    private fun checkForUpdateOnOpen() {
        updateScope.launch {
            val prefs = getSharedPreferences(UpdateConfig.PREFS, Context.MODE_PRIVATE)

            // Case 1: update already downloaded and waiting
            if (prefs.getBoolean(UpdateConfig.KEY_READY, false)) {
                val apkFile = getApkFile(this@MainActivity)
                if (apkFile.exists()) {
                    val savedVersion = prefs.getString(UpdateConfig.KEY_VERSION, "") ?: ""
                    // FIX (e): bring this Activity to front before showing dialog
                    closeCctAndBringToFront()
                    delay(700)
                    promptInstall(this@MainActivity, savedVersion)
                    return@launch
                } else {
                    // APK was cleared (e.g. uninstall cleaned storage) — reset flag
                    prefs.edit().remove(UpdateConfig.KEY_READY).remove(UpdateConfig.KEY_VERSION).apply()
                }
            }

            // Case 2: no pending APK — check GitHub for new version
            if (!isNetworkAvailable()) return@launch
            val latestVersion = fetchLatestVersionName() ?: return@launch
            val currentVersion = try {
                packageManager.getPackageInfo(packageName, 0).versionName
            } catch (e: PackageManager.NameNotFoundException) { return@launch }
            if (!isNewer(latestVersion, currentVersion)) return@launch

            Log.d(UpdateConfig.TAG, "Newer version $latestVersion found — downloading…")
            val success = downloadApkSilently(this@MainActivity)
            if (success) {
                // FIX (e): bring to front so dialog is visible
                closeCctAndBringToFront()
                delay(700)
                promptInstall(this@MainActivity, latestVersion)
            }
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        return cm.activeNetworkInfo?.isConnected == true
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    override fun onDestroy() {
        super.onDestroy()
        floatAnimator?.cancel()
        updateScope.cancel()
        stopApkFileWatcher()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    @Deprecated("Overridden to disable back navigation")
    override fun onBackPressed() { /* disabled */ }

    // ── Deep link handler ──────────────────────────────────────────────────

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun handleDeepLink(intent: Intent) {
        val data: Uri = intent.data ?: return
        if (data.scheme != "mylibraryapp") return
        when (data.host) {
            "wifi" -> {
                val ssid        = data.getQueryParameter("ssid")              ?: ""
                val pass        = data.getQueryParameter("pass")              ?: ""
                val ssid2       = data.getQueryParameter("ssid2")             ?: ""
                val pass2       = data.getQueryParameter("pass2")             ?: ""
                val survivalStr = data.getQueryParameter("usersurvivaltime")  ?: "0"
                val name        = data.getQueryParameter("name")              ?: ""
                val father      = data.getQueryParameter("father")            ?: ""
                val phone       = data.getQueryParameter("phone")             ?: ""
                val address     = data.getQueryParameter("address")           ?: ""

                val survivalDays = survivalStr.toDoubleOrNull() ?: 0.0

                UserDataManager.saveAll(
                    context      = this,
                    name         = name,
                    father       = father,
                    phone        = phone,
                    address      = address,
                    ssid         = ssid,
                    pass         = pass,
                    ssid2        = ssid2,
                    pass2        = pass2,
                    survivalDays = survivalDays
                )

                if (ssid.isNotBlank() && pass.isNotBlank()) {
                    connectToLibraryWifi(ssid, pass, ssid2, pass2)
                }

                // Open Offline Dashboard immediately after saving data
                startActivity(Intent(this, OfflineDashboardActivity::class.java))
            }
            "forgetwifi" -> forgetLibraryWifi()
            "getdeviceid" -> handleGetDeviceId(data.getQueryParameter("callback"))
        }
    }

    // ── MediaDrm ──────────────────────────────────────────────────────────

    private fun handleGetDeviceId(callbackUrl: String?) {
        try {
            val mediaDrm      = MediaDrm(WIDEVINE_UUID)
            val deviceIdBytes = mediaDrm.getPropertyByteArray(MediaDrm.PROPERTY_DEVICE_UNIQUE_ID)
            mediaDrm.close()
            val deviceIdHex = deviceIdBytes.joinToString("") { "%02x".format(it) }
            val returnUrl = if (!callbackUrl.isNullOrBlank())
                Uri.parse(callbackUrl).buildUpon().appendQueryParameter("drm_id", deviceIdHex).build().toString()
            else LIBRARY_URL
            openInCustomTab(returnUrl)
        } catch (e: Exception) {
            Log.e("MediaDrm", "Failed: ${e.message}")
            Toast.makeText(this, "Device verification failed. Please try again.", Toast.LENGTH_LONG).show()
            val errorUrl = if (!callbackUrl.isNullOrBlank())
                Uri.parse(callbackUrl).buildUpon().appendQueryParameter("drm_error", "extraction_failed").build().toString()
            else LIBRARY_URL
            openInCustomTab(errorUrl)
        }
    }

    // ── Wi-Fi helpers ──────────────────────────────────────────────────────

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun connectToLibraryWifi(ssid: String, pass: String, ssid2: String = "", pass2: String = "") {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Location permission was revoked. Please allow it in Settings.",
                Toast.LENGTH_LONG).show()
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), PERMISSION_REQUEST_CODE)
            return
        }
        val suggestions = mutableListOf(
            WifiNetworkSuggestion.Builder()
                .setSsid(ssid).setWpa2Passphrase(pass)
                .setIsAppInteractionRequired(true).setPriority(999).build()
        )
        if (ssid2.isNotBlank() && pass2.isNotBlank()) {
            suggestions.add(
                WifiNetworkSuggestion.Builder()
                    .setSsid(ssid2).setWpa2Passphrase(pass2)
                    .setIsAppInteractionRequired(true).setPriority(998).build()
            )
        }
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wm.removeNetworkSuggestions(wm.networkSuggestions)
        wm.addNetworkSuggestions(suggestions)
        // Offline Dashboard shows the result UI — no Toast needed here
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun forgetLibraryWifi() {
        UserDataManager.deleteAll(this)
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wm.removeNetworkSuggestions(wm.networkSuggestions)
        Toast.makeText(this, "Disconnected from Library Wi-Fi.\nMembership ended.", Toast.LENGTH_LONG).show()
    }
}
