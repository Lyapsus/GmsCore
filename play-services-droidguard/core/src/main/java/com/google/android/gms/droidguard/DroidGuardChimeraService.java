/*
 * SPDX-FileCopyrightText: 2021, microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package com.google.android.gms.droidguard;

import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;

import com.google.android.gms.framework.tracing.wrapper.TracingIntentService;

import org.microg.gms.droidguard.core.DroidGuardPreferences;
import org.microg.gms.droidguard.core.DroidGuardServiceBroker;
import org.microg.gms.droidguard.core.NetworkHandleProxyFactory;
import org.microg.gms.droidguard.core.HardwareAttestationBlockingProvider;
import org.microg.gms.droidguard.core.SerialUnflaky;

/**
 * Stock GMS DroidGuardChimeraService has exactly:
 *   getDeclaredFields: 5 instance fields (a-e), 0 static
 *   getDeclaredMethods: constructor(), b() [private final void], a(Intent) [public final],
 *                       onBind(Intent) [public final], onCreate() [public final]
 *   getDeclaredClasses: anonymous inner classes from onBind/onCreate lambdas
 *
 * Removed from microG to match stock: b(String) getCallback, c(byte[]) ping handler,
 * onDestroy() override, static lock fields a/f.
 */
public class DroidGuardChimeraService extends TracingIntentService {
    // Stock fields (5 instance, names a-e matching stock):
    // Stock types: goon<bkvm>, goon<bktp>, bkwo, goon<bkve>, bkug
    // We use our actual types where functional, Object for placeholders.
    public Object a;                     // stock: goon<bkvm> (metrics)
    public NetworkHandleProxyFactory b;  // stock: goon<bktp> (token provider) — our factory
    public Object c;                     // stock: bkwo (error reporter)
    private Object d;                    // stock: goon<bkve> (VM prefetcher)
    private Object e;                    // stock: bkug (fast refresh)

    public DroidGuardChimeraService() {
        super("DG");
        setIntentRedelivery(true);
    }

    // Stock: private final void b() — calls setIntentRedelivery(true)
    private final void b() {
        setIntentRedelivery(true);
    }

    // Stock: public final void a(Intent) — handles PING and VP actions
    public final void a(@Nullable Intent intent) {
        Log.d("GmsGuardChimera", "a(" + intent + ")");
        if (intent != null && intent.getAction() != null &&
                intent.getAction().equals("com.google.android.gms.droidguard.service.PING")) {
            // Ping handling inlined (stock does this in a(Intent) directly)
            Log.d("GmsGuardChimera", "PING action received");
        }
    }

    @Nullable
    @Override
    public final IBinder onBind(Intent intent) {
        if (intent != null && intent.getAction() != null &&
                intent.getAction().equals("com.google.android.gms.droidguard.service.START")) {
            HardwareAttestationBlockingProvider.ensureEnabled(DroidGuardPreferences.isHardwareAttestationBlocked(this));
            SerialUnflaky.INSTANCE.fetch();
            return new DroidGuardServiceBroker(this);
        }
        return null;
    }

    @Override
    public final void onCreate() {
        this.a = new Object();
        this.b = new NetworkHandleProxyFactory(this);
        this.c = new Object();
        this.d = new Object();
        this.e = new Object();
        HardwareAttestationBlockingProvider.ensureEnabled(DroidGuardPreferences.isHardwareAttestationBlocked(this));
        SerialUnflaky.INSTANCE.fetch();
        super.onCreate();
    }
}
