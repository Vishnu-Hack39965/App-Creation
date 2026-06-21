package com.rakshapal.library

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
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
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.FileProvider
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException

class MainActivity : AppCompatActivity() {

    // ─── Constants ────────────────────────────────────────────────────────────
    private val LIBRARY_URL            = "https://rakshapal-singh-library-ded2e.web.app"
    private val PERMISSION_REQUEST_CODE = 1001
    private val RC_SIGN_IN             = 9001

    // ── GitHub repo for auto-update (owner/repo) ────────────────────────────
    // Replace with your actual GitHub username and repo name:
    private val GITHUB_REPO            = "Vishnu-Hack39965/App-Creation"
    private val GITHUB_API_URL         = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"

    // ─── Firebase / Auth ──────────────────────────────────────────────────────
    private lateinit var auth            : FirebaseAuth
    private lateinit var googleSignInClient: GoogleSignInClient
    private lateinit var webView         : WebView
    private lateinit var signinOverlay   : FrameLayout

    // ─── Sign-in guard (prevents multiple taps) ───────────────────────────────
    private var isSigningIn = false

    // ─── Auto-update download ID ──────────────────────────────────────────────
    private var downloadId: Long = -1
    private var downloadReceiver: BroadcastReceiver? = null

    // ─────────────────────────────────────────────────────────────────────────
    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView        = findViewById(R.id.webView)
        signinOverlay  = findViewById(R.id.signinLoadingOverlay)

        // 1. Firebase Auth
        auth = FirebaseAuth.getInstance()

        // 2. Google Sign-In — uses your web_client_id from google-services.json
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id))
            .requestEmail()
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)

        // 3. Setup native WebView
        setupWebView()

        // 4. Handle deep links (Wi-Fi)
        handleDeepLink(intent)

        // 5. Request location permission, then load the page
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            loadWebApp()
        } else {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                PERMISSION_REQUEST_CODE
            )
        }

        // 6. Check for app updates (non-blocking, runs in background)
        checkForUpdate()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // FEATURE 2 — Native WebView (no CCT for main content)
    // ─────────────────────────────────────────────────────────────────────────
    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled      = true
            domStorageEnabled      = true
            databaseEnabled        = true
            loadWithOverviewMode   = true
            useWideViewPort        = true
            allowFileAccess        = false
            setSupportZoom(false)
            builtInZoomControls    = false
            displayZoomControls    = false
        }

        // Inject JS bridge so the webpage can call native Kotlin functions
        webView.addJavascriptInterface(WebAppInterface(), "AndroidBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url.toString()
                // FEATURE 3 — If it's a Google sign-in URL → open in CCT
                if (isGoogleSignInUrl(url)) {
                    openInCustomTab(url)
                    return true
                }
                // All other URLs load natively inside the WebView
                return false
            }
        }

        webView.webChromeClient = WebChromeClient()
    }

    private fun loadWebApp() {
        webView.loadUrl(LIBRARY_URL)
    }

    // Returns true if the URL belongs to Google's sign-in / OAuth flow
    private fun isGoogleSignInUrl(url: String): Boolean {
        return url.contains("accounts.google.com") ||
               url.contains("oauth2.googleapis.com") ||
               url.contains("googleapis.com/identitytoolkit") ||
               url.contains("securetoken.googleapis.com")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // FEATURE 3 — Google Sign-In via CCT only
    // FEATURE 4 — Loading overlay while signing in
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Called from the webpage via AndroidBridge.startGoogleSignIn()
     * OR from the native sign-in button.
     */
    private fun startGoogleSignIn() {
        if (isSigningIn) {
            // Guard: user already tapped — ignore extra taps
            Toast.makeText(this, "Sign-in already in progress…", Toast.LENGTH_SHORT).show()
            return
        }
        isSigningIn = true

        // Show the loading overlay so the user can't tap again
        signinOverlay.visibility = View.VISIBLE

        val signInIntent = googleSignInClient.signInIntent
        startActivityForResult(signInIntent, RC_SIGN_IN)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == RC_SIGN_IN) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            try {
                val account = task.getResult(ApiException::class.java)
                firebaseAuthWithGoogle(account)
            } catch (e: ApiException) {
                Log.w("SignIn", "Google sign-in failed: ${e.statusCode}")
                // Hide overlay — let user try again
                hideSigninOverlay()
                Toast.makeText(this, "Sign-in failed. Please try again.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun firebaseAuthWithGoogle(account: GoogleSignInAccount) {
        val credential = GoogleAuthProvider.getCredential(account.idToken, null)
        auth.signInWithCredential(credential)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    val user = auth.currentUser
                    Log.d("SignIn", "signInWithCredential:success — ${user?.email}")

                    // FEATURE 1 — Save user info to Firebase Realtime Database
                    saveUserToFirebase(user?.uid, user?.email, user?.displayName, user?.photoUrl?.toString())

                    // Notify the webpage that sign-in is complete
                    webView.evaluateJavascript(
                        "window.onNativeSignInSuccess('${user?.uid}','${user?.email}','${user?.displayName}','${user?.photoUrl}')",
                        null
                    )

                    // Hide the loading overlay — sign-in done
                    hideSigninOverlay()

                } else {
                    Log.w("SignIn", "signInWithCredential:failure", task.exception)
                    hideSigninOverlay()
                    Toast.makeText(this, "Authentication failed.", Toast.LENGTH_LONG).show()
                }
            }
    }

    private fun hideSigninOverlay() {
        runOnUiThread {
            signinOverlay.visibility = View.GONE
            isSigningIn = false
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // FEATURE 1 — Firebase Realtime Database
    // ─────────────────────────────────────────────────────────────────────────
    private fun saveUserToFirebase(uid: String?, email: String?, name: String?, photo: String?) {
        if (uid == null) return
        val db  = FirebaseDatabase.getInstance()
        val ref = db.getReference("users/$uid")
        val data = mapOf(
            "email"     to (email ?: ""),
            "name"      to (name  ?: ""),
            "photo"     to (photo ?: ""),
            "lastLogin" to System.currentTimeMillis()
        )
        ref.setValue(data)
            .addOnSuccessListener { Log.d("Firebase", "User saved: $uid") }
            .addOnFailureListener { Log.e("Firebase", "Save failed: ${it.message}") }
    }

    // Read any node from the database — called via JavaScript bridge
    private fun readFromFirebase(path: String, callbackFn: String) {
        val db  = FirebaseDatabase.getInstance()
        val ref = db.getReference(path)
        ref.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val json = snapshot.value?.let { JSONObject(it as Map<*, *>).toString() } ?: "null"
                webView.evaluateJavascript("$callbackFn($json)", null)
            }
            override fun onCancelled(error: DatabaseError) {
                webView.evaluateJavascript("$callbackFn(null)", null)
            }
        })
    }

    // ─────────────────────────────────────────────────────────────────────────
    // FEATURE 6 — Auto-update: checks GitHub releases, downloads & prompts install
    // ─────────────────────────────────────────────────────────────────────────
    private fun checkForUpdate() {
        val client  = OkHttpClient()
        val request = Request.Builder()
            .url(GITHUB_API_URL)
            .addHeader("Accept", "application/vnd.github.v3+json")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.d("AutoUpdate", "Update check failed: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) return
                try {
                    val body       = response.body?.string() ?: return
                    val json       = JSONObject(body)
                    val tagName    = json.getString("tag_name")       // e.g. "v1.0.42"
                    val latestCode = tagName.filter { it.isDigit() }.takeLast(4).toIntOrNull() ?: 0
                    val assets     = json.getJSONArray("assets")

                    // Find the APK asset
                    var apkUrl: String? = null
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        if (asset.getString("name").endsWith(".apk")) {
                            apkUrl = asset.getString("browser_download_url")
                            break
                        }
                    }

                    val currentCode = packageManager
                        .getPackageInfo(packageName, 0).versionCode

                    if (latestCode > currentCode && apkUrl != null) {
                        val finalUrl = apkUrl
                        runOnUiThread {
                            showUpdateDialog(tagName, finalUrl)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("AutoUpdate", "Parse error: ${e.message}")
                }
            }
        })
    }

    private fun showUpdateDialog(version: String, apkUrl: String) {
        AlertDialog.Builder(this)
            .setTitle("Update Available  🎉")
            .setMessage("A new version ($version) of the Library App is available.\n\nDownload and install now?")
            .setPositiveButton("Download & Install") { _, _ ->
                downloadAndInstallApk(apkUrl, version)
            }
            .setNegativeButton("Later", null)
            .setCancelable(false)
            .show()
    }

    private fun downloadAndInstallApk(url: String, version: String) {
        val fileName = "RakshapalLibrary-$version.apk"

        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("Library App Update")
            .setDescription("Downloading $version…")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, fileName)

        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        downloadId = dm.enqueue(request)

        Toast.makeText(this, "Downloading update… you'll be notified when ready.", Toast.LENGTH_LONG).show()

        // Register receiver to trigger install when download completes
        downloadReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    installApk(fileName)
                    unregisterReceiver(this)
                    downloadReceiver = null
                }
            }
        }
        registerReceiver(downloadReceiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
    }

    private fun installApk(fileName: String) {
        val file = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
        if (!file.exists()) {
            Toast.makeText(this, "Download not found.", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val install = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(install)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // FEATURE 7 — Wi-Fi connect / forget in background with Toast popup only
    // ─────────────────────────────────────────────────────────────────────────
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun connectToLibraryWifi(ssid: String, pass: String) {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Location permission required for Wi-Fi.", Toast.LENGTH_LONG).show()
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), PERMISSION_REQUEST_CODE)
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

        // Toast only — screen stays on current page (Feature 7)
        if (status == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
            Toast.makeText(this, "✅ Connecting to Library Wi-Fi…\nYou'll be connected shortly.", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "⚠️ Could not connect. Please check Wi-Fi settings.", Toast.LENGTH_LONG).show()
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun forgetLibraryWifi() {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiManager.removeNetworkSuggestions(wifiManager.networkSuggestions)
        // Toast only — no screen change (Feature 7)
        Toast.makeText(this, "📴 Disconnected from Library Wi-Fi.\nMembership ended.", Toast.LENGTH_LONG).show()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // JS Bridge — webpage calls these via AndroidBridge.methodName(...)
    // ─────────────────────────────────────────────────────────────────────────
    inner class WebAppInterface {

        /** Webpage calls this to start Google sign-in */
        @JavascriptInterface
        fun startGoogleSignIn() {
            runOnUiThread { this@MainActivity.startGoogleSignIn() }
        }

        /** Webpage calls this to read a Firebase path */
        @JavascriptInterface
        fun readFirebase(path: String, callbackFn: String) {
            readFromFirebase(path, callbackFn)
        }

        /** Webpage calls this to connect Wi-Fi in background */
        @RequiresApi(Build.VERSION_CODES.Q)
        @JavascriptInterface
        fun connectWifi(ssid: String, pass: String) {
            runOnUiThread { connectToLibraryWifi(ssid, pass) }
        }

        /** Webpage calls this to forget Wi-Fi in background */
        @RequiresApi(Build.VERSION_CODES.Q)
        @JavascriptInterface
        fun forgetWifi() {
            runOnUiThread { forgetLibraryWifi() }
        }

        /** Webpage calls this to trigger update check manually */
        @JavascriptInterface
        fun checkUpdate() {
            checkForUpdate()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CCT (Custom Tab) — only for Google Sign-In page
    // ─────────────────────────────────────────────────────────────────────────
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

    // ─────────────────────────────────────────────────────────────────────────
    // Deep-link handler (Wi-Fi actions from webpage links)
    // ─────────────────────────────────────────────────────────────────────────
    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
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
            "forgetwifi" -> forgetLibraryWifi()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Permission result
    // ─────────────────────────────────────────────────────────────────────────
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                loadWebApp()
            } else {
                Toast.makeText(
                    this,
                    "Location permission is required to use this app.\nPlease allow it to continue.",
                    Toast.LENGTH_LONG
                ).show()
                // Re-ask after 3 seconds
                Handler(Looper.getMainLooper()).postDelayed({
                    requestPermissions(
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                        PERMISSION_REQUEST_CODE
                    )
                }, 3000)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Back navigation inside WebView
    // ─────────────────────────────────────────────────────────────────────────
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Cleanup
    // ─────────────────────────────────────────────────────────────────────────
    override fun onDestroy() {
        super.onDestroy()
        downloadReceiver?.let { unregisterReceiver(it) }
    }
}
