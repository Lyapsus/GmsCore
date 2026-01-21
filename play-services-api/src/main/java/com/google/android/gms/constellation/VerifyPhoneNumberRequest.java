/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package com.google.android.gms.constellation;

import android.os.Bundle;
import android.os.Parcel;
import androidx.annotation.NonNull;
import com.google.android.gms.common.internal.safeparcel.AbstractSafeParcelable;
import com.google.android.gms.common.internal.safeparcel.SafeParcelable;
import com.google.android.gms.common.internal.safeparcel.SafeParcelableCreatorAndWriter;

import java.util.List;

// NOTE: GMS VerifyPhoneNumberRequest field 5 is List<ImsiRequest>, NOT SimCapability.

/**
 * Request to verify a phone number via Constellation.
 *
 * Based on decompiled GMS, fields:
 * - phoneNumber: Phone number to verify (E.164 format)
     * - subscriptionId: SIM subscription ID
     * - idTokenRequest: Optional request for ID token
     * - extras: Additional parameters
     * - imsiRequests: List of IMSI requests (ImsiRequest)
     * - allowFallback: Whether to allow fallback verification methods
     * - verificationType: Preferred verification type
     * - verificationCapabilities: Supported verification methods

 */
@SafeParcelable.Class
public class VerifyPhoneNumberRequest extends AbstractSafeParcelable {
    @Field(1)
    public String phoneNumber;
    @Field(2)
    public long subscriptionId;
    @Field(3)
    public IdTokenRequest idTokenRequest;
    @Field(4)
    public Bundle extras;
    @Field(5)
    public List<ImsiRequest> imsiRequests;
    @Field(6)
    public boolean allowFallback;
    @Field(7)
    public int verificationType;
    @Field(8)
    public List<VerificationCapability> verificationCapabilities;

    @Constructor
    public VerifyPhoneNumberRequest(
            @Param(1) String phoneNumber,
            @Param(2) long subscriptionId,
            @Param(3) IdTokenRequest idTokenRequest,
            @Param(4) Bundle extras,
            @Param(5) List<ImsiRequest> imsiRequests,
            @Param(6) boolean allowFallback,
            @Param(7) int verificationType,
            @Param(8) List<VerificationCapability> verificationCapabilities) {
        this.phoneNumber = phoneNumber;
        this.subscriptionId = subscriptionId;
        this.idTokenRequest = idTokenRequest;
        this.extras = extras;
        this.imsiRequests = imsiRequests;
        this.allowFallback = allowFallback;
        this.verificationType = verificationType;
        this.verificationCapabilities = verificationCapabilities;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        CREATOR.writeToParcel(this, dest, flags);
    }

    public static final SafeParcelableCreatorAndWriter<VerifyPhoneNumberRequest> CREATOR = findCreator(VerifyPhoneNumberRequest.class);
}
