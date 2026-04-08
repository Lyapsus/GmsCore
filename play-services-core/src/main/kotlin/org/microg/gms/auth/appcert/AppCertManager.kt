/*
 * SPDX-FileCopyrightText: 2022 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.auth.appcert

import android.content.Context
import android.database.Cursor
import android.util.Base64
import android.util.Log
import com.android.volley.NetworkResponse
import com.android.volley.Request
import com.android.volley.Response
import com.android.volley.VolleyError
import com.android.volley.toolbox.Volley
import com.google.android.gms.droidguard.DroidGuardClient
import com.google.android.gms.tasks.await
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.ByteString.Companion.of
import org.microg.gms.profile.Build
import org.microg.gms.profile.ProfileManager
import org.microg.gms.settings.SettingsContract.CheckIn
import org.microg.gms.settings.SettingsContract.getSettings
import org.microg.gms.utils.digest
import org.microg.gms.utils.getCertificates
import org.microg.gms.utils.singleInstanceOf
import org.microg.gms.utils.toBase64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

class AppCertManager(private val context: Context) {
    private val queue = singleInstanceOf { Volley.newRequestQueue(context.applicationContext) }

    private fun readDeviceKey() {
        try {
            val keyFile = context.getFileStreamPath("device_key")
            if (keyFile.exists()) {
                if (keyFile.length() == 0L) {
                    Log.w(TAG, "device_key is 0 bytes, deleting stale file")
                    keyFile.delete()
                    deviceKeyCacheTime = -1
                    return
                }
                val key = context.openFileInput("device_key").use { DeviceKey.ADAPTER.decode(it) }
                if (key.macSecret == null) {
                    Log.w(TAG, "device_key has no macSecret, deleting invalid file")
                    keyFile.delete()
                    deviceKey = null
                    deviceKeyCacheTime = -1
                    return
                }
                deviceKey = key
                deviceKeyCacheTime = keyFile.lastModified()
            } else {
                deviceKeyCacheTime = -1
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read device_key", e)
            deviceKeyCacheTime = -1
        }
    }

    suspend fun fetchDeviceKey(): Boolean {
        ProfileManager.ensureInitialized(context)
        if (deviceKeyCacheTime == 0L) readDeviceKey()
        deviceKeyLock.withLock {
            try {
                val currentTime = System.currentTimeMillis()
                if (deviceKeyCacheTime > 0 && currentTime - deviceKeyCacheTime < DEVICE_KEY_TIMEOUT) {
                    return deviceKey != null
                }
                Log.w(TAG, "DeviceKeys for app certifications are experimental")
                deviceKeyCacheTime = currentTime
                // Stock GMS reads android_id from GServices ContentProvider
                // (content://com.google.android.gsf.gservices key "android_id") via
                // belp.d() → belo.b() → bdno.b(belp.d) → fngx.c(). This is the CHECKIN
                // android_id assigned during device registration, NOT Settings.Secure.ANDROID_ID.
                // microG stores this in SettingsContract.CheckIn.ANDROID_ID.
                val checkinAndroidId = try {
                    getSettings(context, CheckIn.getContentUri(context), arrayOf(CheckIn.ANDROID_ID)) { cursor: Cursor -> cursor.getLong(0) }
                } catch (e: Exception) { 0L }
                if (checkinAndroidId == 0L) {
                    Log.w(TAG, "Checkin not completed (android_id=0), cannot fetch device_key")
                    deviceKeyCacheTime = 0
                    return false
                }
                val androidIdHex = java.lang.Long.toHexString(checkinAndroidId)
                val sessionId = Random.nextLong()
                // Stock GMS v26.02.33 version code — devicekey server validates this
                val stockGmsVersionCode = 260233029
                val data = hashMapOf(
                        "dg_androidId" to androidIdHex,
                        "dg_session" to java.lang.Long.toHexString(sessionId),
                        "dg_gmsCoreVersion" to stockGmsVersionCode.toString(),
                        "dg_sdkVersion" to Build.VERSION.SDK_INT.toString()
                )
                val droidGuardResult = try {
                    DroidGuardClient.getResults(context, "devicekey", data).await()
                } catch (e: Exception) {
                    null
                }
                // Stock GMS (ajoq.b bytecode) sends literal "missing_token" — no FCM registration
                val request = DeviceKeyRequest(
                        droidGuardResult = droidGuardResult,
                        androidId = checkinAndroidId,
                        sessionId = sessionId,
                        versionInfo = DeviceKeyRequest.VersionInfo(Build.VERSION.SDK_INT, stockGmsVersionCode),
                        token = "missing_token"
                )
                Log.d(TAG, "androidId hex=$androidIdHex long=$checkinAndroidId session=${java.lang.Long.toHexString(sessionId)}")
                val bodyBytes = request.encode()
                Log.d(TAG, "Request body: ${bodyBytes.size} bytes, hex=${bodyBytes.take(64).joinToString("") { "%02x".format(it) }}...")
                // Debug: save raw request body for curl testing
                try {
                    val dumpFile = java.io.File(context.filesDir, "devicekey_request.bin")
                    dumpFile.writeBytes(bodyBytes)
                    Log.d(TAG, "Saved request body to ${dumpFile.absolutePath}")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to save request body: ${e.message}")
                }
                val deferredResponse = CompletableDeferred<ByteArray?>()
                queue.add(object : Request<ByteArray?>(Method.POST, "https://android.googleapis.com/auth/devicekey", null) {
                    override fun getBody(): ByteArray = request.encode()

                    override fun getBodyContentType(): String = "application/x-protobuf"

                    override fun parseNetworkResponse(response: NetworkResponse): Response<ByteArray?> {
                        return if (response.statusCode == 200) {
                            Response.success(response.data, null)
                        } else {
                            Response.success(null, null)
                        }
                    }

                    override fun deliverError(error: VolleyError) {
                        val nr = error.networkResponse
                        if (nr != null) {
                            val body = nr.data?.let { String(it, Charsets.UTF_8) } ?: ""
                            Log.d(TAG, "HTTP ${nr.statusCode}: ${body.take(500)}")
                        } else {
                            Log.d(TAG, "Error: ${error.message}")
                        }
                        deviceKeyCacheTime = 0
                        deferredResponse.complete(null)
                    }

                    override fun deliverResponse(response: ByteArray?) {
                        deferredResponse.complete(response)
                    }

                    override fun getHeaders(): Map<String, String> {
                        // Stock bddx.d() sets: device, app, gmsversion, gmscoreFlow.
                        // Stock Cronet adds User-Agent at native level.
                        // Wire capture (capture_full_bodies.flow): exact stock headers that got HTTP 200.
                        // Content-Type set via getBodyContentType().
                        val headers = mapOf(
                                "app" to "com.google.android.gms",
                                "device" to androidIdHex,
                                "gmsversion" to stockGmsVersionCode.toString(),
                                "gmscoreflow" to "3",
                                "User-Agent" to "com.google.android.gms/$stockGmsVersionCode (Linux; U; Android ${android.os.Build.VERSION.RELEASE}; ${java.util.Locale.getDefault()}; ${Build.MODEL}; Build/${Build.ID}; Cronet/144.0.7509.3)"
                        )
                        for ((k, v) in headers) {
                            Log.d(TAG, "Header: $k: $v")
                        }
                        return headers
                    }
                })
                val deviceKeyBytes = deferredResponse.await() ?: return false
                context.openFileOutput("device_key", Context.MODE_PRIVATE).use {
                    it.write(deviceKeyBytes)
                }
                context.getFileStreamPath("device_key").setLastModified(currentTime)
                deviceKey = DeviceKey.ADAPTER.decode(deviceKeyBytes)
                return true
            } catch (e: Exception) {
                Log.w(TAG, e)
                return false
            }
        }
    }

    suspend fun getSpatulaHeader(packageName: String): String? {
        // Try to fetch/refresh device key. Even if fetchDeviceKey() returns false
        // (e.g. endpoint HTTP 400), readDeviceKey() inside it may have loaded a valid
        // key from disk. Always check the companion field after the attempt.
        if (deviceKey == null) fetchDeviceKey()
        val deviceKey = deviceKey
        val packageCertificateHash = context.packageManager.getCertificates(packageName).firstOrNull()?.digest("SHA1")?.toBase64(Base64.NO_WRAP)
        val proto = if (deviceKey != null) {
            val macSecret = deviceKey.macSecret?.toByteArray()
            if (macSecret == null) {
                Log.w(TAG, "Invalid device key: $deviceKey")
                return null
            }
            val mac = Mac.getInstance("HMACSHA256")
            mac.init(SecretKeySpec(macSecret, "HMACSHA256"))
            val hmac = mac.doFinal("$packageName$packageCertificateHash".toByteArray())
            SpatulaHeaderProto(
                    packageInfo = SpatulaHeaderProto.PackageInfo(packageName, packageCertificateHash),
                    hmac = of(*hmac),
                    deviceId = deviceKey.deviceId,
                    keyId = deviceKey.keyId,
                    keyCert = deviceKey.keyCert ?: of()
            )
        } else {
            Log.d(TAG, "Using fallback spatula header based on Android ID")
            val androidId = getSettings(context, CheckIn.getContentUri(context), arrayOf(CheckIn.ANDROID_ID)) { cursor: Cursor -> cursor.getLong(0) }
            SpatulaHeaderProto(
                    packageInfo = SpatulaHeaderProto.PackageInfo(packageName, packageCertificateHash),
                    deviceId = androidId
            )
        }
        Log.d(TAG, "Spatula Header: $proto")
        return Base64.encodeToString(proto.encode(), Base64.NO_WRAP)
    }

    companion object {
        private const val TAG = "AppCertManager"
        private const val DEVICE_KEY_TIMEOUT = 60 * 60 * 1000L
        private val deviceKeyLock = Mutex()
        private var deviceKey: DeviceKey? = null
        private var deviceKeyCacheTime = 0L
    }
}
