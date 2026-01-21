/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.constellation;

import android.content.Context;
import android.os.Bundle;
import android.os.Parcel;
import android.os.RemoteException;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.google.android.gms.common.api.ApiMetadata;
import com.google.android.gms.common.api.CommonStatusCodes;
import com.google.android.gms.common.api.Status;
import com.google.android.gms.constellation.GetIidTokenRequest;
import com.google.android.gms.constellation.GetIidTokenResponse;
import com.google.android.gms.constellation.GetPnvCapabilitiesRequest;
import com.google.android.gms.constellation.GetPnvCapabilitiesResponse;
import com.google.android.gms.constellation.PhoneNumberInfo;
import com.google.android.gms.constellation.PhoneNumberVerification;
import com.google.android.gms.constellation.VerifyPhoneNumberRequest;
import com.google.android.gms.constellation.VerifyPhoneNumberResponse;
import com.google.android.gms.constellation.internal.IConstellationApiService;
import com.google.android.gms.constellation.internal.IConstellationCallbacks;

import java.util.ArrayList;
import java.util.List;

/**
 * Constellation Service Implementation - Phone Number Verification for RCS.
 *
 * This stub implementation logs all calls and returns success responses.
 * The goal is to:
 * 1. Verify that Google Messages binds to this service
 * 2. Understand what methods are called and in what order
 * 3. Eventually implement real TS.43 EAP-AKA verification
 *
 * Key insight from bounty research:
 * - benwaffle: "If I implement Constellation in microg with a valid response 
 *   but a fake token, it proceeds to the next step"
 * - This means even stub responses can help Messages progress past "Setting up..."
 *
 * AIDL Interface (microG Stub mapping, decompiled from system APK):
 * - Code 1: verifyPhoneNumberV1 (Bundle-based, legacy)
 * - Code 2: verifyPhoneNumberSingleUse (Bundle-based, legacy)
 * - Code 3: verifyPhoneNumber (VerifyPhoneNumberRequest, current)
 * - Code 4: getIidToken
 * - Code 5: getPnvCapabilities
 */
public class ConstellationServiceImpl extends IConstellationApiService.Stub {
    private static final String TAG = "GmsConstellationSvcImpl";

    private final Context context;
    private final Ts43Client ts43Client;

    public ConstellationServiceImpl(Context context) {
        this.context = context;
        this.ts43Client = new Ts43Client(context);
        Log.i(TAG, "ConstellationServiceImpl created - RCS phone verification service");
    }


    /**
     * Legacy V1 phone number verification (Bundle-based).
     */
    @Override
    public void verifyPhoneNumberV1(IConstellationCallbacks callbacks, Bundle bundle, ApiMetadata metadata) throws RemoteException {
        Log.w(TAG, "verifyPhoneNumberV1() called with Bundle");
        Log.d(TAG, "  Bundle contents: " + bundleToString(bundle));
        
        // Extract phone number from Bundle - try various key names
        String phoneNumber = null;
        int subId = -1;
        if (bundle != null) {
            phoneNumber = bundle.getString("phone_number");
            if (phoneNumber == null) phoneNumber = bundle.getString("phoneNumber");
            if (phoneNumber == null) phoneNumber = bundle.getString("msisdn");
            if (phoneNumber == null) phoneNumber = bundle.getString("number");
            
            subId = bundle.getInt("subscription_id", bundle.getInt("subscriptionId", bundle.getInt("sub_id", -1)));
            if (subId == -1) {
                subId = (int) bundle.getLong("subscription_id", bundle.getLong("subscriptionId", bundle.getLong("sub_id", -1L)));
            }
        }
        
        // If phone number not in Bundle, get from TelephonyManager
        if (phoneNumber == null || phoneNumber.isEmpty()) {
            phoneNumber = getPhoneNumberFromSim(subId);
            Log.d(TAG, "  Phone number from SIM: " + phoneNumber);
        }
        
        Log.d(TAG, "  Extracted phoneNumber: " + phoneNumber + ", subId: " + subId);
        
        // Call verifyPhoneNumber with extracted data
            VerifyPhoneNumberRequest request = new VerifyPhoneNumberRequest(
            phoneNumber,
            subId,
            null,  // idTokenRequest
            bundle != null ? bundle : new Bundle(),
            null,  // imsiRequests
            true,  // allowFallback
            PhoneNumberVerification.METHOD_TS43,
            null   // verificationCapabilities
        );
        verifyPhoneNumber(callbacks, request, metadata);
    }
    
    /**
     * Get phone number from SIM via TelephonyManager.
     */
    private String getPhoneNumberFromSim(int subId) {
        try {
            TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm == null) return null;
            
            // If specific subId, try to get that SIM's number
            if (subId > 0) {
                TelephonyManager tmSub = tm.createForSubscriptionId(subId);
                String number = tmSub.getLine1Number();
                if (number != null && !number.isEmpty()) {
                    return number;
                }
            }
            
            // Fallback to default SIM
            String number = tm.getLine1Number();
            if (number != null && !number.isEmpty()) {
                return number;
            }
            
            // Try SubscriptionManager
            SubscriptionManager sm = (SubscriptionManager) context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
            if (sm != null) {
                List<SubscriptionInfo> subs = sm.getActiveSubscriptionInfoList();
                if (subs != null && !subs.isEmpty()) {
                    for (SubscriptionInfo sub : subs) {
                        String subNumber = sub.getNumber();
                        if (subNumber != null && !subNumber.isEmpty()) {
                            return subNumber;
                        }
                    }
                }
            }
        } catch (SecurityException e) {
            Log.w(TAG, "No permission to read phone number: " + e.getMessage());
        } catch (Exception e) {
            Log.w(TAG, "Failed to get phone number from SIM: " + e.getMessage());
        }
        return null;
    }

    /**
     * Single-use phone number verification (Bundle-based).
     */
    @Override
    public void verifyPhoneNumberSingleUse(IConstellationCallbacks callbacks, Bundle bundle, ApiMetadata metadata) throws RemoteException {
        Log.w(TAG, "verifyPhoneNumberSingleUse() called with Bundle");
        Log.d(TAG, "  Bundle contents: " + bundleToString(bundle));
        // Reuse the same logic as V1
        verifyPhoneNumberV1(callbacks, bundle, metadata);
    }

    /**
     * Verify a phone number.
     *
     * This is the main method for phone verification (current API).
     * Google Messages calls this to verify the SIM's phone number.
     */
    @Override
    public void verifyPhoneNumber(IConstellationCallbacks callbacks, VerifyPhoneNumberRequest request, ApiMetadata metadata) throws RemoteException {
        Log.i(TAG, "verifyPhoneNumber() called - THIS IS THE KEY RCS METHOD");
        if (request != null) {
            Log.d(TAG, "  phoneNumber: " + request.phoneNumber);
            Log.d(TAG, "  subscriptionId: " + request.subscriptionId);
            Log.d(TAG, "  allowFallback: " + request.allowFallback);
            Log.d(TAG, "  verificationType: " + request.verificationType);
            Log.d(TAG, "  imsiRequests: " + request.imsiRequests);
            Log.d(TAG, "  verificationCapabilities: " + request.verificationCapabilities);
            Log.d(TAG, "  extras: " + bundleToString(request.extras));
            if (request.idTokenRequest != null) {
                Log.d(TAG, "  idTokenRequest: audience=" + request.idTokenRequest.audience + ", nonce=" + request.idTokenRequest.nonce);
            }
        }
        Log.d(TAG, "  metadata: " + metadata);

        try {
            String phoneNumber = request != null ? request.phoneNumber : null;
            int subId = request != null ? (int) request.subscriptionId : -1;
            
            // If phone number is null, try to get from SIM
            if (phoneNumber == null || phoneNumber.isEmpty()) {
                phoneNumber = getPhoneNumberFromSim(subId);
                Log.d(TAG, "  Phone number from SIM (fallback): " + phoneNumber);
            }
            
            // Try to get a real token via TS.43 (currently a stub)
            String token = ts43Client.performEntitlementCheck(subId, phoneNumber);
            if (token == null) {
                // Fallback to fake JWT for edge cases (SocketTimeout, generic IOException)
                token = ts43Client.generateStubToken();
                Log.w(TAG, "TS.43 returned null, using fallback fake JWT token");
            }

            PhoneNumberVerification verification = new PhoneNumberVerification(
                phoneNumber,
                System.currentTimeMillis(),
                PhoneNumberVerification.METHOD_TS43,
                PhoneNumberVerification.ERROR_NONE,
                token,
                new Bundle(),
                PhoneNumberVerification.STATUS_VERIFIED,
                System.currentTimeMillis() + 86400000
            );
            
            PhoneNumberVerification[] verifications = new PhoneNumberVerification[] { verification };
            VerifyPhoneNumberResponse response = new VerifyPhoneNumberResponse(verifications, new Bundle());
            
            callbacks.onPhoneNumberVerificationsCompleted(
                Status.SUCCESS,
                response,
                ApiMetadata.DEFAULT
            );
            Log.i(TAG, "verifyPhoneNumber() completed - returned STUB verified response for: " + phoneNumber);
            Log.w(TAG, "NOTE: This is a STUB token - real implementation needs TS.43 EAP-AKA");
        } catch (Exception e) {
            Log.e(TAG, "verifyPhoneNumber() failed", e);
            callbacks.onPhoneNumberVerificationsCompleted(
                new Status(CommonStatusCodes.INTERNAL_ERROR, e.getMessage()),
                null,
                ApiMetadata.DEFAULT
            );
        }
    }

    /**
     * Get Instance ID token.
     * Transaction code 4.
     *
     * This is used for push notifications and device identification.
     */
    @Override
    public void getIidToken(IConstellationCallbacks callbacks, GetIidTokenRequest request, ApiMetadata metadata) throws RemoteException {
        Log.i(TAG, "getIidToken() called");
        if (request != null) {
            Log.d(TAG, "  subscriptionId: " + request.subscriptionId);
        }
        Log.d(TAG, "  metadata: " + metadata);

        try {
            // Return a stub IID token
            GetIidTokenResponse response = new GetIidTokenResponse(
                "STUB_IID_TOKEN",      // token
                "com.google.android.apps.messaging",  // audience
                null,                   // signature
                System.currentTimeMillis() + 86400000  // expirationTime
            );
            
            callbacks.onIidTokenGenerated(
                Status.SUCCESS,
                response,
                ApiMetadata.DEFAULT
            );
            Log.i(TAG, "getIidToken() completed - returned stub token");
        } catch (Exception e) {
            Log.e(TAG, "getIidToken() failed", e);
            callbacks.onIidTokenGenerated(
                new Status(CommonStatusCodes.INTERNAL_ERROR, e.getMessage()),
                null,
                ApiMetadata.DEFAULT
            );
        }
    }

    /**
     * Get phone number verification capabilities.
     * Transaction code 5.
     *
     * Returns what verification methods are available for the given phone numbers/SIMs.
     */
    @Override
    public void getPnvCapabilities(IConstellationCallbacks callbacks, GetPnvCapabilitiesRequest request, ApiMetadata metadata) throws RemoteException {
        Log.i(TAG, "getPnvCapabilities() called");
        if (request != null) {
            Log.d(TAG, "  packageName: " + request.packageName);
            Log.d(TAG, "  phoneNumbers: " + request.phoneNumbers);
            Log.d(TAG, "  subscriptionIds: " + request.subscriptionIds);
        }
        Log.d(TAG, "  metadata: " + metadata);

        try {
            // Return empty capabilities list
            // In real implementation, this would query SIM capabilities
            GetPnvCapabilitiesResponse response = new GetPnvCapabilitiesResponse(new ArrayList<>());
            
            callbacks.onGetPnvCapabilitiesCompleted(
                Status.SUCCESS,
                response,
                ApiMetadata.DEFAULT
            );
            Log.i(TAG, "getPnvCapabilities() completed");
        } catch (Exception e) {
            Log.e(TAG, "getPnvCapabilities() failed", e);
            callbacks.onGetPnvCapabilitiesCompleted(
                new Status(CommonStatusCodes.INTERNAL_ERROR, e.getMessage()),
                null,
                ApiMetadata.DEFAULT
            );
        }
    }

    /**
     * Handle unknown transaction codes for forward compatibility.
     */
    @Override
    public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        Log.d(TAG, "onTransact code=" + code);
        if (super.onTransact(code, data, reply, flags)) {
            return true;
        }
        Log.w(TAG, "onTransact: unknown code " + code);
        return false;
    }

    /**
     * Helper to convert Bundle to string for logging.
     */
    private String bundleToString(Bundle bundle) {
        if (bundle == null) return "null";
        StringBuilder sb = new StringBuilder("{");
        for (String key : bundle.keySet()) {
            if (sb.length() > 1) sb.append(", ");
            sb.append(key).append("=").append(bundle.get(key));
        }
        sb.append("}");
        return sb.toString();
    }
}
