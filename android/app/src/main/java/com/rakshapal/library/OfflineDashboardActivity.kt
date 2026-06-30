package com.rakshapal.library

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.*
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.*

class OfflineDashboardActivity : AppCompatActivity() {

    private var currentSurvivalDays: Double = 0.0
    private val tickHandler = Handler(Looper.getMainLooper())
    private var tickRunnable: Runnable? = null

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_offline_dashboard)
        setupDashboard()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun setupDashboard() {
        val noDataLayout  = findViewById<LinearLayout>(R.id.layoutNoData)
        val dataLayout    = findViewById<ScrollView>(R.id.layoutData)
        val feesBanner    = findViewById<LinearLayout>(R.id.bannerFeesDue)
        val survivalText  = findViewById<TextView>(R.id.tvSurvivalTime)
        val validUntil    = findViewById<TextView>(R.id.tvValidUntil)
        val btnWifi1      = findViewById<Button>(R.id.btnWifi1)
        val btnWifi2      = findViewById<Button>(R.id.btnWifi2)
        val tvName        = findViewById<TextView>(R.id.tvName)
        val tvFather      = findViewById<TextView>(R.id.tvFather)
        val tvPhone       = findViewById<TextView>(R.id.tvPhone)
        val tvAddress     = findViewById<TextView>(R.id.tvAddress)
        // Two SEPARATE result blocks — one under each button
        val wifiResult1   = findViewById<LinearLayout>(R.id.layoutWifiResult1)
        val tvWifiResult1 = findViewById<TextView>(R.id.tvWifiResult1)
        val tvWifiSub1    = findViewById<TextView>(R.id.tvWifiSubtext1)
        val wifiResult2   = findViewById<LinearLayout>(R.id.layoutWifiResult2)
        val tvWifiResult2 = findViewById<TextView>(R.id.tvWifiResult2)
        val tvWifiSub2    = findViewById<TextView>(R.id.tvWifiSubtext2)
        val btnBack       = findViewById<Button>(R.id.btnBackToMain)

        // ── SAMPLE DATA BUTTON (testing) ──────────────────────────────────
        val btnSample = findViewById<Button>(R.id.btnLoadSampleData)
        btnSample.setOnClickListener {
            UserDataManager.saveAll(
                context      = this,
                name         = "Ramesh Kumar",
                father       = "Suresh Kumar",
                phone        = "9876543210",
                address      = "12, Gandhi Nagar, Agra, UP - 282001",
                ssid         = "Library_WiFi_5G",
                pass         = "library@2024",
                ssid2        = "Library_WiFi_2G",
                pass2        = "library@2024",
                survivalDays = 5.75   // ~5 days 18 hours remaining
            )
            Toast.makeText(this, "✅ Sample data loaded! Refreshing…", Toast.LENGTH_SHORT).show()
            setupDashboard()   // re-run to show data
        }

        btnBack.setOnClickListener { finish() }

        // ── Check if data exists ──────────────────────────────────────────
        if (!UserDataManager.filesExist(this)) {
            noDataLayout.visibility = View.VISIBLE
            dataLayout.visibility   = View.GONE
            feesBanner.visibility   = View.GONE
            btnSample.visibility    = View.VISIBLE
            return
        }

        btnSample.visibility    = View.VISIBLE   // keep visible for testing
        noDataLayout.visibility = View.GONE
        dataLayout.visibility   = View.VISIBLE

        // ── Load & update survival time ───────────────────────────────────
        currentSurvivalDays = UserDataManager.readAndUpdateSurvivalTime(this)

        // ── User data ─────────────────────────────────────────────────────
        val userData = UserDataManager.getUserData(this)
        tvName.text    = userData?.get("name")    ?: "—"
        tvFather.text  = userData?.get("father")  ?: "—"
        tvPhone.text   = userData?.get("phone")   ?: "—"
        tvAddress.text = userData?.get("address") ?: "—"

        // ── Wi-Fi button labels ───────────────────────────────────────────
        val wifiData = UserDataManager.getWifiData(this)
        val ssid1Label = if (!wifiData?.get("ssid").isNullOrBlank()) wifiData!!["ssid"]!! else "Internet"
        val ssid2Label = if (!wifiData?.get("ssid2").isNullOrBlank()) wifiData!!["ssid2"]!! else "Internet 2"
        btnWifi1.text = "🛜 Connect to $ssid1Label"
        btnWifi2.text = "🛜 Connect to $ssid2Label"

        // ── Fees due banner ───────────────────────────────────────────────
        if (currentSurvivalDays < -1.0) {
            feesBanner.visibility = View.VISIBLE
        } else {
            feesBanner.visibility = View.GONE
        }

        // ── Validity date ─────────────────────────────────────────────────
        updateValidityDate(validUntil)

        // ── Tick every second ─────────────────────────────────────────────
        startTicker(survivalText, validUntil, feesBanner)

        // ── Wi-Fi connect buttons ─────────────────────────────────────────
        val wifiClickListener = View.OnClickListener { view ->
            val isWifi2 = (view.id == R.id.btnWifi2)

            // Pick the correct result block based on which button was pressed
            val resultLayout = if (isWifi2) wifiResult2 else wifiResult1
            val tvMain        = if (isWifi2) tvWifiResult2 else tvWifiResult1
            val tvSub         = if (isWifi2) tvWifiSub2 else tvWifiSub1

            if (currentSurvivalDays < -1.0) {
                showWifiResult(resultLayout, tvMain, tvSub,
                    isError = true,
                    main    = "⚠️ Please, Pay your fees first",
                    sub     = "Your membership has expired. Visit the Home Page to renew.")
                return@OnClickListener
            }

            if (isLibraryClosed()) {
                showWifiResult(resultLayout, tvMain, tvSub,
                    isError = true,
                    main    = "🔒 Library Closed",
                    sub     = "Library is closed during 6:00–7:00 AM and 6:00–6:30 PM.")
                return@OnClickListener
            }

            // NEW: check if the device's own hotspot is currently turned on.
            // If it is, block the connect attempt and ask user to turn it off.
            if (isHotspotEnabled()) {
                showWifiResult(resultLayout, tvMain, tvSub,
                    isError = true,
                    main    = "📵 Please turn off hotspot first",
                    sub     = "Your mobile hotspot is currently ON. Turn it off, then try connecting again.")
                return@OnClickListener
            }

            // Connect
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val wd   = UserDataManager.getWifiData(this)
                val ssid = if (isWifi2) wd?.get("ssid2") ?: "" else wd?.get("ssid") ?: ""
                val pass = if (isWifi2) wd?.get("pass2") ?: "" else wd?.get("pass") ?: ""
                if (ssid.isBlank() || pass.isBlank()) {
                    showWifiResult(resultLayout, tvMain, tvSub,
                        isError = true,
                        main    = "❌ Wi-Fi data missing",
                        sub     = "Please visit Home Page to refresh your data.")
                    return@OnClickListener
                }
                connectWifi(ssid, pass, resultLayout, tvMain, tvSub)
            }
        }

        btnWifi1.setOnClickListener(wifiClickListener)
        btnWifi2.setOnClickListener(wifiClickListener)
    }

    // ── Ticker ────────────────────────────────────────────────────────────

    private fun startTicker(survivalText: TextView, validUntil: TextView, feesBanner: LinearLayout) {
        val startSurvival    = currentSurvivalDays
        val startEpoch       = System.currentTimeMillis()

        tickRunnable = object : Runnable {
            override fun run() {
                val elapsedDays = (System.currentTimeMillis() - startEpoch) / 86_400_000.0
                val remaining   = startSurvival - elapsedDays
                currentSurvivalDays = remaining

                survivalText.text  = formatSurvival(remaining)
                survivalText.setTextColor(
                    getColor(if (remaining >= 0) R.color.green_live else R.color.red_live)
                )
                updateValidityDate(validUntil)
                feesBanner.visibility = if (remaining < -1.0) View.VISIBLE else View.GONE

                tickHandler.postDelayed(this, 1000)
            }
        }
        tickHandler.post(tickRunnable!!)
    }

    private fun updateValidityDate(tv: TextView) {
        if (currentSurvivalDays > 0) {
            val cal = Calendar.getInstance()
            cal.add(Calendar.SECOND, (currentSurvivalDays * 86400).toLong().toInt())
            val sdf = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
            tv.text    = "📅 Valid until: ${sdf.format(cal.time)}"
            tv.visibility = View.VISIBLE
        } else {
            tv.text      = "⛔ Membership expired"
            tv.visibility = View.VISIBLE
        }
    }

    // ── Library closed check ──────────────────────────────────────────────

    private fun isLibraryClosed(): Boolean {
        val cal     = Calendar.getInstance()
        val hour    = cal.get(Calendar.HOUR_OF_DAY)
        val minute  = cal.get(Calendar.MINUTE)
        val totalMin = hour * 60 + minute
        // 6:00 AM – 7:00 AM  → 360..419
        // 6:00 PM – 6:30 PM  → 1080..1109
        return totalMin in 360..419 || totalMin in 1080..1109
    }

    // ── Hotspot check ────────────────────────────────────────────────────
    // WifiManager.isWifiApEnabled() is a hidden/internal API (no public
    // method exists), so it's accessed via reflection. This is the same
    // approach used system-wide by apps that need to detect hotspot state.
    private fun isHotspotEnabled(): Boolean {
        return try {
            val wifiManager = applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            val method = wifiManager.javaClass.getMethod("isWifiApEnabled")
            method.isAccessible = true
            method.invoke(wifiManager) as? Boolean ?: false
        } catch (e: Exception) {
            // If reflection fails on a particular OEM/Android version,
            // fail safe by assuming hotspot is OFF so the app doesn't
            // permanently block connecting.
            false
        }
    }

    // ── Wi-Fi connect ─────────────────────────────────────────────────────

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun connectWifi(
        ssid: String, pass: String,
        resultLayout: LinearLayout,
        tvMain: TextView,
        tvSub: TextView
    ) {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            showWifiResult(resultLayout, tvMain, tvSub,
                isError = true,
                main    = "❌ Location permission required",
                sub     = "Please allow location permission in Settings.")
            return
        }

        val suggestion = WifiNetworkSuggestion.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(pass)
            .setIsAppInteractionRequired(true)
            .setPriority(999)
            .build()

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiManager.removeNetworkSuggestions(wifiManager.networkSuggestions)
        val status = wifiManager.addNetworkSuggestions(listOf(suggestion))

        if (status == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
            showWifiResult(resultLayout, tvMain, tvSub,
                isError = false,
                main    = "✅ Successful, Wi-Fi will be connected shortly",
                sub     = "Please disconnect from other Wi-Fi if connected.")
        } else {
            showWifiResult(resultLayout, tvMain, tvSub,
                isError = true,
                main    = "❌ Could not connect",
                sub     = "Please check Wi-Fi settings or try again.")
        }
    }

    private fun showWifiResult(
        layout: LinearLayout, tvMain: TextView, tvSub: TextView,
        isError: Boolean, main: String, sub: String
    ) {
        layout.visibility  = View.VISIBLE
        layout.setBackgroundResource(
            if (isError) R.drawable.bg_result_error else R.drawable.bg_result_success
        )
        tvMain.text = main
        tvSub.text  = sub
    }

    // ── Survival formatter ────────────────────────────────────────────────

    private fun formatSurvival(decimalDays: Double): String {
        val sign = if (decimalDays < 0) "-" else ""
        val ts   = Math.floor(Math.abs(decimalDays) * 86400).toLong()
        val d    = ts / 86400
        val h    = (ts % 86400) / 3600
        val m    = (ts % 3600) / 60
        val s    = ts % 60
        return "$sign${d.toString().padStart(2,'0')}:${h.toString().padStart(2,'0')}:${m.toString().padStart(2,'0')}:${s.toString().padStart(2,'0')}"
    }

    override fun onDestroy() {
        super.onDestroy()
        tickRunnable?.let { tickHandler.removeCallbacks(it) }
    }
}
