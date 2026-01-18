/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.constellation;

import android.content.Context;

import org.junit.Test;
import static org.junit.Assert.*;

import java.util.Arrays;

public class Ts43ClientTest {

    // Mock Base64Decoder using java.util.Base64
    private final Ts43Client.Base64Decoder base64Decoder = new Ts43Client.Base64Decoder() {
        @Override
        public byte[] decode(String str) {
            return java.util.Base64.getDecoder().decode(str);
        }

        @Override
        public String encodeToString(byte[] input) {
            return java.util.Base64.getEncoder().encodeToString(input);
        }
    };

    // Mock Logger
    private final Ts43Client.Logger logger = new Ts43Client.Logger() {
        @Override public void d(String tag, String msg) { System.out.println("D/" + tag + ": " + msg); }
        @Override public void i(String tag, String msg) { System.out.println("I/" + tag + ": " + msg); }
        @Override public void w(String tag, String msg) { System.out.println("W/" + tag + ": " + msg); }
        @Override public void w(String tag, String msg, Throwable tr) { System.out.println("W/" + tag + ": " + msg); tr.printStackTrace(); }
        @Override public void e(String tag, String msg) { System.out.println("E/" + tag + ": " + msg); }
        @Override public void e(String tag, String msg, Throwable tr) { System.out.println("E/" + tag + ": " + msg); tr.printStackTrace(); }
    };

    @Test
    public void testHandleEapAkaChallenge() {
        // Mock SimAuthProvider
        Ts43Client.SimAuthProvider simAuthProvider = new Ts43Client.SimAuthProvider() {
            @Override
            public String getNetworkOperator() {
                return "12345";
            }

            @Override
            public String getIccAuthentication(int appType, int authType, String data) {
                // Verify input data
                // data is Base64 encoded: len(RAND) + RAND + len(AUTN) + AUTN
                byte[] decoded = java.util.Base64.getDecoder().decode(data);
                assertEquals(34, decoded.length); // 1 + 16 + 1 + 16
                assertEquals(16, decoded[0]); // len(RAND)
                assertEquals(16, decoded[17]); // len(AUTN)
                
                // Return dummy response
                return "DUMMY_RES";
            }
        };

        Ts43Client client = new Ts43Client(null, simAuthProvider, base64Decoder, logger);

        // Construct EAP-AKA Challenge Packet
        // Header: Code=1, ID=1, Len=48 (0x0030), Type=23, Subtype=1, Res=0
        byte[] header = new byte[] { 0x01, 0x01, 0x00, 0x30, 0x17, 0x01, 0x00, 0x00 };
        
        // AT_RAND (Type 1, Length 5 words = 20 bytes)
        byte[] atRandHeader = new byte[] { 0x01, 0x05, 0x00, 0x00 };
        byte[] rand = new byte[16];
        Arrays.fill(rand, (byte) 0xAA);
        
        // AT_AUTN (Type 2, Length 5 words = 20 bytes)
        byte[] atAutnHeader = new byte[] { 0x02, 0x05, 0x00, 0x00 };
        byte[] autn = new byte[16];
        Arrays.fill(autn, (byte) 0xBB);

        // Assemble packet
        byte[] packet = new byte[header.length + atRandHeader.length + rand.length + atAutnHeader.length + autn.length];
        int pos = 0;
        System.arraycopy(header, 0, packet, pos, header.length); pos += header.length;
        System.arraycopy(atRandHeader, 0, packet, pos, atRandHeader.length); pos += atRandHeader.length;
        System.arraycopy(rand, 0, packet, pos, rand.length); pos += rand.length;
        System.arraycopy(atAutnHeader, 0, packet, pos, atAutnHeader.length); pos += atAutnHeader.length;
        System.arraycopy(autn, 0, packet, pos, autn.length);

        String nonce = java.util.Base64.getEncoder().encodeToString(packet);
        String challenge = "EAP-AKA realm=\"example.com\", nonce=\"" + nonce + "\"";

        String response = client.handleEapAkaChallenge(1, challenge);
        
        assertNotNull(response);
        assertTrue(response.contains("EAP-AKA response=\"DUMMY_RES\""));
    }
}
