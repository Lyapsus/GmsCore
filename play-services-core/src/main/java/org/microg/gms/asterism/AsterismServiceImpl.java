/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.asterism;

import android.content.Context;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import org.microg.gms.constellation.GoogleConstellationClient;

/**
 * Asterism Service Implementation - handles consent + IID token API calls.
 *
 * Stock GMS flow (adsd.java): Messages calls getAsterismConsent → GMS does
 * GetConsent gRPC → returns consent state + IID token + gaia token.
 * Messages stores the IID token and later uses it as gmscore_instance_id_token
 * HTTP header in the ACS POST request.
 *
 * AIDL interface: IAsterismApiService
 *   tx 1 = getAsterismConsent(cb, GetAsterismConsentRequest, ApiMetadata)
 *   tx 2 = setAsterismConsent(cb, SetAsterismConsentRequest, ApiMetadata)
 *   tx 3 = getIsPnvrConstellationDevice(cb, ApiMetadata)
 *
 * Callback interface: IAsterismCallbacks (oneway)
 *   tx 1 = onConsentFetched(Status, GetAsterismConsentResponse, ApiMetadata)
 *   tx 2 = onConsentRegistered(Status, SetAsterismConsentResponse, ApiMetadata)
 *   tx 3 = onIsPnvrConstellationDevice(Status, boolean, ApiMetadata)
 *
 * GetAsterismConsentResponse SafeParcelable:
 *   field 1 (a) = int requestCode
 *   field 2 (b) = int consentState (0=unknown, 1=CONSENTED, 2=NO_CONSENT)
 *   field 3 (c) = String iidToken  ← CRITICAL for gmscore_instance_id_token header
 *   field 4 (d) = String gaiaToken
 *   field 5 (e) = int consentVersion
 */
public class AsterismServiceImpl extends Binder {
    private static final String TAG = "GmsAsterismSvcImpl";
    private static final String DESCRIPTOR = "com.google.android.gms.asterism.internal.IAsterismApiService";
    private static final String CB_DESCRIPTOR = "com.google.android.gms.asterism.internal.IAsterismCallbacks";

    // AIDL transaction codes (FIRST_CALL_TRANSACTION = 1)
    private static final int TX_GET_CONSENT = 1;
    private static final int TX_SET_CONSENT = 2;
    private static final int TX_IS_PNVR_DEVICE = 3;

    // Callback transaction codes
    private static final int CB_ON_CONSENT_FETCHED = 1;
    private static final int CB_ON_CONSENT_REGISTERED = 2;
    private static final int CB_ON_IS_PNVR_DEVICE = 3;

    // Messages IID sender for ACS POST header
    private static final String MESSAGES_IID_SENDER = "466216207879";

    private final Context context;

    public AsterismServiceImpl(Context context) {
        this.context = context;
        Log.i(TAG, "AsterismServiceImpl created");
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (code == INTERFACE_TRANSACTION) {
            reply.writeString(DESCRIPTOR);
            return true;
        }
        data.enforceInterface(DESCRIPTOR);

        switch (code) {
            case TX_GET_CONSENT:
                return handleGetConsent(data, reply);
            case TX_SET_CONSENT:
                return handleSetConsent(data, reply);
            case TX_IS_PNVR_DEVICE:
                return handleIsPnvrDevice(data, reply);
            default:
                Log.w(TAG, "Unknown tx code: " + code);
                return handleGenericSuccess(data, reply);
        }
    }

    /**
     * getAsterismConsent → onConsentFetched(Status, GetAsterismConsentResponse, ApiMetadata)
     *
     * Returns CONSENTED + IID token for sender 466216207879.
     * Messages stores this IID token and uses it as gmscore_instance_id_token header.
     */
    private boolean handleGetConsent(Parcel data, Parcel reply) {
        try {
            // Read: IAsterismCallbacks cb, GetAsterismConsentRequest req, ApiMetadata meta
            IBinder callback = data.readStrongBinder();
            // Skip request and metadata SafeParcelables (read as generic objects)
            data.readInt(); // request SafeParcel header or null marker
            Log.i(TAG, "getAsterismConsent called");

            // Get IID token for Messages sender 466216207879
            String iidToken = null;
            try {
                kotlin.Pair<String, String> result = GoogleConstellationClient.getOrRegisterIidToken(
                    context, context.getPackageName(), MESSAGES_IID_SENDER);
                iidToken = result.getFirst();
                Log.i(TAG, "IID token for sender " + MESSAGES_IID_SENDER + ": " +
                    (iidToken != null ? iidToken.substring(0, Math.min(20, iidToken.length())) + "..." : "null"));
            } catch (Exception e) {
                Log.w(TAG, "Failed to get IID token: " + e.getMessage());
            }

            if (callback != null) {
                Parcel cb = Parcel.obtain();
                try {
                    cb.writeInterfaceToken(CB_DESCRIPTOR);
                    // Param 1: Status SafeParcelable (SUCCESS = status code 0)
                    writeStatus(cb, 0);
                    // Param 2: GetAsterismConsentResponse SafeParcelable
                    writeGetConsentResponse(cb, 1 /* CONSENTED */, iidToken);
                    // Param 3: ApiMetadata SafeParcelable
                    writeDefaultApiMetadata(cb);
                    callback.transact(CB_ON_CONSENT_FETCHED, cb, null, FLAG_ONEWAY);
                } finally {
                    cb.recycle();
                }
            }

            reply.writeNoException();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error in getAsterismConsent", e);
            reply.writeNoException();
            return true;
        }
    }

    /**
     * setAsterismConsent → onConsentRegistered(Status, SetAsterismConsentResponse, ApiMetadata)
     */
    private boolean handleSetConsent(Parcel data, Parcel reply) {
        try {
            IBinder callback = data.readStrongBinder();
            Log.i(TAG, "setAsterismConsent called");

            // Get IID token for response
            String iidToken = null;
            try {
                kotlin.Pair<String, String> result = GoogleConstellationClient.getOrRegisterIidToken(
                    context, context.getPackageName(), MESSAGES_IID_SENDER);
                iidToken = result.getFirst();
            } catch (Exception e) {
                Log.w(TAG, "Failed to get IID token for setConsent response: " + e.getMessage());
            }

            if (callback != null) {
                Parcel cb = Parcel.obtain();
                try {
                    cb.writeInterfaceToken(CB_DESCRIPTOR);
                    // Param 1: Status (SUCCESS)
                    writeStatus(cb, 0);
                    // Param 2: SetAsterismConsentResponse { int requestCode (a), String iidToken (b), String gaiaToken (c) }
                    writeSetConsentResponse(cb, iidToken);
                    // Param 3: ApiMetadata
                    writeDefaultApiMetadata(cb);
                    callback.transact(CB_ON_CONSENT_REGISTERED, cb, null, FLAG_ONEWAY);
                } finally {
                    cb.recycle();
                }
            }

            reply.writeNoException();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error in setAsterismConsent", e);
            reply.writeNoException();
            return true;
        }
    }

    /**
     * getIsPnvrConstellationDevice → onIsPnvrConstellationDevice(Status, boolean, ApiMetadata)
     */
    private boolean handleIsPnvrDevice(Parcel data, Parcel reply) {
        try {
            IBinder callback = data.readStrongBinder();
            Log.i(TAG, "getIsPnvrConstellationDevice called");

            if (callback != null) {
                Parcel cb = Parcel.obtain();
                try {
                    cb.writeInterfaceToken(CB_DESCRIPTOR);
                    // Param 1: Status (SUCCESS)
                    writeStatus(cb, 0);
                    // Param 2: boolean result = true
                    cb.writeInt(1); // true
                    // Param 3: ApiMetadata
                    writeDefaultApiMetadata(cb);
                    callback.transact(CB_ON_IS_PNVR_DEVICE, cb, null, FLAG_ONEWAY);
                } finally {
                    cb.recycle();
                }
            }

            reply.writeNoException();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error in getIsPnvrConstellationDevice", e);
            reply.writeNoException();
            return true;
        }
    }

    // === SafeParcelable writers ===

    /**
     * Write Status SafeParcelable.
     * Status has: versionCode (field 1000), statusCode (field 1), statusMessage (field 2),
     *   pendingIntent (field 3), connectionResult (field 5).
     * For SUCCESS: statusCode=0, everything else null/default.
     */
    private void writeStatus(Parcel dest, int statusCode) {
        // SafeParcel: write data length header, then fields
        int startPos = beginSafeParcelable(dest);
        writeSafeParcelField(dest, 1000, 1); // versionCode = 1
        writeSafeParcelField(dest, 1, statusCode); // statusCode
        endSafeParcelable(dest, startPos);
    }

    /**
     * Write GetAsterismConsentResponse SafeParcelable.
     * Fields: 1=requestCode(int), 2=consentState(int), 3=iidToken(String), 4=gaiaToken(String), 5=consentVersion(int)
     */
    private void writeGetConsentResponse(Parcel dest, int consentState, String iidToken) {
        // Write non-null marker (1) then the SafeParcelable
        dest.writeInt(1);
        int startPos = beginSafeParcelable(dest);
        writeSafeParcelField(dest, 1, 0); // requestCode = 0
        writeSafeParcelField(dest, 2, consentState); // 1 = CONSENTED
        if (iidToken != null) {
            writeSafeParcelStringField(dest, 3, iidToken); // iidToken
        }
        // field 4: gaiaToken - omit (null)
        writeSafeParcelField(dest, 5, 1); // consentVersion = 1
        endSafeParcelable(dest, startPos);
    }

    /**
     * Write SetAsterismConsentResponse SafeParcelable.
     * Fields: 1=requestCode(int), 2=iidToken(String), 3=gaiaToken(String)
     */
    private void writeSetConsentResponse(Parcel dest, String iidToken) {
        dest.writeInt(1); // non-null marker
        int startPos = beginSafeParcelable(dest);
        writeSafeParcelField(dest, 1, 0); // requestCode = 0
        if (iidToken != null) {
            writeSafeParcelStringField(dest, 2, iidToken); // iidToken
        }
        endSafeParcelable(dest, startPos);
    }

    /**
     * Write default ApiMetadata SafeParcelable (minimal).
     */
    private void writeDefaultApiMetadata(Parcel dest) {
        dest.writeInt(1); // non-null marker
        int startPos = beginSafeParcelable(dest);
        // ApiMetadata minimal: just versionCode
        writeSafeParcelField(dest, 1000, 1);
        endSafeParcelable(dest, startPos);
    }

    // === SafeParcel encoding helpers ===
    // SafeParcel format: [total_length:4] [field_header:4 field_data:N]... [end_marker:0]
    // field_header = (fieldId << 16) | dataSize, or (fieldId << 16) | 0xFFFF for variable-length

    private int beginSafeParcelable(Parcel dest) {
        // Write placeholder for total length
        int startPos = dest.dataPosition();
        dest.writeInt(0); // placeholder
        return startPos;
    }

    private void endSafeParcelable(Parcel dest, int startPos) {
        int endPos = dest.dataPosition();
        dest.setDataPosition(startPos);
        dest.writeInt(endPos - startPos - 4); // total length (excluding the length field itself)
        dest.setDataPosition(endPos);
    }

    private void writeSafeParcelField(Parcel dest, int fieldId, int value) {
        // Int field: header = (fieldId << 16) | 4 (size of int)
        dest.writeInt((fieldId << 16) | 4);
        dest.writeInt(value);
    }

    private void writeSafeParcelStringField(Parcel dest, int fieldId, String value) {
        // String: variable length, header = (fieldId << 16) | 0xFFFF
        dest.writeInt((fieldId << 16) | 0xFFFF);
        int lenPos = dest.dataPosition();
        dest.writeInt(0); // placeholder for data length
        int dataStart = dest.dataPosition();
        dest.writeString(value);
        int dataEnd = dest.dataPosition();
        dest.setDataPosition(lenPos);
        dest.writeInt(dataEnd - dataStart);
        dest.setDataPosition(dataEnd);
    }

    private boolean handleGenericSuccess(Parcel data, Parcel reply) {
        try {
            IBinder callback = null;
            try { callback = data.readStrongBinder(); } catch (Exception e) {}

            if (callback != null) {
                Parcel cb = Parcel.obtain();
                try {
                    cb.writeInterfaceToken(CB_DESCRIPTOR);
                    writeStatus(cb, 0);
                    callback.transact(CB_ON_CONSENT_FETCHED, cb, null, FLAG_ONEWAY);
                } finally {
                    cb.recycle();
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
