/*
 * SPDX-FileCopyrightText: 2022 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.droidguard

import android.content.Context
import android.os.Bundle
import android.os.Parcelable

class HandleProxy(val handle: Any, val vmKey: String, val extra: ByteArray = ByteArray(0)) {
    constructor(clazz: Class<*>, context: Context, vmKey: String, data: Parcelable) : this(
        kotlin.runCatching {
            DgIntrospect.markPhase("CONSTRUCT")
            android.util.Log.d("DroidGuard", "HandleProxy(vmKey=$vmKey): constructing via (Context, Parcelable)")
            var ctx: Context = context
            if (DgIntrospect.isEnabled(context)) ctx = DgIntrospect.InstrumentedContext(ctx)
            clazz.getDeclaredConstructor(Context::class.java, Parcelable::class.java).newInstance(ctx, data)
        }.getOrElse {
            android.util.Log.e("DroidGuard", "HandleProxy(vmKey=$vmKey): DG VM init FAILED: ${it.javaClass.simpleName}: ${it.message}")
            throw BytesException(ByteArray(0), it)
        },
        vmKey
    )

    constructor(clazz: Class<*>, context: Context, flow: String?, byteCode: ByteArray, callback: Any, vmKey: String, extra: ByteArray, bundle: Bundle?) : this(
        kotlin.runCatching {
            DgIntrospect.markPhase("CONSTRUCT")
            android.util.Log.d("DroidGuard", "HandleProxy(flow=$flow, vmKey=${vmKey.take(12)}...): constructing via (Context, String, ByteArray, Object, Bundle)")
            var ctx: Context = context
            if (DgIntrospect.isEnabled(context)) ctx = DgIntrospect.InstrumentedContext(ctx)
            clazz.getDeclaredConstructor(Context::class.java, String::class.java, ByteArray::class.java, Object::class.java, Bundle::class.java).newInstance(ctx, flow, byteCode, callback, bundle)
        }.getOrElse {
            android.util.Log.e("DroidGuard", "HandleProxy(flow=$flow, vmKey=${vmKey.take(12)}...): DG VM init FAILED: ${it.javaClass.simpleName}: ${it.message}")
            throw BytesException(extra, it)
        }, vmKey, extra)

    fun run(data: Map<Any, Any>): ByteArray {
        try {
            DgIntrospect.markPhase("RUN")
            val result = handle.javaClass.getDeclaredMethod("run", Map::class.java).invoke(handle, data) as ByteArray
            DgIntrospect.markPhase("RUN_DONE")
            DgIntrospect.log("RUN_RESULT", "size=${result.size}")
            android.util.Log.i("DroidGuard", "DG VM run(vmKey=${vmKey.take(12)}...): returned ${result.size} bytes")
            return result
        } catch (e: Exception) {
            DgIntrospect.log("RUN_ERROR", e.message ?: "unknown")
            android.util.Log.e("DroidGuard", "DG VM run(vmKey=${vmKey.take(12)}...): FAILED: ${e.javaClass.simpleName}: ${e.message}")
            throw BytesException(extra, e)
        }
    }

    fun init(): Boolean {
        try {
            DgIntrospect.markPhase("DG_INIT")
            val result = handle.javaClass.getDeclaredMethod("init").invoke(handle) as Boolean
            DgIntrospect.log("DG_INIT_RESULT", "$result")
            android.util.Log.i("DroidGuard", "DG VM init(vmKey=${vmKey.take(12)}...): result=$result")
            return result
        } catch (e: Exception) {
            DgIntrospect.log("DG_INIT_ERROR", e.message ?: "unknown")
            android.util.Log.e("DroidGuard", "DG VM init(vmKey=${vmKey.take(12)}...): FAILED: ${e.javaClass.simpleName}: ${e.message}")
            throw BytesException(extra, e)
        }
    }

    fun close() {
        try {
            DgIntrospect.markPhase("CLOSE")
            handle.javaClass.getDeclaredMethod("close").invoke(handle)
            // Stop introspection and flush all captured data
            DgIntrospect.unhookNative()
            DgIntrospect.stopCapture()
        } catch (e: Exception) {
            android.util.Log.w("DroidGuard", "DG VM close(vmKey=${vmKey.take(12)}...): error: ${e.message}")
            DgIntrospect.unhookNative()
            DgIntrospect.stopCapture()
            throw BytesException(extra, e)
        }
    }

}
