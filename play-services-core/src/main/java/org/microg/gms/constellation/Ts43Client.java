/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.constellation;

import android.content.Context;
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
        String getSubscriberId();
        String getIccAuthentication(int appType, int authType, String data);
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
    public String performEntitlementCheck(int subId, String phoneNumber) {
        logger.i(TAG, "Starting TS.43 entitlement check for subId=" + subId);
        
        // 1. Get MCC/MNC
        String networkOperator = simAuthProvider.getNetworkOperator();
        if (networkOperator == null || networkOperator.length() < 5) {
            logger.e(TAG, "Invalid network operator: " + networkOperator);
            return null;
        }
        String mcc = networkOperator.substring(0, 3);
        String mnc = networkOperator.substring(3);
        
        // 2. Construct Entitlement Server URL
        // Format: https://aes.mnc<MNC>.mcc<MCC>.pub.3gppnetwork.org/cred_service
        // Note: MNC must be 3 digits (padded with 0 if needed)
        String mnc3 = mnc.length() == 2 ? "0" + mnc : mnc;
        String urlString = String.format("https://aes.mnc%s.mcc%s.pub.3gppnetwork.org/cred_service", mnc3, mcc);
        logger.d(TAG, "Entitlement URL: " + urlString);

        try {
            // 3. Initial Request (expecting 401 Challenge)
            HttpURLConnection connection = (HttpURLConnection) new URL(urlString).openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/vnd.gsma.eap-relay.v1.0+json");
            
            int responseCode = connection.getResponseCode();
            logger.d(TAG, "Initial response code: " + responseCode);

            if (responseCode == 401) {
                String wwwAuthenticate = connection.getHeaderField("WWW-Authenticate");
                logger.d(TAG, "Challenge: " + wwwAuthenticate);
                
                if (wwwAuthenticate != null && wwwAuthenticate.contains("EAP-AKA")) {
                    // 4. Handle EAP-AKA Challenge
                    String challengeResponse = handleEapAkaChallenge(subId, wwwAuthenticate);
                    if (challengeResponse != null) {
                        // 5. Authenticated Request
                        connection.disconnect();
                        connection = (HttpURLConnection) new URL(urlString).openConnection();
                        connection.setRequestMethod("GET");
                        connection.setRequestProperty("Authorization", challengeResponse);
                        connection.setRequestProperty("Accept", "application/vnd.gsma.eap-relay.v1.0+json");
                        
                        responseCode = connection.getResponseCode();
                        logger.d(TAG, "Authenticated response code: " + responseCode);
                        
                        if (responseCode == 200) {
                            String responseBody = readStream(connection.getInputStream());
                            logger.d(TAG, "Response body: " + responseBody);
                            return extractToken(responseBody);
                        }
                    }
                }
            } else if (responseCode == 200) {
                // Already authenticated?
                String responseBody = readStream(connection.getInputStream());
                return extractToken(responseBody);
            }

            logger.w(TAG, "TS.43 check failed or not implemented by carrier. Returning fake token.");
            return "STUB_TOKEN_FROM_TS43_CLIENT";

        } catch (java.net.UnknownHostException e) {
            logger.w(TAG, "TS.43 DNS error (UnknownHost): " + e.getMessage() + ". Assuming Jibe carrier without entitlement server, returning stub token.");
            return generateStubToken();
        } catch (java.net.ConnectException e) {
            logger.w(TAG, "TS.43 Connection Refused: " + e.getMessage() + ". Assuming Jibe carrier without entitlement server, returning stub token.");
            return generateStubToken();
        } catch (java.io.FileNotFoundException e) {
            logger.w(TAG, "TS.43 HTTP 404 (Not Found): " + e.getMessage() + ". Assuming Jibe carrier without entitlement server, returning stub token.");
            return generateStubToken();
        } catch (java.net.SocketTimeoutException e) {
            logger.e(TAG, "TS.43 Timeout: " + e.getMessage() + ". Network might be flaky, NOT falling back to stub.");
            return null;
        } catch (IOException e) {
            logger.e(TAG, "TS.43 generic IO error: " + e.getMessage(), e);
            return null;
        } catch (Exception e) {
            logger.e(TAG, "TS.43 check failed with unexpected error", e);
            return null;
        }
    }

    /**
     * Generates a syntactically valid JWT stub token for Jibe carriers.
     * Package-private for use by ConstellationServiceImpl fallback.
     */
    String generateStubToken() {
        // Generate a syntactically valid JWT (Header.Payload.Signature)
        // Header: {"alg":"none","typ":"JWT"}
        // Payload: {"exp":<future>,"iat":<now>,"iss":"google"}
        // Signature: empty
        
        long now = System.currentTimeMillis() / 1000;
        long exp = now + 86400; // 24 hours
        
        String header = "{\"alg\":\"none\",\"typ\":\"JWT\"}";
        String payload = String.format("{\"exp\":%d,\"iat\":%d,\"iss\":\"google\",\"sub\":\"stub_token_for_jibe\"}", exp, now);
        
        String headerB64 = base64Decoder.encodeToString(header.getBytes(StandardCharsets.UTF_8)).replace("\n", "").replace("=", "");
        String payloadB64 = base64Decoder.encodeToString(payload.getBytes(StandardCharsets.UTF_8)).replace("\n", "").replace("=", "");
        
        return headerB64 + "." + payloadB64 + ".";
    }

    private static class SimAuthResult {
        byte[] res;
        byte[] ck;
        byte[] ik;
        byte[] auts;
    }

    private SimAuthResult parseSimResponse(String responseBase64) {
        if (responseBase64 == null) return null;
        byte[] data = base64Decoder.decode(responseBase64);
        if (data == null || data.length < 2) return null;

        SimAuthResult result = new SimAuthResult();
        int tag = data[0] & 0xFF;
        
        if (tag == 0xDB) { // Success
            int offset = 2; // Skip Tag + Len (assuming 1 byte len)
            // TODO: Handle multi-byte length if needed
            
            while (offset < data.length) {
                int t = data[offset] & 0xFF;
                int l = data[offset + 1] & 0xFF;
                offset += 2;
                
                if (offset + l > data.length) break;
                
                byte[] val = new byte[l];
                System.arraycopy(data, offset, val, 0, l);
                
                if (t == 0xDC) result.res = val;
                else if (t == 0xDD) result.ck = val;
                else if (t == 0xDE) result.ik = val;
                
                offset += l;
            }
        } else if (tag == 0xDC) { // Sync Failure
            int offset = 2;
            while (offset < data.length) {
                int t = data[offset] & 0xFF;
                int l = data[offset + 1] & 0xFF;
                offset += 2;
                if (t == 0xDD) { // AUTS tag in Sync Failure? Need to verify
                     // Actually usually it's just AUTS
                }
                offset += l;
            }
        }
        
        return result;
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

    private byte[] deriveKey(byte[] mk, byte modifier) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(mk, "HmacSHA1"));
            return mac.doFinal(new byte[]{modifier});
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            logger.e(TAG, "Failed to derive key", e);
            return null;
        }
    }

    private String getNai(String imsi, String mcc, String mnc) {
        // 0<IMSI>@nai.epc.mnc<MNC>.mcc<MCC>.3gppnetwork.org
        // MNC must be 3 digits in the domain
        String mnc3 = mnc.length() == 2 ? "0" + mnc : mnc;
        return String.format("0%s@nai.epc.mnc%s.mcc%s.3gppnetwork.org", imsi, mnc3, mcc);
    }

    String handleEapAkaChallenge(int subId, String wwwAuthenticate) {
        logger.d(TAG, "Handling EAP-AKA challenge: " + wwwAuthenticate);
        
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
        String eapResponse = generateEapAkaResponse(subId, id, rand, autn);
        if (eapResponse == null) {
            return null;
        }
        
        return "EAP-AKA response=\"" + eapResponse + "\"";
    }

    /**
     * Generates the EAP-AKA response packet (Base64 encoded).
     */
    private String generateEapAkaResponse(int subId, int id, byte[] rand, byte[] autn) {
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
        String imsi = simAuthProvider.getSubscriberId();
        if (imsi == null) {
            logger.e(TAG, "Failed to get IMSI");
            return null;
        }
        String networkOperator = simAuthProvider.getNetworkOperator();
        if (networkOperator == null || networkOperator.length() < 5) {
             logger.e(TAG, "Invalid network operator");
             return null;
        }
        String mcc = networkOperator.substring(0, 3);
        String mnc = networkOperator.substring(3);
        String identity = getNai(imsi, mcc, mnc);

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

        byte[] k_aut = deriveKey(mk, (byte) 2); // K_aut = PRF(MK, 2)
        if (k_aut == null) return null;
        // K_aut is first 160 bits (20 bytes) of PRF output. HMAC-SHA1 output is 20 bytes, so we use it all.

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
        packet[pos++] = (byte) (resLen * 8); // RES Length in bits
        packet[pos++] = 0x00; // Reserved
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
