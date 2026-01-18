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
 * NOTE: microG's generated Stub uses Binder-style transaction IDs (decompiled from system APK):
 *   - verifyPhoneNumberV1       = 3
 *   - verifyPhoneNumberSingleUse= 4
 *   - verifyPhoneNumber         = 5
 *   - getIidToken               = 6
 *   - getPnvCapabilities        = 7
 */
interface IConstellationApiService {
    void verifyPhoneNumberV1(IConstellationCallbacks callbacks, in Bundle bundle, in ApiMetadata metadata) = 3;
    void verifyPhoneNumberSingleUse(IConstellationCallbacks callbacks, in Bundle bundle, in ApiMetadata metadata) = 4;
    void verifyPhoneNumber(IConstellationCallbacks callbacks, in VerifyPhoneNumberRequest request, in ApiMetadata metadata) = 5;
    void getIidToken(IConstellationCallbacks callbacks, in GetIidTokenRequest request, in ApiMetadata metadata) = 6;
    void getPnvCapabilities(IConstellationCallbacks callbacks, in GetPnvCapabilitiesRequest request, in ApiMetadata metadata) = 7;
}
