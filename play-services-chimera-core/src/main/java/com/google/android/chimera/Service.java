/*
 * SPDX-FileCopyrightText: 2021, microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package com.google.android.chimera;

import android.app.Application;
import android.app.Notification;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.IBinder;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * Stock GMS chimera.Service:
 *   getDeclaredFields: 1 (private ProxyCallbacks UT) + 9 static final int constants
 *   getDeclaredMethods: ~28 methods (constructor, getApplication, getContainerService,
 *     getContainerServiceClassName, getForegroundServiceType, getProxyCallbacks,
 *     onBind [abstract], onCreate, onDestroy, onStartCommand, onUnbind, publicDump,
 *     setProxyCallbacks, startForeground x2, stopForeground x2, stopSelf x2, stopSelfResult,
 *     getChimeraImpl, onLowMemory, onConfigurationChanged, onRebind, onTaskRemoved,
 *     onTrimMemory, onStart, dump)
 *   implements: yrv (getChimeraImpl), yru (setProxyCallbacks) — we use InstanceProvider
 */
public abstract class Service extends ContextWrapper implements InstanceProvider {
    public static final int START_CONTINUATION_MASK = 0xf;
    public static final int START_FLAG_REDELIVERY = 1;
    public static final int START_FLAG_RETRY = 2;
    public static final int START_NOT_STICKY = 2;
    public static final int START_REDELIVER_INTENT = 3;
    public static final int START_STICKY = 1;
    public static final int START_STICKY_COMPATIBILITY = 0;
    public static final int STOP_FOREGROUND_DETACH = 2;
    public static final int STOP_FOREGROUND_REMOVE = 1;

    // Stock: private ProxyCallbacks UT — single field
    private ProxyCallbacks UT;

    public interface ProxyCallbacks {
        android.app.Service getContainerService();
        String getContainerServiceClassName();
        Application superGetApplication();
        int superGetForegroundServiceType();
        void superOnCreate();
        void superOnDestroy();
        int superOnStartCommand(Intent intent, int flags, int startId);
        void superStartForeground(int id, Notification notification);
        void superStartForeground(int id, Notification notification, int foregroundServiceType);
        void superStopForeground(int flags);
        void superStopForeground(boolean removeNotification);
        void superStopSelf();
        void superStopSelf(int startId);
        boolean superStopSelfResult(int startId);
    }

    public Service() {
        super(null);
    }

    protected void dump(FileDescriptor fs, PrintWriter writer, String[] args) {
    }

    public final Application getApplication() {
        return UT.superGetApplication();
    }

    @Override
    public Object getChimeraImpl() {
        return this;
    }

    public android.app.Service getContainerService() {
        return UT.getContainerService();
    }

    public String getContainerServiceClassName() {
        return UT.getContainerServiceClassName();
    }

    public final int getForegroundServiceType() {
        return UT.superGetForegroundServiceType();
    }

    public ProxyCallbacks getProxyCallbacks() {
        return UT;
    }

    public abstract IBinder onBind(Intent intent);

    public void onConfigurationChanged(Configuration configuration) {
    }

    public void onCreate() {
        this.UT.superOnCreate();
    }

    public void onDestroy() {
        this.UT.superOnDestroy();
    }

    public void onLowMemory() {
    }

    public void onRebind(Intent intent) {
    }

    @Deprecated
    public void onStart(Intent intent, int startId) {
    }

    public int onStartCommand(Intent intent, int flags, int startId) {
        return this.UT.superOnStartCommand(intent, flags, startId);
    }

    public void onTaskRemoved(Intent rootIntent) {
    }

    public void onTrimMemory(int level) {
    }

    public boolean onUnbind(Intent intent) {
        return false;
    }

    public void publicDump(FileDescriptor fd, PrintWriter writer, String[] args) {
        dump(fd, writer, args);
    }

    public void setProxyCallbacks(ProxyCallbacks proxyCallbacks, Context context) {
        this.UT = proxyCallbacks;
        attachBaseContext(context);
    }

    public final void startForeground(int id, Notification notification) {
        UT.superStartForeground(id, notification);
    }

    public final void startForeground(int id, Notification notification, int foregroundServiceType) {
        UT.superStartForeground(id, notification, foregroundServiceType);
    }

    public final void stopForeground(int flags) {
        UT.superStopForeground(flags);
    }

    @Deprecated
    public final void stopForeground(boolean removeNotification) {
        UT.superStopForeground(removeNotification);
    }

    public final void stopSelf() {
        this.UT.superStopSelf();
    }

    public final void stopSelf(int startId) {
        this.UT.superStopSelf(startId);
    }

    public final boolean stopSelfResult(int startId) {
        return this.UT.superStopSelfResult(startId);
    }
}
