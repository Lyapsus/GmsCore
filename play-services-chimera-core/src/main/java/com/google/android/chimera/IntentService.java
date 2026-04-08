/*
 * SPDX-FileCopyrightText: 2021, microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package com.google.android.chimera;

import android.content.Intent;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;

/**
 * Stock GMS IntentService:
 *   getDeclaredFields: 4 (private volatile Looper UU, private volatile ylf b, private final String c, private boolean d)
 *   getDeclaredClasses: empty (handler is external class ylf)
 *   getDeclaredMethods: constructor(String), onBind, onCreate, onDestroy, onHandleIntent [abstract],
 *                       onStart, onStartCommand, setIntentRedelivery
 */
public abstract class IntentService extends Service {
    private volatile Looper UU;
    private volatile IntentServiceHandler b;
    private final String c;
    private boolean d;

    public IntentService(String str) {
        this.c = str;
    }

    public void setIntentRedelivery(boolean z) {
        this.d = z;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        HandlerThread thread = new HandlerThread("IntentService[" + c + "]");
        thread.start();
        UU = thread.getLooper();
        b = new IntentServiceHandler(this, UU);
    }

    @Override
    public void onStart(Intent intent, int startId) {
        Message msg = this.b.obtainMessage();
        msg.arg1 = startId;
        msg.obj = intent;
        this.b.sendMessage(msg);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        onStart(intent, startId);
        return d ? START_REDELIVER_INTENT : START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        UU.quit();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public abstract void onHandleIntent(Intent intent);
}
