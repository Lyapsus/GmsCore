/*
 * SPDX-FileCopyrightText: 2021 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.droidguard.core

import android.annotation.SuppressLint
import android.content.Context
import android.os.ConditionVariable
import android.os.ParcelFileDescriptor
import android.os.Parcelable
import android.util.Log
import com.google.android.gms.droidguard.internal.DroidGuardInitReply
import com.google.android.gms.droidguard.internal.DroidGuardResultsRequest
import com.google.android.gms.droidguard.internal.IDroidGuardHandle
import org.microg.gms.droidguard.BytesException
import org.microg.gms.droidguard.GuardCallback
import org.microg.gms.droidguard.HandleProxy
import java.io.FileNotFoundException
import java.io.File
import java.security.MessageDigest

class DroidGuardHandleImpl(private val context: Context, private val packageName: String, private val factory: NetworkHandleProxyFactory, private val callback: GuardCallback) : IDroidGuardHandle.Stub() {
    private val condition = ConditionVariable()

    private var flow: String? = null
    private var handleProxy: HandleProxy? = null
    private var handleInitError: Throwable? = null

    override fun init(flow: String?) {
        Log.d(TAG, "init($flow)")
        initWithRequest(flow, null)
    }

    @SuppressLint("SetWorldReadable")
    override fun initWithRequest(flow: String?, request: DroidGuardResultsRequest?): DroidGuardInitReply {
        Log.d(TAG, "initWithRequest($flow, $request)")
        this.flow = flow
        var handleProxy: HandleProxy? = null
        try {
            if (!LOW_LATENCY_ENABLED || flow in NOT_LOW_LATENCY_FLOWS) {
                handleProxy = null
            } else {
                try {
                    handleProxy = factory.createLowLatencyHandle(flow, callback, request)
                    Log.d(TAG, "Using low-latency handle")
                } catch (e: Exception) {
                    Log.w(TAG, e)
                    handleProxy = null
                }
            }
            if (handleProxy == null) {
                handleProxy = factory.createHandle(packageName, flow, callback, request)
            }
            if (handleProxy.init()) {
                this.handleProxy = handleProxy
            } else {
                throw Exception("init failed")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error during handle init", e)
            this.handleInitError = e
        }
        this.condition.open()
        if (handleInitError == null) {
            try {
                val `object` = handleProxy!!.handle.javaClass.getDeclaredMethod("rb").invoke(handleProxy.handle) as? Parcelable?
                if (`object` != null) {
                    val vmKey = handleProxy.vmKey
                    val theApk = factory.getTheApkFile(vmKey)
                    try {
                        theApk.setReadable(true, false)
                        return DroidGuardInitReply(ParcelFileDescriptor.open(theApk, ParcelFileDescriptor.MODE_READ_ONLY), `object`)
                    } catch (e: FileNotFoundException) {
                        throw Exception("Files for VM $vmKey not found on disk")
                    }
                }
            } catch (e: Exception) {
                this.handleProxy = null
                handleInitError = e
            }
        }
        return DroidGuardInitReply(null, null)
    }

    override fun snapshot(map: MutableMap<Any?, Any?>): ByteArray {
        Log.d(TAG, "snapshot($map)")
        condition.block()

        // Include bindings hash in cache key so different content bindings get fresh tokens.
        // DroidGuard tokens are cryptographically bound to their content bindings (e.g., rpc path).
        // Without this, changing the rpc binding would still return old cached tokens.
        val cacheKey = if (flow != null && map.isNotEmpty()) {
            val hash = map.entries.sortedBy { it.key.toString() }
                .joinToString(",") { "${it.key}=${it.value}" }.hashCode()
            "${flow}_${hash.toUInt().toString(16)}"
        } else {
            flow
        }
        Log.d(TAG, "Cache key: '$cacheKey' (flow='$flow', bindings=${map.keys})")

        // Check for cached token first (like stock GMS does)
        val cachedToken = DroidGuardPreferences.getCachedToken(context, cacheKey)
        if (cachedToken != null) {
            Log.i(TAG, "Using cached DroidGuard token for flow '$flow' (${cachedToken.size} bytes)")
            return cachedToken
        }

        handleInitError?.let { return FallbackCreator.create(flow, context, map, it) }
        val handleProxy = this.handleProxy ?: return FallbackCreator.create(flow, context, map, IllegalStateException())
        return try {
            val token = handleProxy.handle::class.java.getDeclaredMethod("ss", Map::class.java).invoke(handleProxy.handle, map) as ByteArray
            // Cache the generated token with bindings-aware key
            DroidGuardPreferences.setCachedToken(context, cacheKey, token)
            Log.i(TAG, "Generated and cached new DroidGuard token for flow '$flow' (${token.size} bytes)")

             // Token content is opaque; log a stable fingerprint for diffing.
             if (flow == "constellation_verify") {
                 try {
                     val sha = sha256Hex(token).take(16)
                     val first = token.take(8).toByteArray().joinToString("") { String.format("%02x", it) }
                     Log.i(TAG, "constellation_verify token fingerprint: sha256=$sha firstBytes=$first")
                     logConstellationVerifyContext(map, token)
                 } catch (_: Throwable) {
                 }
             } else if (flow == "tachyon_registration") {
                 try {
                     val sha = sha256Hex(token).take(16)
                     val first = token.take(8).toByteArray().joinToString("") { String.format("%02x", it) }
                     Log.i(TAG, "tachyon_registration token fingerprint: sha256=$sha firstBytes=$first")
                     logTachyonRegistrationContext(map, token)
                 } catch (_: Throwable) {
                 }
             }
             token
         } catch (e: Exception) {
            try {
                throw BytesException(handleProxy.extra, e)
            } catch (e2: Exception) {
                FallbackCreator.create(flow, context, map, e2)
            }
        }
    }

    override fun close() {
        Log.d(TAG, "close()")
        condition.block()
        try {
            callback.logSessionSummary("close/flow=$flow")
        } catch (e: Exception) {
            Log.w(TAG, "Error logging session summary", e)
        }
        try {
            handleProxy?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error during handle close", e)
        }
        handleProxy = null
        handleInitError = null
    }

    companion object {
        private const val TAG = "GmsGuardHandleImpl"
        private val LOW_LATENCY_ENABLED = false
        private val NOT_LOW_LATENCY_FLOWS = setOf("ad_attest", "attest", "checkin", "federatedMachineLearningReduced", "msa-f", "ad-event-attest-token")
    }
}

private fun sha256Hex(bytes: ByteArray): String {
    val md = MessageDigest.getInstance("SHA-256")
    val d = md.digest(bytes)
    val sb = StringBuilder(d.size * 2)
    for (b in d) sb.append(String.format("%02x", b))
    return sb.toString()
}

private fun logConstellationVerifyContext(bindings: Map<Any?, Any?>, token: ByteArray) {
    // Opinionated, diff-friendly logging. Avoid leaking the full bindings.
    try {
        val keys = bindings.keys.map { it?.toString() ?: "null" }.sorted()
        Log.i("GmsGuardHandleImpl", "constellation_verify bindings keys=$keys")

        fun summarizeValue(v: Any?): String {
            if (v == null) return "null"
            val s = v.toString()
            return if (s.length > 64) {
                "len=${s.length} sha256=${sha256Hex(s.toByteArray()).take(16)}"
            } else {
                "len=${s.length} sha256=${sha256Hex(s.toByteArray()).take(16)} value=${s.replace("\n", "\\n")}" 
            }
        }

        for (k in keys) {
            val v = bindings.entries.firstOrNull { (it.key?.toString() ?: "null") == k }?.value
            if (k == "rpc") {
                Log.i("GmsGuardHandleImpl", "constellation_verify binding rpc=${v?.toString() ?: "null"}")
            } else {
                Log.i("GmsGuardHandleImpl", "constellation_verify binding $k: ${summarizeValue(v)}")
            }
        }
    } catch (_: Throwable) {
    }

    try {
        val env = linkedMapOf<String, String?>()
        env["sdk"] = android.os.Build.VERSION.SDK_INT.toString()
        env["device"] = android.os.Build.DEVICE
        env["model"] = android.os.Build.MODEL
        env["fingerprint"] = android.os.Build.FINGERPRINT
        env["tags"] = getSystemProperty("ro.build.tags")
        env["type"] = getSystemProperty("ro.build.type")

        // Boot/attestation related props that often affect antiabuse decisions.
        env["ro.boot.verifiedbootstate"] = getSystemProperty("ro.boot.verifiedbootstate")
        env["ro.boot.vbmeta.device_state"] = getSystemProperty("ro.boot.vbmeta.device_state")
        env["ro.boot.flash.locked"] = getSystemProperty("ro.boot.flash.locked")
        env["ro.boot.bootstate"] = getSystemProperty("ro.boot.bootstate")
        env["ro.boot.warranty_bit"] = getSystemProperty("ro.boot.warranty_bit")
        env["ro.boot.veritymode"] = getSystemProperty("ro.boot.veritymode")
        env["ro.boot.avb_version"] = getSystemProperty("ro.boot.avb_version")
        env["ro.boot.verifiedbootstate2"] = getSystemProperty("ro.boot.verifiedbootstate2")
        env["ro.boot.device_state"] = getSystemProperty("ro.boot.device_state")
        env["ro.boot.bootloader"] = getSystemProperty("ro.boot.bootloader")
        env["ro.boot.bootmode"] = getSystemProperty("ro.boot.bootmode")
        env["ro.bootmode"] = getSystemProperty("ro.bootmode")
        env["ro.crypto.state"] = getSystemProperty("ro.crypto.state")
        env["ro.crypto.type"] = getSystemProperty("ro.crypto.type")
        env["ro.hardware.keystore"] = getSystemProperty("ro.hardware.keystore")
        env["ro.hardware.keystore_desede"] = getSystemProperty("ro.hardware.keystore_desede")
        env["ro.hardware.keystore_secure"] = getSystemProperty("ro.hardware.keystore_secure")
        env["ro.security.keystore_keymint"] = getSystemProperty("ro.security.keystore_keymint")
        env["ro.boot.keymaster"] = getSystemProperty("ro.boot.keymaster")
        env["ro.boot.keymaster_version"] = getSystemProperty("ro.boot.keymaster_version")
        env["ro.boot.keymint"] = getSystemProperty("ro.boot.keymint")
        env["ro.boot.keymint_version"] = getSystemProperty("ro.boot.keymint_version")
        env["ro.boot.qcom"] = getSystemProperty("ro.boot.qcom")
        env["ro.boot.secureboot"] = getSystemProperty("ro.boot.secureboot")
        env["ro.boot.selinux"] = getSystemProperty("ro.boot.selinux")
        env["ro.product.first_api_level"] = getSystemProperty("ro.product.first_api_level")
        env["ro.build.version.sdk"] = getSystemProperty("ro.build.version.sdk")
        env["ro.build.version.release"] = getSystemProperty("ro.build.version.release")
        env["ro.build.version.security_patch"] = getSystemProperty("ro.build.version.security_patch")
        env["ro.build.version.incremental"] = getSystemProperty("ro.build.version.incremental")
        env["ro.vendor.build.security_patch"] = getSystemProperty("ro.vendor.build.security_patch")
        env["ro.odm.build.security_patch"] = getSystemProperty("ro.odm.build.security_patch")
        env["ro.system.build.security_patch"] = getSystemProperty("ro.system.build.security_patch")
        env["ro.bootimage.build.fingerprint"] = getSystemProperty("ro.bootimage.build.fingerprint")
        env["ro.vendor.bootimage.build.fingerprint"] = getSystemProperty("ro.vendor.bootimage.build.fingerprint")
        env["ro.product.build.fingerprint"] = getSystemProperty("ro.product.build.fingerprint")
        env["ro.build.host"] = getSystemProperty("ro.build.host")
        env["ro.build.user"] = getSystemProperty("ro.build.user")
        env["ro.build.display.id"] = getSystemProperty("ro.build.display.id")
        env["ro.build.description"] = getSystemProperty("ro.build.description")

        // Debug flags.
        env["ro.debuggable"] = getSystemProperty("ro.debuggable")
        env["ro.secure"] = getSystemProperty("ro.secure")
        env["ro.adb.secure"] = getSystemProperty("ro.adb.secure")

        env["selinux_enforced"] = getSelinuxEnforced()?.toString()

        // Magisk/Zygisk/LSPosed breadcrumbs (heuristics only).
        env["path_/sbin/.magisk"] = File("/sbin/.magisk").exists().toString()
        env["path_/debug_ramdisk/.magisk"] = File("/debug_ramdisk/.magisk").exists().toString()
        env["path_/data/adb/magisk"] = File("/data/adb/magisk").exists().toString()
        env["path_/data/adb/modules"] = File("/data/adb/modules").exists().toString()
        env["path_/data/adb/lspd"] = File("/data/adb/lspd").exists().toString()

        val envStr = env.entries.joinToString(", ") { (k, v) -> "$k=${v ?: "null"}" }
        Log.i("GmsGuardHandleImpl", "constellation_verify env: $envStr")
    } catch (_: Throwable) {
    }

    try {
        // Token size can fluctuate slightly; log it alongside the fingerprint.
        Log.i("GmsGuardHandleImpl", "constellation_verify token bytes=${token.size}")
    } catch (_: Throwable) {
    }
}

private fun logTachyonRegistrationContext(bindings: Map<Any?, Any?>, token: ByteArray) {
    // Opinionated, diff-friendly logging. Avoid leaking full bindings.
    try {
        val keys = bindings.keys.map { it?.toString() ?: "null" }.sorted()
        Log.i("GmsGuardHandleImpl", "tachyon_registration bindings keys=$keys")

        fun summarizeValue(v: Any?): String {
            if (v == null) return "null"
            val s = v.toString()
            return if (s.length > 64) {
                "len=${s.length} sha256=${sha256Hex(s.toByteArray()).take(16)}"
            } else {
                "len=${s.length} sha256=${sha256Hex(s.toByteArray()).take(16)} value=${s.replace("\n", "\\n")}" 
            }
        }

        for (k in keys) {
            val v = bindings.entries.firstOrNull { (it.key?.toString() ?: "null") == k }?.value
            when (k) {
                // These are high-signal, expected fields for this flow.
                "APP_NAME", "ID" -> Log.i("GmsGuardHandleImpl", "tachyon_registration binding $k: ${summarizeValue(v)}")
                // ISSUED_AT is expected to change every refresh; log presence + shape, not full value.
                "ISSUED_AT" -> Log.i("GmsGuardHandleImpl", "tachyon_registration binding ISSUED_AT: ${summarizeValue(v)}")
                else -> Log.i("GmsGuardHandleImpl", "tachyon_registration binding $k: ${summarizeValue(v)}")
            }
        }
    } catch (_: Throwable) {
    }

    // For now, keep env logging limited to constellation_verify to avoid extra log volume.
    // If we need it later, we can add a shared env logger here.
}

private fun getSystemProperty(key: String): String? {
    return try {
        val cls = Class.forName("android.os.SystemProperties")
        val m = cls.getMethod("get", String::class.java)
        (m.invoke(null, key) as? String)?.takeIf { it.isNotEmpty() }
    } catch (_: Throwable) {
        null
    }
}

private fun getSelinuxEnforced(): Boolean? {
    // android.os.SELinux is hidden API in the public SDK; use reflection.
    return try {
        val cls = Class.forName("android.os.SELinux")
        val m = cls.getMethod("isSELinuxEnforced")
        m.invoke(null) as? Boolean
    } catch (_: Throwable) {
        null
    }
}
