/*
 * Pre-spoof process-level signals before DroidGuard VM init.
 *
 * S227 sweep proved PM proxy (ActivityThread.sPackageManager patching, 386 stock
 * permissions injection, installer spoof) is unnecessary for tachyon registration.
 * Only spoofAppInfo + snet_shared_uuid + pif.prop deletion are needed.
 */

package org.microg.gms.droidguard

import android.content.Context
import android.content.pm.ApplicationInfo

object DgSpoofContext {
    private const val STOCK_APPLICATION_CLASS = "co.g.App"
    private const val STOCK_TARGET_SDK = 36

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
     * Pre-spoof process-level state before DG init.
     * Called once from HandleProxyFactory before loading DG VM.
     */
    fun prespoofProcessInfo(context: Context) {
        try {
            spoofAppInfo(context.applicationInfo)
        } catch (_: Exception) {}

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
