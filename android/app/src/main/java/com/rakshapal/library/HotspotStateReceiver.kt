package com.rakshapal.library

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.util.Log

/**
 * HotspotStateReceiver
 * ─────────────────────
 * Listens for the device's mobile hotspot (Wi-Fi AP) turning ON.
 * The moment it turns on, this fires INSTANTLY (event-driven, not polled)
 * and removes any Wi-Fi network suggestions made by this app — so the
 * app's suggested library Wi-Fi never conflicts with the user's own
 * hotspot being active at the same time.
 *
 * Registered as a dynamic (runtime) receiver from MainActivity.onCreate()
 * because WIFI_AP_STATE_CHANGED is NOT exposed for manifest-declared
 * (static) receivers since Android 8.0 (API 26) — it must be registered
 * in code while the app process is alive.
 */
object HotspotStateReceiver {

    private const val TAG = "HotspotStateReceiver"

    // The actual broadcast action used by AOSP for hotspot state changes.
    // This is a hidden/internal Android action (no public constant exists),
    // but it is stable and has been used since Android 8 across all OEMs.
    private const val WIFI_AP_STATE_CHANGED_ACTION =
        "android.net.wifi.WIFI_AP_STATE_CHANGED"

    // Internal extra key + state values mirrored from WifiManager's hidden API
    private const val EXTRA_WIFI_AP_STATE = "wifi_state"
    private const val WIFI_AP_STATE_ENABLED = 13   // matches WifiManager.WIFI_AP_STATE_ENABLED

    private var receiver: BroadcastReceiver? = null

    fun register(context: Context) {
        if (receiver != null) return  // already registered

        receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != WIFI_AP_STATE_CHANGED_ACTION) return

                val state = intent.getIntExtra(EXTRA_WIFI_AP_STATE, -1)
                Log.d(TAG, "Hotspot state changed: $state")

                if (state == WIFI_AP_STATE_ENABLED) {
                    Log.d(TAG, "Hotspot turned ON — removing app Wi-Fi suggestions immediately.")
                    try {
                        val wifiManager = ctx.applicationContext
                            .getSystemService(Context.WIFI_SERVICE) as WifiManager
                        wifiManager.removeNetworkSuggestions(wifiManager.networkSuggestions)
                        Log.d(TAG, "Wi-Fi suggestions removed due to hotspot being enabled.")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to remove suggestions on hotspot enable: ${e.message}")
                    }
                }
            }
        }

        val filter = IntentFilter(WIFI_AP_STATE_CHANGED_ACTION)
        context.applicationContext.registerReceiver(receiver, filter)
        Log.d(TAG, "HotspotStateReceiver registered.")
    }

    fun unregister(context: Context) {
        receiver?.let {
            try {
                context.applicationContext.unregisterReceiver(it)
            } catch (e: Exception) {
                Log.w(TAG, "Receiver already unregistered: ${e.message}")
            }
        }
        receiver = null
    }
}
