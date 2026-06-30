package com.rakshapal.library

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Base64
import android.util.Log
import java.io.File

/**
 * UserDataManager
 * ──────────────
 * Handles reading/writing all offline user data to appdata (filesDir).
 *
 * File layout (all in context.filesDir):
 *   user_data.txt   – plain fields: name, father, phone, address
 *   wifi_data.txt   – ssid/pass/ssid2/pass2 in Base64
 *   survival.txt    – Base64( "<remaining_decimal_days>|<epoch_millis_saved_at>" )
 *
 * Survival time encoding:
 *   On write  → encode( "$remaining|$nowMillis" ) → Base64 → file
 *   On read   → Base64 decode → split → remaining − elapsedDays → new remaining
 *             → re-encode with current epoch → write back → return new remaining
 */
object UserDataManager {

    private const val TAG             = "UserDataManager"
    private const val FILE_USER       = "user_data.txt"
    private const val FILE_WIFI       = "wifi_data.txt"
    private const val FILE_SURVIVAL   = "survival.txt"

    // ─── Write (called from deep link) ────────────────────────────────────

    fun saveAll(
        context: Context,
        name: String,
        father: String,
        phone: String,
        address: String,
        ssid: String,
        pass: String,
        ssid2: String,
        pass2: String,
        survivalDays: Double
    ) {
        saveUserData(context, name, father, phone, address)
        saveWifiData(context, ssid, pass, ssid2, pass2)
        saveSurvivalTime(context, survivalDays)
        Log.d(TAG, "All user data saved to appdata.")
    }

    fun saveUserData(context: Context, name: String, father: String, phone: String, address: String) {
        val content = "name=$name\nfather=$father\nphone=$phone\naddress=$address"
        getFile(context, FILE_USER).writeText(content)
    }

    fun saveWifiData(context: Context, ssid: String, pass: String, ssid2: String, pass2: String) {
        // Each credential encoded separately for clarity
        val content = listOf(
            "ssid=" + encode(ssid),
            "pass=" + encode(pass),
            "ssid2=" + encode(ssid2),
            "pass2=" + encode(pass2)
        ).joinToString("\n")
        getFile(context, FILE_WIFI).writeText(content)
    }

    /** Encodes remaining days + current epoch → Base64 → writes file. */
    fun saveSurvivalTime(context: Context, remainingDays: Double) {
        val nowMillis = System.currentTimeMillis()
        val raw = "$remainingDays|$nowMillis"
        getFile(context, FILE_SURVIVAL).writeText(encode(raw))
        Log.d(TAG, "Survival saved: remaining=$remainingDays at epoch=$nowMillis")
    }

    // ─── Read ─────────────────────────────────────────────────────────────

    fun filesExist(context: Context): Boolean =
        getFile(context, FILE_USER).exists() &&
        getFile(context, FILE_WIFI).exists() &&
        getFile(context, FILE_SURVIVAL).exists()

    fun getUserData(context: Context): Map<String, String>? {
        val f = getFile(context, FILE_USER)
        if (!f.exists()) return null
        return f.readLines()
            .mapNotNull { line -> line.split("=", limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }
            .toMap()
    }

    /** Returns decoded wifi credentials (plaintext). */
    fun getWifiData(context: Context): Map<String, String>? {
        val f = getFile(context, FILE_WIFI)
        if (!f.exists()) return null
        return f.readLines()
            .mapNotNull { line ->
                val parts = line.split("=", limit = 2)
                if (parts.size == 2) parts[0] to decode(parts[1]) else null
            }
            .toMap()
    }

    /**
     * Reads survival file, computes updated remaining time,
     * re-saves the updated value with current epoch, and returns the new remaining days.
     */
    fun readAndUpdateSurvivalTime(context: Context): Double {
        val f = getFile(context, FILE_SURVIVAL)
        if (!f.exists()) return 0.0
        return try {
            val raw = decode(f.readText().trim())
            val parts = raw.split("|")
            val savedRemaining = parts[0].toDouble()
            val savedEpoch     = parts[1].toLong()
            val nowMillis      = System.currentTimeMillis()
            val elapsedDays    = (nowMillis - savedEpoch) / 86_400_000.0
            val newRemaining   = savedRemaining - elapsedDays
            // Re-save with updated value and new timestamp
            val newRaw = "$newRemaining|$nowMillis"
            f.writeText(encode(newRaw))
            Log.d(TAG, "Survival updated: was=$savedRemaining elapsed=$elapsedDays new=$newRemaining")

            // NEW: if survival time has dropped below -1, wipe only the
            // saved Wi-Fi credentials (ssid/pass/ssid2/pass2). user_data.txt
            // and survival.txt are left untouched.
            if (newRemaining < -1.0) {
                val wifiFile = getFile(context, FILE_WIFI)
                if (wifiFile.exists()) {
                    wifiFile.delete()
                    Log.d(TAG, "Survival < -1 ($newRemaining) — wifi_data.txt deleted.")
                }
                try {
                    val wm = context.applicationContext
                        .getSystemService(Context.WIFI_SERVICE) as WifiManager
                    wm.removeNetworkSuggestions(wm.networkSuggestions)
                    Log.d(TAG, "Survival < -1 — Wi-Fi network suggestions removed.")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to remove Wi-Fi suggestions: ${e.message}")
                }
            }

            newRemaining
        } catch (e: Exception) {
            Log.e(TAG, "Survival read error: ${e.message}")
            0.0
        }
    }

    /** Reads current remaining survival without re-saving (for quick checks). */
    fun peekSurvivalTime(context: Context): Double {
        val f = getFile(context, FILE_SURVIVAL)
        if (!f.exists()) return 0.0
        return try {
            val raw = decode(f.readText().trim())
            val parts = raw.split("|")
            val savedRemaining = parts[0].toDouble()
            val savedEpoch     = parts[1].toLong()
            val elapsedDays    = (System.currentTimeMillis() - savedEpoch) / 86_400_000.0
            savedRemaining - elapsedDays
        } catch (e: Exception) { 0.0 }
    }

    // ─── Delete ───────────────────────────────────────────────────────────

    fun deleteAll(context: Context) {
        listOf(FILE_USER, FILE_WIFI, FILE_SURVIVAL).forEach { name ->
            val f = getFile(context, name)
            if (f.exists()) { f.delete(); Log.d(TAG, "Deleted $name") }
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────

    private fun getFile(context: Context, name: String) = File(context.filesDir, name)

    private fun encode(s: String): String =
        Base64.encodeToString(s.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    private fun decode(s: String): String =
        String(Base64.decode(s, Base64.NO_WRAP), Charsets.UTF_8)
}
