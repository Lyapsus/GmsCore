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
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.security.cert.Certificate
import java.util.*

open class HandleProxyFactory(private val context: Context) {

    fun createHandle(vmKey: String, pfd: ParcelFileDescriptor, extras: Bundle): HandleProxy {
        fetchFromFileDescriptor(pfd, vmKey)
        // S220: libgcore_jni.so no longer loaded. Stock .unstable has zero extra native libs.
        // nativeDlopenLazy/link_map unlink were only needed for loadStockNativeLibs (removed).
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
                updateCacheTimestamp(vmKey)
                return cachedClass
            }
            val weakClass = weakClassMap[vmKey]
            if (weakClass != null) {
                classMap[vmKey] = weakClass
                updateCacheTimestamp(vmKey)
                return weakClass
            }
            if (!isValidCache(vmKey)) {
                throw BytesException(bytes, "VM key $vmKey not found in cache")
            }
            if (!verifyApkSignature(getTheApkFile(vmKey))) {
                getCacheDir(vmKey).deleteRecursively()
                throw ClassNotFoundException("APK signature verification failed")
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

            DgIntrospect.markPhase("LOAD")
            val parentLoader = context.classLoader
            // Named class: getClass().getName() = "com.google.android.gms.droidguard.DgVmClassLoader"
            // instead of anonymous inner class name that leaks org.microg package
            val dgLoader = com.google.android.gms.droidguard.DgVmClassLoader(
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
        // S220: Stock uses app_dg_cache/ (visible in /proc/self/maps). getDir("dg_cache") → app_dg_cache/
        // Was "cache_dg" → app_cache_dg/ which was a non-stock path visible to DG's maps reader.
        const val CACHE_FOLDER_NAME = "dg_cache"
        val CLASS_LOCK = Object()
        @GuardedBy("CLASS_LOCK")
        val weakClassMap = WeakHashMap<String, Class<*>>()
        @GuardedBy("CLASS_LOCK")
        val classMap = hashMapOf<String, Class<*>>()
        val PROD_CERT_HASH = byteArrayOf(61, 122, 18, 35, 1, -102, -93, -99, -98, -96, -29, 67, 106, -73, -64, -119, 107, -5, 79, -74, 121, -12, -34, 95, -25, -62, 63, 50, 108, -113, -103, 74)


    }
}
