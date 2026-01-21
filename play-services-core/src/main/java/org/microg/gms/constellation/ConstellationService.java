/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.constellation;

import android.os.RemoteException;
import android.util.Log;

import com.google.android.gms.common.Feature;
import com.google.android.gms.common.internal.ConnectionInfo;
import com.google.android.gms.common.internal.GetServiceRequest;
import com.google.android.gms.common.internal.IGmsCallbacks;

import org.microg.gms.BaseService;
import org.microg.gms.common.GmsService;

/**
 * Constellation Service - Phone Number Verification for RCS.
 * 
 * This service is called by Google Messages to verify phone numbers
 * for RCS (Rich Communication Services) activation.
 * 
 * Service ID: 155 (CONSTELLATION)
 * Action: com.google.android.gms.constellation.service.START
 */
public class ConstellationService extends BaseService {
    private static final String TAG = "GmsConstellationSvc";
    
    /**
     * Supported features for Constellation service.
     * These MUST match or exceed the versions Messages requests.
     * 
     * From Messages request:
     * - asterism_consent: 3
     * - one_time_verification: 1
     * - carrier_auth: 1
     * - verify_phone_number: 2
     * - get_iid_token: 1
     * - get_pnv_capabilities: 1
     * - ts43: 1
     * - verify_phone_number_local_read: 1
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
    
    private ConstellationServiceImpl impl;

    public ConstellationService() {
        super(TAG, GmsService.CONSTELLATION);
    }

    @Override
    public void handleServiceRequest(IGmsCallbacks callback, GetServiceRequest request, GmsService service) throws RemoteException {
        Log.d(TAG, "handleServiceRequest: supportsConnectionInfo=" + request.supportsConnectionInfo);
        
        if (impl == null) {
            impl = new ConstellationServiceImpl(this);
        }
        
        if (request.supportsConnectionInfo) {
            // Return ConnectionInfo with supported features
            ConnectionInfo info = new ConnectionInfo();
            info.features = SUPPORTED_FEATURES;
            Log.d(TAG, "Returning ConnectionInfo with " + SUPPORTED_FEATURES.length + " features");
            callback.onPostInitCompleteWithConnectionInfo(0, impl.asBinder(), info);
        } else {
            callback.onPostInitComplete(0, impl.asBinder(), null);
        }
    }
}
