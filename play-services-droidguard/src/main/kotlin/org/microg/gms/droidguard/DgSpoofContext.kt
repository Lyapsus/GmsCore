/*
 * DG-facing Context wrapper that spoofs microG-specific signals.
 *
 * DG's Java code calls context.getPackageManager().getPackageInfo() through
 * normal Java dispatch. We intercept by replacing the IPackageManager binder
 * inside ApplicationPackageManager with a Proxy that modifies getPackageInfo results.
 */

package org.microg.gms.droidguard

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

class DgSpoofContext(base: Context) : ContextWrapper(base) {

    @Volatile private var spoofedPm: PackageManager? = null

    /**
     * Override getPackageManager() so DG's JNI context.getPackageManager()
     * returns OUR patched PM, not the base context's unpatched one.
     */
    override fun getPackageManager(): PackageManager {
        spoofedPm?.let { return it }
        val pm = super.getPackageManager()
        patchPmInstanceDirect(pm, packageName)
        spoofedPm = pm
        return pm
    }

    /**
     * DG calls context.getApplicationContext().getPackageManager() on background threads.
     * Return ourselves so it still goes through our getPackageManager() override.
     */
    override fun getApplicationContext(): Context = this

    companion object {
        const val STOCK_VERSION_NAME = "26.02.33 (190400-858744110)"
        const val STOCK_APPLICATION_CLASS = "co.g.App"
        const val STOCK_TARGET_SDK = 36

        @Volatile private var pmPatched = false

        fun spoofPackageInfo(info: PackageInfo, gmsPackage: String) {
            // Only spoof our own package
            if (info.packageName != gmsPackage && info.packageName != null) return

            info.versionName = STOCK_VERSION_NAME

            // Replace permission list with stock GMS's full 386 permissions
            info.requestedPermissions = StockGmsData.PERMISSIONS
            info.requestedPermissionsFlags = IntArray(StockGmsData.PERMISSIONS.size) {
                PackageInfo.REQUESTED_PERMISSION_GRANTED
            }

            info.applicationInfo?.let { spoofAppInfo(it) }
        }

        fun spoofAppInfo(info: ApplicationInfo) {
            try {
                val f = ApplicationInfo::class.java.getDeclaredField("className")
                f.isAccessible = true
                f.set(info, STOCK_APPLICATION_CLASS)
            } catch (_: Exception) {}
            info.targetSdkVersion = STOCK_TARGET_SDK
            // Mask FLAG_DEBUGGABLE (debug builds set it, stock doesn't)
            info.flags = info.flags and ApplicationInfo.FLAG_DEBUGGABLE.inv()
            // Ensure FLAG_SYSTEM and FLAG_UPDATED_SYSTEM_APP are set (stock GMS is system app)
            info.flags = info.flags or ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
        }

        /**
         * Patch a specific PackageManager instance's mPM binder with our intercepting proxy.
         * No static guard - patches exactly the instance given.
         */
        fun patchPmInstanceDirect(pm: PackageManager, gmsPackage: String) {
            try {
                // Unwrap through any wrapper layers to find ApplicationPackageManager
                var realPm: Any = pm
                for (attempt in 0..5) {
                    var hasMpm = false
                    var c: Class<*>? = realPm.javaClass
                    while (c != null) {
                        try { c.getDeclaredField("mPM"); hasMpm = true; break } catch (_: NoSuchFieldException) { c = c.superclass }
                    }
                    if (hasMpm) break
                    var unwrapped = false
                    for (fname in arrayOf("wrapped", "mWrapped", "delegate", "mDelegate", "mPm")) {
                        var fc: Class<*>? = realPm.javaClass
                        while (fc != null) {
                            try {
                                val f = fc.getDeclaredField(fname)
                                f.isAccessible = true
                                val inner = f.get(realPm)
                                if (inner != null) { realPm = inner; unwrapped = true; break }
                            } catch (_: NoSuchFieldException) { fc = fc.superclass }
                            if (unwrapped) break
                        }
                        if (unwrapped) break
                    }
                    if (!unwrapped) break
                }

                var mPmField: java.lang.reflect.Field? = null
                var clazz: Class<*>? = realPm.javaClass
                while (clazz != null) {
                    try { mPmField = clazz.getDeclaredField("mPM"); break } catch (_: NoSuchFieldException) { clazz = clazz.superclass }
                }
                if (mPmField == null) {
                    android.util.Log.w("DgSpoofContext", "patchPmDirect: mPM not found in ${realPm.javaClass.name}")
                    return
                }
                mPmField.isAccessible = true
                val originalPm = mPmField.get(realPm) ?: return
                // Skip if already proxied
                if (Proxy.isProxyClass(originalPm.javaClass)) {
                    android.util.Log.d("DgSpoofContext", "patchPmDirect: already proxied")
                    return
                }

                val ipmClass = Class.forName("android.content.pm.IPackageManager")
                val allIfaces = mutableSetOf(ipmClass)
                for (iface in originalPm.javaClass.interfaces) { allIfaces.add(iface) }

                val proxyPm = Proxy.newProxyInstance(
                    ipmClass.classLoader,
                    allIfaces.toTypedArray(),
                    PmInvocationHandler(originalPm, gmsPackage)
                )
                mPmField.set(realPm, proxyPm)
                android.util.Log.i("DgSpoofContext", "patchPmDirect: proxy installed on ${clazz?.name} (from ${pm.javaClass.name})")
            } catch (e: Exception) {
                android.util.Log.w("DgSpoofContext", "patchPmDirect failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        /**
         * Patch the IPackageManager binder inside the PackageManager to intercept
         * getPackageInfo results and spoof microG-specific fields.
         */
        fun patchPackageManager(context: Context) {
            if (pmPatched) return
            synchronized(this) {
                if (pmPatched) return
                try {
                    val pm = context.packageManager
                    android.util.Log.i("DgSpoofContext", "PM class: ${pm.javaClass.name}")

                    // Dump full class hierarchy + all fields for debugging
                    var dumpClass: Class<*>? = pm.javaClass
                    while (dumpClass != null) {
                        val fields = dumpClass.declaredFields
                        val fieldDescs = fields.map { f ->
                            f.isAccessible = true
                            val v = try { f.get(pm) } catch (_: Exception) { "?" }
                            "${f.name}:${f.type.simpleName}=${v?.javaClass?.name ?: "null"}"
                        }
                        android.util.Log.i("DgSpoofContext", "  ${dumpClass.name} fields(${fields.size}): ${fieldDescs.joinToString(", ")}")
                        dumpClass = dumpClass.superclass
                    }

                    // Hierarchy is: TracingIntentService$1 → PackageManagerWrapper{wrapped} → ApplicationPackageManager{mPM}
                    // Navigate through wrappers to find the real ApplicationPackageManager, then its mPM field

                    // Step 1: Unwrap to find ApplicationPackageManager
                    var realPm: Any = pm
                    for (attempt in 0..5) {
                        // Check if current object has mPM
                        var hasMpm = false
                        var c: Class<*>? = realPm.javaClass
                        while (c != null) {
                            try { c.getDeclaredField("mPM"); hasMpm = true; break } catch (_: NoSuchFieldException) { c = c.superclass }
                        }
                        if (hasMpm) break

                        // Try unwrapping via known delegate fields
                        var unwrapped = false
                        for (fname in arrayOf("wrapped", "mWrapped", "delegate", "mDelegate", "mPm")) {
                            var fc: Class<*>? = realPm.javaClass
                            while (fc != null) {
                                try {
                                    val f = fc.getDeclaredField(fname)
                                    f.isAccessible = true
                                    val inner = f.get(realPm)
                                    if (inner != null) {
                                        android.util.Log.i("DgSpoofContext", "Unwrapped via ${fc.name}.$fname → ${inner.javaClass.name}")
                                        realPm = inner
                                        unwrapped = true
                                        break
                                    }
                                } catch (_: NoSuchFieldException) { fc = fc.superclass }
                                if (unwrapped) break
                            }
                            if (unwrapped) break
                        }
                        if (!unwrapped) break
                    }

                    // Step 2: Find mPM in the (now unwrapped) PM
                    var mPmField: java.lang.reflect.Field? = null
                    var foundInClass: Class<*>? = null
                    var clazz: Class<*>? = realPm.javaClass
                    while (clazz != null) {
                        try {
                            mPmField = clazz.getDeclaredField("mPM")
                            foundInClass = clazz
                            break
                        } catch (_: NoSuchFieldException) { clazz = clazz.superclass }
                    }
                    if (mPmField == null) {
                        android.util.Log.w("DgSpoofContext", "mPM not found even after unwrap. Final class: ${realPm.javaClass.name}")
                        pmPatched = true
                        return
                    }
                    mPmField.isAccessible = true
                    val originalPm = mPmField.get(realPm)
                    if (originalPm == null) {
                        android.util.Log.w("DgSpoofContext", "mPM field is null")
                        pmPatched = true
                        return
                    }
                    android.util.Log.i("DgSpoofContext", "mPM found in ${clazz?.name}: ${originalPm.javaClass.name}")

                    // Get IPackageManager interface
                    val ipmClass = Class.forName("android.content.pm.IPackageManager")
                    val allIfaces = mutableSetOf(ipmClass)
                    // Add any other interfaces the original implements
                    for (iface in originalPm.javaClass.interfaces) {
                        allIfaces.add(iface)
                    }

                    val proxyPm = Proxy.newProxyInstance(
                        ipmClass.classLoader,
                        allIfaces.toTypedArray(),
                        PmInvocationHandler(originalPm, context.packageName)
                    )
                    mPmField.set(realPm, proxyPm)
                    pmPatched = true
                    android.util.Log.i("DgSpoofContext", "IPackageManager proxy installed on ${clazz?.name}")
                } catch (e: Exception) {
                    android.util.Log.w("DgSpoofContext", "PM patch failed: ${e.javaClass.simpleName}: ${e.message}")
                    pmPatched = true  // Don't retry on failure
                }
            }
        }

        /**
         * Patch the process-global IPackageManager cached in ActivityThread.sPackageManager.
         * Every ApplicationPackageManager in this process reads from this static field.
         * Patching it once catches all callers regardless of Context or PM instance.
         */
        @Volatile private var activityThreadPatched = false
        fun patchActivityThreadPm(gmsPackage: String) {
            if (activityThreadPatched) return
            synchronized(this) {
                if (activityThreadPatched) return
                try {
                    val atClass = Class.forName("android.app.ActivityThread")
                    val spmField = atClass.getDeclaredField("sPackageManager")
                    spmField.isAccessible = true
                    val original = spmField.get(null)
                    if (original == null) {
                        android.util.Log.w("DgSpoofContext", "ActivityThread.sPackageManager is null")
                        activityThreadPatched = true
                        return
                    }
                    if (Proxy.isProxyClass(original.javaClass)) {
                        android.util.Log.d("DgSpoofContext", "ActivityThread.sPackageManager already proxied")
                        activityThreadPatched = true
                        return
                    }

                    val ipmClass = Class.forName("android.content.pm.IPackageManager")
                    val allIfaces = mutableSetOf(ipmClass)
                    for (iface in original.javaClass.interfaces) { allIfaces.add(iface) }

                    val proxy = Proxy.newProxyInstance(
                        ipmClass.classLoader,
                        allIfaces.toTypedArray(),
                        PmInvocationHandler(original, gmsPackage)
                    )
                    spmField.set(null, proxy)
                    activityThreadPatched = true
                    android.util.Log.i("DgSpoofContext", "ActivityThread.sPackageManager proxied (process-global)")
                } catch (e: Exception) {
                    android.util.Log.w("DgSpoofContext", "ActivityThread patch failed: ${e.javaClass.simpleName}: ${e.message}")
                    activityThreadPatched = true
                }
            }
        }

        /**
         * Pre-spoof process-level state before DG init.
         */
        fun prespoofProcessInfo(context: Context) {
            try {
                spoofAppInfo(context.applicationInfo)
            } catch (_: Exception) {}

            // Patch PackageManager to intercept getPackageInfo results
            patchPackageManager(context)

            // Patch process-global ActivityThread.sPackageManager - catches ALL PM calls
            // regardless of which Context or PackageManager instance is used
            patchActivityThreadPm(context.packageName)

            // Ensure snet_shared_uuid exists
            try {
                val prefs = context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)
                if (!prefs.contains("snet_shared_uuid")) {
                    prefs.edit().putString("snet_shared_uuid", java.util.UUID.randomUUID().toString()).apply()
                }
            } catch (_: Exception) {}

            // Delete pif.prop (DG probes via faccessat)
            try {
                java.io.File(context.filesDir, "pif.prop").let { if (it.exists()) it.delete() }
            } catch (_: Exception) {}
        }
    }

    override fun getApplicationInfo(): ApplicationInfo {
        val info = super.getApplicationInfo()
        spoofAppInfo(info)
        return info
    }

    /**
     * InvocationHandler that intercepts IPackageManager method calls.
     * When getPackageInfo returns a PackageInfo for the GMS package,
     * spoof the microG-specific fields.
     */
    private class PmInvocationHandler(
        private val delegate: Any,
        private val gmsPackage: String
    ) : InvocationHandler {
        override fun invoke(proxy: Any?, method: Method, args: Array<out Any?>?): Any? {
            val result = if (args != null) method.invoke(delegate, *args) else method.invoke(delegate)

            // Log ALL PM method calls to discover what DG actually queries
            val argStr = args?.joinToString(", ") { it?.toString()?.take(60) ?: "null" } ?: ""
            val resultStr = when (result) {
                null -> "null"
                is PackageInfo -> "PackageInfo(${result.packageName})"
                is ApplicationInfo -> "ApplicationInfo(${result.packageName})"
                is Array<*> -> "${result.javaClass.componentType?.simpleName}[${result.size}]"
                is String -> "\"${result.take(60)}\""
                else -> "${result.javaClass.simpleName}"
            }
            android.util.Log.d("DgSpoofContext", "PM CALL ${method.name}($argStr) → $resultStr")

            // Intercept getPackageInfo results
            if (result is PackageInfo && method.name == "getPackageInfo") {
                val origPerms = result.requestedPermissions?.size ?: 0
                spoofPackageInfo(result, gmsPackage)
                val newPerms = result.requestedPermissions?.size ?: 0
                android.util.Log.i("DgSpoofContext", "PM INTERCEPT getPackageInfo(${result.packageName}): " +
                    "perms $origPerms→$newPerms, versionName=${result.versionName}, " +
                    "className=${result.applicationInfo?.className}, targetSdk=${result.applicationInfo?.targetSdkVersion}")
            }

            // Intercept getApplicationInfo results
            if (result is ApplicationInfo && method.name == "getApplicationInfo") {
                val pkg = args?.firstOrNull() as? String
                if (pkg == gmsPackage) {
                    spoofAppInfo(result)
                    android.util.Log.i("DgSpoofContext", "PM INTERCEPT getApplicationInfo($pkg): " +
                        "className=${result.className}, targetSdk=${result.targetSdkVersion}")
                }
            }

            // Intercept getInstallerPackageName — return Play Store for GMS
            if (method.name == "getInstallerPackageName" || method.name == "getInstallSourceInfo") {
                val pkg = args?.firstOrNull() as? String
                if (pkg == gmsPackage) {
                    if (method.name == "getInstallerPackageName") {
                        android.util.Log.d("DgSpoofContext", "PM INTERCEPT getInstallerPackageName($pkg) → com.android.vending")
                        return "com.android.vending"
                    }
                }
            }

            return result
        }
    }
}
