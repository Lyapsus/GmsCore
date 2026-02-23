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

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static android.os.Build.VERSION.SDK_INT;

/**
 * Callbacks invoked from the DroidGuard VM via JNI (GetMethodID).
 * <p>
 * Methods a-h must match stock GMS's RuntimeApi (com.google.android.gms.droidguard.loader.RuntimeApi)
 * exactly in name, parameter types, and return types. The VM looks up methods by reflection.
 * <p>
 * Stock GMS RuntimeApi (v26.02.33) has 8 public methods:
 *   a(byte[])→String    — @Deprecated, returns ""
 *   b()→String           — Android ID (deprecated getter, belp.d)
 *   c()→String           — package name
 *   d(Object,byte[])→void — @Deprecated, no-op
 *   e(int)→void          — @Deprecated, no-op
 *   f()→String           — Android ID (modern getter, belp.e)
 *   g(long)→Bundle       — Play Protect isEnabled
 *   h(long,int)→Bundle   — harmful apps scan data
 * <p>
 * Methods i-n are speculative traps: they do NOT exist in stock GMS v26.02.33 but are
 * present here to catch any future VM bytecode that tries to call them. If any of these
 * fire, it means the VM expects methods we hadn't mapped.
 */
public class GuardCallback {
    private static final String TAG = "GmsGuardCallback";
    private final Context context;
    private final String packageName;
    private final long constructedAt;

    // Global call sequence — shows exact interleaving of all method calls from the VM.
    private final AtomicInteger callSeq = new AtomicInteger(0);

    // Per-method hit counters for session summary.
    private int hits_a, hits_b, hits_c, hits_d, hits_e, hits_f, hits_g, hits_h;
    private int hits_speculative; // any i-n call

    public GuardCallback(Context context, String packageName) {
        this.context = context;
        this.packageName = packageName;
        this.constructedAt = System.currentTimeMillis();
        try {
            String thread = Thread.currentThread().getName() + "/" + Thread.currentThread().getId();
            Log.i(TAG, "CREATED pkg=" + packageName + " thread=" + thread);
            // Self-introspection: log all declared methods so we can verify the VM sees them.
            StringBuilder sb = new StringBuilder();
            for (Method m : getClass().getDeclaredMethods()) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(m.getName());
                sb.append("(");
                Class<?>[] params = m.getParameterTypes();
                for (int i = 0; i < params.length; i++) {
                    if (i > 0) sb.append(",");
                    sb.append(params[i].getSimpleName());
                }
                sb.append(")→");
                sb.append(m.getReturnType().getSimpleName());
            }
            Log.i(TAG, "declared methods: [" + sb + "]");
        } catch (Throwable t) {
            Log.w(TAG, "constructor logging failed", t);
        }
    }

    private String callPrefix(String method) {
        int seq = callSeq.incrementAndGet();
        String thread = Thread.currentThread().getName();
        long elapsed = System.currentTimeMillis() - constructedAt;
        return "#" + seq + " @" + elapsed + "ms [" + thread + "] " + method;
    }

    /**
     * Stock GMS: @Deprecated, returns "".
     * Previously microG called FallbackCreator here — WRONG, stock returns empty string.
     */
    public final String a(final byte[] array) {
        hits_a++;
        Log.d(TAG, callPrefix("a[deprecated]") + "(" + (array != null ? array.length + " bytes" : "null") + ") → \"\"");
        return "";
    }

    /**
     * Android ID (deprecated getter). Stock GMS calls belp.d(context).
     */
    public final String b() {
        hits_b++;
        String result = getAndroidIdString();
        Log.d(TAG, callPrefix("b[getAndroidId]") + "() = " + result);
        return result;
    }

    /**
     * Package name. Stock GMS returns constructor's 2nd param.
     */
    public final String c() {
        hits_c++;
        Log.d(TAG, callPrefix("c[getPackageName]") + "() = " + packageName);
        return packageName;
    }

    /**
     * Stock GMS: @Deprecated, no-op. microG closes MediaDrm session.
     * Keeping MediaDrm close — harmless if VM calls it, and correct if session needs cleanup.
     */
    public final void d(final Object mediaDrm, final byte[] sessionId) {
        hits_d++;
        Log.d(TAG, callPrefix("d[closeMediaDrmSession]") + "(" + mediaDrm + ", " + (sessionId != null ? sessionId.length + " bytes" : "null") + ")");
        synchronized (MediaDrmLock.LOCK) {
            if (SDK_INT >= 18) {
                try {
                    ((MediaDrm) mediaDrm).closeSession(sessionId);
                } catch (Exception e) {
                    Log.w(TAG, "d[closeMediaDrmSession] failed", e);
                }
            }
        }
    }

    /**
     * Stock GMS: @Deprecated, no-op.
     */
    public final void e(final int task) {
        hits_e++;
        Log.d(TAG, callPrefix("e[deprecated]") + "(" + task + ")");
    }

    /**
     * Android ID (modern getter). Stock GMS calls belp.e(context).
     * belp.e() returns 0L in direct boot mode (before first unlock), otherwise same as belp.d().
     */
    public final String f() {
        hits_f++;
        String result;
        if (SDK_INT >= 24) {
            try {
                UserManager um = (UserManager) context.getSystemService(Context.USER_SERVICE);
                if (um != null && !um.isUserUnlocked()) {
                    result = "0";
                    Log.d(TAG, callPrefix("f[getAndroidIdModern]") + "() = 0 (direct boot mode)");
                    return result;
                }
            } catch (Throwable t) {
                Log.w(TAG, "f: isUserUnlocked check failed", t);
            }
        }
        result = getAndroidIdString();
        Log.d(TAG, callPrefix("f[getAndroidIdModern]") + "() = " + result);
        return result;
    }

    /**
     * Play Protect isEnabled check.
     * Stock GMS calls i().bF() which queries SafetyNet/Play Protect API.
     * Returns Bundle: "ie" (boolean isEnabled), "e" (String error or null).
     */
    public final Bundle g(final long timeoutMs) {
        hits_g++;
        Log.d(TAG, callPrefix("g[playProtectIsEnabled]") + "(timeout=" + timeoutMs + "ms)");
        Bundle bundle = new Bundle();
        bundle.putBoolean("ie", true);
        bundle.putString("e", null);
        Log.d(TAG, callPrefix("g") + " → ie=true, e=null");
        return bundle;
    }

    /**
     * Harmful apps scan data.
     * Stock GMS calls i().bG() which queries Play Protect harmful apps API.
     * Returns Bundle: "lst" (long lastScanTime), "hls" (int harmfulAppsListStatus, -1 if null),
     *   "hac" (int harmfulAppsCount), "ha" (ArrayList harmful apps), "e" (String error or null).
     */
    public final Bundle h(final long timeoutMs, final int maxApps) {
        hits_h++;
        Log.d(TAG, callPrefix("h[harmfulAppsData]") + "(timeout=" + timeoutMs + "ms, maxApps=" + maxApps + ")");
        Bundle bundle = new Bundle();
        bundle.putLong("lst", System.currentTimeMillis());
        bundle.putInt("hls", -1);
        bundle.putInt("hac", 0);
        bundle.putParcelableArrayList("ha", new ArrayList<Parcelable>());
        bundle.putString("e", null);
        Log.d(TAG, callPrefix("h") + " → lst=" + bundle.getLong("lst") + ", hls=-1, hac=0, e=null");
        return bundle;
    }

    // ── Speculative trap methods (i-n) ──────────────────────────────
    // These do NOT exist in stock GMS v26.02.33 RuntimeApi.
    // If any fire, the VM has methods we haven't mapped yet.
    // Covering common JNI return types: String, Bundle, int, boolean, void, byte[].

    public final String i() {
        hits_speculative++;
        Log.w(TAG, callPrefix("i[SPECULATIVE]") + "() — UNEXPECTED CALL, not in stock RuntimeApi!");
        return "";
    }

    public final String i(long arg0) {
        hits_speculative++;
        Log.w(TAG, callPrefix("i[SPECULATIVE]") + "(long=" + arg0 + ") — UNEXPECTED CALL!");
        return "";
    }

    public final Bundle j(long arg0) {
        hits_speculative++;
        Log.w(TAG, callPrefix("j[SPECULATIVE]") + "(long=" + arg0 + ") — UNEXPECTED CALL!");
        return new Bundle();
    }

    public final int k() {
        hits_speculative++;
        Log.w(TAG, callPrefix("k[SPECULATIVE]") + "() — UNEXPECTED CALL!");
        return 0;
    }

    public final boolean l() {
        hits_speculative++;
        Log.w(TAG, callPrefix("l[SPECULATIVE]") + "() — UNEXPECTED CALL!");
        return false;
    }

    public final void m(int arg0) {
        hits_speculative++;
        Log.w(TAG, callPrefix("m[SPECULATIVE]") + "(int=" + arg0 + ") — UNEXPECTED CALL!");
    }

    public final byte[] n(byte[] arg0) {
        hits_speculative++;
        Log.w(TAG, callPrefix("n[SPECULATIVE]") + "(" + (arg0 != null ? arg0.length + " bytes" : "null") + ") — UNEXPECTED CALL!");
        return new byte[0];
    }

    // ── Lifecycle ───────────────────────────────────────────────────

    /** Log session summary showing exactly which methods were/weren't called. */
    public void logSessionSummary(String reason) {
        try {
            long duration = System.currentTimeMillis() - constructedAt;
            int total = hits_a + hits_b + hits_c + hits_d + hits_e + hits_f + hits_g + hits_h + hits_speculative;
            Log.i(TAG, "SESSION SUMMARY (" + reason + ") duration=" + duration + "ms totalCalls=" + total
                    + " | a=" + hits_a + " b=" + hits_b + " c=" + hits_c + " d=" + hits_d
                    + " e=" + hits_e + " f=" + hits_f + " g=" + hits_g + " h=" + hits_h
                    + " speculative=" + hits_speculative);
            // Flag methods that were NEVER called — key diagnostic for what the VM actually needs.
            StringBuilder never = new StringBuilder();
            if (hits_a == 0) never.append("a ");
            if (hits_b == 0) never.append("b ");
            if (hits_c == 0) never.append("c ");
            if (hits_d == 0) never.append("d ");
            if (hits_e == 0) never.append("e ");
            if (hits_f == 0) never.append("f ");
            if (hits_g == 0) never.append("g ");
            if (hits_h == 0) never.append("h ");
            if (never.length() > 0) {
                Log.i(TAG, "NEVER CALLED: " + never.toString().trim());
            }
        } catch (Throwable t) {
            Log.w(TAG, "logSessionSummary failed", t);
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            logSessionSummary("finalize/GC");
        } finally {
            super.finalize();
        }
    }

    // ── Internal ────────────────────────────────────────────────────

    private String getAndroidIdString() {
        // Check for override first (e.g., stock GMS checkin ID preserved during swap)
        // Set via: adb shell settings put global microg_dg_android_id_override <stock_checkin_id>
        // Or from constellation_prefs.xml stock_checkin_android_id key
        try {
            String override = android.provider.Settings.Global.getString(
                    context.getContentResolver(), "microg_dg_android_id_override");
            if (override != null && !override.isEmpty()) {
                Log.d(TAG, "getAndroidIdString() using override: " + override);
                return override;
            }
        } catch (Throwable e) {
            Log.w(TAG, "getAndroidIdString() override check failed", e);
        }

        // Also check constellation_prefs for stock checkin ID (auto-preserved during swap)
        try {
            android.content.SharedPreferences prefs = context.getSharedPreferences(
                    "constellation_prefs", Context.MODE_PRIVATE);
            String stockId = prefs.getString("stock_checkin_android_id", null);
            if (stockId != null && !stockId.isEmpty()) {
                Log.d(TAG, "getAndroidIdString() using stock ID from constellation_prefs: " + stockId);
                return stockId;
            }
        } catch (Throwable e) {
            Log.w(TAG, "getAndroidIdString() constellation_prefs check failed", e);
        }

        // Fall back to microG's own checkin ID
        try {
            long androidId = SettingsContract.INSTANCE.getSettings(context,
                    SettingsContract.CheckIn.INSTANCE.getContentUri(context),
                    new String[]{SettingsContract.CheckIn.ANDROID_ID},
                    cursor -> cursor.getLong(0));
            return String.valueOf(androidId);
        } catch (Throwable e) {
            Log.w(TAG, "getAndroidIdString() failed", e);
        }
        return "0";
    }
}
