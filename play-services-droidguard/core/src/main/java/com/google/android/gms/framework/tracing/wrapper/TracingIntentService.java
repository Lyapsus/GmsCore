/*
 * SPDX-FileCopyrightText: 2021, microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package com.google.android.gms.framework.tracing.wrapper;

import android.content.Context;
import android.content.Intent;

import androidx.annotation.Nullable;

import com.google.android.chimera.IntentService;

/**
 * Stock GMS: extends IntentService, has private bvho a (tracer), methods are final.
 * getDeclaredMethods: constructor, a(Intent) [abstract], attachBaseContext [protected final], onHandleIntent [public final]
 * getDeclaredFields: one private field (tracer)
 * getDeclaredClasses: empty
 * No getPackageManager override — PM spoofing is process-level via ActivityThread.sPackageManager.
 */
public abstract class TracingIntentService extends IntentService {
    // Match stock field: private bvho a (tracer). We use Object since bvho is obfuscated.
    private Object a;

    public TracingIntentService(String name) {
        super(name);
        this.a = null;
    }

    protected abstract void a(@Nullable Intent intent);

    @Override
    protected final void attachBaseContext(Context newBase) {
        super.attachBaseContext(newBase);
    }

    @Override
    public final void onHandleIntent(@Nullable Intent intent) {
        this.a(intent);
    }
}
