package com.rakshapal.library

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent

class MainActivity : AppCompatActivity() {

    private val LIBRARY_URL = "https://rakshapal-singh-library-ded2e.web.app"
    private val PERMISSION_REQUEST_CODE = 1001

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
        setIntent(intent)
        handleDeepLink(intent)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onResume() {
        super.onResume()
        // After deep link is handled, reopen website automatically
        if (intent.data != null) {
            intent.data = null
            openInCustomTab(LIBRARY_URL)
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

    private fun openInCustomTab(url: String) {
        val customTabsIntent = CustomTabsIntent.Builder()
            .setShowTitle(true)
            .build()
        customTabsIntent.intent.setPackage("com.android.chrome")
        try {
            customTabsIntent.launchUrl(this, Uri.parse(url))
        } catch (e: Exception) {
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(browserIntent)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun handleDeepLink(intent: Intent) {
        val data: Uri = intent.data ?: return
        if (data.scheme != "rakshapallibrary") return

        when (data.host) {
            "wifi" -> {
                val ssid = data.getQueryParameter("ssid") ?: return
                val pass = data.getQueryParameter("pass") ?: return
                connectToLibraryWifi(ssid, pass)
            }
            "forgetwifi" -> {
                forgetLibraryWifi()
            }
        }
    }

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

        val wifiManager = applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return

        val suggestion = WifiNetworkSuggestion.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(pass)
            .setPriority(999)
            .build()

        val suggestionsList = listOf(suggestion)
        wifiManager.removeNetworkSuggestions(suggestionsList)
        val status = wifiManager.addNetworkSuggestions(suggestionsList)

        if (status == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
            Toast.makeText(
                this,
                "Connecting to Library Wi-Fi...\nYou will be connected shortly.",
                Toast.LENGTH_LONG
            ).show()
        } else {
            Toast.makeText(
                this,
                "Could not connect. Error: $status",
                Toast.LENGTH_LONG
            ).show()
        }
    }

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
    }
}
