/*
 * Named classloader classes for DroidGuard VM loading.
 *
 * DG captures classloader chain via getClass().getName(). Anonymous Kotlin object
 * expressions produce names like "org.microg.gms.droidguard.HandleProxyFactory$...$1"
 * which directly identify microG. These named classes in the stock GMS package
 * produce "com.google.android.gms.droidguard.StockFirstClassLoader" etc.
 */

package com.google.android.gms.droidguard

import dalvik.system.DexClassLoader
import dalvik.system.PathClassLoader
import org.microg.gms.droidguard.DgIntrospect

/**
 * Stock-first PathClassLoader: searches stock GMS DEX before microG.
 * Overrides parent-first delegation so DG FindClass probes hit stock classes.
 */
class StockFirstClassLoader(
    dexPath: String,
    parent: ClassLoader
) : PathClassLoader(dexPath, parent) {

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        DgIntrospect.log("STOCK_LC", "$name t=${Thread.currentThread().name}")
        findLoadedClass(name)?.let {
            DgIntrospect.log("STOCK_CACHED", name)
            return it
        }
        try {
            val c = findClass(name)
            DgIntrospect.log("STOCK_FOUND", name)
            return c
        } catch (_: ClassNotFoundException) {}
        try {
            val c = parent.loadClass(name)
            DgIntrospect.log("STOCK_PARENT", name)
            return c
        } catch (e: ClassNotFoundException) {
            // CRITICAL: log ALL failed lookups to logcat unconditionally.
            // DG's 23 encrypted FindClass probes target specific classes — if they
            // go through this classloader, failed lookups reveal what DG checks for.
            android.util.Log.w("DG_CLASS_MISS", "NOT FOUND: $name (thread=${Thread.currentThread().name})")
            throw e
        }
    }

    override fun findClass(name: String): Class<*> {
        DgIntrospect.log("STOCK_FC", name)
        return super.findClass(name)
    }

    override fun toString(): String {
        return "dalvik.system.PathClassLoader[DexPathList[[zip file \"/system/priv-app/GmsCore/GmsCore.apk\"],nativeLibraryDirectories=[/system/priv-app/GmsCore/lib/arm64, /system/lib64, /system/product/lib64]]]"
    }
}

/**
 * DexClassLoader for DG's the.apk VM code.
 * Logs all class lookups when introspection is enabled.
 */
class DgVmClassLoader(
    dexPath: String,
    optimizedDirectory: String?,
    librarySearchPath: String?,
    parent: ClassLoader
) : DexClassLoader(dexPath, optimizedDirectory, librarySearchPath, parent) {

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        DgIntrospect.log("DG_LC", "$name t=${Thread.currentThread().name}")
        return super.loadClass(name, resolve)
    }

    override fun findClass(name: String): Class<*> {
        DgIntrospect.log("DG_FC", name)
        return super.findClass(name)
    }
}
