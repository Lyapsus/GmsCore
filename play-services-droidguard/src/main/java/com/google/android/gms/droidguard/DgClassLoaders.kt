/*
 * Named classloader for DroidGuard VM loading.
 *
 * DG captures classloader chain via getClass().getName(). Anonymous Kotlin object
 * expressions produce names like "org.microg.gms.droidguard.HandleProxyFactory$...$1"
 * which directly identify microG. This named class in the stock GMS package
 * produces "com.google.android.gms.droidguard.DgVmClassLoader".
 */

package com.google.android.gms.droidguard

import dalvik.system.DexClassLoader
import org.microg.gms.droidguard.DgIntrospect

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
