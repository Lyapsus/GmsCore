/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.constellation

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.squareup.wire.GrpcClient
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okio.ByteString
import org.microg.gms.common.PackageUtils
import org.microg.gms.droidguard.DroidGuardClientImpl
import org.microg.gms.gcm.GcmDatabase
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import google.internal.communications.phonedeviceverification.v1.ClientInfo
import google.internal.communications.phonedeviceverification.v1.ConnectivityAvailability
import google.internal.communications.phonedeviceverification.v1.ConnectivityInfo
import google.internal.communications.phonedeviceverification.v1.ConnectivityState
import google.internal.communications.phonedeviceverification.v1.ConnectivityType
import google.internal.communications.phonedeviceverification.v1.CountryInfo
import google.internal.communications.phonedeviceverification.v1.DeviceId
import google.internal.communications.phonedeviceverification.v1.DeviceSignals
import google.internal.communications.phonedeviceverification.v1.DeviceType
import google.internal.communications.phonedeviceverification.v1.GrpcPhoneDeviceVerificationClient
import google.internal.communications.phonedeviceverification.v1.RequestHeader
import google.internal.communications.phonedeviceverification.v1.RequestTrigger
import google.internal.communications.phonedeviceverification.v1.SIMAssociation
import google.internal.communications.phonedeviceverification.v1.SIMInfo
import google.internal.communications.phonedeviceverification.v1.SyncRequest
import google.internal.communications.phonedeviceverification.v1.TriggerType
import google.internal.communications.phonedeviceverification.v1.UserProfileType
import google.internal.communications.phonedeviceverification.v1.Verification
import google.internal.communications.phonedeviceverification.v1.VerificationAssociation
import google.internal.communications.phonedeviceverification.v1.VerificationState

class GoogleConstellationClient(private val context: Context) {
    companion object {
        private const val TAG = "GmsConstellationClient"
        // GMS uses API key auth, NOT OAuth! (decompiled from GMS bewt.java:445)
        private const val API_KEY = "AIzaSyAP-gfH3qvi6vgHZbSYwQ_XHqV_mXHhzIk"
    }

    fun verifyPhoneNumber(msisdn: String, imsi: String): Ts43Client.EntitlementResult {
        Log.i(TAG, "Starting Google Constellation verification for $msisdn")

        return try {
            // 1. Get package info for headers
            val packageName = context.packageName
            val certSha1 = PackageUtils.firstSignatureDigest(context, packageName)
            Log.d(TAG, "Using API key auth with package=$packageName, cert=$certSha1")

            runBlocking {
                // 2. Get IID token first (needed for DroidGuard content bindings)
                // Try to find any real GCM registration to use as IID token
                val gcmDb = GcmDatabase(context)
                var iidToken: String? = null
                var iidSource = "none"

                // First try our own package
                val ownReg = gcmDb.getRegistration(packageName, certSha1)?.registerId
                if (ownReg != null) {
                    iidToken = ownReg
                    iidSource = "own"
                } else {
                    // Try to find any Google-signed app's registration
                    val googleSig = "38918a453d07199354f8b19af05ec6562ced5788"
                    val cursor = gcmDb.readableDatabase.query(
                        "registrations", arrayOf("register_id", "package_name"),
                        "signature = ?", arrayOf(googleSig),
                        null, null, null, "1"
                    )
                    if (cursor.moveToFirst()) {
                        iidToken = cursor.getString(0)
                        val pkg = cursor.getString(1)
                        iidSource = "borrowed:$pkg"
                    }
                    cursor.close()
                }
                gcmDb.close()

                if (iidToken == null) {
                    iidToken = "stub-iid-token-${System.currentTimeMillis()}"
                    iidSource = "stub"
                }
                Log.d(TAG, "IID token source: $iidSource, token prefix: ${iidToken.take(20)}...")

                // 3. Calculate iidHash for DroidGuard content bindings
                // Per API: "iidHash: base64 encoded, sha-256 of the device iid token"
                val iidHashBytes = MessageDigest.getInstance("SHA-256").digest(iidToken.toByteArray())
                val iidHash = Base64.getEncoder().encodeToString(iidHashBytes)
                val rpcName = "/google.internal.communications.phonedeviceverification.v1.PhoneDeviceVerification/Sync"
                Log.d(TAG, "DroidGuard bindings: iidHash=$iidHash, rpc=$rpcName")

                // 4. Get DroidGuard Token with content bindings
                val droidGuard = DroidGuardClientImpl(context)
                val dgBindings = mapOf(
                    "iidHash" to iidHash,
                    "rpc" to rpcName
                )
                val dgTask = droidGuard.getResults("constellation_verify", dgBindings, null)
                val dgToken = try {
                    Tasks.await(dgTask, 30, TimeUnit.SECONDS)
                } catch (e: Exception) {
                    Log.e(TAG, "DroidGuard task failed", e)
                    null
                }

                if (dgToken == null) {
                    Log.e(TAG, "Failed to get DroidGuard token")
                    return@runBlocking Ts43Client.EntitlementResult.error("droidguard-failed")
                }

                // 3. Setup Grpc Client with API Key auth (NOT OAuth!)
                // GMS uses: X-Goog-Api-Key, X-Android-Package, X-Android-Cert
                val okHttpClient = OkHttpClient.Builder()
                    .addInterceptor { chain ->
                        val original = chain.request()
                        val request = original.newBuilder()
                            .header("X-Goog-Api-Key", API_KEY)
                            .header("X-Android-Package", packageName)
                            .header("X-Android-Cert", certSha1 ?: "")
                            .method(original.method, original.body)
                            .build()
                        chain.proceed(request)
                    }
                    .build()

                val grpcClient = GrpcClient.Builder()
                    .client(okHttpClient)
                    .baseUrl("https://phonedeviceverification-pa.googleapis.com/")
                    .build()

                val client = GrpcPhoneDeviceVerificationClient(grpcClient)

                // 5. Generate a client public key (ephemeral for now)
                val keyGen = KeyPairGenerator.getInstance("EC")
                keyGen.initialize(256)
                val keyPair = keyGen.generateKeyPair()
                val publicKeyBytes = ByteString.of(*keyPair.public.encoded)

                // 6. Get CountryInfo from TelephonyManager (verified in GMS bbah.java:841, bbtm.java:116,140)
                val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                val simCountry = telephonyManager?.simCountryIso?.uppercase() ?: ""
                val networkCountry = telephonyManager?.networkCountryIso?.uppercase() ?: ""
                Log.d(TAG, "CountryInfo: simCountry=$simCountry, networkCountry=$networkCountry")

                val countryInfo = CountryInfo(
                    sim_countries = if (simCountry.isNotEmpty()) listOf(simCountry) else emptyList(),
                    network_countries = if (networkCountry.isNotEmpty()) listOf(networkCountry) else emptyList()
                )

                // 7. Get ConnectivityInfo from ConnectivityManager (verified in GMS bbah.java:1024-1104)
                val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                val connectivityInfos = mutableListOf<ConnectivityInfo>()

                try {
                    @Suppress("DEPRECATION")
                    val allNetworks = connectivityManager?.allNetworkInfo
                    allNetworks?.forEach { networkInfo ->
                        val type = networkInfo.type
                        // Only include WIFI (1) and MOBILE (0) like GMS does
                        if (type == ConnectivityManager.TYPE_WIFI || type == ConnectivityManager.TYPE_MOBILE) {
                            val connType = when (type) {
                                ConnectivityManager.TYPE_WIFI -> ConnectivityType.CONNECTIVITY_TYPE_WIFI
                                ConnectivityManager.TYPE_MOBILE -> ConnectivityType.CONNECTIVITY_TYPE_MOBILE
                                else -> ConnectivityType.CONNECTIVITY_TYPE_UNKNOWN
                            }
                            val connState = when (networkInfo.state) {
                                NetworkInfo.State.CONNECTED -> ConnectivityState.CONNECTIVITY_STATE_CONNECTED
                                NetworkInfo.State.CONNECTING -> ConnectivityState.CONNECTIVITY_STATE_CONNECTING
                                NetworkInfo.State.DISCONNECTED -> ConnectivityState.CONNECTIVITY_STATE_DISCONNECTED
                                NetworkInfo.State.DISCONNECTING -> ConnectivityState.CONNECTIVITY_STATE_DISCONNECTING
                                else -> ConnectivityState.CONNECTIVITY_STATE_UNKNOWN
                            }
                            val connAvail = if (networkInfo.isAvailable) {
                                ConnectivityAvailability.CONNECTIVITY_AVAILABLE
                            } else {
                                ConnectivityAvailability.CONNECTIVITY_NOT_AVAILABLE
                            }
                            connectivityInfos.add(ConnectivityInfo(
                                type = connType,
                                state = connState,
                                availability = connAvail
                            ))
                        }
                    }
                } catch (e: SecurityException) {
                    Log.w(TAG, "Could not get connectivity info", e)
                }
                Log.d(TAG, "ConnectivityInfos: ${connectivityInfos.size} networks")

                // 8. Build Sync Request with ALL required fields
                val sessionId = UUID.randomUUID().toString()
                val localeStr = Locale.getDefault().toString()

                val request = SyncRequest(
                    header_ = RequestHeader(
                        session_id = sessionId,
                        client_info = ClientInfo(
                            device_id = DeviceId(
                                iid_token = iidToken
                            ),
                            client_public_key = publicKeyBytes,
                            locale = localeStr,
                            gmscore_version_number = 255034004,  // Match GMS: 25.50.34 build 004
                            gmscore_version = "25.50.34 (040400-855735097)",  // Exact format from GMS bbah.java:926
                            android_sdk_version = Build.VERSION.SDK_INT,
                            device_signals = DeviceSignals(
                                droidguard_result = dgToken
                            ),
                            model = Build.MODEL,
                            manufacturer = Build.MANUFACTURER,
                            device_fingerprint = Build.FINGERPRINT,
                            device_type = DeviceType.DEVICE_TYPE_PHONE,
                            user_profile_type = UserProfileType.REGULAR_USER,
                            country_info = countryInfo,  // NEW: Field 13
                            connectivity_infos = connectivityInfos  // NEW: Field 14
                        ),
                        trigger = RequestTrigger(
                            type = TriggerType.TRIGGER_TYPE_SIM_STATE_CHANGED
                        )
                    ),
                    verifications = listOf(
                        Verification(
                            state = VerificationState.VERIFICATION_STATE_NONE,
                            association = VerificationAssociation(
                                sim = SIMAssociation(
                                    sim_info = SIMInfo(
                                        imsi = listOf(imsi),
                                        sim_readable_number = msisdn
                                    )
                                )
                            )
                        )
                    )
                )

                // 9. Execute Sync
                Log.d(TAG, "Sending Sync request...")
                val response = client.Sync().execute(request)
                Log.d(TAG, "Received Sync response: $response")

                // 10. Process Response
                if (response.responses.isNotEmpty()) {
                    val vResponse = response.responses[0]
                    if (vResponse.verification != null) {
                        if (vResponse.verification.state == VerificationState.VERIFICATION_STATE_VERIFIED) {
                            Log.i(TAG, "Got VERIFIED state!")
                            // TODO: Extract token
                            return@runBlocking Ts43Client.EntitlementResult.error("not-implemented-parsing")
                        } else if (vResponse.verification.state == VerificationState.VERIFICATION_STATE_PENDING) {
                            Log.i(TAG, "Got PENDING state - Challenge required")
                            return@runBlocking Ts43Client.EntitlementResult.error("challenge-required")
                        }
                    }
                }

                Ts43Client.EntitlementResult.error("unknown-response")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in verifyPhoneNumber", e)
            Ts43Client.EntitlementResult.error("exception: ${e.message}")
        }
    }
}
