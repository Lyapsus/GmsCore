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

import java.util.List;

/**
 * Request to get phone number verification (PNV) capabilities.
 *
 * Fields:
 * - packageName: The calling app's package name
 * - phoneNumbers: List of phone numbers to check capabilities for
 * - subscriptionIds: List of subscription IDs to check
 */
@SafeParcelable.Class
public class GetPnvCapabilitiesRequest extends AbstractSafeParcelable {
    @Field(1)
    public String packageName;
    @Field(2)
    public List<String> phoneNumbers;
    @Field(3)
    public List<Long> subscriptionIds;

    @Constructor
    public GetPnvCapabilitiesRequest(
            @Param(1) String packageName,
            @Param(2) List<String> phoneNumbers,
            @Param(3) List<Long> subscriptionIds) {
        this.packageName = packageName;
        this.phoneNumbers = phoneNumbers;
        this.subscriptionIds = subscriptionIds;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        CREATOR.writeToParcel(this, dest, flags);
    }

    public static final SafeParcelableCreatorAndWriter<GetPnvCapabilitiesRequest> CREATOR = findCreator(GetPnvCapabilitiesRequest.class);
}
