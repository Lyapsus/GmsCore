/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.asterism;

import android.os.RemoteException;
import android.util.Log;

import com.google.android.gms.common.Feature;
import com.google.android.gms.common.internal.ConnectionInfo;
import com.google.android.gms.common.internal.GetServiceRequest;
import com.google.android.gms.common.internal.IGmsCallbacks;

import org.microg.gms.BaseService;
import org.microg.gms.common.GmsService;

/**
 * Asterism Service - Consent Management for RCS.
 *
 * This service handles Google Terms of Service consent for RCS.
 * Messages calls setConsent() to record ToS agreement before provisioning.
 *
 * Service ID: 199 (ASTERISM)
 * Action: com.google.android.gms.asterism.service.START
 */
public class AsterismService extends BaseService {
    private static final String TAG = "GmsAsterismSvc";

    /**
     * Supported features - same as Constellation since they're related services.
     */
    private static final Feature[] SUPPORTED_FEATURES = new Feature[] {
        new Feature("asterism_consent", 3),
        new Feature("one_time_verification", 1),
        new Feature("carrier_auth", 1),
        new Feature("verify_phone_number", 2),
        new Feature("get_iid_token", 1),
        new Feature("get_pnv_capabilities", 1),
        new Feature("ts43", 1),
        new Feature("verify_phone_number_local_read", 1)
    };

    private AsterismServiceImpl impl;

    public AsterismService() {
        super(TAG, GmsService.ASTERISM);
    }

    @Override
    public void handleServiceRequest(IGmsCallbacks callback, GetServiceRequest request, GmsService service) throws RemoteException {
        Log.d(TAG, "handleServiceRequest: supportsConnectionInfo=" + request.supportsConnectionInfo);

        if (impl == null) {
            impl = new AsterismServiceImpl(this);
        }

        if (request.supportsConnectionInfo) {
            ConnectionInfo info = new ConnectionInfo();
            info.features = SUPPORTED_FEATURES;
            Log.d(TAG, "Returning ConnectionInfo with " + SUPPORTED_FEATURES.length + " features");
            callback.onPostInitCompleteWithConnectionInfo(0, impl.asBinder(), info);
        } else {
            callback.onPostInitComplete(0, impl.asBinder(), null);
        }
    }
}
