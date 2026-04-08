/*
 * SPDX-FileCopyrightText: 2021 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.droidguard.core

import android.content.Context
import android.accounts.AccountManager
import com.android.volley.NetworkResponse
import com.android.volley.VolleyError
import com.android.volley.toolbox.RequestFuture
import com.android.volley.toolbox.Volley
import com.google.android.gms.droidguard.internal.DroidGuardResultsRequest
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.of
import org.microg.gms.droidguard.*
import org.microg.gms.profile.Build
import org.microg.gms.profile.ProfileManager
import org.microg.gms.utils.singleInstanceOf
import java.io.File
import java.util.*
import java.security.MessageDigest
import com.android.volley.Request as VolleyRequest
import com.android.volley.Response as VolleyResponse

class NetworkHandleProxyFactory(private val context: Context) : HandleProxyFactory(context) {
    private val dgDb: DgDatabaseHelper = DgDatabaseHelper(context)
    private val version = VersionUtil(context)
    private val queue = singleInstanceOf { Volley.newRequestQueue(context.applicationContext) }

    fun createHandle(packageName: String, flow: String?, callback: GuardCallback, request: DroidGuardResultsRequest?): HandleProxy {
        if (!DroidGuardPreferences.isLocalAvailable(context)) throw IllegalAccessException("DroidGuard should not be available locally")
        val (vmKey, byteCode, bytes) = readFromDatabase(flow) ?: fetchFromServer(flow, packageName)
        return createHandleProxy(flow, vmKey, byteCode, bytes, callback, request)
    }

    fun createPingHandle(packageName: String, flow: String, callback: GuardCallback, pingData: PingData?): HandleProxy {
        if (!DroidGuardPreferences.isLocalAvailable(context)) throw IllegalAccessException("DroidGuard should not be available locally")
        val (vmKey, byteCode, bytes) = fetchFromServer(flow, createRequest(flow, packageName, pingData))
        return createHandleProxy(flow, vmKey, byteCode, bytes, callback, DroidGuardResultsRequest().also { it.clientVersion = 0 })
    }

    fun createLowLatencyHandle(flow: String?, callback: GuardCallback, request: DroidGuardResultsRequest?): HandleProxy {
        if (!DroidGuardPreferences.isLocalAvailable(context)) throw IllegalAccessException("DroidGuard should not be available locally")
        val (vmKey, byteCode, bytes) = readFromDatabase("fast") ?: throw Exception("low latency (fast) flow not available")
        return createHandleProxy(flow, vmKey, byteCode, bytes, callback, request)
    }

    fun SignedResponse.unpack(): Response {
        if (SignatureVerifier.verifySignature(data_!!.toByteArray(), signature!!.toByteArray())) {
            return Response.ADAPTER.decode(data_!!)
        } else {
            throw SecurityException("Signature invalid")
        }
    }

    private fun readFromDatabase(flow: String?): Triple<String, ByteArray, ByteArray>? {
        ProfileManager.ensureInitialized(context)
        val id = "$flow/${version.versionString}/${Build.FINGERPRINT}"
        val hit = dgDb.get(id)
        // For constellation_verify debugging, we need to know whether we're actually exercising
        // a new server VM/bytecode or reusing a cached one.
        if (flow == "constellation_verify") {
            if (hit == null) {
                android.util.Log.i("DroidGuard", "DB cache MISS for flow='$flow' id='$id'")
            } else {
                android.util.Log.i(
                    "DroidGuard",
                    "DB cache HIT for flow='$flow' id='$id': vmKey=${hit.first.take(12)}... byteCode=${hit.second.size}B extra=${hit.third.size}B"
                )
            }
        }
        return hit
    }

    fun createRequest(flow: String?, packageName: String, pingData: PingData? = null, extra: ByteArray? = null): Request {
        ProfileManager.ensureInitialized(context)

        // Stock GMS includes whether the device already has a Google account.
        // This may influence the VM/bytecode returned for certain high-risk flows (e.g. constellation_verify).
        // We must not hardcode this to false.
        val hasGoogleAccount = try {
            val am = AccountManager.get(context)
            am.accounts.any { it.type == "com.google" }
        } catch (_: Throwable) {
            false
        }

        if (flow != null) {
            try {
                val am = AccountManager.get(context)
                val types = am.accounts.map { it.type }.toSet()
                android.util.Log.d("DroidGuard", "createRequest(flow='$flow', pkg='$packageName'): hasGoogleAccount=$hasGoogleAccount accountTypes=$types")
            } catch (_: Throwable) {
                android.util.Log.d("DroidGuard", "createRequest(flow='$flow', pkg='$packageName'): hasGoogleAccount=$hasGoogleAccount")
            }
        }

        val cachedKeys = getCacheDir().list()?.map { it.decodeHex() }.orEmpty()
        val sendCachedKeys = if (flow == "constellation_verify") {
            // For debugging we want to observe the server's full response payload (VM APK content).
            // If we advertise cached VM keys, the server may omit content (content=0B) and we lose
            // the ability to diff the VM/bytecode response. Constellation runs infrequently.
            if (cachedKeys.isNotEmpty()) {
                android.util.Log.i("DroidGuard", "createRequest(flow='$flow'): forcing cachedCount=0 (local cachedCount=${cachedKeys.size}) to force VM content")
            }
            emptyList()
        } else {
            cachedKeys
        }

        return Request(
                usage = Usage(flow, packageName),
                info = listOf(
                        KeyValuePair("BOARD", Build.BOARD),
                        KeyValuePair("BOOTLOADER", Build.BOOTLOADER),
                        KeyValuePair("BRAND", Build.BRAND),
                        KeyValuePair("CPU_ABI", Build.CPU_ABI),
                        KeyValuePair("CPU_ABI2", Build.CPU_ABI2),
                        KeyValuePair("SUPPORTED_ABIS", Build.SUPPORTED_ABIS.joinToString(",")),
                        KeyValuePair("DEVICE", Build.DEVICE),
                        KeyValuePair("DISPLAY", Build.DISPLAY),
                        KeyValuePair("FINGERPRINT", Build.FINGERPRINT),
                        KeyValuePair("HARDWARE", Build.HARDWARE),
                        KeyValuePair("HOST", Build.HOST),
                        KeyValuePair("ID", Build.ID),
                        KeyValuePair("MANUFACTURER", Build.MANUFACTURER),
                        KeyValuePair("MODEL", Build.MODEL),
                        KeyValuePair("PRODUCT", Build.PRODUCT),
                        KeyValuePair("RADIO", Build.RADIO),
                        KeyValuePair("SERIAL", Build.SERIAL),
                        KeyValuePair("TAGS", Build.TAGS),
                        KeyValuePair("TIME", Build.TIME.toString()),
                        KeyValuePair("TYPE", Build.TYPE),
                        KeyValuePair("USER", Build.USER),
                        KeyValuePair("VERSION.CODENAME", Build.VERSION.CODENAME),
                        KeyValuePair("VERSION.INCREMENTAL", Build.VERSION.INCREMENTAL),
                        KeyValuePair("VERSION.RELEASE", Build.VERSION.RELEASE),
                        KeyValuePair("VERSION.SDK", Build.VERSION.SDK),
                        KeyValuePair("VERSION.SDK_INT", Build.VERSION.SDK_INT.toString()),
                ),
                versionName = version.versionString,
                versionCode = BuildConfig.VERSION_CODE,
                hasAccount = hasGoogleAccount,
                isGoogleCn = false,
                enableInlineVm = true,
                cached = sendCachedKeys,
                arch = System.getProperty("os.arch"),
                ping = pingData,
                field10 = extra?.let { of(*it) },
        )
    }

    fun fetchFromServer(flow: String?, packageName: String): Triple<String, ByteArray, ByteArray> {
        return fetchFromServer(flow, createRequest(flow, packageName))
    }

    fun fetchFromServer(flow: String?, request: Request): Triple<String, ByteArray, ByteArray> {
        ProfileManager.ensureInitialized(context)

        if (flow == "constellation_verify") {
            val bodySize = try { request.encode().size } catch (_: Throwable) { -1 }
            android.util.Log.i(
                "DroidGuard",
                "fetchFromServer(flow='$flow'): requestBytes=${if (bodySize >= 0) bodySize else "?"} " +
                    "usagePkg=${request.usage?.packageName} " +
                    "verName=${request.versionName} verCode=${request.versionCode} " +
                    "hasAccount=${request.hasAccount} inlineVm=${request.enableInlineVm} " +
                    "cachedCount=${request.cached?.size ?: 0} arch=${request.arch}"
            )
        }

        val future = RequestFuture.newFuture<SignedResponse>()
        queue.add(object : VolleyRequest<SignedResponse>(Method.POST, SERVER_URL, future) {
            override fun parseNetworkResponse(response: NetworkResponse): VolleyResponse<SignedResponse> {
                return try {
                    VolleyResponse.success(SignedResponse.ADAPTER.decode(response.data), null)
                } catch (e: Exception) {
                    VolleyResponse.error(VolleyError(e))
                }
            }

            override fun deliverResponse(response: SignedResponse) {
                future.onResponse(response)
            }

            override fun getBody(): ByteArray = request.encode()

            override fun getBodyContentType(): String = "application/x-protobuf"

            override fun getHeaders(): Map<String, String> {
                return mapOf(
                    "User-Agent" to "DroidGuard/${version.versionCode}"
                )
            }
        })
        val signed: SignedResponse = future.get()
        val response = signed.unpack()
        // S220: Stock GMS uses uppercase hex for vmKey (visible in maps as DG cache path).
        // okio ByteString.hex() returns lowercase → maps showed app_dg_cache/c979... vs stock C979...
        val vmKey = response.vmChecksum!!.hex().uppercase()

        if (flow == "constellation_verify") {
            try {
                val apkFile = getTheApkFile(vmKey)
                val optDir = getOptDir(vmKey)
                val cacheDir = getCacheDir(vmKey)
                android.util.Log.i(
                    "DroidGuard",
                    "VM cache paths for vmKey=$vmKey: cacheDir=${cacheDir.absolutePath} apk=${apkFile.absolutePath} opt=${optDir.absolutePath} " +
                        "exists(cacheDir=${cacheDir.exists()}, apk=${apkFile.isFile}, opt=${optDir.isDirectory})"
                )
            } catch (_: Throwable) {
            }
        }

        if (flow == "constellation_verify") {
            android.util.Log.i(
                "DroidGuard",
                "fetchFromServer(flow='$flow'): response vmKey=$vmKey expiryTimeSecs=${response.expiryTimeSecs} save=${response.save} " +
                    "byteCode=${response.byteCode?.size ?: 0}B extra=${response.extra?.size ?: 0}B content=${response.content?.size ?: 0}B"
            )

            // Hashes allow comparing server payloads without dumping them.
            try {
                val byteCodeBytes = response.byteCode?.toByteArray() ?: ByteArray(0)
                val contentBytes = response.content?.toByteArray() ?: ByteArray(0)
                val extraBytes = response.extra?.toByteArray() ?: ByteArray(0)
                android.util.Log.i(
                    "DroidGuard",
                    "fetchFromServer(flow='$flow'): response sha256 byteCode=${sha256Hex(byteCodeBytes).take(16)} " +
                        "content=${sha256Hex(contentBytes).take(16)} extra=${sha256Hex(extraBytes).take(16)}"
                )
            } catch (_: Throwable) {
            }
        }

        if (!isValidCache(vmKey)) {
            if (flow == "constellation_verify") {
                android.util.Log.i("DroidGuard", "VM cache MISS for vmKey=$vmKey (no APK cached yet); writing VM APK")
            }
            val temp = File(getCacheDir(), "${UUID.randomUUID()}.apk")
            temp.parentFile!!.mkdirs()
            temp.writeBytes(response.content!!.toByteArray())
            getOptDir(vmKey).mkdirs()
            temp.renameTo(getTheApkFile(vmKey))
            updateCacheTimestamp(vmKey)
            if (!isValidCache(vmKey)) {
                getCacheDir(vmKey).deleteRecursively()
                throw IllegalStateException()
            }
            if (flow == "constellation_verify") {
                try {
                    val apkFile = getTheApkFile(vmKey)
                    val optDir = getOptDir(vmKey)
                    android.util.Log.i(
                        "DroidGuard",
                        "VM cache write complete for vmKey=$vmKey: apk=${apkFile.isFile} (${apkFile.length()}B) opt=${optDir.isDirectory}"
                    )
                } catch (_: Throwable) {
                }
            }
        } else if (flow == "constellation_verify") {
            android.util.Log.i("DroidGuard", "VM cache HIT for vmKey=$vmKey (APK already cached)")
        }
        val id = "$flow/${version.versionString}/${Build.FINGERPRINT}"
        val expiry = (response.expiryTimeSecs ?: 0).toLong()
        val byteCode = response.byteCode?.toByteArray() ?: ByteArray(0)
        val extra = response.extra?.toByteArray() ?: ByteArray(0)
        if (response.save != false) {
            dgDb.put(id, expiry, vmKey, byteCode, extra)
        }
        return Triple(vmKey, byteCode, extra)
    }

    private fun createHandleProxy(
        flow: String?,
        vmKey: String,
        byteCode: ByteArray,
        extra: ByteArray,
        callback: GuardCallback,
        request: DroidGuardResultsRequest?
    ): HandleProxy {
        ProfileManager.ensureInitialized(context)
        val clazz = loadClass(vmKey, extra)
        return HandleProxy(clazz, context, flow, byteCode, callback, vmKey, extra, request?.bundle)
    }

    companion object {
        const val SERVER_URL = "https://www.googleapis.com/androidantiabuse/v1/x/create?alt=PROTO&key=AIzaSyBofcZsgLSS7BOnBjZPEkk4rYwzOIz-lTI"
    }
}

private fun sha256Hex(bytes: ByteArray): String {
    val md = MessageDigest.getInstance("SHA-256")
    val d = md.digest(bytes)
    val sb = StringBuilder(d.size * 2)
    for (b in d) sb.append(String.format("%02x", b))
    return sb.toString()
}
