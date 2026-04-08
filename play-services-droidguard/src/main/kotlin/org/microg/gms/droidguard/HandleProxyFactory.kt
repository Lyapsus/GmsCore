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

            // Force ART to load 4 DEX files it normally skips (6,8,11,15).
            // Stock GMS has 17 dalvik-classes regions in maps; we have 13 because ART
            // doesn't load DEX whose classes aren't referenced. Loading one class from
            // each triggers ART to extract and mmap the DEX.
            forceLoadSkippedDex(context)

            // Pre-spoof process-level signals: ApplicationInfo, snet_shared_uuid, pif.prop
            DgSpoofContext.prespoofProcessInfo(context)

            // Spoof Build fields so DG VM sees real Samsung device identity.
            // PIF's spoofBuild crashes .unstable on Samsung Android 12 (JNI field ID bug).
            // Java reflection avoids the JNI layer entirely.
            spoofBuildFields(context)

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
            DgIntrospect.markPhase("LOAD")
            val parentLoader = context.classLoader
            val originalClassLoader: ClassLoader? = null // no spoofing needed — natural CL is correct
            try {
                // Named class: getClass().getName() = "com.google.android.gms.droidguard.DgVmClassLoader"
                // instead of "org.microg.gms.droidguard.HandleProxyFactory$loadClass$1$dgLoader$1"
                val dgLoader = com.google.android.gms.droidguard.DgVmClassLoader(
                    getTheApkFile(vmKey).absolutePath, getOptDir(vmKey).absolutePath, null, parentLoader
                )
                // Log classloader chain as DG will see it via getClass().getName()
                var cl: ClassLoader? = dgLoader
                val chainNames = mutableListOf<String>()
                while (cl != null) {
                    chainNames.add("${cl.javaClass.name}(${cl.toString().take(80)})")
                    cl = cl.parent
                }
                android.util.Log.i("HandleProxyFactory", "DG classloader chain: ${chainNames.joinToString(" → ")}")
                val clazz = dgLoader.loadClass(CLASS_NAME)
                classMap[vmKey] = clazz
                weakClassMap[vmKey] = clazz
                return clazz
            } finally {
                // Restore original classloader after DG class loading
                if (originalClassLoader != null) {
                    spoofContextClassLoader(context, originalClassLoader)
                }
            }
        }
    }

    companion object {
        const val CLASS_NAME = "com.google.ccc.abuse.droidguard.DroidGuard"
        // Stock GMS uses app_dg_cache/ (visible in /proc/self/maps as a mapped path).
        // getDir("dg_cache") creates app_dg_cache/ to match.
        const val CACHE_FOLDER_NAME = "dg_cache"
        const val STOCK_DEX_DIR = "gms_dex"
        val CLASS_LOCK = Object()
        @GuardedBy("CLASS_LOCK")
        val weakClassMap = WeakHashMap<String, Class<*>>()
        @GuardedBy("CLASS_LOCK")
        val classMap = hashMapOf<String, Class<*>>()
        @Volatile
        private var enrichedClassLoader: ClassLoader? = null

        fun getEnrichedClassLoader(context: Context): ClassLoader {
            enrichedClassLoader?.let { return it }
            synchronized(CLASS_LOCK) {
                enrichedClassLoader?.let { return it }
                val stockDir = File(context.filesDir, STOCK_DEX_DIR)
                if (!stockDir.isDirectory) {
                    android.util.Log.i("HandleProxyFactory", "No $STOCK_DEX_DIR dir — using default classloader")
                    return context.classLoader.also { enrichedClassLoader = it }
                }
                val dexFiles = stockDir.listFiles { f -> f.extension == "dex" }?.sortedBy { it.name } ?: emptyList()
                if (dexFiles.isEmpty()) {
                    android.util.Log.i("HandleProxyFactory", "$STOCK_DEX_DIR dir empty — using default classloader")
                    return context.classLoader.also { enrichedClassLoader = it }
                }
                val dexPath = dexFiles.joinToString(":") { it.absolutePath }
                val optDir = File(context.codeCacheDir, "gms_dex_opt")
                optDir.mkdirs()
                android.util.Log.i("HandleProxyFactory", "Loading ${dexFiles.size} stock DEX files from $stockDir (${dexFiles.sumOf { it.length() } / 1024 / 1024}MB)")
                try {
                    // STOCK-ONLY classloader: parent = system classloader (has framework classes)
                    // but NOT microG's app classloader. DG's FindClass probes walk this chain:
                    // DgVmClassLoader → StockOnly(stock DEX) → SystemClassLoader(framework) → Boot
                    // microG classes are NOT in the chain — FindClass on org.microg.* returns null,
                    // exactly as it would on genuine stock GMS. Previously parent=context.classLoader
                    // leaked microG classes as fallback (any class in microG APK was discoverable).
                    val systemClassLoader = ClassLoader.getSystemClassLoader()
                    val loader = com.google.android.gms.droidguard.StockFirstClassLoader(dexPath, systemClassLoader)
                    android.util.Log.i("HandleProxyFactory", "Stock-ONLY classloader: ${dexFiles.size} stock DEX, parent=system (NO microG)")
                    enrichedClassLoader = loader
                    return loader
                } catch (e: Exception) {
                    android.util.Log.e("HandleProxyFactory", "Failed to create enriched classloader: ${e.message}")
                    return context.classLoader.also { enrichedClassLoader = it }
                }
            }
        }
        /**
         * Force ART to load DEX files it normally skips.
         * Stock GMS shows 17 dalvik-classes regions in /proc/self/maps.
         * ART skips DEX whose classes aren't referenced by running code.
         * Loading one class from each skipped DEX triggers ART extraction.
         */
        @Volatile private var dexForceLoaded = false
        fun forceLoadSkippedDex(context: Context) {
            if (dexForceLoaded) return
            dexForceLoaded = true
            // One class from each of the 4 DEX files ART skips (6,8,11,15)
            // These are stock GMS classes present in the hybrid APK.
            val targets = arrayOf(
                "an.\$\$ExternalSyntheticApiModelOutline0",                           // classes6
                "com.google.android.gms.nearby.sharing.AdvertisingOptions",           // classes8
                "com.google.android.libraries.barhopper.Barcode",                     // classes11
                "j\$.net.URLDecoder",                                                 // classes15
            )
            var loaded = 0
            for (name in targets) {
                try {
                    Class.forName(name, false, context.classLoader)
                    loaded++
                } catch (_: ClassNotFoundException) {
                    // Expected if class not in our APK — only matters for hybrid APK
                }
            }
            android.util.Log.i("HandleProxyFactory", "Force-loaded $loaded/${targets.size} DEX trigger classes")
        }

        val PROD_CERT_HASH = byteArrayOf(61, 122, 18, 35, 1, -102, -93, -99, -98, -96, -29, 67, 106, -73, -64, -119, 107, -5, 79, -74, 121, -12, -34, 95, -25, -62, 63, 50, 108, -113, -103, 74)

        @Volatile
        private var stockLibsLoaded = false

        // Keep references to prevent GC from unmapping
        private val mmapBuffers = mutableListOf<java.nio.MappedByteBuffer>()
        private val mmapFiles = mutableListOf<java.io.RandomAccessFile>()

        /**
         * Load stock GMS native libraries so they appear in dl_iterate_phdr and /proc/self/maps.
         * DG calls dl_iterate_phdr 7 times per session to enumerate loaded .so files.
         * Only r-xp (dlopen'd) libs appear — r--s (mmap'd) are invisible.
         *
         * Strategy: use native dlopen(RTLD_LAZY) via DgIntrospect helper.
         * RTLD_LAZY defers symbol resolution (avoids crash on unresolved deps).
         * Falls back to mmap (r--s) if dlopen fails or native helper unavailable.
         */
        fun loadStockNativeLibs(context: Context) {
            if (stockLibsLoaded) return
            synchronized(CLASS_LOCK) {
                if (stockLibsLoaded) return
                // Load from the system native lib directory (where stock GMS loads from).
                // This matches stock paths in both /proc/self/maps and dl_iterate_phdr.
                // Stock: /system/priv-app/GmsCore/lib/arm64/*.so
                // Fallback: /data/data/.../files/gms_libs/*.so (old path, non-stock)
                val systemLibDir = java.io.File(context.applicationInfo.nativeLibraryDir ?: "/system/priv-app/GmsCore/lib/arm64")
                val fallbackLibDir = java.io.File(context.filesDir, "gms_libs")
                val libDir = if (systemLibDir.isDirectory && (systemLibDir.listFiles { f -> f.extension == "so" }?.size ?: 0) > 0) {
                    android.util.Log.i("HandleProxyFactory", "Loading native libs from system path: ${systemLibDir.absolutePath}")
                    systemLibDir
                } else if (fallbackLibDir.isDirectory) {
                    android.util.Log.w("HandleProxyFactory", "System native lib dir empty/missing, falling back to gms_libs (non-stock path)")
                    fallbackLibDir
                } else {
                    android.util.Log.i("HandleProxyFactory", "No native libs dir found")
                    stockLibsLoaded = true
                    return
                }
                val libs = libDir.listFiles { f -> f.extension == "so" } ?: emptyArray()
                var dlopened = 0
                var failed = 0
                for (lib in libs) {
                    if (lib.name == "libgcore_jni.so") continue // skip ourselves
                    // dlopen(RTLD_LAZY) via our native helper — defers symbol resolution.
                    // System.load() uses RTLD_NOW which crashes on unresolved deps.
                    val handle = try {
                        DgIntrospect.nativeDlopenLazy(lib.absolutePath)
                    } catch (e: UnsatisfiedLinkError) {
                        0L  // native helper not loaded
                    }
                    if (handle != 0L) {
                        dlopened++
                    } else {
                        failed++
                    }
                }
                android.util.Log.i("HandleProxyFactory", "GMS native libs: $dlopened dlopen'd + $failed failed / ${libs.size} total")
                stockLibsLoaded = true
            }
        }

        @Volatile
        private var stockApkMapped = false
        private var stockApkRaf: java.io.RandomAccessFile? = null
        private var stockApkBuffer: java.nio.MappedByteBuffer? = null

        /**
         * mmap stock GMS APK into process memory so /proc/self/maps shows a ~379MB
         * file-backed region. DG reads maps via direct SVC syscall and uses APK file
         * size and dalvik-classes region count as signals. Stock: 379MB/17 regions.
         * microG: 57MB/5 regions. SUSFS add_sus_map can hide the microG entry.
         */
        fun mmapStockApk(context: Context) {
            if (stockApkMapped) return
            synchronized(CLASS_LOCK) {
                if (stockApkMapped) return
                val stockApk = java.io.File(context.filesDir, "gmscore_cached.apk")
                if (!stockApk.isFile) {
                    android.util.Log.i("HandleProxyFactory", "No gmscore_cached.apk for mmap")
                    stockApkMapped = true
                    return
                }
                try {
                    val raf = java.io.RandomAccessFile(stockApk, "r")
                    val channel = raf.channel
                    val mapped = channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, channel.size())
                    mapped.get(0)  // touch to ensure kernel maps pages
                    stockApkRaf = raf
                    stockApkBuffer = mapped
                    stockApkMapped = true
                    android.util.Log.i("HandleProxyFactory", "mmap'd stock APK: ${channel.size() / 1024 / 1024}MB from ${stockApk.absolutePath}")
                } catch (e: Exception) {
                    android.util.Log.e("HandleProxyFactory", "Failed to mmap stock APK: ${e.message}")
                    stockApkMapped = true
                }
            }
        }

        /**
         * Temporarily replace the classloader returned by context.getClassLoader().
         * DG collects current_class_loaders telemetry via JNI calling
         * context.getClassLoader().toString(). Without this spoof, it shows
         * microG's PathClassLoader with /system/priv-app/GmsCore/ path.
         * Returns the original classloader (for restoration), or null on failure.
         */
        fun spoofContextClassLoader(context: Context, newLoader: ClassLoader): ClassLoader? {
            try {
                // Unwrap ContextWrapper chain to get ContextImpl
                var ctx: android.content.Context = context
                while (ctx is android.content.ContextWrapper) {
                    val baseField = android.content.ContextWrapper::class.java.getDeclaredField("mBase")
                    baseField.isAccessible = true
                    ctx = baseField.get(ctx) as android.content.Context
                }
                // ContextImpl.mPackageInfo (LoadedApk) holds mClassLoader
                val pkgInfoField = ctx.javaClass.getDeclaredField("mPackageInfo")
                pkgInfoField.isAccessible = true
                val loadedApk = pkgInfoField.get(ctx) ?: return null
                val clField = loadedApk.javaClass.getDeclaredField("mClassLoader")
                clField.isAccessible = true
                val original = clField.get(loadedApk) as? ClassLoader
                clField.set(loadedApk, newLoader)
                android.util.Log.i("HandleProxyFactory", "Context classloader spoofed: ${newLoader.javaClass.simpleName}")
                return original
            } catch (e: Exception) {
                android.util.Log.w("HandleProxyFactory", "Context classloader spoof failed: ${e.message}")
                return null
            }
        }

        @Volatile
        private var buildSpoofed = false

        /**
         * Spoof android.os.Build fields via Java reflection.
         * PIF's JNI-based spoofBuild crashes on Samsung Android 12 (invalid field ID).
         * This achieves the same result safely from Java.
         * Values match PIF's pif.prop (Pixel 7 Beta).
         */
        fun spoofBuildFields(context: Context) {
            if (buildSpoofed) return
            synchronized(CLASS_LOCK) {
                if (buildSpoofed) return
                try {
                    // Read Build identity from accessible locations.
                    // /data/adb/ is not readable by GMS (non-root, SELinux gmscore_app).
                    // Priority: 1) microG files dir pif.prop, 2) real device values (no spoof)
                    val pifLocations = listOf(
                        java.io.File(context.filesDir, "pif.prop"),
                        java.io.File("/data/adb/modules/playintegrityfix/pif.prop")
                    )
                    val props = pifLocations.firstOrNull { it.canRead() }?.let { f ->
                        val p = java.util.Properties()
                        f.inputStream().use { p.load(it) }
                        android.util.Log.i("HandleProxyFactory", "Loaded Build identity from ${f.absolutePath}")
                        p
                    }
                    // Fall back to real device identity — no spoofing mismatch is better than
                    // Pixel 7 Java + Samsung native (a clear spoofing signal DG can detect)
                    val fingerprint = props?.getProperty("FINGERPRINT") ?: android.os.Build.FINGERPRINT
                    val manufacturer = props?.getProperty("MANUFACTURER") ?: android.os.Build.MANUFACTURER
                    val model = props?.getProperty("MODEL") ?: android.os.Build.MODEL
                    val securityPatch = props?.getProperty("SECURITY_PATCH")

                    val buildClass = android.os.Build::class.java
                    val versionClass = android.os.Build.VERSION::class.java

                    fun setField(clazz: Class<*>, name: String, value: String) {
                        try {
                            val field = clazz.getDeclaredField(name)
                            field.isAccessible = true
                            field.set(null, value)
                        } catch (e: Exception) {
                            android.util.Log.w("HandleProxyFactory", "Failed to spoof Build.$name: ${e.message}")
                        }
                    }

                    setField(buildClass, "FINGERPRINT", fingerprint)
                    setField(buildClass, "MANUFACTURER", manufacturer)
                    setField(buildClass, "MODEL", model)
                    setField(buildClass, "BRAND", manufacturer.lowercase())
                    // Parse fingerprint for PRODUCT, DEVICE, ID
                    // Format: brand/product/device:version/id/incremental:type/tags
                    val parts = fingerprint.split("/", ":")
                    if (parts.size >= 5) {
                        setField(buildClass, "PRODUCT", parts[1])
                        setField(buildClass, "DEVICE", parts[2].substringBefore(":"))
                        setField(buildClass, "ID", parts[4])
                    }
                    if (securityPatch != null) {
                        setField(versionClass, "SECURITY_PATCH", securityPatch)
                    }

                    android.util.Log.i("HandleProxyFactory", "Build fields spoofed: MODEL=$model MANUFACTURER=$manufacturer FINGERPRINT=${fingerprint.take(40)}...")
                    buildSpoofed = true
                } catch (e: Exception) {
                    android.util.Log.e("HandleProxyFactory", "Build spoof failed: ${e.message}")
                }
            }
        }
    }
}
