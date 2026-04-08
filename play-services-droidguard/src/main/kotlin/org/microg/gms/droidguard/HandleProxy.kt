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
            // DO NOT wrap in DgSpoofContext — DG calls getClass() on the context and walks
            // the superclass chain. DgSpoofContext(ContextWrapper) exposes "org.microg.gms.droidguard"
            // in the class name, instantly identifying microG. Pass the raw service context so DG
            // sees: DroidGuardChimeraService → TracingIntentService → chimera.Service → ContextWrapper.
            // PM spoofing is process-level (prespoofProcessInfo + patchPmDirect in HandleProxyFactory).
            var ctx: Context = context
            if (DgIntrospect.isEnabled(context)) ctx = DgIntrospect.InstrumentedContext(ctx)
            clazz.getDeclaredConstructor(Context::class.java, Parcelable::class.java).newInstance(ctx, data)
        }.getOrElse {
            throw BytesException(ByteArray(0), it)
        },
        vmKey
    )

    constructor(clazz: Class<*>, context: Context, flow: String?, byteCode: ByteArray, callback: Any, vmKey: String, extra: ByteArray, bundle: Bundle?) : this(
        kotlin.runCatching {
            DgIntrospect.markPhase("CONSTRUCT")
            // Same as above: pass raw context, not DgSpoofContext wrapper.
            var ctx: Context = context
            if (DgIntrospect.isEnabled(context)) ctx = DgIntrospect.InstrumentedContext(ctx)
            clazz.getDeclaredConstructor(Context::class.java, String::class.java, ByteArray::class.java, Object::class.java, Bundle::class.java).newInstance(ctx, flow, byteCode, callback, bundle)
        }.getOrElse {
            throw BytesException(extra, it)
        }, vmKey, extra)

    fun run(data: Map<Any, Any>): ByteArray {
        try {
            DgIntrospect.markPhase("RUN")
            val result = handle.javaClass.getDeclaredMethod("run", Map::class.java).invoke(handle, data) as ByteArray
            DgIntrospect.markPhase("RUN_DONE")
            DgIntrospect.log("RUN_RESULT", "size=${result.size}")
            return result
        } catch (e: Exception) {
            DgIntrospect.log("RUN_ERROR", e.message ?: "unknown")
            throw BytesException(extra, e)
        }
    }

    fun init(): Boolean {
        try {
            DgIntrospect.markPhase("DG_INIT")
            val result = handle.javaClass.getDeclaredMethod("init").invoke(handle) as Boolean
            DgIntrospect.log("DG_INIT_RESULT", "$result")
            return result
        } catch (e: Exception) {
            DgIntrospect.log("DG_INIT_ERROR", e.message ?: "unknown")
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
            DgIntrospect.unhookNative()
            DgIntrospect.stopCapture()
            throw BytesException(extra, e)
        }
    }

}
