package com.google.android.gms.constellation.internal;

import com.google.android.gms.common.api.ApiMetadata;
import com.google.android.gms.common.api.Status;
import com.google.android.gms.constellation.PhoneNumberInfo;
import com.google.android.gms.constellation.GetIidTokenResponse;
import com.google.android.gms.constellation.GetPnvCapabilitiesResponse;
import com.google.android.gms.constellation.VerifyPhoneNumberResponse;

/**
 * Constellation callbacks interface.
 * 
 * Method names and transaction codes from Messages APK decompilation:
 * - Code 1: onPhoneNumberVerified (for V1/SingleUse flows)
 * - Code 2: onPhoneNumberVerificationsCompleted (for verifyPhoneNumber)
 * - Code 3: onIidTokenGenerated
 * - Code 4: onGetPnvCapabilitiesCompleted
 */
oneway interface IConstellationCallbacks {
    // Transaction code 1: Phone numbers verified (from V1/SingleUse flows)
    void onPhoneNumberVerified(in Status status, in List<PhoneNumberInfo> phoneNumbers, in ApiMetadata metadata) = 0;

    // Transaction code 2: Verify phone number completed (for code 3 request)
    void onPhoneNumberVerificationsCompleted(in Status status, in VerifyPhoneNumberResponse response, in ApiMetadata metadata) = 1;

    // Transaction code 3: IID token generated
    void onIidTokenGenerated(in Status status, in GetIidTokenResponse response, in ApiMetadata metadata) = 2;

    // Transaction code 4: Get PNV capabilities completed
    void onGetPnvCapabilitiesCompleted(in Status status, in GetPnvCapabilitiesResponse response, in ApiMetadata metadata) = 3;
}
