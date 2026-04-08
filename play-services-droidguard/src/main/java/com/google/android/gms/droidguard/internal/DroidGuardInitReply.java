/*
 * SPDX-FileCopyrightText: 2020, microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package com.google.android.gms.droidguard.internal;

import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;

import androidx.annotation.Nullable;

public class DroidGuardInitReply implements Parcelable {
    public @Nullable ParcelFileDescriptor pfd;
    public @Nullable Parcelable object;

    public DroidGuardInitReply(@Nullable ParcelFileDescriptor pfd, @Nullable Parcelable object) {
        this.pfd = pfd;
        this.object = object;
    }

    @Override
    public int describeContents() {
        return (pfd != null ? Parcelable.CONTENTS_FILE_DESCRIPTOR : 0) | (object != null ? object.describeContents() : 0);
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeParcelable(pfd, flags);
        dest.writeParcelable(object, flags);
    }

    public final static Creator<DroidGuardInitReply> CREATOR = new Creator<DroidGuardInitReply>() {
        @Override
        public DroidGuardInitReply createFromParcel(Parcel source) {
            ParcelFileDescriptor pfd = source.readParcelable(ParcelFileDescriptor.class.getClassLoader());
            Parcelable object = source.readParcelable(getClass().getClassLoader());
            // Always return a non-null reply. The old code returned null when pfd/object
            // were null, causing DroidGuardApiClient.openHandle() to fall back to init()
            // which calls initWithRequest(flow, null) — losing the DroidGuardResultsRequest
            // Bundle (clientVersion, appArchitecture). The VM uses this Bundle to decide
            // what telemetry to collect: null Bundle → minimal mode → 21K tokens.
            return new DroidGuardInitReply(pfd, object);
        }

        @Override
        public DroidGuardInitReply[] newArray(int size) {
            return new DroidGuardInitReply[size];
        }
    };
}
