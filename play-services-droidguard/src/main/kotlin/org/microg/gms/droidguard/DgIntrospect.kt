/*
 * DroidGuard Introspection - captures what DG probes during VM execution.
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
 *   DgIntrospect.startCapture(context)  - before DG class loading
 *   DgIntrospect.markPhase("RUN")       - before HandleProxy.run()
 *   DgIntrospect.stopCapture()          - after HandleProxy.close()
 *   Log file: /data/data/com.google.android.gms/files/dg_introspect.log
 *
 * Enable:  adb shell "su -c 'touch /data/data/com.google.android.gms/files/dg_introspect_enable'"
 * Disable: adb shell "su -c 'rm /data/data/com.google.android.gms/files/dg_introspect_enable'"
 * Read:    adb shell "su -c 'cat /data/data/com.google.android.gms/files/dg_introspect.log'"
 */

package org.microg.gms.droidguard

import android.content.Context
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
     * Zero overhead when disabled - just a File.exists() check.
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
                pw.println("# DG Introspect Log - ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date())}")
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

}
