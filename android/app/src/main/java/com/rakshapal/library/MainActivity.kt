package com.rakshapal.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        setupWebView()

        // Handle deep link if app was opened via one
        handleDeepLink(intent)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Handle deep link if app was already running
        handleDeepLink(intent)
    }

    // -------------------------------------------------------
    // DEEP LINK HANDLER
    // Handles two routes:
    //   mylibraryapp://wifi?ssid=...&pass=...  → connect WiFi
    //   mylibraryapp://forgetwifi              → disconnect WiFi
    // -------------------------------------------------------
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
        }
    }

    // -------------------------------------------------------
    // CONNECT TO LIBRARY WIFI
    // Uses Network Suggestion API (Android 10+).
    // Password is never shown on screen — comes silently
    // via deep link from your Firebase-backed website.
    // Android OS shows one permission dialog first time only.
    // If app is deleted, Android auto-removes this suggestion
    // and disconnects the device. No extra code needed.
    // -------------------------------------------------------
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun connectToLibraryWifi(ssid: String, pass: String) {
        val suggestion = WifiNetworkSuggestion.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(pass)
            .setIsAppInteractionRequired(true)
            .build()

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        // Remove any old suggestion first so updated password always applies
        wifiManager.removeNetworkSuggestions(wifiManager.networkSuggestions)

        val status = wifiManager.addNetworkSuggestions(listOf(suggestion))

        if (status == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
            // Show clear message to member — they know they are being connected
            Toast.makeText(
                this,
                "Connecting to Library Wi-Fi...\nYou will be connected shortly.",
                Toast.LENGTH_LONG
            ).show()
        } else {
            Toast.makeText(
                this,
                "Could not connect. Please allow Wi-Fi permission in Settings.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // -------------------------------------------------------
    // FORGET LIBRARY WIFI
    // Called when member exits membership.
    // Removes all network suggestions made by this app.
    // Device disconnects from library WiFi immediately.
    // Also called automatically by Android if app is deleted.
    // -------------------------------------------------------
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun forgetLibraryWifi() {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiManager.removeNetworkSuggestions(wifiManager.networkSuggestions)

        Toast.makeText(
            this,
            "Disconnected from Library Wi-Fi.\nMembership ended.",
            Toast.LENGTH_LONG
        ).show()
    }

    // -------------------------------------------------------
    // WEBVIEW SETUP
    // Loads your Firebase-hosted website.
    // Custom Chrome Tab handles OAuth / external links.
    // Deep links (mylibraryapp://) are intercepted here
    // and routed to handleDeepLink instead of opening browser.
    // -------------------------------------------------------
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url ?: return false

                // Intercept deep links — handle in app, don't open browser
                if (url.scheme == "mylibraryapp") {
                    handleDeepLink(Intent(Intent.ACTION_VIEW, url))
                    return true
                }

                // UPI payment links — open in UPI app
                if (url.scheme == "upi") {
                    val intent = Intent(Intent.ACTION_VIEW, url)
                    if (intent.resolveActivity(packageManager) != null) {
                        startActivity(intent)
                    } else {
                        Toast.makeText(
                            this@MainActivity,
                            "No UPI app found. Install PhonePe or GPay.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    return true
                }

                // Google Sign-In and other OAuth links — open in Custom Chrome Tab
                // so Google trusts the browser and sign-in works correctly
                if (url.host?.contains("accounts.google.com") == true ||
                    url.host?.contains("oauth") == true) {
                    openInCustomTab(url.toString())
                    return true
                }

                // All other links load inside the WebView (stays in app)
                return false
            }
        }

        // Load your Firebase hosted website
        webView.loadUrl("https://rakshapal-singh-library-ded2e.web.app")
    }

    // Opens a URL in Custom Chrome Tab — appears inside the app,
    // uses real Chrome engine so Google OAuth works correctly
    private fun openInCustomTab(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    // Handle back button inside WebView
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
