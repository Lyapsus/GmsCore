/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.constellation;

import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
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
import com.google.android.gms.constellation.ImsiRequest;
import com.google.android.gms.constellation.PhoneNumberInfo;
import com.google.android.gms.constellation.PhoneNumberVerification;
import com.google.android.gms.constellation.VerifyPhoneNumberRequest;
import com.google.android.gms.constellation.VerifyPhoneNumberResponse;
import com.google.android.gms.constellation.internal.IConstellationApiService;
import com.google.android.gms.constellation.internal.IConstellationCallbacks;

import com.squareup.wire.GrpcException;

import java.util.ArrayList;
import java.util.List;

/**
 * Constellation Service Implementation - Phone Number Verification for RCS.
 *
 * Production implementation used by Google Messages for phone verification.
 *
 * Invariant: only STATUS_VERIFIED responses may carry a non-empty token.
 * All non-verified statuses must return token="" to avoid poisoning Messages state.
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

    private static final long VERIFICATION_TTL_MS = 86400000L; // 24 hours
    // Binder framework transaction used to query the interface descriptor string.
    // Numeric value is 1598968902 (0x5f4e5446). Defined by android.os.IBinder.
    // Verified in our local decompiled AIDL stubs too (example:
    // messages-analysis/sources/android/support/customtabs/ICustomTabsService.java:88-90).
    // This is transport-level handshake noise, not a Constellation API method call.
    private static final int BINDER_INTERFACE_TRANSACTION = IBinder.INTERFACE_TRANSACTION;

    private final Context context;
    private final Ts43Client ts43Client;

    static final class VerificationDecision {
        final int status;
        final String token;

        VerificationDecision(int status, String token) {
            this.status = status;
            this.token = token;
        }
    }

    static VerificationDecision decideVerificationOutcome(String token, boolean upiIneligible) {
        if (token != null && !token.isEmpty() && !upiIneligible) {
            return new VerificationDecision(PhoneNumberVerification.STATUS_VERIFIED, token);
        }
        if (upiIneligible) {
            return new VerificationDecision(PhoneNumberVerification.STATUS_INELIGIBLE, "");
        }
        return new VerificationDecision(PhoneNumberVerification.STATUS_NON_RETRYABLE_FAILURE, "");
    }

    /**
     * Extract verification method from JWT's google.phone_number_verification_method claim.
     * Stock returns the actual method (MT_SMS, MO_SMS, etc.); we were hardcoding METHOD_TS43_AIDL.
     * Returns METHOD_TS43_AIDL as default for non-JWT tokens or missing claims.
     */
    static int extractVerificationMethodFromJwt(String token) {
        if (token == null || token.isEmpty() || !token.contains(".")) {
            return PhoneNumberVerification.METHOD_TS43_AIDL;
        }
        try {
            String[] parts = token.split("\\.");
            if (parts.length < 2) return PhoneNumberVerification.METHOD_TS43_AIDL;
            byte[] decoded = java.util.Base64.getUrlDecoder().decode(parts[1]);
            String payload = new String(decoded, java.nio.charset.StandardCharsets.UTF_8);
            int idx = payload.indexOf("\"phone_number_verification_method\"");
            if (idx < 0) return PhoneNumberVerification.METHOD_TS43_AIDL;
            int colonIdx = payload.indexOf(':', idx);
            if (colonIdx < 0) return PhoneNumberVerification.METHOD_TS43_AIDL;
            int startQuote = payload.indexOf('"', colonIdx);
            if (startQuote < 0) return PhoneNumberVerification.METHOD_TS43_AIDL;
            int endQuote = payload.indexOf('"', startQuote + 1);
            if (endQuote < 0) return PhoneNumberVerification.METHOD_TS43_AIDL;
            return mapVerificationMethodString(payload.substring(startQuote + 1, endQuote));
        } catch (Exception e) {
            return PhoneNumberVerification.METHOD_TS43_AIDL;
        }
    }

    static int mapVerificationMethodString(String method) {
        switch (method) {
            case "VERIFICATION_METHOD_MT_SMS": return PhoneNumberVerification.METHOD_MT_SMS;
            case "VERIFICATION_METHOD_MO_SMS": return PhoneNumberVerification.METHOD_MO_SMS;
            case "VERIFICATION_METHOD_CARRIER_ID": return PhoneNumberVerification.METHOD_CARRIER_ID;
            case "VERIFICATION_METHOD_IMSI_LOOKUP": return PhoneNumberVerification.METHOD_IMSI_LOOKUP;
            case "VERIFICATION_METHOD_REGISTERED_SMS": return PhoneNumberVerification.METHOD_REGISTERED_SMS;
            case "VERIFICATION_METHOD_FLASH_CALL": return PhoneNumberVerification.METHOD_FLASH_CALL;
            case "VERIFICATION_METHOD_TS43": return PhoneNumberVerification.METHOD_TS43_AIDL;
            default: return PhoneNumberVerification.METHOD_TS43_AIDL;
        }
    }

    /**
     * Map an exception from verifyPhoneNumber to a top-level Status code matching stock GMS.
     *
     * Stock GMS (bevw.java:36-52) maps:
     *   bfpx (NoConsentException)  → 5001
     *   bfpy (RpcException)        → 5002 (default), then sub-codes:
     *     gvhd.RPC_RESOURCE_EXHAUSTED              → 5008
     *     gvhd.RPC_DEADLINE_EXCEEDED/ABORTED/UNAVAILABLE → 5007
     *     gvhd.RPC_PERMISSION_DENIED               → 5009
     *   bfpz (ThrottlingException) → 5003
     *   default (Status.d)         → 8  (INTERNAL_ERROR)
     *
     * We don't have the stock exception hierarchy, so we derive from gRPC status code
     * when a GrpcException is present in the cause chain.
     */
    static int mapExceptionToStatusCode(Throwable e) {
        // Walk cause chain looking for GrpcException
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof GrpcException) {
                int grpcCode = ((GrpcException) t).getGrpcStatus().getCode();
                switch (grpcCode) {
                    case 8:           // RESOURCE_EXHAUSTED → 5008 (bevw.java:44)
                        return 5008;
                    case 4:           // DEADLINE_EXCEEDED  → 5007 (bevw.java:45)
                    case 10:          // ABORTED            → 5007 (bevw.java:45)
                    case 14:          // UNAVAILABLE        → 5007 (bevw.java:45)
                        return 5007;
                    case 7:           // PERMISSION_DENIED  → 5009 (bevw.java:47-48)
                        return 5009;
                    default:          // other gRPC error   → 5002 (bevw.java:41)
                        return 5002;
                }
            }
        }
        // No GrpcException in chain → INTERNAL_ERROR (stock Status.d = new Status(8))
        return 8;
    }

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
            SubscriptionManager sm = (SubscriptionManager) context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);

            // API 33+: Use SubscriptionManager.getPhoneNumber(subId)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (sm != null) {
                    // Try specific subId first
                    if (subId > 0) {
                        String number = sm.getPhoneNumber(subId);
                        if (number != null && !number.isEmpty()) {
                            return number;
                        }
                    }
                    // Fallback to default subscription
                    int defaultSubId = SubscriptionManager.getDefaultSubscriptionId();
                    if (defaultSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                        String number = sm.getPhoneNumber(defaultSubId);
                        if (number != null && !number.isEmpty()) {
                            return number;
                        }
                    }
                }
            }

            // API < 33: Use deprecated but functional APIs
            TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm != null) {
                if (subId > 0) {
                    TelephonyManager tmSub = tm.createForSubscriptionId(subId);
                    @SuppressWarnings("deprecation")
                    String number = tmSub.getLine1Number();
                    if (number != null && !number.isEmpty()) {
                        return number;
                    }
                }
                @SuppressWarnings("deprecation")
                String number = tm.getLine1Number();
                if (number != null && !number.isEmpty()) {
                    return number;
                }
            }

            // Last resort: iterate subscriptions
            if (sm != null) {
                List<SubscriptionInfo> subs = sm.getActiveSubscriptionInfoList();
                if (subs != null && !subs.isEmpty()) {
                    for (SubscriptionInfo sub : subs) {
                        @SuppressWarnings("deprecation")
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
        final int callingUid = android.os.Binder.getCallingUid();
        final int callingPid = android.os.Binder.getCallingPid();
        final String[] callingPackages = getPackagesForUidSafe(callingUid);
        Log.d(TAG, "  caller: uid=" + callingUid + " pid=" + callingPid + " packages=" + java.util.Arrays.toString(callingPackages));
        if (request != null) {
            Log.d(TAG, "  phoneNumber: " + request.phoneNumber);
            Log.d(TAG, "  subscriptionId: " + request.subscriptionId);
            Log.d(TAG, "  allowFallback: " + request.allowFallback);
            Log.d(TAG, "  verificationType: " + request.verificationType);
            Log.d(TAG, "  imsiRequests: " + request.imsiRequests);
            Log.d(TAG, "  verificationCapabilities: " + request.verificationCapabilities);
            Log.d(TAG, "  extras: " + bundleToString(request.extras));
            if (request.idTokenRequest != null) {
                String audience = request.idTokenRequest.audience;
                String nonce = request.idTokenRequest.nonce;
                Log.d(TAG, "  idTokenRequest: audience_len=" + (audience != null ? audience.length() : -1) + ", nonce_len=" + (nonce != null ? nonce.length() : -1));
            }
            Log.d(TAG, "  requestPathClass: " + classifyRequestPath(request));
        }
        Log.d(TAG, "  metadata: " + metadata);

        try {
            String phoneNumber = request != null ? request.phoneNumber : null;
            int subId = request != null ? (int) request.subscriptionId : -1;

            // Try TS.43 first, then Constellation fallback for carriers without TS.43.
            String imsi = null;
            String msisdn = null;
            if (request != null && request.imsiRequests != null && !request.imsiRequests.isEmpty()) {
                ImsiRequest imsiRequest = request.imsiRequests.get(0);
                imsi = imsiRequest != null ? imsiRequest.imsi : null;
                msisdn = imsiRequest != null ? imsiRequest.msisdn : null;
                Log.d(TAG, "  ImsiRequest: imsi=" + redact(imsi) + ", msisdn=" + msisdn);
            }

            if (phoneNumber == null || phoneNumber.isEmpty() || !phoneNumber.startsWith("+")) {
                if (msisdn != null && msisdn.startsWith("+")) {
                    Log.d(TAG, "  Phone number missing or non-E164, using MSISDN from ImsiRequest");
                    phoneNumber = msisdn;
                } else {
                    Log.d(TAG, "  Phone number missing or non-E164, forcing SIM lookup");
                    phoneNumber = getPhoneNumberFromSim(subId);
                    Log.d(TAG, "  Phone number from SIM (fallback): " + phoneNumber);
                }
            }

            Ts43Client.EntitlementResult entitlement = ts43Client.performEntitlementCheckResult(subId, phoneNumber, imsi, msisdn);
            
            // If TS.43 is not available (e.g. Jibe carrier), try Google Constellation
            if (entitlement.ineligible) {
                Log.i(TAG, "TS.43 ineligible, trying Google Constellation...");
                GoogleConstellationClient googleClient = new GoogleConstellationClient(context);
                String callingPackage = null;
                try {
                    String[] packages = context.getPackageManager().getPackagesForUid(android.os.Binder.getCallingUid());
                    if (packages != null && packages.length > 0) {
                        callingPackage = packages[0];
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Failed to resolve calling package", e);
                }
                entitlement = googleClient.verifyPhoneNumber(request, callingPackage, imsi, msisdn);
            }

            String token = entitlement.token;
            boolean upiIneligible = entitlement.ineligible;
            String reason = entitlement.reason;

            // INVARIANT: STATUS_VERIFIED (1) is the ONLY status that may carry a non-empty token.
            // All other statuses MUST have token="".
            //
            // Why: Messages can ignore verificationStatus under phenotype flag
            // (dony.java:388 — flag RcsProvisioning__min_gmscore_version_for_upi_without_acs_fallback_met,
            // default=false per fnfz.java:61) and call t(token) directly. A non-empty invalid token
            // gets stored in bugle.xml → sent to ACS → HTTP 401 → infinite retry loop.
            //
            // Only EntitlementResult.success() (real Google-signed JWT) → STATUS_VERIFIED.
            // Errors → STATUS_NON_RETRYABLE_FAILURE (7) with token="" so Messages
            // falls to non-UPI path (dony.java:448→p(true)→non-UPI fallback GET→OTP flow).
            VerificationDecision decision = decideVerificationOutcome(token, upiIneligible);
            int verificationStatus = decision.status;
            token = decision.token;

            if (verificationStatus == PhoneNumberVerification.STATUS_VERIFIED) {
                // Real success — Google-signed JWT from Constellation
                Log.i(TAG, "Returning verificationStatus=1 (VERIFIED) with real token (reason=" + reason + ")");
            } else if (upiIneligible) {
                Log.i(TAG, "Returning verificationStatus=8 (INELIGIBLE) with empty token (reason=" + reason + ")");
            } else {
                // Error — tell Messages verification failed so it can fall to non-UPI/OTP
                Log.i(TAG, "Returning verificationStatus=7 (NON_RETRYABLE_FAILURE) with empty token (reason=" + reason + ")");
            }

            // Field 8 (h) is retryAfterSeconds (duration, NOT absolute timestamp).
            // Messages does: if (h > 0) Instant.now().plusSeconds(h) to schedule retry.
            // Stock GMS default: -1 (beux.java:16). Both -1 and 0 skip retry scheduling.
            // Field 2 (b) is timestampMillis — stock GMS converts proto seconds→millis
            // via bfpj.a() (seconds*1000+nanos/1e6). Messages logs as "timestampMillis".
            long nowMillis = System.currentTimeMillis();

            PhoneNumberVerification verification = new PhoneNumberVerification(
                phoneNumber,
                nowMillis,
                extractVerificationMethodFromJwt(token),
                PhoneNumberVerification.ERROR_NONE,
                token,
                new Bundle(),
                verificationStatus,
                -1L  // retryAfterSeconds: -1 = default (no retry), matches stock beux.java:16
            );
            
            PhoneNumberVerification[] verifications = new PhoneNumberVerification[] { verification };
            VerifyPhoneNumberResponse response = new VerifyPhoneNumberResponse(verifications, new Bundle());
            
            callbacks.onPhoneNumberVerificationsCompleted(
                Status.SUCCESS,
                response,
                ApiMetadata.DEFAULT
            );
            Log.i(TAG, "verifyPhoneNumber() completed - status=" + verificationStatus + " reason=" + reason + " for: " + phoneNumber);
        } catch (Exception e) {
            Log.e(TAG, "verifyPhoneNumber() failed", e);
            int statusCode = mapExceptionToStatusCode(e);
            Log.w(TAG, "verifyPhoneNumber() mapped exception to Status(" + statusCode + ")");
            // Stock bevw.java:53,83: non-success Status → null response (except 5001 consent bundle).
            // Messages checks response != null before accessing fields.
            callbacks.onPhoneNumberVerificationsCompleted(
                new Status(statusCode, e.getMessage()),
                null,
                ApiMetadata.DEFAULT
            );
        }
    }

    /**
     * Compute FID matching stock cdqp.b() = cdqp.a(keyPair).
     * cdqp.java:40-48: SHA1(publicKey.getEncoded())[0:8], first byte = (b[0] & 0x0F) + 0x70,
     * Base64 flags=11 (NO_WRAP|NO_PADDING|URL_SAFE).
     * Returns null if EC key not available.
     */
    private String computeFidFromEcKey() {
        try {
            android.content.SharedPreferences keyPrefs = context.getSharedPreferences("constellation_prefs", Context.MODE_PRIVATE);
            String pubKeyB64 = keyPrefs.getString("public_key", null);
            if (pubKeyB64 == null) return null;
            byte[] pubKeyBytes = android.util.Base64.decode(pubKeyB64, android.util.Base64.DEFAULT);
            byte[] sha1 = java.security.MessageDigest.getInstance("SHA1").digest(pubKeyBytes);
            sha1[0] = (byte) ((sha1[0] & 0x0F) + 0x70);
            return android.util.Base64.encodeToString(sha1, 0, 8,
                    android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING | android.util.Base64.URL_SAFE);
        } catch (Exception e) {
            Log.w(TAG, "computeFidFromEcKey failed", e);
            return null;
        }
    }

    private String redact(String value) {
        if (value == null || value.length() < 6) return "<redacted>";
        return value.substring(0, 3) + "..." + value.substring(value.length() - 3);
    }

    private String[] getPackagesForUidSafe(int uid) {
        try {
            String[] packages = context.getPackageManager().getPackagesForUid(uid);
            return packages != null ? packages : new String[0];
        } catch (Exception e) {
            Log.w(TAG, "Failed to resolve packages for uid=" + uid, e);
            return new String[0];
        }
    }

    private String classifyRequestPath(VerifyPhoneNumberRequest request) {
        if (request == null) return "NULL_REQUEST";

        String requiredConsent = null;
        String consentType = null;
        if (request.extras != null) {
            requiredConsent = request.extras.getString("required_consumer_consent");
            consentType = request.extras.getString("consent_type");
        }

        if ("RCS".equals(requiredConsent)) return "RCS_PROVISIONING_PATH";
        if ("am_profiles".equals(request.phoneNumber)) return "ASTERISM_PROFILES_WORKER_PATH";
        if (requiredConsent == null && consentType == null) return "GENERIC_NO_CONSENT_EXTRAS_PATH";
        return "OTHER_PATH";
    }

    private String transactionName(int code) {
        switch (code) {
            case 1:
                return "verifyPhoneNumberV1";
            case 2:
                return "verifyPhoneNumberSingleUse";
            case 3:
                return "verifyPhoneNumber";
            case 4:
                return "getIidToken";
            case 5:
                return "getPnvCapabilities";
            case BINDER_INTERFACE_TRANSACTION:
                return "INTERFACE_TRANSACTION";
            default:
                return "unknown";
        }
    }

    /**
     * Get Instance ID token.
     * Transaction code 4.
     *
     * Stock (bevs.java):
     * - request.a = sender/project number (Messages passes 466216207879L, default icer.b()=496232013492L)
     * - bfpe.a(context, senderId) → bfpd(fid, iidToken) for that sender
     * - Response: (iidToken, fid, signature_or_null, signatureTimestampMillis_or_0)
     * - Error: Status(5004)
     */

    @Override
    public void getIidToken(IConstellationCallbacks callbacks, GetIidTokenRequest request, ApiMetadata metadata) throws RemoteException {
        // Stock (bevs.java:48): reads request.a as sender/project number
        // Messages (clqs.java:40) passes 466216207879L
        String senderId;
        if (request != null && request.subscriptionId != null && request.subscriptionId != 0L) {
            senderId = Long.toString(request.subscriptionId);
            Log.i(TAG, "getIidToken() called with sender=" + senderId);
        } else {
            senderId = GoogleConstellationClient.CONSTELLATION_SENDER_ID;
            Log.i(TAG, "getIidToken() called with no sender, using default=" + senderId);
        }

        try {
            // Stock (bevs.java:49): bfpe.a(context, senderId) uses GMS's own context,
            // NOT the caller's package. IID registration is always under GMS identity.
            String packageName = context.getPackageName();

            // Stock (bevs.java:49): bfpe.a(context, senderId) → bfpd(fid, iidToken)
            kotlin.Pair<String, String> result = GoogleConstellationClient.getOrRegisterIidToken(context, packageName, senderId);
            String iidToken = result.getFirst();
            String source = result.getSecond();

            // Stock (bevs.java:50): bfpdVarA.a = FID (Firebase Installation ID)
            // Our FID equivalent = instance_id from constellation_iid prefs
            // (created as side effect of getOrRegisterIidToken when registering fresh)
            android.content.SharedPreferences iidPrefs = context.getSharedPreferences("constellation_iid", Context.MODE_PRIVATE);
            String fid = iidPrefs.getString("instance_id", "");
            if (fid.isEmpty()) {
                // Fallback: compute FID same as stock cdqp.b() = SHA1(ecPublicKey)[0:8] with
                // modified first byte, base64 NO_WRAP|NO_PADDING|URL_SAFE (flags=11).
                // Stock cdqp.java:40-48: digest[0] = (digest[0] & 0x0F) + 0x70
                fid = computeFidFromEcKey();
                if (fid != null) {
                    Log.w(TAG, "No instance_id in prefs, computed FID from EC key (stock cdqp.b() parity)");
                } else {
                    fid = android.provider.Settings.Secure.getString(context.getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
                    Log.w(TAG, "No instance_id and no EC key, using Android ID as FID fallback");
                }
            }

            // Stock (bevs.java:59-64): field 3 = signature bytes (null when phenotype flag off),
            // field 4 = System.currentTimeMillis() when signed, 0 when not signed.
            // We don't implement the signing phenotype flag (icer.a.mq().f()), so: null + 0.
            GetIidTokenResponse response = new GetIidTokenResponse(
                iidToken,   // field 1: IID token (stock: bfpdVarA.b = iidToken)
                fid,        // field 2: FID (stock: bfpdVarA.a = fid, NOT sender ID)
                null,       // field 3: signature (null = signing disabled)
                0L          // field 4: signature timestamp millis (0 = no signature)
            );

            callbacks.onIidTokenGenerated(
                Status.SUCCESS,
                response,
                ApiMetadata.DEFAULT
            );
            Log.i(TAG, "getIidToken() completed - sender=" + senderId + " source=" + source + " fid=" + fid + " token=" + iidToken.substring(0, Math.min(20, iidToken.length())) + "...");
        } catch (Exception e) {
            Log.e(TAG, "getIidToken() failed", e);
            // Stock (bevs.java:68,72): both bfqa and IOException → Status(5004)
            callbacks.onIidTokenGenerated(
                new Status(5004, e.getMessage()),
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
        Log.d(TAG, "onTransact code=" + code + " hex=0x" + Integer.toHexString(code) + " (" + transactionName(code) + ") uid=" + android.os.Binder.getCallingUid() + " pid=" + android.os.Binder.getCallingPid());
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
