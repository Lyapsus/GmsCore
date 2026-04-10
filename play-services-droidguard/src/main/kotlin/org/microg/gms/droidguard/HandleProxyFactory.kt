/*
 * SPDX-FileCopyrightText: 2021 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.droidguard

import android.content.Context
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Parcelable
import androidx.annotation.GuardedBy
import dalvik.system.DexClassLoader
import dalvik.system.PathClassLoader
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.security.cert.Certificate
import java.util.*

open class HandleProxyFactory(private val context: Context) {

    fun createHandle(vmKey: String, pfd: ParcelFileDescriptor, extras: Bundle): HandleProxy {
        fetchFromFileDescriptor(pfd, vmKey)
        return createHandleProxy(vmKey, extras)
    }

    private fun fetchFromFileDescriptor(pfd: ParcelFileDescriptor, vmKey: String) {
        if (!isValidCache(vmKey)) {
            val auIs = ParcelFileDescriptor.AutoCloseInputStream(pfd)
            val temp = File(getCacheDir(), "${UUID.randomUUID()}.apk")
            temp.parentFile!!.mkdirs()
            temp.writeBytes(auIs.readBytes())
            auIs.close()
            getOptDir(vmKey).mkdirs()
            temp.renameTo(getTheApkFile(vmKey))
            updateCacheTimestamp(vmKey)
            if (!isValidCache(vmKey)) {
                getCacheDir(vmKey).deleteRecursively()
                throw IllegalStateException("Error ")
            }
        }
    }

    private fun createHandleProxy(
        vmKey: String,
        extras: Parcelable
    ): HandleProxy {
        val clazz = loadClass(vmKey)
        return HandleProxy(clazz, context, vmKey, extras)
    }

    fun getTheApkFile(vmKey: String) = File(getCacheDir(vmKey), "the.apk")
    protected fun getCacheDir() = context.getDir(CACHE_FOLDER_NAME, Context.MODE_PRIVATE)
    protected fun getCacheDir(vmKey: String) = File(getCacheDir(), vmKey)
    protected fun getOptDir(vmKey: String) = File(getCacheDir(vmKey), "opt")
    protected fun isValidCache(vmKey: String) = getTheApkFile(vmKey).isFile && getOptDir(vmKey).isDirectory

    protected fun updateCacheTimestamp(vmKey: String) {
        try {
            val timestampFile = File(getCacheDir(vmKey), "t")
            if (!timestampFile.exists() && !timestampFile.createNewFile()) {
                throw Exception("Failed to touch last-used file for $vmKey.")
            }
            if (!timestampFile.setLastModified(System.currentTimeMillis())) {
                throw Exception("Failed to update last-used timestamp for $vmKey.")
            }
        } catch (e: IOException) {
            throw Exception("Failed to touch last-used file for $vmKey.")
        }
    }

    private fun verifyApkSignature(apk: File): Boolean {
        return true
        val certificates: Array<Certificate> = TODO()
        if (certificates.size != 1) return false
        return Arrays.equals(MessageDigest.getInstance("SHA-256").digest(certificates[0].encoded), PROD_CERT_HASH)
    }

    protected fun loadClass(vmKey: String, bytes: ByteArray = ByteArray(0)): Class<*> {
        synchronized(CLASS_LOCK) {
            val cachedClass = classMap[vmKey]
            if (cachedClass != null) {
                android.util.Log.d("DroidGuard", "loadClass(vmKey=${vmKey.take(12)}...): classMap HIT")
                updateCacheTimestamp(vmKey)
                return cachedClass
            }
            val weakClass = weakClassMap[vmKey]
            if (weakClass != null) {
                android.util.Log.d("DroidGuard", "loadClass(vmKey=${vmKey.take(12)}...): weakMap HIT")
                classMap[vmKey] = weakClass
                updateCacheTimestamp(vmKey)
                return weakClass
            }
            android.util.Log.i("DroidGuard", "loadClass(vmKey=${vmKey.take(12)}...): LOADING FRESH from ${getCacheDir(vmKey).absolutePath}")
            if (!isValidCache(vmKey)) {
                android.util.Log.e("DroidGuard", "loadClass: cache invalid for vmKey=$vmKey, cacheDir=${getCacheDir(vmKey).absolutePath}, apk=${getTheApkFile(vmKey).exists()}, opt=${getOptDir(vmKey).isDirectory}")
                throw BytesException(bytes, "VM key $vmKey not found in cache")
            }
            if (!verifyApkSignature(getTheApkFile(vmKey))) {
                android.util.Log.e("DroidGuard", "loadClass: APK signature verification failed for vmKey=$vmKey")
                getCacheDir(vmKey).deleteRecursively()
                throw ClassNotFoundException("APK signature verification failed for vmKey=$vmKey")
            }
            // Debug wait: pause before DG init so Frida can attach to .unstable.
            // Opt-in via file (no Settings query = no timing anomaly in normal operation).
            // Enable:  adb shell "su -c 'echo 20 > /data/data/com.google.android.gms/files/dg_debug_wait'"
            // Disable: adb shell "su -c 'rm /data/data/com.google.android.gms/files/dg_debug_wait'"
            val debugWaitFile = java.io.File(context.filesDir, "dg_debug_wait")
            if (debugWaitFile.exists()) {
                val secs = debugWaitFile.readText().trim().toIntOrNull() ?: 15
                android.util.Log.w("HandleProxyFactory", "DEBUG WAIT: sleeping ${secs}s for Frida attach (PID=${android.os.Process.myPid()})")
                Thread.sleep(secs * 1000L)
                android.util.Log.w("HandleProxyFactory", "DEBUG WAIT: resuming")
            }

            // Pre-spoof process-level signals: ApplicationInfo, snet_shared_uuid, pif.prop
            DgSpoofContext.prespoofProcessInfo(context)

            // S220: Stock .unstable loads ZERO native .so from GmsCore/lib/ dir.
            // Verified from /tmp/stock_maps_current_s220.txt: stock has 306 .so (all system/framework),
            // microG had 338 = same 306 + 32 extra from loadStockNativeLibs(). Stock loads native
            // code from WITHIN the APK (r-xp on base.apk at offset 0x8740000), not as separate files.
            // The 32 extra .so were a massive detection signal in dl_iterate_phdr (7 calls/session).
            // loadStockNativeLibs() REMOVED. libgcore_jni.so + link_map unlink REMOVED (not needed).

            // NOTE: mmapStockApk removed. The hybrid APK at /system/priv-app/GmsCore/
            // is already 379MB (includes stock DEX 6-17). Stock GMS on this Samsung does
            // NOT show a separate APK file mmap in maps (only dalvik-cache OAT/VDEX).
            // The gmscore_cached.apk was creating a non-stock artifact that SUSFS had to hide.

            // S217: Maps snapshot REMOVED. Writing to constellation_logs/ creates a non-stock
            // directory that DG can detect via faccessat. For debugging, manually capture maps:
            // adb shell "su -c 'cat /proc/$(pidof com.google.android.gms.unstable)/maps'" > /tmp/maps.txt

            // Use the app's natural PathClassLoader as DG VM parent.
            // S217: StockFirstClassLoader REMOVED from chain. DG captures getClass().getName()
            // for each classloader — our custom class "StockFirstClassLoader" was a detection
            // signal. Stock GMS shows plain "dalvik.system.PathClassLoader". FindClass probes
            // only target DG's own runtime classes (S216 proven), so stock-first ordering
            // provides zero benefit while adding a non-stock class name to the chain.
            // Chain now: DgVmClassLoader → PathClassLoader(APK) → BootClassLoader = stock.
            val parentLoader = context.classLoader
            // S225 PROVEN: DexClassLoader vs DgVmClassLoader is THE tachyon gate.
            // DG captures classloader class name in encrypted telemetry.
            val dgLoader = dalvik.system.DexClassLoader(
                getTheApkFile(vmKey).absolutePath, getOptDir(vmKey).absolutePath, null, parentLoader
            )
            val clazz = dgLoader.loadClass(CLASS_NAME)
            classMap[vmKey] = clazz
            weakClassMap[vmKey] = clazz
            return clazz
        }
    }

    companion object {
        const val CLASS_NAME = "com.google.ccc.abuse.droidguard.DroidGuard"
        // Stock GMS uses app_dg_cache/ (visible in /proc/self/maps as a mapped path).
        // getDir("dg_cache") creates app_dg_cache/ to match.
        const val CACHE_FOLDER_NAME = "dg_cache"
        val CLASS_LOCK = Object()
        @GuardedBy("CLASS_LOCK")
        val weakClassMap = WeakHashMap<String, Class<*>>()
        @GuardedBy("CLASS_LOCK")
        val classMap = hashMapOf<String, Class<*>>()

        val PROD_CERT_HASH = byteArrayOf(61, 122, 18, 35, 1, -102, -93, -99, -98, -96, -29, 67, 106, -73, -64, -119, 107, -5, 79, -74, 121, -12, -34, 95, -25, -62, 63, 50, 108, -113, -103, 74)

    }
}
