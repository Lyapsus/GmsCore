/*
 * SPDX-FileCopyrightText: 2021, microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.droidguard;

import android.content.Context;
import android.media.MediaDrm;
import android.os.Bundle;
import android.os.Parcelable;
import android.os.UserManager;
import android.util.Log;

import org.microg.gms.settings.SettingsContract;

import java.util.ArrayList;

import static android.os.Build.VERSION.SDK_INT;

/**
 * Callbacks invoked from the DroidGuard VM via JNI (GetMethodID).
 * <p>
 * We keep this file in Java to ensure ABI compatibility.
 * Methods are invoked by name from within the VM and thus must keep current name.
 * <p>
 * Methods a-h match stock GMS's RuntimeApi (com.google.android.gms.droidguard.loader.RuntimeApi).
 */
public class GuardCallback {
    private static final String TAG = "GmsGuardCallback";
    private final Context context;
    private final String packageName;

    public GuardCallback(Context context, String packageName) {
        this.context = context;
        this.packageName = packageName;
    }

    /**
     * Stock GMS: @Deprecated, returns "".
     */
    public final String a(final byte[] array) {
        return "";
    }

    // getAndroidId (deprecated getter)
    public final String b() {
        return getAndroidIdString();
    }

    // getPackageName
    public final String c() {
        return packageName;
    }

    // closeMediaDrmSession
    public final void d(final Object mediaDrm, final byte[] sessionId) {
        synchronized (MediaDrmLock.LOCK) {
            if (SDK_INT >= 18) {
                try {
                    ((MediaDrm) mediaDrm).closeSession(sessionId);
                } catch (Exception e) {
                    Log.w(TAG, "closeMediaDrmSession failed", e);
                }
            }
        }
    }

    /**
     * Stock GMS: @Deprecated, no-op.
     */
    public final void e(final int task) {
    }

    /**
     * Android ID (modern getter). Returns 0 in direct boot mode (before first unlock).
     */
    public final String f() {
        if (SDK_INT >= 24) {
            try {
                UserManager um = (UserManager) context.getSystemService(Context.USER_SERVICE);
                if (um != null && !um.isUserUnlocked()) {
                    return "0";
                }
            } catch (Throwable t) {
                Log.w(TAG, "isUserUnlocked check failed", t);
            }
        }
        return getAndroidIdString();
    }

    /**
     * Play Protect isEnabled check. Stock GMS queries SafetyNet/Play Protect API.
     */
    public final Bundle g(final long timeoutMs) {
        Bundle bundle = new Bundle();
        bundle.putBoolean("ie", true);
        bundle.putString("e", null);
        return bundle;
    }

    /**
     * Harmful apps scan data. Stock GMS queries Play Protect harmful apps API.
     */
    public final Bundle h(final long timeoutMs, final int maxApps) {
        Bundle bundle = new Bundle();
        bundle.putLong("lst", System.currentTimeMillis());
        bundle.putInt("hls", -1);
        bundle.putInt("hac", 0);
        bundle.putParcelableArrayList("ha", new ArrayList<Parcelable>());
        bundle.putString("e", null);
        return bundle;
    }

    private String getAndroidIdString() {
        try {
            long androidId = SettingsContract.INSTANCE.getSettings(context,
                    SettingsContract.CheckIn.INSTANCE.getContentUri(context),
                    new String[]{SettingsContract.CheckIn.ANDROID_ID},
                    cursor -> cursor.getLong(0));
            return String.valueOf(androidId);
        } catch (Throwable e) {
            Log.w(TAG, "getAndroidIdString failed", e);
        }
        return "0";
    }
}
