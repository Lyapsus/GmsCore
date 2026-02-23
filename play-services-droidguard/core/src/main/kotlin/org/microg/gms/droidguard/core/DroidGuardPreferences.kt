/*
 * SPDX-FileCopyrightText: 2021 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.droidguard.core

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.util.Base64
import android.util.Log
import androidx.core.database.getStringOrNull
import org.microg.gms.settings.SettingsContract
import org.microg.gms.settings.SettingsContract.DroidGuard.ENABLED
import org.microg.gms.settings.SettingsContract.DroidGuard.FORCE_LOCAL_DISABLED
import org.microg.gms.settings.SettingsContract.DroidGuard.HARDWARE_ATTESTATION_BLOCKED
import org.microg.gms.settings.SettingsContract.DroidGuard.MODE
import org.microg.gms.settings.SettingsContract.DroidGuard.NETWORK_SERVER_URL

object DroidGuardPreferences {
    private const val TAG = "DroidGuardPreferences"
    // Use stock GMS filename for compatibility with inject-dg-token.sh
    private const val CONSTELLATION_PREFS = "constellation_prefs"
    private const val TOKEN_CACHE_PREFS = "droidguard_token_cache"
    private const val TOKEN_PREFIX = "token_"
    private const val TTL_PREFIX = "ttl_"
    // Default TTL: 8 days (like stock GMS)
    private const val DEFAULT_TTL_MS = 8 * 24 * 60 * 60 * 1000L

    private fun <T> getSettings(context: Context, projection: String, def: T, f: (Cursor) -> T): T {
        return try {
            SettingsContract.getSettings(context, SettingsContract.DroidGuard.getContentUri(context), arrayOf(projection), f)
        } catch (e: Exception) {
            def
        }
    }

    /**
     * Get cached DroidGuard token for a specific flow.
     * Returns null if no cached token or if expired.
     *
     * Checks two locations (like stock GMS):
     * 1. constellation_prefs.xml - stock GMS format (single token, injected via inject-dg-token.sh)
     * 2. droidguard_token_cache - per-flow cache (microG's own cache)
     */
    @JvmStatic
    fun getCachedToken(context: Context, flow: String?): ByteArray? {
        if (flow == null) return null

        // First, check stock GMS format (constellation_prefs.xml)
        // This allows inject-dg-token.sh to work
        try {
            val constellationPrefs = context.getSharedPreferences(CONSTELLATION_PREFS, Context.MODE_PRIVATE)
            val stockTokenBase64 = constellationPrefs.getString("droidguard_token", null)
            val stockTtl = constellationPrefs.getLong("droidguard_token_ttl", 0)

            if (stockTokenBase64 != null && System.currentTimeMillis() < stockTtl) {
                val token = Base64.decode(stockTokenBase64, Base64.NO_WRAP)
                Log.i(TAG, "Using STOCK GMS cached token from constellation_prefs.xml for flow '$flow' (${token.size} bytes, expires in ${(stockTtl - System.currentTimeMillis()) / 1000}s)")
                return token
            } else if (stockTokenBase64 != null) {
                Log.d(TAG, "Stock GMS token in constellation_prefs.xml has expired")
            }
        } catch (e: Exception) {
            Log.d(TAG, "No stock GMS token available: ${e.message}")
        }

        // Second, check per-flow cache
        try {
            val prefs = context.getSharedPreferences(TOKEN_CACHE_PREFS, Context.MODE_PRIVATE)
            val tokenBase64 = prefs.getString(TOKEN_PREFIX + flow, null) ?: return null
            val ttl = prefs.getLong(TTL_PREFIX + flow, 0)

            if (System.currentTimeMillis() > ttl) {
                Log.d(TAG, "Cached token for flow '$flow' has expired")
                return null
            }

            val token = Base64.decode(tokenBase64, Base64.NO_WRAP)
            Log.d(TAG, "Returning cached token for flow '$flow' (${token.size} bytes, expires in ${(ttl - System.currentTimeMillis()) / 1000}s)")
            return token
        } catch (e: Exception) {
            Log.w(TAG, "Error reading cached token for flow '$flow'", e)
            return null
        }
    }

    /**
     * Cache a DroidGuard token for a specific flow.
     */
    @JvmStatic
    fun setCachedToken(context: Context, flow: String?, token: ByteArray, ttlMs: Long = DEFAULT_TTL_MS) {
        if (flow == null) return
        try {
            val prefs = context.getSharedPreferences(TOKEN_CACHE_PREFS, Context.MODE_PRIVATE)
            val tokenBase64 = Base64.encodeToString(token, Base64.NO_WRAP)
            val expirationTime = System.currentTimeMillis() + ttlMs

            prefs.edit()
                .putString(TOKEN_PREFIX + flow, tokenBase64)
                .putLong(TTL_PREFIX + flow, expirationTime)
                .apply()

            Log.d(TAG, "Cached token for flow '$flow' (${token.size} bytes, TTL ${ttlMs / 1000}s)")
        } catch (e: Exception) {
            Log.w(TAG, "Error caching token for flow '$flow'", e)
        }
    }

    /**
     * Import a token from external source (e.g., stock GMS backup).
     * Used for injecting cached tokens from stock GMS.
     */
    @JvmStatic
    fun importCachedToken(context: Context, flow: String, tokenBase64: String, ttlTimestampMs: Long) {
        try {
            val prefs = context.getSharedPreferences(TOKEN_CACHE_PREFS, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(TOKEN_PREFIX + flow, tokenBase64)
                .putLong(TTL_PREFIX + flow, ttlTimestampMs)
                .apply()
            Log.i(TAG, "Imported token for flow '$flow' (expires at $ttlTimestampMs)")
        } catch (e: Exception) {
            Log.w(TAG, "Error importing token for flow '$flow'", e)
        }
    }

    /**
     * Clear cached token for a specific flow.
     */
    @JvmStatic
    fun clearCachedToken(context: Context, flow: String?) {
        if (flow == null) return
        try {
            val prefs = context.getSharedPreferences(TOKEN_CACHE_PREFS, Context.MODE_PRIVATE)
            prefs.edit()
                .remove(TOKEN_PREFIX + flow)
                .remove(TTL_PREFIX + flow)
                .apply()
            Log.d(TAG, "Cleared cached token for flow '$flow'")
        } catch (e: Exception) {
            Log.w(TAG, "Error clearing cached token for flow '$flow'", e)
        }
    }

    private fun setSettings(context: Context, f: ContentValues.() -> Unit) =
            SettingsContract.setSettings(context, SettingsContract.DroidGuard.getContentUri(context), f)

    @JvmStatic
    fun isForcedLocalDisabled(context: Context): Boolean = getSettings(context, FORCE_LOCAL_DISABLED, false) { it.getInt(0) != 0 }

    @JvmStatic
    fun isEnabled(context: Context): Boolean = getSettings(context, ENABLED, true) { it.getInt(0) != 0 }

    @JvmStatic
    fun isAvailable(context: Context): Boolean = isEnabled(context) && (!isForcedLocalDisabled(context) || getMode(context) != Mode.Embedded)

    @JvmStatic
    fun isLocalAvailable(context: Context): Boolean = isEnabled(context) && !isForcedLocalDisabled(context) && getMode(context) == Mode.Embedded

    @JvmStatic
    fun setEnabled(context: Context, enabled: Boolean) = setSettings(context) { put(ENABLED, enabled) }

    @JvmStatic
    fun getMode(context: Context): Mode = getSettings(context, MODE, Mode.Embedded) { c ->
        c.getStringOrNull(0)?.let { Mode.valueOf(it) } ?: Mode.Embedded
    }

    @JvmStatic
    fun setMode(context: Context, mode: Mode) = setSettings(context) { put(MODE, mode.toString()) }

    @JvmStatic
    fun getNetworkServerUrl(context: Context): String? = getSettings(context, NETWORK_SERVER_URL, null) { c -> c.getStringOrNull(0) }

    @JvmStatic
    fun setNetworkServerUrl(context: Context, url: String?) = setSettings(context) { put(NETWORK_SERVER_URL, url) }

    @JvmStatic
    fun isHardwareAttestationBlocked(context: Context) = getSettings(context, HARDWARE_ATTESTATION_BLOCKED, false) { it.getInt(0) != 0 }

    @JvmStatic
    fun setHardwareAttestationBlocked(context: Context, value: Boolean) = setSettings(context) { put(HARDWARE_ATTESTATION_BLOCKED, value) }

    enum class Mode {
        Embedded,
        Network
    }
}
