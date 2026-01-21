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
 * Request containing IMSI and associated metadata.
 *
 * Fields (from GMS decompilation):
 * - imsi: IMSI string
 * - simOperator: SIM operator string (e.g., MCCMNC)
 */
@SafeParcelable.Class
public class ImsiRequest extends AbstractSafeParcelable {
    @Field(1)
    public String imsi;
    @Field(2)
    public String simOperator;

    @Constructor
    public ImsiRequest(@Param(1) String imsi, @Param(2) String simOperator) {
        this.imsi = imsi;
        this.simOperator = simOperator;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        CREATOR.writeToParcel(this, dest, flags);
    }

    public static final SafeParcelableCreatorAndWriter<ImsiRequest> CREATOR = findCreator(ImsiRequest.class);
}
