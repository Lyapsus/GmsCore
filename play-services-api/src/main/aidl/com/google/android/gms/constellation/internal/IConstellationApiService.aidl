package com.google.android.gms.constellation.internal;

import android.os.Bundle;
import com.google.android.gms.common.api.ApiMetadata;
import com.google.android.gms.constellation.internal.IConstellationCallbacks;
import com.google.android.gms.constellation.GetIidTokenRequest;
import com.google.android.gms.constellation.GetPnvCapabilitiesRequest;
import com.google.android.gms.constellation.VerifyPhoneNumberRequest;

/**
 * Constellation API Service interface for phone number verification.
 * 
 * CRITICAL: Transaction codes from Messages decompilation (IConstellationApiService.java):
 *   - TRANSACTION_verifyPhoneNumberV1 = 1
 *   - TRANSACTION_verifyPhoneNumberSingleUse = 2
 *   - TRANSACTION_verifyPhoneNumber = 3
 *   - TRANSACTION_getIidToken = 4
 *   - TRANSACTION_getPnvCapabilities = 5
 * 
 * AIDL syntax "= X" means FIRST_CALL_TRANSACTION + (X - 1) = 1 + (X - 1) = X
 * So to get code N, use = (N - FIRST_CALL_TRANSACTION + 1) = (N - 1 + 1) = N... no wait
 * Actually = X means FIRST_CALL_TRANSACTION + X = 1 + X, so:
 *   - = 0 gives code 1
 *   - = 1 gives code 2
 *   - = 2 gives code 3
 *   - etc.
 */
interface IConstellationApiService {
    void verifyPhoneNumberV1(IConstellationCallbacks callbacks, in Bundle bundle, in ApiMetadata metadata) = 0;
    void verifyPhoneNumberSingleUse(IConstellationCallbacks callbacks, in Bundle bundle, in ApiMetadata metadata) = 1;
    void verifyPhoneNumber(IConstellationCallbacks callbacks, in VerifyPhoneNumberRequest request, in ApiMetadata metadata) = 2;
    void getIidToken(IConstellationCallbacks callbacks, in GetIidTokenRequest request, in ApiMetadata metadata) = 3;
    void getPnvCapabilities(IConstellationCallbacks callbacks, in GetPnvCapabilitiesRequest request, in ApiMetadata metadata) = 4;
}
