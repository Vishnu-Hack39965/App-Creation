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

    // Tracks the last CCT intent so we can close it
    private var lastCctIntent: Intent? = null

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        schedulePeriodicCheck(this)
        runEntranceAnimations()

        findViewById<Button>(R.id.btnHome).setOnClickListener { view ->
            animateButtonPress(view) { openInCustomTab(LIBRARY_URL) }
        }
        findViewById<Button>(R.id.btnExit).setOnClickListener { view ->
            animateButtonPress(view) {
                finishAffinity()
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }

        checkForUpdateOnOpen()
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

    // ── Entrance Animations ────────────────────────────────────────────────

    private fun runEntranceAnimations() {
        val card    = findViewById<CardView>(R.id.mainCard)
        val icon    = findViewById<ImageView>(R.id.appIcon)
        val appName = findViewById<TextView>(R.id.appName)
        val divider = findViewById<View>(R.id.divider)
        val version = findViewById<TextView>(R.id.appVersion)
        val btnHome = findViewById<Button>(R.id.btnHome)
        val btnExit = findViewById<Button>(R.id.btnExit)

        // ── Fix 2: Set version dynamically from PackageManager ──
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
            icon.visibility = View.VISIBLE
            icon.startAnimation(iconAnim)
        }, 200)

        Handler(Looper.getMainLooper()).postDelayed({
            appName.visibility = View.VISIBLE
            divider.visibility = View.VISIBLE
            version.visibility = View.VISIBLE
            appName.startAnimation(titleAnim)
            divider.startAnimation(titleAnim)
            version.startAnimation(titleAnim)
        }, 350)

        Handler(Looper.getMainLooper()).postDelayed({
            btnHome.visibility = View.VISIBLE
            btnExit.visibility = View.VISIBLE
            btnHome.startAnimation(buttonsAnim)
            btnExit.startAnimation(buttonsAnim)
        }, 700)

        Handler(Looper.getMainLooper()).postDelayed({
            floatAnimator = AnimatorInflater
                .loadAnimator(this, R.animator.card_float) as ObjectAnimator
            floatAnimator?.target = card
            floatAnimator?.start()
        }, 1300)
    }

    private fun animateButtonPress(view: View, action: () -> Unit) {
        view.animate().scaleX(0.93f).scaleY(0.93f).setDuration(80).withEndAction {
            view.animate().scaleX(1f).scaleY(1f).setDuration(100).withEndAction { action() }.start()
        }.start()
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
            lastCctIntent = customTabsIntent.intent
        } catch (e: Exception) {
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            browserIntent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
            startActivity(browserIntent)
        }
    }

    // ── Fix 3: Close CCT and bring our Activity to front ──────────────────

    /**
     * Closes the Chrome Custom Tab by broadcasting its close action,
     * then moves MainActivity back to the foreground.
     */
    private fun closeCctAndBringToFront() {
        // Broadcast the CCT close intent (Chrome listens for this)
        try {
            val closeIntent = Intent().apply {
                action = "android.support.customtabs.action.CustomTabsService"
                setPackage("com.android.chrome")
            }
            // Move our own task to front first
            val bringFront = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(bringFront)
        } catch (e: Exception) {
            Log.e("CCT", "Could not close CCT: ${e.message}")
        }

        // Additionally send HOME then reopen our task — this reliably dismisses CCT
        try {
            val home = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(home)

            // After 300ms bring our app back to front
            Handler(Looper.getMainLooper()).postDelayed({
                val reopen = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                if (reopen != null) startActivity(reopen)
            }, 300)
        } catch (e: Exception) {
            Log.e("CCT", "Home+reopen failed: ${e.message}")
        }
    }

    // ── On-open update check ───────────────────────────────────────────────

    private fun checkForUpdateOnOpen() {
        updateScope.launch {
            val prefs = getSharedPreferences("update_prefs", Context.MODE_PRIVATE)
            if (prefs.getBoolean("update_ready", false)) {
                val apkFile = File(
                    getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                    UpdateConfig.APK_FILE_NAME
                )
                if (apkFile.exists()) {
                    val savedVersion = prefs.getString("update_version", "") ?: ""
                    prefs.edit().putBoolean("update_ready", false).apply()
                    closeCctAndBringToFront()
                    // Wait for activity to come to front before showing dialog
                    delay(600)
                    promptInstall(this@MainActivity, savedVersion)
                    return@launch
                }
            }

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
                closeCctAndBringToFront()
                // Wait for activity to come to front before showing dialog
                delay(600)
                promptInstall(this@MainActivity, latestVersion)
            }
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE)
                as android.net.ConnectivityManager
        return cm.activeNetworkInfo?.isConnected == true
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    override fun onDestroy() {
        super.onDestroy()
        floatAnimator?.cancel()
        updateScope.cancel()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    @Deprecated("Overridden to disable back navigation")
    override fun onBackPressed() { }

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
                Toast.makeText(this,
                    "Location permission is required to use this app.\nPlease allow it to continue.",
                    Toast.LENGTH_LONG).show()
                Handler(mainLooper).postDelayed({
                    requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                        PERMISSION_REQUEST_CODE)
                }, 3000)
            }
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
            "getdeviceid" -> handleGetDeviceId(data.getQueryParameter("callback"))
        }
    }

    // ── MediaDrm device ID ─────────────────────────────────────────────────

    private fun handleGetDeviceId(callbackUrl: String?) {
        try {
            val mediaDrm      = MediaDrm(WIDEVINE_UUID)
            val deviceIdBytes = mediaDrm.getPropertyByteArray(MediaDrm.PROPERTY_DEVICE_UNIQUE_ID)
            mediaDrm.close()
            val deviceIdHex = deviceIdBytes.joinToString("") { "%02x".format(it) }
            if (!callbackUrl.isNullOrBlank()) {
                val returnUri = Uri.parse(callbackUrl).buildUpon()
                    .appendQueryParameter("drm_id", deviceIdHex).build().toString()
                openInCustomTab(returnUri)
            } else { openInCustomTab(LIBRARY_URL) }
        } catch (e: Exception) {
            Log.e("MediaDrm", "Failed: ${e.message}")
            Toast.makeText(this, "Device verification failed. Please try again.",
                Toast.LENGTH_LONG).show()
            if (!callbackUrl.isNullOrBlank()) {
                val errorUri = Uri.parse(callbackUrl).buildUpon()
                    .appendQueryParameter("drm_error", "extraction_failed").build().toString()
                openInCustomTab(errorUri)
            } else { openInCustomTab(LIBRARY_URL) }
        }
    }

    // ── Wi-Fi helpers ──────────────────────────────────────────────────────

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun connectToLibraryWifi(ssid: String, pass: String) {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Location permission was revoked. Please allow it in Settings.",
                Toast.LENGTH_LONG).show()
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                PERMISSION_REQUEST_CODE)
            return
        }
        val suggestion = WifiNetworkSuggestion.Builder()
            .setSsid(ssid).setWpa2Passphrase(pass)
            .setIsAppInteractionRequired(true).setPriority(999).build()
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiManager.disconnect()
        wifiManager.removeNetworkSuggestions(wifiManager.networkSuggestions)
        val status = wifiManager.addNetworkSuggestions(listOf(suggestion))
        Toast.makeText(this,
            if (status == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS)
                "Connecting to Library Wi-Fi…\nYou will be connected shortly."
            else "Could not connect. Please check Wi-Fi settings.",
            Toast.LENGTH_LONG).show()
        openInCustomTab(LIBRARY_URL)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun forgetLibraryWifi() {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiManager.removeNetworkSuggestions(wifiManager.networkSuggestions)
        Toast.makeText(this, "Disconnected from Library Wi-Fi.\nMembership ended.",
            Toast.LENGTH_LONG).show()
        openInCustomTab(LIBRARY_URL)
    }
}
