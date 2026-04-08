/*
 * DroidGuard Introspection — captures what DG probes during VM execution.
 *
 * Phase 1: Java-level classloader + Context logging.
 *   Logs every loadClass/findClass call through our enriched classloader
 *   and the DG DexClassLoader. Also logs Context method calls via wrapper.
 *
 * Phase 2: JNI function table pre-hooking (native library).
 *   Hooks FindClass/GetMethodID/GetStaticMethodID/GetStringUTFChars at the
 *   JNI table level BEFORE DG copies it. Captures all JNI calls including
 *   from native threads that bypass Java classloaders.
 *
 * Usage:
 *   DgIntrospect.startCapture(context)  — before DG class loading
 *   DgIntrospect.markPhase("RUN")       — before HandleProxy.run()
 *   DgIntrospect.stopCapture()          — after HandleProxy.close()
 *   Log file: /data/data/com.google.android.gms/files/dg_introspect.log
 *
 * Enable:  adb shell "su -c 'touch /data/data/com.google.android.gms/files/dg_introspect_enable'"
 * Disable: adb shell "su -c 'rm /data/data/com.google.android.gms/files/dg_introspect_enable'"
 * Read:    adb shell "su -c 'cat /data/data/com.google.android.gms/files/dg_introspect.log'"
 */

package org.microg.gms.droidguard

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

object DgIntrospect {
    private val enabled = AtomicBoolean(false)
    private val probeCounter = AtomicInteger(0)
    private val entries = ConcurrentLinkedQueue<String>()
    private var logFile: File? = null
    private var startTimeNs: Long = 0
    private var currentPhase = "INIT"

    /**
     * Check if introspection is enabled (file-based opt-in).
     * Zero overhead when disabled — just a File.exists() check.
     */
    fun isEnabled(context: Context): Boolean {
        return File(context.filesDir, "dg_introspect_enable").exists()
    }

    fun startCapture(context: Context) {
        if (!isEnabled(context)) return
        enabled.set(true)
        startTimeNs = System.nanoTime()
        probeCounter.set(0)
        entries.clear()
        logFile = File(context.filesDir, "dg_introspect.log")
        log("START", "pid=${android.os.Process.myPid()} tid=${Thread.currentThread().id}")
        android.util.Log.i("DgIntrospect", "Capture started → ${logFile?.absolutePath}")
    }

    fun markPhase(phase: String) {
        if (!enabled.get()) return
        currentPhase = phase
        log("PHASE", phase)
    }

    fun stopCapture() {
        if (!enabled.get()) return
        log("STOP", "total_probes=${probeCounter.get()}")
        flush()
        enabled.set(false)
        android.util.Log.i("DgIntrospect", "Capture stopped. ${probeCounter.get()} probes. File: ${logFile?.absolutePath}")
    }

    fun log(tag: String, detail: String) {
        if (!enabled.get()) return
        val elapsedUs = (System.nanoTime() - startTimeNs) / 1000
        val n = probeCounter.incrementAndGet()
        val tid = Thread.currentThread().id
        // Format: elapsed_us|probe_number|thread_id|phase|tag|detail
        entries.add("$elapsedUs|$n|$tid|$currentPhase|$tag|$detail")
    }

    private fun flush() {
        val file = logFile ?: return
        try {
            PrintWriter(FileWriter(file, false)).use { pw ->
                pw.println("# DG Introspect Log — ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date())}")
                pw.println("# Format: elapsed_us|probe_num|tid|phase|tag|detail")
                pw.println("# Phases: INIT=before DG load, LOAD=during class load, CONSTRUCT=DG constructor, RUN=DG.run(), CLOSE=after")
                var entry = entries.poll()
                while (entry != null) {
                    pw.println(entry)
                    entry = entries.poll()
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("DgIntrospect", "Flush failed: ${e.message}")
        }
    }

    // --- Phase 2: JNI native hooks ---

    private var nativeLoaded = false

    /**
     * Load the native lib for nativeDlopenLazy() only — NO JNI table hooks.
     * Used when we need RTLD_LAZY loading but don't want introspection/spoofing hooks.
     */
    fun loadNativeLibOnly(context: Context): Boolean {
        if (nativeLoaded) return true
        val nativeDir = context.applicationInfo?.nativeLibraryDir
        val candidates = listOfNotNull(
            nativeDir?.let { File(it, "libgcore_jni.so") },
            File(context.filesDir, "libgcore_jni.so")
        )
        val libPath = candidates.firstOrNull { it.isFile }
        if (libPath == null) {
            android.util.Log.i("DgIntrospect", "loadNativeLibOnly: not found at ${candidates.map { it.absolutePath }}")
            return false
        }
        return try {
            System.load(libPath.absolutePath)
            nativeLoaded = true
            android.util.Log.i("DgIntrospect", "Native lib loaded (dlopen helper only, no hooks): ${libPath.absolutePath}")
            true
        } catch (e: Exception) {
            android.util.Log.w("DgIntrospect", "loadNativeLibOnly failed: ${e.message?.take(80)}")
            false
        }
    }

    /**
     * Try to load the JNI introspection library and hook the JNI function table.
     * The native library provides BOTH logging (when introspection enabled) AND
     * spoofing (always active — filters permissions, fixes versionName etc).
     * Returns true if hooks are active.
     */
    fun tryLoadNativeHooks(context: Context): Boolean {
        if (nativeLoaded) return true
        // Try two locations: system nativeLibraryDir (S217: stock-path, among other .so)
        // then legacy filesDir (for backward compat when manually pushed)
        val nativeDir = context.applicationInfo?.nativeLibraryDir
        val candidates = listOfNotNull(
            nativeDir?.let { File(it, "libgcore_jni.so") },
            File(context.filesDir, "libgcore_jni.so")
        )
        val libPath = candidates.firstOrNull { it.isFile }
        if (libPath == null) {
            android.util.Log.i("DgIntrospect", "No native lib at ${candidates.map { it.absolutePath }} — dlopen disabled")
            return false
        }
        try {
            System.load(libPath.absolutePath)
            nativeLoaded = true
            // Log file only created when introspection is enabled; spoofing is always active
            val logPath = if (enabled.get()) {
                File(context.filesDir, "dg_introspect_jni.log").absolutePath
            } else {
                "/dev/null"  // No logging, but hooks still active for spoofing
            }
            nativeHookJniTable(logPath)
            if (enabled.get()) {
                log("NATIVE", "hooked:$logPath")
            }
            android.util.Log.i("DgIntrospect", "JNI table hooks active (spoof=always, log=${if (enabled.get()) "on" else "off"})")
            return true
        } catch (e: Exception) {
            if (enabled.get()) log("NATIVE", "load_failed:${e.message}")
            android.util.Log.w("DgIntrospect", "Native hooks failed: ${e.message}")
            return false
        }
    }

    fun unhookNative() {
        if (!nativeLoaded) return
        try {
            nativeUnhookJniTable()
            log("NATIVE", "unhooked")
        } catch (e: Exception) {
            log("NATIVE", "unhook_failed:${e.message}")
        }
    }

    // JNI method declarations — implemented in libgcore_jni.so
    @JvmStatic private external fun nativeHookJniTable(logPath: String)
    @JvmStatic private external fun nativeUnhookJniTable()

    /**
     * dlopen a shared library with RTLD_LAZY | RTLD_LOCAL.
     * Unlike System.load() (RTLD_NOW), defers symbol resolution until first use.
     * Libraries loaded this way appear in dl_iterate_phdr() as r-xp segments.
     * Returns handle (non-zero on success, 0 on failure).
     */
    @JvmStatic external fun nativeDlopenLazy(path: String): Long

    /**
     * Unlink libgcore_jni.so from the dynamic linker's link_map chain.
     * After this, dl_iterate_phdr() will NOT enumerate our helper library.
     * DG calls dl_iterate_phdr 7 times per session — this hides us from ALL of them.
     * Code stays mapped in memory, JNI functions still callable.
     * Returns: 1=success, 0=not found, -1=error.
     */
    @JvmStatic external fun nativeHideSelfFromLinkMap(): Int

    /**
     * Hook dl_iterate_phdr GOT in all loaded libraries to filter out
     * libgcore_jni.so from the callback results. DG calls dl_iterate_phdr
     * 7 times per session — this makes our helper library invisible to ALL of them.
     *
     * Unlike link_map modification (which crashes on Android 12), this patches
     * function pointers in each library's GOT — safe, no linker internals touched.
     *
     * Call AFTER all stock .so are loaded and BEFORE DG VM runs.
     * Returns: number of libraries where GOT was successfully hooked.
     */
    @JvmStatic external fun nativeHookDlIteratePhdr(): Int

    /**
     * Load native lib + hide from dl_iterate_phdr via GOT hook.
     * Call after all stock libs have been dlopen'd.
     */
    fun loadAndHideNativeLib(context: Context): Boolean {
        if (!loadNativeLibOnly(context)) return false
        val hooked = try { nativeHookDlIteratePhdr() } catch (e: Exception) {
            android.util.Log.w("DgIntrospect", "hookDlIteratePhdr failed: ${e.message}")
            0
        }
        android.util.Log.i("DgIntrospect", "hookDlIteratePhdr: hooked GOT in $hooked libraries (libgcore_jni.so filtered)")
        return hooked > 0
    }

    // --- Instrumented Context wrapper ---

    /**
     * Wraps a Context to log all method calls DG might make.
     * SSTIC paper: DG collects current_class_loaders via context.getClassLoader().toString(),
     * and reads ApplicationInfo fields (sourceDir, nativeLibraryDir).
     */
    class InstrumentedContext(base: Context) : ContextWrapper(base) {
        override fun getClassLoader(): ClassLoader {
            val cl = super.getClassLoader()
            log("CTX_getClassLoader", cl.toString().take(120))
            return cl
        }

        override fun getPackageName(): String {
            val pkg = super.getPackageName()
            log("CTX_getPackageName", pkg)
            return pkg
        }

        override fun getApplicationInfo(): ApplicationInfo {
            val info = super.getApplicationInfo()
            log("CTX_getApplicationInfo", "src=${info.sourceDir} nativeLib=${info.nativeLibraryDir} dataDir=${info.dataDir}")
            return info
        }

        override fun getPackageManager(): PackageManager {
            val pm = super.getPackageManager()
            log("CTX_getPackageManager", "class=${pm.javaClass.name}")
            return pm
        }

        override fun getSystemService(name: String): Any? {
            val svc = super.getSystemService(name)
            log("CTX_getSystemService", "$name → ${svc?.javaClass?.name ?: "null"}")
            return svc
        }

        override fun getContentResolver(): android.content.ContentResolver {
            val cr = super.getContentResolver()
            log("CTX_getContentResolver", cr.javaClass.name)
            return cr
        }

        override fun getFilesDir(): File {
            val dir = super.getFilesDir()
            log("CTX_getFilesDir", dir.absolutePath)
            return dir
        }

        override fun getApplicationContext(): Context {
            val ctx = super.getApplicationContext()
            log("CTX_getApplicationContext", ctx.javaClass.name)
            return ctx
        }
    }

    // PM wrapping removed — too many abstract methods. JNI hooks capture PM calls at native level.
}
