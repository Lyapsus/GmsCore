/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.constellation;

import android.content.Context;

import org.junit.Test;
import static org.junit.Assert.*;

import java.util.Arrays;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

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
            public String getSubscriberId() {
                return "123456789012345";
            }

            @Override
            public String getIccAuthentication(int appType, int authType, String data) {
                // Verify input data
                byte[] decoded = java.util.Base64.getDecoder().decode(data);
                assertEquals(34, decoded.length);
                
                // Construct SIM Response (Success)
                // Tag 0xDB
                // RES (0xDC): 8 bytes
                // CK (0xDD): 16 bytes
                // IK (0xDE): 16 bytes
                
                byte[] res = new byte[8]; Arrays.fill(res, (byte) 0x11);
                byte[] ck = new byte[16]; Arrays.fill(ck, (byte) 0x22);
                byte[] ik = new byte[16]; Arrays.fill(ik, (byte) 0x33);
                
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                bos.write(0xDB); // Success tag
                
                // Content
                ByteArrayOutputStream content = new ByteArrayOutputStream();
                
                // RES
                content.write(0xDC);
                content.write(res.length);
                try { content.write(res); } catch (IOException e) {}
                
                // CK
                content.write(0xDD);
                content.write(ck.length);
                try { content.write(ck); } catch (IOException e) {}
                
                // IK
                content.write(0xDE);
                content.write(ik.length);
                try { content.write(ik); } catch (IOException e) {}
                
                byte[] contentBytes = content.toByteArray();
                bos.write(contentBytes.length); // Assuming length < 128 for test
                try { bos.write(contentBytes); } catch (IOException e) {}
                
                return java.util.Base64.getEncoder().encodeToString(bos.toByteArray());
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
        assertTrue(response.startsWith("EAP-AKA response=\""));
        
        String responseBase64 = response.substring(18, response.length() - 1);
        byte[] responseBytes = java.util.Base64.getDecoder().decode(responseBase64);
        
        // Verify Header
        assertEquals(0x02, responseBytes[0]); // Code: Response
        assertEquals(0x01, responseBytes[1]); // ID: 1
        assertEquals(23, responseBytes[4]); // Type: EAP-AKA
        assertEquals(1, responseBytes[5]); // Subtype: Challenge
        
        // Verify AT_RES (Type 3)
        // Header length is 8 bytes
        pos = 8;
        assertEquals(3, responseBytes[pos]); // Type: AT_RES
        // Length should be 1 + (8+0)/4 = 3 words
        assertEquals(3, responseBytes[pos+1]); 
        
        // Verify AT_MAC (Type 11)
        pos += 12; // 3 words * 4 bytes
        assertEquals(11, responseBytes[pos]); // Type: AT_MAC
        assertEquals(5, responseBytes[pos+1]); // Length: 5 words
    }
}
