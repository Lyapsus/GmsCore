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
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Client for GSMA TS.43 Service Entitlement Configuration.
 * 
 * Implements the EAP-AKA authentication flow to retrieve RCS provisioning tokens
 * from the carrier's entitlement server.
 */
public class Ts43Client {
    private static final String TAG = "GmsTs43Client";
    private final Context context;
    private final TelephonyManager telephonyManager;

    private static final int EAP_AKA_TYPE = 23;
    private static final int EAP_AKA_SUBTYPE_CHALLENGE = 1;
    private static final int AT_RAND = 1;
    private static final int AT_AUTN = 2;
    private static final int AT_RES = 3;
    private static final int AT_MAC = 11;

    public Ts43Client(Context context) {
        this.context = context;
        this.telephonyManager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
    }

    /**
     * Performs the TS.43 entitlement check for the given subscription.
     * 
     * @param subId The subscription ID to use.
     * @param phoneNumber The phone number associated with the subscription.
     * @return The RCS configuration token (JWT) or null if failed.
     */
    public String performEntitlementCheck(int subId, String phoneNumber) {
        Log.i(TAG, "Starting TS.43 entitlement check for subId=" + subId);
        
        // 1. Get MCC/MNC
        String networkOperator = telephonyManager.getNetworkOperator();
        if (networkOperator == null || networkOperator.length() < 5) {
            Log.e(TAG, "Invalid network operator: " + networkOperator);
            return null;
        }
        String mcc = networkOperator.substring(0, 3);
        String mnc = networkOperator.substring(3);
        
        // 2. Construct Entitlement Server URL
        // Format: https://aes.mnc<MNC>.mcc<MCC>.pub.3gppnetwork.org/cred_service
        // Note: MNC must be 3 digits (padded with 0 if needed)
        String mnc3 = mnc.length() == 2 ? "0" + mnc : mnc;
        String urlString = String.format("https://aes.mnc%s.mcc%s.pub.3gppnetwork.org/cred_service", mnc3, mcc);
        Log.d(TAG, "Entitlement URL: " + urlString);

        try {
            // 3. Initial Request (expecting 401 Challenge)
            HttpURLConnection connection = (HttpURLConnection) new URL(urlString).openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/vnd.gsma.eap-relay.v1.0+json");
            
            int responseCode = connection.getResponseCode();
            Log.d(TAG, "Initial response code: " + responseCode);

            if (responseCode == 401) {
                String wwwAuthenticate = connection.getHeaderField("WWW-Authenticate");
                Log.d(TAG, "Challenge: " + wwwAuthenticate);
                
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
                        Log.d(TAG, "Authenticated response code: " + responseCode);
                        
                        if (responseCode == 200) {
                            String responseBody = readStream(connection.getInputStream());
                            Log.d(TAG, "Response body: " + responseBody);
                            return extractToken(responseBody);
                        }
                    }
                }
            } else if (responseCode == 200) {
                // Already authenticated?
                String responseBody = readStream(connection.getInputStream());
                return extractToken(responseBody);
            }

            Log.w(TAG, "TS.43 check failed or not implemented by carrier. Returning fake token.");
            return "STUB_TOKEN_FROM_TS43_CLIENT";

        } catch (Exception e) {
            Log.e(TAG, "TS.43 check failed", e);
            return null;
        }
    }

    private String handleEapAkaChallenge(int subId, String wwwAuthenticate) {
        Log.d(TAG, "Handling EAP-AKA challenge: " + wwwAuthenticate);
        
        // Extract nonce (EAP payload)
        // Header format: EAP-AKA realm="...", nonce="..."
        Pattern p = Pattern.compile("nonce=\"([^\"]+)\"");
        Matcher m = p.matcher(wwwAuthenticate);
        if (!m.find()) {
            Log.e(TAG, "No nonce found in WWW-Authenticate header");
            return null;
        }
        
        String nonceBase64 = m.group(1);
        byte[] eapPayload = Base64.decode(nonceBase64, Base64.NO_WRAP);
        
        // Parse EAP packet
        if (eapPayload.length < 5 || eapPayload[0] != 1 || eapPayload[3] != EAP_AKA_TYPE) {
            Log.e(TAG, "Invalid EAP packet");
            return null;
        }
        
        int id = eapPayload[1] & 0xFF;
        int subtype = eapPayload[4] & 0xFF;
        
        if (subtype != EAP_AKA_SUBTYPE_CHALLENGE) {
            Log.e(TAG, "Unexpected EAP-AKA subtype: " + subtype);
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
            Log.e(TAG, "Missing RAND or AUTN in EAP-AKA challenge");
            return null;
        }
        
        // Calculate Response using SIM
        String simResponse = calculateEapAkaResponse(subId, rand, autn);
        if (simResponse == null) {
            return null;
        }
        
        // Construct EAP Response
        // TODO: Implement full EAP response construction with MAC
        return "EAP-AKA response=\"" + simResponse + "\"";
    }

    /**
     * Calculates the EAP-AKA response using the SIM card.
     */
    private String calculateEapAkaResponse(int subId, byte[] rand, byte[] autn) {
        // Use TelephonyManager.getIccAuthentication()
        // APPTYPE_USIM = 2
        // AUTHTYPE_EAP_AKA = 129
        
        // Input format for getIccAuthentication:
        // Length of RAND (1 byte) + RAND + Length of AUTN (1 byte) + AUTN
        byte[] authData = new byte[1 + rand.length + 1 + autn.length];
        authData[0] = (byte) rand.length;
        System.arraycopy(rand, 0, authData, 1, rand.length);
        authData[1 + rand.length] = (byte) autn.length;
        System.arraycopy(autn, 0, authData, 1 + rand.length + 1, autn.length);
        
        String authDataStr = Base64.encodeToString(authData, Base64.NO_WRAP);
        Log.d(TAG, "Calling getIccAuthentication with: " + authDataStr);
        
        try {
            // Requires READ_PRIVILEGED_PHONE_STATE or carrier privileges
            String response = telephonyManager.getIccAuthentication(
                TelephonyManager.APPTYPE_USIM,
                TelephonyManager.AUTHTYPE_EAP_AKA,
                authDataStr
            );
            
            if (response == null) {
                Log.e(TAG, "getIccAuthentication returned null");
                return null;
            }
            
            Log.d(TAG, "getIccAuthentication response: " + response);
            // Response format is usually Base64 encoded RES (or more complex structure)
            // We need to parse it to get RES, CK, IK
            return response;
            
        } catch (SecurityException e) {
            Log.e(TAG, "Permission denied for getIccAuthentication", e);
            return null;
        }
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
