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
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private val LIBRARY_URL = "https://rakshapal-singh-library-ded2e.web.app"
    private val PERMISSION_REQUEST_CODE = 1001

    // Widevine UUID — the standard DRM system used to get a stable hardware device ID
    private val WIDEVINE_UUID = UUID(-0x121074568629b532L, -0x5c37d8232ae2de13L)

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

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

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    /**
     * Block the back button entirely — the user should never be able to
     * close the CCT or land on the bare app background by pressing back.
     * On Android 13+ this is handled via OnBackPressedCallback; for older
     * versions we override the legacy method.
     */
    @Deprecated("Overridden to disable back navigation")
    override fun onBackPressed() {
        // Intentionally do nothing — swallow the back press
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

    /**
     * Opens the given URL in a Chrome Custom Tab.
     * - setShareState(NO_SHARE) removes the share button so the toolbar feels
     *   like part of the app rather than a browser.
     * - FLAG_ACTIVITY_NO_HISTORY ensures that if the CCT is somehow dismissed,
     *   it doesn't linger in the back stack showing the blue MainActivity behind it.
     */
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
            "forgetwifi" -> {
                forgetLibraryWifi()
            }
            // Website triggers: mylibraryapp://getdeviceid?callback=https://yoursite.com/verify
            // App extracts MediaDrm ID and redirects back to the website
            "getdeviceid" -> {
                val callbackUrl = data.getQueryParameter("callback")
                handleGetDeviceId(callbackUrl)
            }
        }
    }

    /**
     * Extracts the hardware MediaDrm (Widevine) Device ID and sends it back
     * to the website via the provided callback URL.
     *
     * Flow:
     *   1. Website opens: mylibraryapp://getdeviceid?callback=https://yoursite.com/verify
     *   2. App extracts the Widevine device ID (stable, hardware-bound)
     *   3. App opens: https://yoursite.com/verify?drm_id=<hex_id>
     *   4. Website receives the DRM ID and checks it against the DB
     */
    private fun handleGetDeviceId(callbackUrl: String?) {
        try {
            val mediaDrm = MediaDrm(WIDEVINE_UUID)
            val deviceIdBytes = mediaDrm.getPropertyByteArray(MediaDrm.PROPERTY_DEVICE_UNIQUE_ID)
            mediaDrm.close()

            // Convert byte array to a hex string for safe URL transport
            val deviceIdHex = deviceIdBytes.joinToString("") { "%02x".format(it) }

            Log.d("MediaDrm", "Device ID extracted successfully")

            if (!callbackUrl.isNullOrBlank()) {
                val returnUri = Uri.parse(callbackUrl)
                    .buildUpon()
                    .appendQueryParameter("drm_id", deviceIdHex)
                    .build()
                    .toString()
                openInCustomTab(returnUri)
            } else {
                Log.w("MediaDrm", "No callback URL provided, returning to main site")
                openInCustomTab(LIBRARY_URL)
            }

        } catch (e: Exception) {
            Log.e("MediaDrm", "Failed to extract Device ID: ${e.message}")
            Toast.makeText(
                this,
                "Device verification failed. Please try again.",
                Toast.LENGTH_LONG
            ).show()

            if (!callbackUrl.isNullOrBlank()) {
                val errorUri = Uri.parse(callbackUrl)
                    .buildUpon()
                    .appendQueryParameter("drm_error", "extraction_failed")
                    .build()
                    .toString()
                openInCustomTab(errorUri)
            } else {
                // Fallback: reopen library so the blue screen is never left exposed
                openInCustomTab(LIBRARY_URL)
            }
        }
    }

    /**
     * Connects to the library Wi-Fi, then immediately reopens the CCT
     * so the user never sees the bare blue app background.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun connectToLibraryWifi(ssid: String, pass: String) {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(
                this,
                "Location permission was revoked. Please allow it in Settings.",
                Toast.LENGTH_LONG
            ).show()
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                PERMISSION_REQUEST_CODE
            )
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

        if (status == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
            Toast.makeText(
                this,
                "Connecting to Library Wi-Fi...\nYou will be connected shortly.",
                Toast.LENGTH_LONG
            ).show()
        } else {
            Toast.makeText(
                this,
                "Could not connect. Please check Wi-Fi settings.",
                Toast.LENGTH_LONG
            ).show()
        }

        // Always reopen the CCT immediately after Wi-Fi action so the
        // user is never left staring at the bare app background.
        openInCustomTab(LIBRARY_URL)
    }

    /**
     * Disconnects from library Wi-Fi, then immediately reopens the CCT
     * so the user never sees the bare blue app background.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun forgetLibraryWifi() {
        val wifiManager = applicationContext
            .getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiManager.removeNetworkSuggestions(wifiManager.networkSuggestions)
        Toast.makeText(
            this,
            "Disconnected from Library Wi-Fi.\nMembership ended.",
            Toast.LENGTH_LONG
        ).show()

        // Reopen the CCT immediately so the user is never left on the
        // bare blue app background after the membership ends.
        openInCustomTab(LIBRARY_URL)
    }
}
