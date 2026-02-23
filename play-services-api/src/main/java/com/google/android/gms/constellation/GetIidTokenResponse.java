/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package com.google.android.gms.constellation;

import android.os.Parcel;
import androidx.annotation.NonNull;
import com.google.android.gms.common.internal.safeparcel.AbstractSafeParcelable;
import com.google.android.gms.common.internal.safeparcel.SafeParcelable;
import com.google.android.gms.common.internal.safeparcel.SafeParcelableCreatorAndWriter;

/**
 * Response containing Instance ID token.
 *
 * Stock (bevs.java:64): GetIidTokenResponse(iidToken, fid, signature, signatureTimestampMillis)
 * - field 1: IID token string (bfpd.b = iidToken)
 * - field 2: Firebase Installation ID (bfpd.a = fid, confirmed by bfpd.toString: "IidTokenResult{fid=...}")
 * - field 3: Client signature bytes (null when signing phenotype flag off)
 * - field 4: Signature timestamp millis (System.currentTimeMillis() when signed, 0 when not)
 */
@SafeParcelable.Class
public class GetIidTokenResponse extends AbstractSafeParcelable {
    @Field(1)
    public String token;
    @Field(2)
    public String fid;
    @Field(3)
    public byte[] signature;
    @Field(4)
    public long signatureTimestampMillis;

    @Constructor
    public GetIidTokenResponse(
            @Param(1) String token,
            @Param(2) String fid,
            @Param(3) byte[] signature,
            @Param(4) long signatureTimestampMillis) {
        this.token = token;
        this.fid = fid;
        this.signature = signature;
        this.signatureTimestampMillis = signatureTimestampMillis;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        CREATOR.writeToParcel(this, dest, flags);
    }

    public static final SafeParcelableCreatorAndWriter<GetIidTokenResponse> CREATOR = findCreator(GetIidTokenResponse.class);
}
