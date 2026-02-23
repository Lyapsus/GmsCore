/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.asterism;

import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

/**
 * Asterism Service Implementation - handles consent API calls.
 *
 * This is a minimal implementation that returns success for consent operations.
 * The actual consent state is managed locally by Messages; we just need to
 * acknowledge the sync request.
 *
 * Based on decompiled GMS analysis:
 * - setConsent: Records ToS consent state
 * - getConsent: Returns current consent state
 */
public class AsterismServiceImpl extends Binder {
    private static final String TAG = "GmsAsterismSvcImpl";

    // Interface descriptor expected by clients (e.g. Google Messages).
    // If this does not match, GmsClient aborts with "service descriptor mismatch".
    private static final String DESCRIPTOR = "com.google.android.gms.asterism.internal.IAsterismApiService";

    // Transaction codes (estimated from typical AIDL patterns)
    private static final int TRANSACTION_setConsent = 1;
    private static final int TRANSACTION_getConsent = 2;
    private static final int TRANSACTION_sync = 3;

    private final Context context;

    public AsterismServiceImpl(Context context) {
        this.context = context;
        Log.i(TAG, "AsterismServiceImpl created - Consent management service");
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        Log.d(TAG, "onTransact code=" + code);

        // Handle interface descriptor query
        if (code == INTERFACE_TRANSACTION) {
            reply.writeString(DESCRIPTOR);
            return true;
        }

        // Verify interface descriptor
        data.enforceInterface(DESCRIPTOR);

        switch (code) {
            case TRANSACTION_setConsent:
                return handleSetConsent(data, reply);
            case TRANSACTION_getConsent:
                return handleGetConsent(data, reply);
            case TRANSACTION_sync:
                return handleSync(data, reply);
            default:
                // For unknown transactions, try to return success
                Log.w(TAG, "Unknown transaction code: " + code + ", returning success");
                return handleGenericSuccess(data, reply);
        }
    }

    /**
     * Handle setConsent - just acknowledge success.
     * Messages will store consent locally; we just confirm the server sync.
     */
    private boolean handleSetConsent(Parcel data, Parcel reply) {
        try {
            // Read callback binder
            IBinder callback = data.readStrongBinder();
            // Read consent value (int)
            int consent = data.readInt();
            // Read other params that might be present
            Log.i(TAG, "setConsent: consent=" + consent);

            // Call back success
            if (callback != null) {
                Parcel callbackData = Parcel.obtain();
                Parcel callbackReply = Parcel.obtain();
                try {
                    callbackData.writeInterfaceToken("com.google.android.gms.asterism.internal.IAsterismCallbacks");
                    // Transaction code 1 = onSuccess typically
                    callback.transact(1, callbackData, callbackReply, FLAG_ONEWAY);
                } finally {
                    callbackData.recycle();
                    callbackReply.recycle();
                }
            }

            reply.writeNoException();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error in setConsent", e);
            reply.writeException(e);
            return true;
        }
    }

    /**
     * Handle getConsent - return consented state (1).
     */
    private boolean handleGetConsent(Parcel data, Parcel reply) {
        try {
            IBinder callback = data.readStrongBinder();
            Log.i(TAG, "getConsent called");

            if (callback != null) {
                Parcel callbackData = Parcel.obtain();
                Parcel callbackReply = Parcel.obtain();
                try {
                    callbackData.writeInterfaceToken("com.google.android.gms.asterism.internal.IAsterismCallbacks");
                    // Write consent state = 1 (consented)
                    callbackData.writeInt(1);
                    callback.transact(1, callbackData, callbackReply, FLAG_ONEWAY);
                } finally {
                    callbackData.recycle();
                    callbackReply.recycle();
                }
            }

            reply.writeNoException();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error in getConsent", e);
            reply.writeException(e);
            return true;
        }
    }

    /**
     * Handle sync - just acknowledge success.
     */
    private boolean handleSync(Parcel data, Parcel reply) {
        try {
            IBinder callback = data.readStrongBinder();
            Log.i(TAG, "sync called");

            if (callback != null) {
                Parcel callbackData = Parcel.obtain();
                Parcel callbackReply = Parcel.obtain();
                try {
                    callbackData.writeInterfaceToken("com.google.android.gms.asterism.internal.IAsterismCallbacks");
                    callback.transact(1, callbackData, callbackReply, FLAG_ONEWAY);
                } finally {
                    callbackData.recycle();
                    callbackReply.recycle();
                }
            }

            reply.writeNoException();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error in sync", e);
            reply.writeException(e);
            return true;
        }
    }

    /**
     * Generic success handler for unknown transactions.
     */
    private boolean handleGenericSuccess(Parcel data, Parcel reply) {
        try {
            // Try to read callback if present
            IBinder callback = null;
            try {
                callback = data.readStrongBinder();
            } catch (Exception e) {
                // Ignore if no callback
            }

            if (callback != null) {
                Parcel callbackData = Parcel.obtain();
                Parcel callbackReply = Parcel.obtain();
                try {
                    callbackData.writeInterfaceToken("com.google.android.gms.asterism.internal.IAsterismCallbacks");
                    callback.transact(1, callbackData, callbackReply, FLAG_ONEWAY);
                } finally {
                    callbackData.recycle();
                    callbackReply.recycle();
                }
            }

            reply.writeNoException();
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Error in generic handler", e);
            reply.writeNoException();
            return true;
        }
    }

    public IBinder asBinder() {
        return this;
    }
}
