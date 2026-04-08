/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.constellation;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.provider.Settings;
import android.telephony.CarrierConfigManager;
import android.telephony.TelephonyManager;
import android.util.Base64;
import android.util.Log;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Client for GSMA TS.43 Service Entitlement Configuration.
 * 
 * Implements the EAP-AKA authentication flow to retrieve RCS provisioning tokens
 * from the carrier's entitlement server.
 */
public class Ts43Client {
    private static final String TAG = "GmsTs43Client";
    private final Context context;
    private final SimAuthProvider simAuthProvider;
    private final Base64Decoder base64Decoder;
    private final Logger logger;

    private static final int EAP_AKA_TYPE = 23;
    private static final int EAP_AKA_SUBTYPE_CHALLENGE = 1;
    private static final int AT_RAND = 1;
    private static final int AT_AUTN = 2;
    private static final int AT_RES = 3;
    private static final int AT_MAC = 11;

    private static final String EAP_RELAY_ACCEPT = "application/vnd.gsma.eap-relay.v1.0+json";
    // NAI realm default: nai.epc.mnc<MNC>.mcc<MCC>.3gppnetwork.org (3GPP TS 23.003)

    private static final int NETWORK_BIND_NONE = 0;
    private static final int NETWORK_BIND_WIFI = 1;
    private static final int NETWORK_BIND_CELLULAR = 2;
    private static final int TS43_TIMEOUT_MS = 15000;

    // Interface for logging
    interface Logger {
        void d(String tag, String msg);
        void i(String tag, String msg);
        void w(String tag, String msg);
        void w(String tag, String msg, Throwable tr);
        void e(String tag, String msg);
        void e(String tag, String msg, Throwable tr);
    }

    // Interface for Base64 decoding to allow testing without Android dependencies
    interface Base64Decoder {

        byte[] decode(String str);
        String encodeToString(byte[] input);
    }

    // Interface for SIM authentication to allow testing
    interface SimAuthProvider {
        String getNetworkOperator();
        String getSimOperator();
        String getSubscriberId();
        String getIccAuthentication(int appType, int authType, String data);
    }

    private static final class Ts43SimInfo {
        private final String imsi;
        private final String simOperator;

        private Ts43SimInfo(String imsi, String simOperator) {
            this.imsi = imsi;
            this.simOperator = simOperator;
        }
    }

    public static final class EntitlementResult {
        public final String token;
        public final boolean ineligible;
        public final String reason;
        /** Original exception for error mapping. Stock GMS (bevw.java:36-52) maps
         *  gRPC exceptions to Status(500x) codes. Carried so ConstellationServiceImpl
         *  can replicate the same mapping. null for success/ineligible/non-exception errors. */
        public final Throwable cause;
        /** When true, server returned PHONE_NUMBER_ENTRY_REQUIRED (reason=5).
         *  Maps to verificationStatus=7 → Messages shows phone number input UI. */
        public final boolean needsManualMsisdn;

        private EntitlementResult(String token, boolean ineligible, String reason, Throwable cause, boolean needsManualMsisdn) {
            this.token = token;
            this.ineligible = ineligible;
            this.reason = reason;
            this.cause = cause;
            this.needsManualMsisdn = needsManualMsisdn;
        }

        public static EntitlementResult success(String token) {
            return new EntitlementResult(token, false, "success", null, false);
        }

        public static EntitlementResult ineligible(String token, String reason) {
            return new EntitlementResult(token, true, reason, null, false);
        }

        public static EntitlementResult error(String reason) {
            return new EntitlementResult(null, false, reason, null, false);
        }

        public static EntitlementResult error(String reason, Throwable cause) {
            return new EntitlementResult(null, false, reason, cause, false);
        }

        /** Server says user must manually enter phone number (UnverifiedReason=5).
         *  Maps to verificationStatus=7 → Messages shows phone input UI → re-calls verifyPhoneNumber. */
        public static EntitlementResult phoneNumberEntryRequired(String reason) {
            return new EntitlementResult(null, false, reason, null, true);
        }

        /** True when this result represents an error (no token, not ineligible, not manual MSISDN). */
        public boolean isError() {
            return !ineligible && !needsManualMsisdn && (token == null || token.isEmpty());
        }
    }

    private static final class EntitlementEndpoint {
        final String url;
        final boolean fromCarrierConfig;

        private EntitlementEndpoint(String url, boolean fromCarrierConfig) {
            this.url = url;
            this.fromCarrierConfig = fromCarrierConfig;
        }
    }

    public Ts43Client(Context context) {
        this.context = context;
        TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        this.simAuthProvider = new SimAuthProvider() {
            @Override
            public String getNetworkOperator() {
                return tm.getNetworkOperator();
            }

            @Override
            public String getSimOperator() {
                return tm.getSimOperator();
            }

            @Override
            public String getSubscriberId() {
                try {
                    return tm.getSubscriberId();
                } catch (SecurityException e) {
                    Log.e(TAG, "Permission denied for getSubscriberId", e);
                    return null;
                }
            }

            @Override
            public String getIccAuthentication(int appType, int authType, String data) {
                return tm.getIccAuthentication(appType, authType, data);
            }
        };
        this.base64Decoder = new Base64Decoder() {
            @Override
            public byte[] decode(String str) {
                return Base64.decode(str, Base64.NO_WRAP);
            }

            @Override
            public String encodeToString(byte[] input) {
                return Base64.encodeToString(input, Base64.NO_WRAP);
            }
        };
        this.logger = new Logger() {
            @Override public void d(String tag, String msg) { Log.d(tag, msg); }
            @Override public void i(String tag, String msg) { Log.i(tag, msg); }
            @Override public void w(String tag, String msg) { Log.w(tag, msg); }
            @Override public void w(String tag, String msg, Throwable tr) { Log.w(tag, msg, tr); }
            @Override public void e(String tag, String msg) { Log.e(tag, msg); }
            @Override public void e(String tag, String msg, Throwable tr) { Log.e(tag, msg, tr); }
        };
    }

    // Constructor for testing
    Ts43Client(Context context, SimAuthProvider simAuthProvider, Base64Decoder base64Decoder, Logger logger) {
        this.context = context;
        this.simAuthProvider = simAuthProvider;
        this.base64Decoder = base64Decoder;
        this.logger = logger;
    }


    /**
     * Performs the TS.43 entitlement check for the given subscription.
     * 
     * @param subId The subscription ID to use.
     * @param phoneNumber The phone number associated with the subscription.
     * @return The RCS configuration token (JWT) or null if failed.
     */
    public EntitlementResult performEntitlementCheckResult(int subId, String phoneNumber, String requestImsi, String requestMsisdn) {
        logger.i(TAG, "Starting TS.43 entitlement check for subId=" + subId);

        Ts43SimInfo simInfo = resolveSimInfo(subId, requestImsi, requestMsisdn);
        if (simInfo == null) {
            logger.e(TAG, "Unable to resolve SIM info for entitlement check");
            return EntitlementResult.error("sim-info");
        }

        if (phoneNumber == null || phoneNumber.isEmpty() || !phoneNumber.startsWith("+")) {
            logger.w(TAG, "Phone number missing or non-E164, forcing SIM lookup (value=" + phoneNumber + ")");
            phoneNumber = null;
        }

        // 1. Get MCC/MNC from SIM operator (NOT network operator)
        String simOperator = simInfo.simOperator;
        if (simOperator == null || simOperator.length() < 5) {
            logger.e(TAG, "Invalid SIM operator: " + simOperator);
            return EntitlementResult.error("sim-operator");
        }
        String mcc = simOperator.substring(0, 3);
        String mnc = simOperator.substring(3);
        logger.d(TAG, "Parsed MCC/MNC from SIM operator: mcc=" + mcc + ", mnc=" + mnc);

        // 2. Construct Entitlement Server URL
        // Format: https://aes.mnc<MNC>.mcc<MCC>.pub.3gppnetwork.org/cred_service
        // Note: MNC must be 3 digits (padded with 0 if needed)
        String mnc3 = mnc.length() == 2 ? "0" + mnc : mnc;
        String fallbackUrl = String.format("https://aes.mnc%s.mcc%s.pub.3gppnetwork.org/cred_service", mnc3, mcc);
        EntitlementEndpoint endpoint = resolveEntitlementUrl(subId, fallbackUrl);
        String urlString = endpoint.url;
        logger.d(TAG, "Entitlement URL: " + urlString + " (simOperator=" + simOperator + ")");
        if (!endpoint.fromCarrierConfig) {
            // For Jibe carriers without TS.43 (like Spusu):
            // For Jibe carriers without TS.43: return INELIGIBLE with empty token
            // This triggers Messages to use "UpiIneligibleMsisdnToken" magic constant
            // when upi_enabled_state is 5 or 6 (which Spusu has)
            logger.w(TAG, "CarrierConfig entitlement URL missing; returning INELIGIBLE with empty token to trigger UpiIneligibleMsisdnToken");
            return EntitlementResult.ineligible("", "jibe-no-ts43");
        }

        try {
            // 3. Initial Request (expecting 401 Challenge)
            HttpURLConnection connection = (HttpURLConnection) openNetworkConnection(urlString, subId);
            connection.setConnectTimeout(TS43_TIMEOUT_MS);
            connection.setReadTimeout(TS43_TIMEOUT_MS);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", EAP_RELAY_ACCEPT);
            logger.d(TAG, "Request headers: Accept=" + EAP_RELAY_ACCEPT);

            int responseCode = connection.getResponseCode();
            logger.d(TAG, "Initial response code: " + responseCode + " (url=" + urlString + ")");

            if (responseCode == 401) {
                String wwwAuthenticate = connection.getHeaderField("WWW-Authenticate");
                logger.d(TAG, "Challenge: " + wwwAuthenticate + " (subId=" + subId + ")");

                
                if (wwwAuthenticate != null && wwwAuthenticate.contains("EAP-AKA")) {
                    // 4. Handle EAP-AKA Challenge
                    String challengeResponse = handleEapAkaChallenge(subId, wwwAuthenticate, simInfo.imsi, simInfo.simOperator);
                    if (challengeResponse != null) {
                        // 5. Authenticated Request
                        connection.disconnect();
                        connection = (HttpURLConnection) openNetworkConnection(urlString, subId);
                        connection.setConnectTimeout(TS43_TIMEOUT_MS);
                        connection.setReadTimeout(TS43_TIMEOUT_MS);
                        connection.setRequestMethod("GET");
                        connection.setRequestProperty("Authorization", challengeResponse);
                        connection.setRequestProperty("Accept", EAP_RELAY_ACCEPT);

                        responseCode = connection.getResponseCode();
                        logger.d(TAG, "Authenticated response code: " + responseCode);
                        
                        if (responseCode == 200) {
                            String responseBody = readStream(connection.getInputStream());
                            logger.d(TAG, "Response body: " + responseBody);
                            String token = extractToken(responseBody);
                            if (token == null) {
                                logger.w(TAG, "Token missing from authenticated TS.43 response");
                                return EntitlementResult.error("missing-token-authenticated");
                            }
                            return EntitlementResult.success(token);
                        }
                    }
                }
            } else if (responseCode == 200) {
                // Already authenticated?
                String responseBody = readStream(connection.getInputStream());
                String token = extractToken(responseBody);
                if (token == null) {
                    logger.w(TAG, "Token missing from TS.43 200 response");
                    return EntitlementResult.error("missing-token-200");
                }
                return EntitlementResult.success(token);
            }

            logger.w(TAG, "TS.43 check failed or not implemented by carrier (HTTP " + responseCode + ")");
            return EntitlementResult.error("ts43-http-" + responseCode);

        } catch (java.net.UnknownHostException e) {
            logger.w(TAG, "TS.43 DNS error (UnknownHost): " + e.getMessage() + ". No entitlement server.");
            return EntitlementResult.error("unknown-host");
        } catch (java.net.ConnectException e) {
            logger.w(TAG, "TS.43 Connection Refused: " + e.getMessage() + ". No entitlement server.");
            return EntitlementResult.error("connection-refused");
        } catch (java.io.FileNotFoundException e) {
            logger.w(TAG, "TS.43 HTTP 404 (Not Found): " + e.getMessage() + ". No entitlement server.");
            return EntitlementResult.error("not-found");
        } catch (java.net.SocketTimeoutException e) {
            logger.e(TAG, "TS.43 Timeout: " + e.getMessage() + ". Network might be flaky; returning error.");
            return EntitlementResult.error("timeout");
        } catch (IOException e) {
            logger.e(TAG, "TS.43 generic IO error: " + e.getMessage(), e);
            return EntitlementResult.error("io-error");
        } catch (Exception e) {
            logger.e(TAG, "TS.43 check failed with unexpected error", e);
            return EntitlementResult.error("unexpected");
        }
    }

    public String performEntitlementCheck(int subId, String phoneNumber, String requestImsi, String requestMsisdn) {
        return performEntitlementCheckResult(subId, phoneNumber, requestImsi, requestMsisdn).token;
    }

    private static class SimAuthResult {
        byte[] res;
        byte[] ck;
        byte[] ik;
        byte[] auts;
    }

    /**
     * Parse SIM EAP-AKA authentication response (3GPP TS 31.102 Section 7.1.2).
     *
     * Success format: 0xDB [len_RES] [RES] [len_CK] [CK] [len_IK] [IK]
     * Sync failure:   0xDC [len_AUTS] [AUTS]
     *
     * Note: No inner tags - just sequential length-value pairs after the status byte.
     */
    private SimAuthResult parseSimResponse(String responseBase64) {
        if (responseBase64 == null) return null;
        byte[] data = base64Decoder.decode(responseBase64);
        if (data == null || data.length < 2) return null;

        SimAuthResult result = new SimAuthResult();
        int tag = data[0] & 0xFF;
        int offset = 1;

        if (tag == 0xDB) {
            // Success: sequential LV for RES, CK, IK
            result.res = extractLv(data, offset);
            if (result.res == null) {
                logger.w(TAG, "Failed to extract RES from SIM response");
                return null;
            }
            offset += 1 + result.res.length;

            result.ck = extractLv(data, offset);
            if (result.ck == null) {
                logger.w(TAG, "Failed to extract CK from SIM response");
                return null;
            }
            offset += 1 + result.ck.length;

            result.ik = extractLv(data, offset);
            if (result.ik == null) {
                logger.w(TAG, "Failed to extract IK from SIM response");
                return null;
            }

            logger.d(TAG, "SIM auth success: RES=" + result.res.length + "B, CK=" + result.ck.length + "B, IK=" + result.ik.length + "B");
        } else if (tag == 0xDC) {
            // Sync failure: LV for AUTS
            result.auts = extractLv(data, offset);
            if (result.auts == null) {
                logger.w(TAG, "Failed to extract AUTS from SIM response");
                return null;
            }
            logger.d(TAG, "SIM auth sync failure: AUTS=" + result.auts.length + "B");
        } else {
            logger.w(TAG, "Unknown SIM response tag: 0x" + Integer.toHexString(tag));
            return null;
        }

        return result;
    }

    /** Extract a length-value pair at the given offset. Returns the value bytes, or null on error. */
    private byte[] extractLv(byte[] data, int offset) {
        if (offset >= data.length) return null;
        int len = data[offset] & 0xFF;
        int valueStart = offset + 1;
        int valueEnd = valueStart + len;
        if (valueEnd > data.length) return null;
        return Arrays.copyOfRange(data, valueStart, valueEnd);
    }

    private byte[] calculateMac(byte[] k_aut, byte[] packet) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(k_aut, "HmacSHA1"));
            return mac.doFinal(packet);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            logger.e(TAG, "Failed to calculate MAC", e);
            return null;
        }
    }

    /**
     * FIPS 186-2 Change Notice 1 PRF (Appendix 3.1).
     * Used by EAP-AKA (RFC 4187 Section 7) to derive key material from MK.
     * Produces 160 bytes: K_encr(16) + K_aut(16) + MSK(64) + EMSK(64).
     */
    private byte[] fips186Prf(byte[] xKey) {
        // SHA-1 initial hash values (H0-H4)
        final int[] H = {0x67452301, 0xEFCDAB89, 0x98BADCFE, 0x10325476, 0xC3D2E1F0};

        byte[] result = new byte[160]; // 8 iterations * 20 bytes each
        byte[] xKeyPadded = new byte[64];
        System.arraycopy(xKey, 0, xKeyPadded, 0, Math.min(xKey.length, 64));

        for (int iter = 0; iter < 8; iter++) {
            // Message schedule W[0..79] from xKeyPadded
            int[] w = new int[80];
            for (int i = 0; i < 16; i++) {
                w[i] = ((xKeyPadded[i * 4] & 0xFF) << 24)
                      | ((xKeyPadded[i * 4 + 1] & 0xFF) << 16)
                      | ((xKeyPadded[i * 4 + 2] & 0xFF) << 8)
                      | (xKeyPadded[i * 4 + 3] & 0xFF);
            }
            for (int i = 16; i < 80; i++) {
                w[i] = Integer.rotateLeft(w[i - 3] ^ w[i - 8] ^ w[i - 14] ^ w[i - 16], 1);
            }

            // SHA-1 compression with H[] as IV
            int a = H[0], b = H[1], c = H[2], d = H[3], e = H[4];
            for (int i = 0; i < 80; i++) {
                int f, k;
                if (i < 20) { f = (b & c) | (~b & d); k = 0x5A827999; }
                else if (i < 40) { f = b ^ c ^ d; k = 0x6ED9EBA1; }
                else if (i < 60) { f = (b & c) | (b & d) | (c & d); k = 0x8F1BBCDC; }
                else { f = b ^ c ^ d; k = 0xCA62C1D6; }
                int temp = Integer.rotateLeft(a, 5) + f + e + k + w[i];
                e = d; d = c; c = Integer.rotateLeft(b, 30); b = a; a = temp;
            }

            // Output = H[] + {a,b,c,d,e} (without adding back to H[])
            int[] out = {H[0] + a, H[1] + b, H[2] + c, H[3] + d, H[4] + e};
            for (int i = 0; i < 5; i++) {
                int off = iter * 20 + i * 4;
                result[off]     = (byte) (out[i] >> 24);
                result[off + 1] = (byte) (out[i] >> 16);
                result[off + 2] = (byte) (out[i] >> 8);
                result[off + 3] = (byte) out[i];
            }

            // xKey += output (last 20 bytes) with carry, per FIPS 186-2
            long carry = 0;
            int outStart = iter * 20;
            for (int i = 19; i >= 0; i--) {
                carry += (xKeyPadded[i] & 0xFFL) + (result[outStart + i] & 0xFFL);
                xKeyPadded[i] = (byte) carry;
                carry >>= 8;
            }
        }

        return result;
    }

    /**
     * Derive EAP-AKA keys from MK using FIPS 186-2 PRF (RFC 4187 Section 7).
     * Returns: K_encr(16) + K_aut(16) + MSK(64) + EMSK(64) = 160 bytes.
     */
    private byte[] deriveKeys(byte[] mk) {
        return fips186Prf(mk);
    }

    private String getNai(String imsi, String mcc, String mnc, String realm) {
        // 0<IMSI>@<realm>
        // Default realm: nai.epc.mnc<MNC>.mcc<MCC>.3gppnetwork.org (3GPP TS 23.003)
        // MNC must be 3 digits in the domain
        String mnc3 = mnc.length() == 2 ? "0" + mnc : mnc;
        String effectiveRealm;
        if (realm != null && !realm.isEmpty() && !realm.equals("nai.epc")
                && realm.contains(".mnc") && realm.contains(".mcc") && realm.contains("3gppnetwork.org")) {
            effectiveRealm = realm;
        } else if (realm != null && !realm.isEmpty() && !realm.equals("nai.epc")) {
            effectiveRealm = realm;
        } else {
            effectiveRealm = String.format("nai.epc.mnc%s.mcc%s.3gppnetwork.org", mnc3, mcc);
        }
        String nai = "0" + imsi + "@" + effectiveRealm;
        logger.d(TAG, "Derived NAI: " + nai + (realm != null ? " (server realm=" + realm + ")" : " (default realm)"));
        return nai;
    }

    private Ts43SimInfo resolveSimInfo(int subId, String requestImsi, String requestMsisdn) {
        String simOperator = null;
        String networkOperator = null;
        String imsi = requestImsi;

        // CRITICAL FIX: When subId is 0 or invalid, we MUST derive simOperator from the IMSI prefix
        // because TelephonyManager.getSimOperator() returns the DEFAULT SIM, not the target SIM.
        // IMSI format: MCC (3 digits) + MNC (2-3 digits) + MSIN (remaining)
        // For Austria: 23203=Magenta, 23217=Spusu
        
        if (subId > 0) {
            try {
                TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
                if (tm != null) {
                    TelephonyManager tmSub = tm.createForSubscriptionId(subId);
                    simOperator = tmSub.getSimOperator();
                    networkOperator = tmSub.getNetworkOperator();
                    if (imsi == null || imsi.isEmpty()) {
                        try {
                            imsi = tmSub.getSubscriberId();
                        } catch (SecurityException e) {
                            logger.w(TAG, "Permission denied for subscriber ID (subId)", e);
                        }
                    }
                }
            } catch (Exception e) {
                logger.w(TAG, "Failed to read SIM info for subId", e);
            }
        }

        // If we have an IMSI from the request, derive simOperator from it (most reliable)
        if (imsi != null && imsi.length() >= 5) {
            // Try 3-digit MNC first (MCC=3, MNC=3)
            String derivedOperator6 = imsi.substring(0, 6);
            String derivedOperator5 = imsi.substring(0, 5);
            
            // Austrian carriers use 2-digit MNC (e.g., 23217 for Spusu, 23203 for Magenta)
            // We'll use 5 digits (MCC+2-digit MNC) as the primary assumption
            String derivedOperator = derivedOperator5;
            
            // Log what we derived
            logger.d(TAG, "Derived simOperator from IMSI: " + derivedOperator + " (IMSI prefix)");
            
            // Override simOperator if we derived it from IMSI (more reliable than subId=0 lookup)
            if (simOperator == null || simOperator.length() < 5 || subId <= 0) {
                logger.i(TAG, "Using IMSI-derived operator " + derivedOperator + " instead of " + simOperator + " (subId=" + subId + ")");
                simOperator = derivedOperator;
            }
        }

        if (simOperator == null || simOperator.length() < 5) {
            simOperator = simAuthProvider.getSimOperator();
            logger.d(TAG, "Fallback to default simOperator: " + simOperator);
        }

        if (networkOperator == null || networkOperator.length() < 5) {
            networkOperator = simAuthProvider.getNetworkOperator();
        }

        if (imsi == null || imsi.isEmpty()) {
            imsi = simAuthProvider.getSubscriberId();
        }

        if (simOperator == null || simOperator.length() < 5) {
            logger.e(TAG, "SIM operator missing or invalid");
            return null;
        }

        if (imsi == null || imsi.isEmpty()) {
            logger.e(TAG, "IMSI missing from SIM info");
            return null;
        }

        if (requestMsisdn != null && requestMsisdn.startsWith("+")) {
            logger.d(TAG, "Request MSISDN present: " + requestMsisdn);
        }

        logger.d(TAG, "Resolved SIM info: imsi=" + redact(imsi)
                + ", simOperator=" + simOperator
                + ", networkOperator=" + networkOperator
                + ", subId=" + subId);
        return new Ts43SimInfo(imsi, simOperator);
    }

    private String redact(String value) {
        if (value == null || value.length() < 6) return "<redacted>";
        return value.substring(0, 3) + "..." + value.substring(value.length() - 3);
    }

    private static final String ENTITLEMENT_URL_KEY = "entitlement_server_url_string";

    private EntitlementEndpoint resolveEntitlementUrl(int subId, String fallbackUrl) {
        try {
            CarrierConfigManager manager = (CarrierConfigManager) context.getSystemService(Context.CARRIER_CONFIG_SERVICE);
            if (manager != null) {
                android.os.PersistableBundle bundle = manager.getConfigForSubId(subId);
                if (bundle != null) {
                    String entitlementUrl = bundle.getString(ENTITLEMENT_URL_KEY);
                    if (entitlementUrl != null && !entitlementUrl.isEmpty()) {
                        logger.d(TAG, "CarrierConfig entitlement URL: " + entitlementUrl + " (subId=" + subId + ")");
                        return new EntitlementEndpoint(entitlementUrl, true);
                    }
                    logger.d(TAG, "CarrierConfig entitlement URL empty (subId=" + subId + ")");
                } else {
                    logger.d(TAG, "CarrierConfig bundle null (subId=" + subId + ")");
                }
            } else {
                logger.d(TAG, "CarrierConfig manager null");
            }
        } catch (Exception e) {
            logger.w(TAG, "Failed to read carrier config entitlement URL", e);
        }

        logger.d(TAG, "CarrierConfig entitlement URL empty, using fallback: " + fallbackUrl);
        return new EntitlementEndpoint(fallbackUrl, false);
    }

    private HttpURLConnection openNetworkConnection(String urlString, int subId) throws IOException {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        int mode = getNetworkBindMode();
        if (cm != null && mode != NETWORK_BIND_NONE) {
            Network network = findNetworkForMode(cm, mode);
            if (network != null) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                LinkProperties linkProps = cm.getLinkProperties(network);
                logger.d(TAG, "Binding TS.43 request to network mode=" + mode
                        + ", transports=" + caps
                        + ", ifaces=" + (linkProps != null ? linkProps.getInterfaceName() : "null")
                        + ", subId=" + subId);
                return (HttpURLConnection) network.openConnection(new URL(urlString));
            }
            logger.w(TAG, "Requested network mode not available, falling back to default network (mode=" + mode + ")");
        }

        logActiveNetwork(cm, subId);
        return (HttpURLConnection) new URL(urlString).openConnection();
    }

    private void logActiveNetwork(ConnectivityManager cm, int subId) {
        if (cm == null) {
            logger.w(TAG, "ConnectivityManager unavailable (subId=" + subId + ")");
            return;
        }
        Network network = cm.getActiveNetwork();
        NetworkCapabilities caps = cm.getNetworkCapabilities(network);
        LinkProperties linkProps = cm.getLinkProperties(network);
        logger.d(TAG, "Active network: " + network
                + ", transports=" + caps
                + ", ifaces=" + (linkProps != null ? linkProps.getInterfaceName() : "null")
                + ", subId=" + subId);
    }

    private int getNetworkBindMode() {
        // 0 = default network, 1 = Wi-Fi only, 2 = cellular only
        try {
            String value = Settings.Global.getString(context.getContentResolver(), "microg_ts43_network_mode");
            if ("wifi".equalsIgnoreCase(value)) {
                return NETWORK_BIND_WIFI;
            }
            if ("cellular".equalsIgnoreCase(value)) {
                return NETWORK_BIND_CELLULAR;
            }
        } catch (Exception e) {
            logger.w(TAG, "Failed to read microg_ts43_network_mode", e);
        }
        return NETWORK_BIND_NONE;
    }

    private Network findNetworkForMode(ConnectivityManager cm, int mode) {
        Network[] networks = cm.getAllNetworks();
        if (networks == null) return null;
        for (Network network : networks) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            if (caps == null) continue;
            if (mode == NETWORK_BIND_WIFI && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return network;
            }
            if (mode == NETWORK_BIND_CELLULAR && caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                return network;
            }
        }
        return null;
    }

    String handleEapAkaChallenge(int subId, String wwwAuthenticate, String imsi, String simOperator) {
        logger.d(TAG, "Handling EAP-AKA challenge: " + wwwAuthenticate);
        if (imsi == null || simOperator == null || simOperator.length() < 5) {
            logger.e(TAG, "Missing IMSI or SIM operator for EAP-AKA");
            return null;
        }
        
        // Extract nonce (EAP payload)
        // Header format: EAP-AKA realm="...", nonce="..."
        Pattern p = Pattern.compile("nonce=\"([^\"]+)\"");
        Matcher m = p.matcher(wwwAuthenticate);
        if (!m.find()) {
            logger.e(TAG, "No nonce found in WWW-Authenticate header");
            return null;
        }
        
        String nonceBase64 = m.group(1);
        byte[] eapPayload = base64Decoder.decode(nonceBase64);
        
        // Parse EAP packet
        if (eapPayload.length < 5 || eapPayload[0] != 1 || eapPayload[4] != EAP_AKA_TYPE) {
            logger.e(TAG, "Invalid EAP packet");
            return null;
        }
        
        int id = eapPayload[1] & 0xFF;
        int subtype = eapPayload[5] & 0xFF;
        
        if (subtype != EAP_AKA_SUBTYPE_CHALLENGE) {
            logger.e(TAG, "Unexpected EAP-AKA subtype: " + subtype);
            return null;
        }
        
        // Extract Attributes (RAND, AUTN)
        byte[] rand = null;
        byte[] autn = null;
        
        int offset = 8; // Skip header (Code, ID, Len, Type, Subtype, Reserved)
        while (offset < eapPayload.length) {
            int attrType = eapPayload[offset] & 0xFF;
            int attrLen = (eapPayload[offset + 1] & 0xFF) * 4; // Length in 4-byte words
            
            if (attrType == AT_RAND) {
                rand = new byte[attrLen - 4]; // Subtract header (2 bytes) + reserved (2 bytes)
                System.arraycopy(eapPayload, offset + 4, rand, 0, rand.length);
            } else if (attrType == AT_AUTN) {
                autn = new byte[attrLen - 4];
                System.arraycopy(eapPayload, offset + 4, autn, 0, autn.length);
            }
            
            offset += attrLen;
        }
        
        if (rand == null || autn == null) {
            logger.e(TAG, "Missing RAND or AUTN in EAP-AKA challenge");
            return null;
        }
        
        // Calculate Response using SIM
        String eapResponse = generateEapAkaResponse(subId, id, rand, autn, imsi, simOperator);
        if (eapResponse == null) {
            return null;
        }
        
        return "EAP-AKA response=\"" + eapResponse + "\"";
    }

    /**
     * Generates the EAP-AKA response packet (Base64 encoded).
     */
    private String generateEapAkaResponse(int subId, int id, byte[] rand, byte[] autn, String imsi, String simOperator) {
        // 1. Get SIM Authentication
        // Input format for getIccAuthentication:
        // Length of RAND (1 byte) + RAND + Length of AUTN (1 byte) + AUTN
        byte[] authData = new byte[1 + rand.length + 1 + autn.length];
        authData[0] = (byte) rand.length;
        System.arraycopy(rand, 0, authData, 1, rand.length);
        authData[1 + rand.length] = (byte) autn.length;
        System.arraycopy(autn, 0, authData, 1 + rand.length + 1, autn.length);
        
        String authDataStr = base64Decoder.encodeToString(authData);
        logger.d(TAG, "Calling getIccAuthentication with: " + authDataStr);
        
        String simResponseBase64;
        try {
            simResponseBase64 = simAuthProvider.getIccAuthentication(
                TelephonyManager.APPTYPE_USIM,
                TelephonyManager.AUTHTYPE_EAP_AKA,
                authDataStr
            );
        } catch (SecurityException e) {
            logger.e(TAG, "Permission denied for getIccAuthentication", e);
            return null;
        }

        if (simResponseBase64 == null) {
            logger.e(TAG, "getIccAuthentication returned null");
            return null;
        }

        // 2. Parse SIM Response
        SimAuthResult authResult = parseSimResponse(simResponseBase64);
        if (authResult == null) {
            logger.e(TAG, "Failed to parse SIM response");
            return null;
        }

        if (authResult.auts != null) {
            // Synchronization Failure
            // Construct EAP-Response/AKA-Synchronization-Failure
            // Code=2, ID=id, Type=23, Subtype=4 (Sync-Failure)
            // Attributes: AT_AUTS
            return constructSyncFailure(id, authResult.auts);
        }

        if (authResult.res == null || authResult.ck == null || authResult.ik == null) {
            logger.e(TAG, "SIM response missing RES, CK, or IK");
            return null;
        }

        // 3. Derive Keys
        if (imsi == null) {
            logger.e(TAG, "Missing IMSI for key derivation");
            return null;
        }
        if (simOperator == null || simOperator.length() < 5) {
            logger.e(TAG, "Invalid SIM operator for key derivation");
            return null;
        }
        String mcc = simOperator.substring(0, 3);
        String mnc = simOperator.substring(3);
        String identity = getNai(imsi, mcc, mnc, null);

        byte[] mkInput = new byte[identity.length() + authResult.ik.length + authResult.ck.length];
        int pos = 0;
        System.arraycopy(identity.getBytes(StandardCharsets.UTF_8), 0, mkInput, pos, identity.length()); pos += identity.length();
        System.arraycopy(authResult.ik, 0, mkInput, pos, authResult.ik.length); pos += authResult.ik.length;
        System.arraycopy(authResult.ck, 0, mkInput, pos, authResult.ck.length);

        byte[] mk;
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            mk = sha1.digest(mkInput);
        } catch (NoSuchAlgorithmException e) {
            logger.e(TAG, "SHA-1 not supported", e);
            return null;
        }

        // Derive keys using FIPS 186-2 PRF (RFC 4187 Section 7)
        // Output: K_encr(16) + K_aut(16) + MSK(64) + EMSK(64) = 160 bytes
        byte[] keyMaterial = deriveKeys(mk);
        if (keyMaterial == null) return null;
        byte[] k_aut = Arrays.copyOfRange(keyMaterial, 16, 32); // K_aut = bytes 16-31 (128 bits)

        // 4. Construct EAP Response Packet
        // Code=2 (Response), ID=id, Type=23, Subtype=1 (Challenge)
        // Attributes: AT_RES, AT_MAC
        
        // AT_RES: Type 3, Length (1 + res_len/4), RES (padded)
        int resLen = authResult.res.length;
        int resPad = (4 - (resLen % 4)) % 4;
        int atResLen = 4 + resLen + resPad; // Header + RES + Pad
        
        // AT_MAC: Type 11, Length 5 (20 bytes), MAC (16 bytes) + Reserved (2 bytes) ?
        // RFC 4187: AT_MAC Value field is 16 bytes.
        // Format: Type(1) + Length(1) + Reserved(2) + MAC(16) = 20 bytes.
        int atMacLen = 20;
        
        int totalLen = 8 + atResLen + atMacLen; // Header(8) + AT_RES + AT_MAC
        
        byte[] packet = new byte[totalLen];
        pos = 0;
        
        // Header
        packet[pos++] = 0x02; // Code: Response
        packet[pos++] = (byte) id;
        packet[pos++] = (byte) (totalLen >> 8);
        packet[pos++] = (byte) totalLen;
        packet[pos++] = (byte) EAP_AKA_TYPE;
        packet[pos++] = 0x01; // Subtype: Challenge
        packet[pos++] = 0x00; // Reserved
        packet[pos++] = 0x00; // Reserved
        
        // AT_RES
        packet[pos++] = (byte) AT_RES;
        packet[pos++] = (byte) (atResLen / 4);
        // RES Length in bits, 2-byte big-endian (RFC 4187 Section 10.3)
        int resBits = resLen * 8;
        packet[pos++] = (byte) (resBits >> 8);
        packet[pos++] = (byte) resBits;
        System.arraycopy(authResult.res, 0, packet, pos, resLen);
        pos += resLen;
        pos += resPad; // Skip padding (already 0)
        
        // AT_MAC (initialized to 0)
        int macOffset = pos;
        packet[pos++] = (byte) AT_MAC;
        packet[pos++] = 0x05; // Length 5 words
        packet[pos++] = 0x00; // Reserved
        packet[pos++] = 0x00; // Reserved
        pos += 16; // MAC placeholder
        
        // Calculate MAC
        byte[] mac = calculateMac(k_aut, packet);
        if (mac == null) return null;
        
        // Copy first 16 bytes of MAC to packet
        System.arraycopy(mac, 0, packet, macOffset + 4, 16);
        
        return base64Decoder.encodeToString(packet);
    }

    private String constructSyncFailure(int id, byte[] auts) {
        // AT_AUTS: Type 4, Length 4 (16 bytes), AUTS (14 bytes)
        // Format: Type(1) + Length(1) + AUTS(14) = 16 bytes
        int atAutsLen = 16;
        int totalLen = 8 + atAutsLen;
        
        byte[] packet = new byte[totalLen];
        int pos = 0;
        
        // Header
        packet[pos++] = 0x02; // Code: Response
        packet[pos++] = (byte) id;
        packet[pos++] = (byte) (totalLen >> 8);
        packet[pos++] = (byte) totalLen;
        packet[pos++] = (byte) EAP_AKA_TYPE;
        packet[pos++] = 0x04; // Subtype: Synchronization-Failure
        packet[pos++] = 0x00; // Reserved
        packet[pos++] = 0x00; // Reserved
        
        // AT_AUTS
        packet[pos++] = 0x04; // Type: AT_AUTS
        packet[pos++] = 0x04; // Length: 4 words
        System.arraycopy(auts, 0, packet, pos, auts.length);
        
        return base64Decoder.encodeToString(packet);
    }



    private String extractToken(String responseBody) {
        // Simple regex to find token in JSON/XML
        // JSON: "token": "..."
        // XML: <token>...</token>
        Pattern p = Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"|<token>([^<]+)</token>");
        Matcher m = p.matcher(responseBody);
        if (m.find()) {
            return m.group(1) != null ? m.group(1) : m.group(2);
        }
        return null;
    }

    private String readStream(InputStream in) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        return sb.toString();
    }
}
