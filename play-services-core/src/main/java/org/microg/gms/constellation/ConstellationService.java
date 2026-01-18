/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.constellation;

import android.os.RemoteException;

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
    
    private ConstellationServiceImpl impl;

    public ConstellationService() {
        super(TAG, GmsService.CONSTELLATION);
    }

    @Override
    public void handleServiceRequest(IGmsCallbacks callback, GetServiceRequest request, GmsService service) throws RemoteException {
        if (impl == null) {
            impl = new ConstellationServiceImpl(this);
        }
        callback.onPostInitComplete(0, impl.asBinder(), null);
    }
}
