/*
 * SPDX-FileCopyrightText: 2021, microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package com.google.android.chimera;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;

/**
 * Stock GMS has this as top-level obfuscated class `ylf extends Handler`.
 * Must be separate from IntentService so getDeclaredClasses() returns empty.
 */
final class IntentServiceHandler extends Handler {
    final IntentService a;

    public IntentServiceHandler(IntentService service, Looper looper) {
        super(looper);
        this.a = service;
    }

    @Override
    public final void handleMessage(Message msg) {
        a.onHandleIntent((Intent) msg.obj);
        a.stopSelf(msg.arg1);
    }
}
