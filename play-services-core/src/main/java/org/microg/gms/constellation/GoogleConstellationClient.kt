/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.constellation

import android.content.Context
import android.accounts.AccountManager
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import org.microg.gms.checkin.LastCheckinInfo
import org.microg.gms.gcm.RegisterRequest
import org.microg.gms.gcm.RegisterResponse
import android.telephony.ServiceState
import android.telephony.TelephonyManager
import android.telephony.SubscriptionManager
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.squareup.wire.GrpcClient
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okio.ByteString
import okio.ByteString.Companion.decodeBase64
import org.microg.gms.common.PackageUtils
import org.microg.gms.droidguard.DroidGuardClientImpl
import org.microg.gms.gcm.GcmDatabase
import org.microg.gms.auth.AuthConstants
import org.microg.gms.auth.AuthManager
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
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
import google.internal.communications.phonedeviceverification.v1.StringId
import google.internal.communications.phonedeviceverification.v1.GaiaCredential
import google.internal.communications.phonedeviceverification.v1.GaiaId
import google.internal.communications.phonedeviceverification.v1.PartialSIMInfo
import google.internal.communications.phonedeviceverification.v1.PartialSIMData
import google.internal.communications.phonedeviceverification.v1.IMSIRequest
import google.internal.communications.phonedeviceverification.v1.Param
import google.internal.communications.phonedeviceverification.v1.SyncRequest
import google.internal.communications.phonedeviceverification.v1.Verification
import google.internal.communications.phonedeviceverification.v1.VerificationAssociation
import google.internal.communications.phonedeviceverification.v1.VerificationState
import google.internal.communications.phonedeviceverification.v1.TriggerType
import google.internal.communications.phonedeviceverification.v1.ExperimentInfo
import google.internal.communications.phonedeviceverification.v1.TelephonyInfo
import google.internal.communications.phonedeviceverification.v1.TelephonyInfoContainer
import google.internal.communications.phonedeviceverification.v1.TelephonyInfoEntry
import google.internal.communications.phonedeviceverification.v1.Timestamp
import google.internal.communications.phonedeviceverification.v1.ClientCredentials
import google.internal.communications.phonedeviceverification.v1.PublicKeyStatus
import google.internal.communications.phonedeviceverification.v1.MobileOperatorCountry
import google.internal.communications.phonedeviceverification.v1.ExtensionMetadata
import google.internal.communications.phonedeviceverification.v1.ExtensionData
import google.internal.communications.phonedeviceverification.v1.CredentialMetadata
import google.internal.communications.phonedeviceverification.v1.SIMSlot
import com.google.android.gms.constellation.VerifyPhoneNumberRequest as AidlVerifyPhoneNumberRequest
import com.google.android.gms.constellation.IdTokenRequest as ProtoIdTokenRequest

class GoogleConstellationClient(private val context: Context) {
    companion object {
        private const val TAG = "GmsConstellationClient" 
        // GMS uses API key auth, NOT OAuth! (decompiled from GMS bewt.java:445)
        private const val API_KEY = "AIzaSyAP-gfH3qvi6vgHZbSYwQ_XHqV_mXHhzIk"
        private const val GMSCORE_VERSION_NUMBER = 260233
        private const val GMSCORE_VERSION = "26.02.33 (190400-{{cl}})"
        private const val GAIA_TOKEN_SCOPE = "oauth2:https://www.googleapis.com/auth/numberer"
    }

    private fun logSnapshot(label: String, json: JSONObject) {
        val payload = json.toString()
        val chunkSize = 3000
        var offset = 0
        while (offset < payload.length) {
            val end = kotlin.math.min(offset + chunkSize, payload.length)
            Log.i(TAG, "$label[$offset]: ${payload.substring(offset, end)}")
            offset = end
        }
    }

    private fun getGaiaTokens(packageName: String): List<String> {
        val accountManager = AccountManager.get(context)
        val accounts = accountManager.getAccountsByType(AuthConstants.DEFAULT_ACCOUNT_TYPE)
        if (accounts.isEmpty()) {
            Log.d(TAG, "No Google accounts available for gaia tokens")
            return emptyList()
        }
        Log.d(TAG, "Gaia token scope: $GAIA_TOKEN_SCOPE")
        Log.d(TAG, "Google accounts found: ${accounts.size}")
        val tokens = ArrayList<String>(accounts.size)
        for (account in accounts) {
            val authManager = AuthManager(context, account.name, packageName, GAIA_TOKEN_SCOPE)
            authManager.isGmsApp = true
            val token = authManager.getAuthToken() ?: try {
                authManager.requestAuthWithBackgroundResolution(false).auth
            } catch (e: Exception) {
                Log.w(TAG, "Failed to get gaia token for ${account.name}", e)
                null
            }
            if (!token.isNullOrEmpty()) {
                tokens.add(token)
                Log.d(TAG, "Gaia token for ${account.name}: $token")
            } else {
                Log.w(TAG, "No gaia token available for ${account.name}")
            }
        }
        Log.d(TAG, "Gaia tokens acquired: ${tokens.size}")
        return tokens
    }

    /**
     * Get Gaia IDs (numeric Google account IDs) for all logged-in Google accounts.
     * These are used in TelephonyInfoContainer (ClientInfo field 20).
     * Gaia ID format: "116253767460812011076" (numeric string)
     */
    private fun getGaiaIds(): List<String> {
        val accountManager = AccountManager.get(context)
        val accounts = accountManager.getAccountsByType(AuthConstants.DEFAULT_ACCOUNT_TYPE)
        if (accounts.isEmpty()) {
            Log.d(TAG, "No Google accounts available for Gaia IDs")
            return emptyList()
        }
        val gaiaIds = ArrayList<String>(accounts.size)
        for (account in accounts) {
            // GoogleUserId is the numeric Gaia ID stored during account login
            val gaiaId = accountManager.getUserData(account, "GoogleUserId")
            if (!gaiaId.isNullOrEmpty()) {
                gaiaIds.add(gaiaId)
                Log.d(TAG, "Gaia ID for ${account.name}: $gaiaId")
            } else {
                Log.w(TAG, "No Gaia ID available for ${account.name}")
            }
        }
        Log.d(TAG, "Gaia IDs acquired: ${gaiaIds.size}")
        return gaiaIds
    }

    private fun bundleToParams(bundle: Bundle?): List<Param> {
        if (bundle == null || bundle.isEmpty) {
            return emptyList()
        }
        val params = ArrayList<Param>(bundle.size())
        for (key in bundle.keySet()) {
            val value = bundle.getString(key) ?: continue
            params.add(Param(name = key, value_ = value))
        }
        return params
    }

    private fun loadExtensionData(): List<ExtensionData> {
        val prefs = context.getSharedPreferences("constellation_extension_data", Context.MODE_PRIVATE)
        val dataBase64 = prefs.getString("data_base64", null)
        if (dataBase64.isNullOrEmpty()) {
            Log.d(TAG, "No extension data found in SharedPreferences")
            return emptyList()
        }
        val dataBytes = dataBase64.decodeBase64()
        if (dataBytes == null) {
            Log.w(TAG, "Invalid base64 in extension data")
            return emptyList()
        }
        val timestampNanos = prefs.getLong("timestamp_nanos", 0L)
        val sequence = prefs.getInt("sequence", 0)
        val metadata = if (timestampNanos != 0L || sequence != 0) {
            ExtensionMetadata(timestamp_nanos = timestampNanos, sequence = sequence)
        } else {
            null
        }
        Log.d(TAG, "Loaded extension data: bytes=${dataBytes.size}, timestamp=$timestampNanos, sequence=$sequence")
        return listOf(ExtensionData(data_ = dataBytes, metadata = metadata))
    }

    fun verifyPhoneNumber(request: AidlVerifyPhoneNumberRequest?, callingPackage: String?, imsiOverride: String?, msisdnOverride: String?): Ts43Client.EntitlementResult {
        val requestedNumber = request?.phoneNumber ?: msisdnOverride
        Log.i(TAG, "Starting Google Constellation verification for $requestedNumber")

        return try {
            // 1. Get package info for headers
            val packageName = context.packageName
            val certSha1 = PackageUtils.firstSignatureDigest(context, packageName)
            Log.d(TAG, "Using API key auth with package=$packageName, cert=$certSha1")

            runBlocking {
                // 2. Get IID token - MUST be registered with Constellation project ID!
                // GMS uses sender ID "496232013492" (Phenotype IidToken__default_project_number)
                // Using tokens from other apps (Messages, etc.) will cause INVALID_ARGUMENT
                val CONSTELLATION_SENDER_ID = "496232013492"

                var iidToken: String? = null
                var iidSource = "none"
                var instanceId: String? = null

                // First check if we have a cached Constellation-specific token
                val prefs = context.getSharedPreferences("constellation_iid", Context.MODE_PRIVATE)
                val cachedToken = prefs.getString("iid_token_$CONSTELLATION_SENDER_ID", null)
                if (cachedToken != null) {
                    iidToken = cachedToken
                    iidSource = "cached-constellation"
                    Log.d(TAG, "Using cached Constellation IID token")
                }

                if (iidToken == null) {
                    // Register for FCM with Constellation project ID - this is how GMS does it
                    Log.d(TAG, "No cached Constellation IID token, registering with sender=$CONSTELLATION_SENDER_ID...")
                    try {
                        // Generate RSA keypair for Instance ID (fallback)
                        instanceId = prefs.getString("instance_id", null)

                        if (instanceId == null) {
                            val rsaGenerator = java.security.KeyPairGenerator.getInstance("RSA")
                            rsaGenerator.initialize(2048)
                            val keyPair = rsaGenerator.generateKeyPair()

                            // Calculate Instance ID: SHA1 of public key, modified first byte, base64
                            val digest = MessageDigest.getInstance("SHA1").digest(keyPair.public.encoded)
                            digest[0] = ((112 + (0xF and digest[0].toInt())) and 0xFF).toByte()
                            instanceId = android.util.Base64.encodeToString(digest, 0, 8,
                                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
                            prefs.edit().putString("instance_id", instanceId).apply()
                            Log.d(TAG, "Generated new Instance ID: $instanceId")
                        }

                        // Try to get a registration token from Google with Constellation sender ID
                        val checkinInfo = LastCheckinInfo.read(context)
                        if (checkinInfo.androidId != 0L && checkinInfo.securityToken != 0L) {
                            try {
                                // GMS sends ALL these parameters (from ccvs.a() + ccvb.e()):
                                // Missing any causes registration to fail silently!
                                val versionCode = org.microg.gms.common.Constants.GMS_VERSION_CODE
                                val versionName = "%09d".format(versionCode).let {
                                    "${it.substring(0, 2)}.${it.substring(2, 4)}.${it.substring(4, 6)} (190400-{{cl}})"
                                }
                                val clientLibVersion = "iid-${(versionCode / 1000) * 1000}"

                                val response: RegisterResponse = RegisterRequest()
                                    .build(context)
                                    .checkin(checkinInfo)
                                    .app(packageName, certSha1, versionCode)
                                    .sender(CONSTELLATION_SENDER_ID)  // CRITICAL: Must be Constellation project ID!
                                    // GMS ccvb.e() parameters:
                                    .extraParam("subscription", CONSTELLATION_SENDER_ID)
                                    .extraParam("X-subscription", CONSTELLATION_SENDER_ID)
                                    .extraParam("subtype", CONSTELLATION_SENDER_ID)
                                    .extraParam("X-subtype", CONSTELLATION_SENDER_ID)
                                    .extraParam("scope", "GCM")
                                    // GMS ccvs.a() parameters:
                                    .extraParam("gmsv", versionCode.toString())
                                    .extraParam("osv", Build.VERSION.SDK_INT.toString())
                                    .extraParam("app_ver", versionCode.toString())
                                    .extraParam("app_ver_name", versionName)
                                    .extraParam("cliv", clientLibVersion)
                                    .extraParam("appid", instanceId!!)
                                    .getResponse()

                                if (response.token != null) {
                                    iidToken = response.token
                                    iidSource = "registered-constellation"
                                    Log.i(TAG, "Got Constellation registration token from Google: ${response.token.take(20)}...")
                                    // Cache it for future use
                                    prefs.edit().putString("iid_token_$CONSTELLATION_SENDER_ID", response.token).apply()
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Registration failed: ${e.message}, using Instance ID")
                            }
                        }

                        // Use Instance ID if registration failed
                        if (iidToken == null) {
                            iidToken = instanceId
                            iidSource = "instance-id"
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to generate Instance ID", e)
                    }
                }

                // Final fallback
                if (iidToken == null) {
                    iidToken = java.util.UUID.randomUUID().toString().take(11).replace("-", "")
                    iidSource = "random-fallback"
                    Log.w(TAG, "Using random ID as last resort!")
                }
                Log.d(TAG, "IID token source: $iidSource, token prefix: ${iidToken.take(20)}...")

                // 3. Calculate iidHash for DroidGuard content bindings
                // GMS bfck.java:24-30: SHA-256 hash, pad to 64 bytes, base64 NO_PADDING|NO_WRAP (NOT URL_SAFE!), truncate to 32 chars
                // CRITICAL FIX: GMS uses flag 3 = NO_PADDING(1) | NO_WRAP(2), NOT URL_SAFE!
                val iidHashDigest = MessageDigest.getInstance("SHA-256").digest(iidToken.toByteArray(Charsets.UTF_8))
                val iidHashPadded = iidHashDigest.copyOf(64)  // Pad to 64 bytes with zeros
                val iidHashFull = android.util.Base64.encodeToString(iidHashPadded,
                    android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)  // Flag 3 = NO_PADDING | NO_WRAP
                val iidHash = iidHashFull.substring(0, 32)  // Truncate to 32 chars like GMS

                // Helper function to generate RPC-specific DroidGuard token
                // CRITICAL: Each API method REQUIRES its own token with matching RPC binding!
                // GMS bbah.java passes ONLY the method name (lowercase), NOT full gRPC path:
                //   line 369: "setConsent"
                //   line 516: "getConsent"
                //   line 1229: "sync"
                //   line 1354: "proceed"
                // See bbsd.java:92 - ezgo.o("iidHash", ..., "rpc", str2) where str2 is method name
                fun getDroidGuardToken(rpcMethod: String): String? {
                    // GMS uses lowercase method name, NOT full gRPC path!
                    Log.d(TAG, "Generating DroidGuard token for RPC: $rpcMethod")

                    val droidGuard = DroidGuardClientImpl(context)
                    val dgBindings = mapOf(
                        "iidHash" to iidHash,
                        "rpc" to rpcMethod  // Just method name, e.g., "sync", "getConsent"
                    )
                    val dgTask = droidGuard.getResults("constellation_verify", dgBindings, null)
                    return try {
                        val token = Tasks.await(dgTask, 30, TimeUnit.SECONDS)
                        Log.i(TAG, "Got DroidGuard token for $rpcMethod (${token?.length ?: 0} chars)")
                        token
                    } catch (e: Exception) {
                        Log.e(TAG, "DroidGuard task failed for $rpcMethod", e)
                        null
                    }
                }

                // Now call the actual Constellation API with the DroidGuard token
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

                // 5. Get or generate client key pair (MUST persist BOTH keys like GMS does!)
                // GMS: bekg.java:841-856 - reads from SharedPrefs "public_key", generates if missing
                // GMS: bekf.java:92 - signs with SHA256withECDSA using private key
                // We need to persist BOTH keys so we can sign client_credentials after server acknowledges
                val keyPrefs = context.getSharedPreferences("constellation_keys", Context.MODE_PRIVATE)
                val storedPublicKeyBase64 = keyPrefs.getString("public_key", null)
                val storedPrivateKeyBase64 = keyPrefs.getString("private_key", null)

                val (publicKeyBytes: ByteString, privateKey: java.security.PrivateKey?) = if (!storedPublicKeyBase64.isNullOrEmpty() && !storedPrivateKeyBase64.isNullOrEmpty()) {
                    // Use stored key pair (like GMS does)
                    try {
                        val publicDecoded = android.util.Base64.decode(storedPublicKeyBase64, android.util.Base64.DEFAULT)
                        val privateDecoded = android.util.Base64.decode(storedPrivateKeyBase64, android.util.Base64.DEFAULT)
                        // Reconstruct private key from PKCS8 encoded bytes
                        val keyFactory = java.security.KeyFactory.getInstance("EC")
                        val privKeySpec = java.security.spec.PKCS8EncodedKeySpec(privateDecoded)
                        val privKey = keyFactory.generatePrivate(privKeySpec)
                        Log.d(TAG, "Using stored key pair (public: ${publicDecoded.size} bytes, private: ${privateDecoded.size} bytes)")
                        Pair(ByteString.of(*publicDecoded), privKey)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to decode stored keys, generating new", e)
                        // Clear invalid keys and generate new
                        keyPrefs.edit().remove("public_key").remove("private_key").apply()
                        val keyGen = KeyPairGenerator.getInstance("EC")
                        keyGen.initialize(256)
                        val keyPair = keyGen.generateKeyPair()
                        val publicEncoded = keyPair.public.encoded
                        val privateEncoded = keyPair.private.encoded
                        // Store both keys for next time
                        keyPrefs.edit()
                            .putString("public_key", android.util.Base64.encodeToString(publicEncoded, android.util.Base64.DEFAULT))
                            .putString("private_key", android.util.Base64.encodeToString(privateEncoded, android.util.Base64.DEFAULT))
                            .apply()
                        Log.d(TAG, "Generated and stored new key pair (public: ${publicEncoded.size} bytes, private: ${privateEncoded.size} bytes)")
                        Pair(ByteString.of(*publicEncoded), keyPair.private)
                    }
                } else {
                    // Generate new key pair and store BOTH keys (like GMS does on first run)
                    val keyGen = KeyPairGenerator.getInstance("EC")
                    keyGen.initialize(256)
                    val keyPair = keyGen.generateKeyPair()
                    val publicEncoded = keyPair.public.encoded
                    val privateEncoded = keyPair.private.encoded
                    // Store both keys for next time
                    keyPrefs.edit()
                        .putString("public_key", android.util.Base64.encodeToString(publicEncoded, android.util.Base64.DEFAULT))
                        .putString("private_key", android.util.Base64.encodeToString(privateEncoded, android.util.Base64.DEFAULT))
                        .apply()
                    Log.d(TAG, "Generated and stored new key pair (public: ${publicEncoded.size} bytes, private: ${privateEncoded.size} bytes)")
                    Pair(ByteString.of(*publicEncoded), keyPair.private)
                }

                val publicKeyBase64 = android.util.Base64.encodeToString(
                    publicKeyBytes.toByteArray(),
                    android.util.Base64.NO_WRAP
                )

                // 5b. Check if server has acknowledged our public key (GMS bbah.java:1163)
                // If true, we need to sign requests with client_credentials
                val isPublicKeyAcked = keyPrefs.getBoolean("is_public_key_acked", false)
                Log.d(TAG, "is_public_key_acked: $isPublicKeyAcked")

                var clientSigInput: String? = null
                var clientSigBase64: String? = null
                var clientSigSeconds: Long? = null
                var clientSigNanos: Int? = null

                // Helper to create ClientCredentials with signature (GMS bekg.java:1166-1198, bekf.java:92)
                // Signature string format: {iid_token}:{seconds}:{nanos} (GMS bbah.java:1178)
                // Signed with SHA256withECDSA using the persisted private key
                fun createClientCredentials(iidTokenForSig: String, deviceIdForCreds: DeviceId, force: Boolean = false): ClientCredentials? {
                    if ((!isPublicKeyAcked && !force) || privateKey == null) {
                        return null
                    }
                    try {
                        val nowMillis = System.currentTimeMillis()
                        val seconds = nowMillis / 1000
                        val nanos = ((nowMillis % 1000) * 1000000).toInt()

                        // Build signing string: {iid_token}:{seconds}:{nanos}
                        val signingString = "$iidTokenForSig:$seconds:$nanos"
                        Log.d(TAG, "Signing string: $signingString")
                        clientSigInput = signingString
                        clientSigSeconds = seconds
                        clientSigNanos = nanos

                        // Sign with SHA256withECDSA
                        val signature = java.security.Signature.getInstance("SHA256withECDSA")
                        signature.initSign(privateKey)
                        signature.update(signingString.toByteArray(Charsets.UTF_8))
                        val signatureBytes = signature.sign()
                        Log.d(TAG, "Generated signature (${signatureBytes.size} bytes)")
                        clientSigBase64 = android.util.Base64.encodeToString(
                            signatureBytes,
                            android.util.Base64.NO_WRAP
                        )

                        return ClientCredentials(
                            device_id = deviceIdForCreds,
                            client_signature = ByteString.of(*signatureBytes),
                            metadata = CredentialMetadata(
                                timestamp_nanos = seconds,  // Actually seconds despite name (GMS hnnk.b)
                                nonce = nanos               // Actually nanos (GMS hnnk.c)
                            )
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to create client credentials", e)
                        return null
                    }
                }

                // 6. Get CountryInfo from TelephonyManager (verified in GMS bbah.java:841, bbtm.java:116,140)
                val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                val simCountry = telephonyManager?.simCountryIso?.lowercase(Locale.ROOT) ?: ""
                val networkCountry = telephonyManager?.networkCountryIso?.lowercase(Locale.ROOT) ?: ""
                Log.d(TAG, "CountryInfo: simCountry=$simCountry, networkCountry=$networkCountry")

                val countryInfo = CountryInfo(
                    sim_countries = if (simCountry.isNotEmpty()) listOf(simCountry) else emptyList(),
                    network_countries = if (networkCountry.isNotEmpty()) listOf(networkCountry) else emptyList()
                )

                // 7. Get ConnectivityInfo from ConnectivityManager (verified in GMS bbah.java:1024-1104)
                val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                val connectivityInfos = mutableListOf<ConnectivityInfo>()
                val connectivityInfosJson = JSONArray()

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
                                NetworkInfo.State.SUSPENDED -> ConnectivityState.CONNECTIVITY_STATE_SUSPENDED
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
                            connectivityInfosJson.put(
                                JSONObject()
                                    .put("type", connType.name)
                                    .put("state", connState.name)
                                    .put("availability", connAvail.name)
                                    .put("raw_type", type)
                                    .put("raw_state", networkInfo.state.toString())
                                    .put("is_available", networkInfo.isAvailable)
                            )
                        }
                    }
                } catch (e: SecurityException) {
                    Log.w(TAG, "Could not get connectivity info", e)
                }
                Log.d(TAG, "ConnectivityInfos: ${connectivityInfos.size} networks")

                // 8. Build TelephonyInfo (REQUIRED for SIM verifications per proto)
                // CRITICAL FIX (Session 65): TelephonyInfo structure was completely wrong!
                // Field 1: int32 phone_type (enum)
                // Field 2: string group_id_level1
                val subscriptionManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
                val subscriptionInfo = subscriptionManager?.activeSubscriptionInfoList?.firstOrNull()
                val subId = subscriptionInfo?.subscriptionId ?: SubscriptionManager.INVALID_SUBSCRIPTION_ID
                val telephonyManagerSub = if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                    try {
                        telephonyManager?.createForSubscriptionId(subId) ?: telephonyManager
                    } catch (e: Exception) {
                        telephonyManager
                    }
                } else {
                    telephonyManager
                }
                val simOperatorStr = telephonyManagerSub?.simOperator ?: ""
                val networkOperatorStr = telephonyManagerSub?.networkOperator ?: ""
                val groupIdLevel1 = try {
                    telephonyManagerSub?.groupIdLevel1 ?: ""
                } catch (e: SecurityException) {
                    Log.w(TAG, "No permission for GroupIdLevel1")
                    ""
                }

                // Get IMEI (requires permission)
                val imei = try {
                    @Suppress("DEPRECATION")
                    telephonyManagerSub?.deviceId ?: ""
                } catch (e: SecurityException) {
                    Log.w(TAG, "No permission for IMEI")
                    ""
                }

                // Get ICCID (SIM serial number, requires permission)
                val iccId = try {
                    subscriptionInfo?.iccId
                        ?: run {
                            @Suppress("DEPRECATION")
                            telephonyManagerSub?.simSerialNumber
                        }
                        ?: ""
                } catch (e: SecurityException) {
                    Log.w(TAG, "No permission for ICCID")
                    ""
                }

                // TelephonyInfo field ordering follows 26.02.33 builder (bfqi.java)
                val phoneTypeEnum = when (telephonyManagerSub?.phoneType) {
                    TelephonyManager.PHONE_TYPE_GSM -> 1
                    TelephonyManager.PHONE_TYPE_CDMA -> 2
                    TelephonyManager.PHONE_TYPE_SIP -> 3
                    else -> 0
                }

                val isRoaming = telephonyManagerSub?.isNetworkRoaming == true
                val roamingStateTelephony = if (isRoaming) 2 else 1

                val activeNetworkInfo = connectivityManager?.activeNetworkInfo
                val roamingStateNetwork = when {
                    activeNetworkInfo == null -> 0
                    activeNetworkInfo.isRoaming -> 2
                    else -> 1
                }

                val hasReadSms = context.checkSelfPermission(android.Manifest.permission.READ_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED
                val hasSendSms = context.checkSelfPermission(android.Manifest.permission.SEND_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED
                var smsCapabilityInt = 3
                if (hasReadSms && hasSendSms) {
                    val userManager = context.getSystemService(Context.USER_SERVICE) as? android.os.UserManager
                    val userRestricted = userManager?.userRestrictions?.getBoolean("no_sms") == true
                    val isSmsCapable = telephonyManagerSub?.isSmsCapable == true
                    val i2 = if (userRestricted) 6 else if (!isSmsCapable) 3 else 4
                    smsCapabilityInt = i2 - 2
                }

                val activeSubCount = subscriptionManager?.activeSubscriptionInfoCount ?: 1

                val maxSubCount = subscriptionManager?.activeSubscriptionInfoCountMax ?: 1

                val simSlotIndex = subscriptionInfo?.simSlotIndex ?: 0

                val hasPrivilegedPhoneState = context.checkSelfPermission("android.permission.READ_PRIVILEGED_PHONE_STATE") == android.content.pm.PackageManager.PERMISSION_GRANTED
                val hasCarrierIdCapability = if (!hasPrivilegedPhoneState || telephonyManagerSub == null) {
                    false
                } else {
                    try {
                        telephonyManagerSub.javaClass.getMethod("getIccAuthentication", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java)
                        true
                    } catch (e: Exception) {
                        try {
                            telephonyManagerSub.javaClass.getMethod("getIccSimChallengeResponse", Int::class.javaPrimitiveType, String::class.java)
                            true
                        } catch (e2: Exception) {
                            false
                        }
                    }
                }
                val carrierIdCapabilityInt = if (hasCarrierIdCapability) 2 else 1

                val smsNoConfirmGranted = context.checkSelfPermission("android.permission.SEND_SMS_NO_CONFIRMATION") == android.content.pm.PackageManager.PERMISSION_GRANTED
                val smsNoConfirmInt = if (smsNoConfirmGranted) 2 else 1

                val simStateEnum = if (telephonyManagerSub?.simState == TelephonyManager.SIM_STATE_READY) 2 else 1

                val serviceStateEnum = try {
                    when (telephonyManagerSub?.serviceState?.state) {
                        ServiceState.STATE_IN_SERVICE -> 1
                        ServiceState.STATE_OUT_OF_SERVICE -> 2
                        ServiceState.STATE_EMERGENCY_ONLY -> 3
                        ServiceState.STATE_POWER_OFF -> 4
                        else -> 0
                    }
                } catch (e: Exception) {
                    0
                }

                val isEmbedded = subscriptionInfo?.isEmbedded ?: false
                val carrierId = try {
                    telephonyManagerSub?.simCarrierId?.toLong() ?: -1L
                } catch (e: Exception) {
                    -1L
                }

                val telephonyInfo = TelephonyInfo(
                    sim_operator = phoneTypeEnum,
                    network_operator = groupIdLevel1,
                    sim_country = MobileOperatorCountry(      // Field 3 (bfdv.java:220)
                        country_iso = simCountry,
                        mcc_mnc = simOperatorStr,
                        operator_name = telephonyManager?.simOperatorName ?: "",
                        nil_since_millis = 0
                    ),
                    network_country = MobileOperatorCountry(  // Field 4 (bfdv.java:244)
                        country_iso = networkCountry,
                        mcc_mnc = networkOperatorStr,
                        operator_name = telephonyManager?.networkOperatorName ?: "",
                        nil_since_millis = 0
                    ),
                    data_roaming = roamingStateTelephony,
                    roaming = roamingStateNetwork,
                    sms_capability = smsCapabilityInt,
                    subscription_count = activeSubCount,      // Field 8 (bfdv.java:285)
                    subscription_count_max = maxSubCount,     // Field 9 (bfdv.java:290)
                    eap_aka_capability = carrierIdCapabilityInt,
                    sms_no_confirm_capability = smsNoConfirmInt,
                    service_state = simStateEnum,
                    imei = imei.takeIf { it.isNotEmpty() },
                    phone_type = serviceStateEnum,
                    is_embedded = isEmbedded,
                    timestamp = carrierId
                )
                Log.d(TAG, "TelephonyInfo: phoneType=$phoneTypeEnum, gid1=$groupIdLevel1, simState=$simStateEnum, serviceState=$serviceStateEnum, subs=$activeSubCount/$maxSubCount")

                // 9. Build Sync Request with ALL required fields
                val sessionId = UUID.randomUUID().toString()
                // GMS uses "language_country" format explicitly (e.g., "en_US")
                val locale = Locale.getDefault()
                val localeStr = "${locale.language}_${locale.country}"

                val gaiaTokens = getGaiaTokens(packageName)
                // CRITICAL FIX 2026-01-31: Field 12 is GaiaId containing OAuth token
                // Captured data shows: {field 1: "ya29.m...."}
                val gaiaCredentials = gaiaTokens.filter { it.isNotEmpty() }.map { GaiaId(oauth_token = it) }
                
                // Field 2 of GetConsentRequest: GaiaCredential (single, not repeated)
                val gaiaCredential = gaiaTokens.firstOrNull()?.let { GaiaCredential(oauth_token = it) }
                
                // Field 9: gaia_ids in ClientInfo - same type as field 12 (repeated GaiaId)
                val gaiaIdsForClientInfo = gaiaTokens.filter { it.isNotEmpty() }.map { GaiaId(oauth_token = it) }
                
                // SIMAssociation.identifiers (field 2) uses repeated StringId
                val simAssociationIdentifiers = gaiaTokens.filter { it.isNotEmpty() }.map { StringId(value_ = it) }

                // CRITICAL FIX 2026-02-01: Build TelephonyInfoContainer (field 20)
                // GMS sends this with Gaia IDs - NOT iccid as previously assumed!
                val gaiaIdsList = getGaiaIds()
                val nowMillis = System.currentTimeMillis()
                val telephonyInfoContainer = if (gaiaIdsList.isNotEmpty()) {
                    val entries = gaiaIdsList.map { gaiaId ->
                        TelephonyInfoEntry(
                            gaia_id = gaiaId,
                            state = 1,  // State=1 in all captured GMS traffic
                            timestamp = Timestamp(
                                seconds = nowMillis / 1000,
                                nanos = ((nowMillis % 1000) * 1_000_000).toInt()
                            )
                        )
                    }
                    TelephonyInfoContainer(entries = entries)
                } else {
                    null
                }
                Log.d(TAG, "TelephonyInfoContainer: ${gaiaIdsList.size} Gaia IDs")

                val requestImsi = request?.imsiRequests?.firstOrNull()?.imsi
                val requestMsisdn = request?.imsiRequests?.firstOrNull()?.msisdn
                val imsi = requestImsi ?: imsiOverride ?: ""
                val msisdn = requestMsisdn ?: msisdnOverride ?: requestedNumber ?: ""
                val phoneNumber = request?.phoneNumber ?: msisdn

                // CRITICAL FIX 2026-02-01: GMS hardcodes these required params (bekg.java captured diff)
                // GMS sends: policy_id=test_app_all_challenge_types, calling_api=verifyPhoneNumber, calling_package=com.google.android.gms
                // We were incorrectly passing through caller's extras (consent_type, force_provisioning, etc.)
                val requiredParams = listOf(
                    Param(name = "policy_id", value_ = "test_app_all_challenge_types"),
                    Param(name = "calling_api", value_ = "verifyPhoneNumber"),
                    Param(name = "calling_package", value_ = "com.google.android.gms")
                )
                val callerParams = bundleToParams(request?.extras)
                val params = requiredParams + callerParams  // Required params first, then caller's extras
                Log.d(TAG, "Verification params: ${params.joinToString { "${it.name}=${it.value_}" }}")

                val imsiRequests = if (request?.imsiRequests != null && request.imsiRequests.isNotEmpty()) {
                    request.imsiRequests.map {
                        IMSIRequest(imsi = it.imsi ?: "", phone_number_hint = it.msisdn ?: "")
                    }
                } else if (imsi.isNotEmpty()) {
                    listOf(IMSIRequest(imsi = imsi, phone_number_hint = msisdn))
                } else {
                    emptyList()
                }

                val idTokenRequest = request?.idTokenRequest?.let {
                    ProtoIdTokenRequest(it.audience ?: "", it.nonce ?: "")
                }

                // NOTE: CarrierInfo (hzcz) is NOT used in Verification.field_7 (that's VerificationMethodInfo/hyze)
                // CarrierInfo IS defined in GetConsentRequest.field_7 but stock GMS doesn't set it
                // Keeping this code commented for future reference if needed:
                // val carrierInfo = if (phoneNumber.isNotEmpty() || imsiRequests.isNotEmpty() || idTokenRequest != null) {
                //     CarrierInfo(phone_number = phoneNumber, subscription_id = request?.subscriptionId ?: -1L, ...)
                // } else { null }

                val extensionData = loadExtensionData()

                // Get Android IDs for DeviceId (GMS bejs.java:93-126)
                val androidIdStr = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                val androidIdFromSettings = try { java.lang.Long.parseUnsignedLong(androidIdStr, 16) } catch (e: Exception) { 0L }

                // device_user_id = UserManager.getSerialNumberForUser() (GMS bejs.java:56-60)
                val userManager = context.getSystemService(Context.USER_SERVICE) as? android.os.UserManager
                val deviceUserId = try {
                    val serial = userManager?.getSerialNumberForUser(android.os.Process.myUserHandle())
                    Log.d(TAG, "UserManager.getSerialNumberForUser() = $serial")
                    serial ?: 0L
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to get user serial number", e)
                    0L
                }

                // user_android_id = Settings.Secure.ANDROID_ID (GMS bejs.java:42-54, bdzc.d)
                val userAndroidId = androidIdFromSettings

                // device_android_id = SharedPreferences "primary_device_id" with fallback (GMS bejs.java:116-123, beob.java:57-59)
                // For new clients: primary_device_id=0, then if flag.l() && isSystemUser() && 0==0, falls back to user_android_id
                val devicePrefs = context.getSharedPreferences("constellation_prefs", Context.MODE_PRIVATE)
                var deviceAndroidId = devicePrefs.getLong("primary_device_id", 0L)
                if (deviceAndroidId == 0L) {
                    // Fallback logic: if system user and primary_device_id not set, use user_android_id
                    val isSystemUser = userManager?.isSystemUser ?: true
                    if (isSystemUser) {
                        deviceAndroidId = userAndroidId
                    }
                }

                Log.d(TAG, "Android IDs: device=$deviceAndroidId, deviceUser=$deviceUserId, userAndroid=$userAndroidId")

                fun buildSnapshot(
                    phase: String,
                    droidGuardToken: String?,
                    clientCredentialsIncluded: Boolean?
                ): JSONObject {
                    val snapshot = JSONObject()
                    snapshot.put("phase", phase)
                    snapshot.put("timestamp_ms", System.currentTimeMillis())
                    snapshot.put("api_key", API_KEY)
                    snapshot.put("package_name", packageName)
                    snapshot.put("cert_sha1", certSha1)
                    snapshot.put("iid_token", iidToken)
                    snapshot.put("iid_source", iidSource)
                    snapshot.put("instance_id", instanceId)
                    snapshot.put("iid_hash_full", iidHashFull)
                    snapshot.put("iid_hash_32", iidHash)
                    snapshot.put("public_key_base64", publicKeyBase64)
                    snapshot.put("is_public_key_acked", isPublicKeyAcked)
                    snapshot.put("client_signature_base64", clientSigBase64)
                    snapshot.put("client_signature_input", clientSigInput)
                    snapshot.put("client_signature_seconds", clientSigSeconds)
                    snapshot.put("client_signature_nanos", clientSigNanos)
                    if (clientCredentialsIncluded != null) {
                        snapshot.put("client_credentials_included", clientCredentialsIncluded)
                    }
                    snapshot.put("session_id", sessionId)
                    snapshot.put("locale", localeStr)
                    snapshot.put("gaia_token_count", simAssociationIdentifiers.size)
                    snapshot.put("gmscore_version_number", GMSCORE_VERSION_NUMBER)
                    snapshot.put("gmscore_version", GMSCORE_VERSION)
                    snapshot.put("android_sdk_version", Build.VERSION.SDK_INT)
                    snapshot.put("model", Build.MODEL)
                    snapshot.put("manufacturer", Build.MANUFACTURER)
                    snapshot.put("device_fingerprint", Build.FINGERPRINT)
                    snapshot.put("device_type", DeviceType.DEVICE_TYPE_PHONE.name)
                    snapshot.put("has_read_privileged_phone_state_permission", 1)
                    snapshot.put("is_standalone_device", false)
                    snapshot.put("telephony_info_container_included", telephonyInfoContainer != null)
                    snapshot.put("gaia_ids_count", gaiaIdsList.size)

                    val deviceIdJson = JSONObject()
                        .put("iid_token", iidToken)
                        .put("device_android_id", deviceAndroidId)
                        .put("device_user_id", deviceUserId)
                        .put("user_android_id", userAndroidId)
                    snapshot.put("device_id", deviceIdJson)

                    val topDeviceIdJson = JSONObject().put("iid_token", iidToken)
                    snapshot.put("device_id_top_level", topDeviceIdJson)

                    val deviceSignalsJson = JSONObject()
                        .put("droidguard_result", JSONObject.NULL)
                        .put("droidguard_token", droidGuardToken)
                        .put("droidguard_token_length", droidGuardToken?.length ?: 0)
                    snapshot.put("device_signals", deviceSignalsJson)

                    val countryJson = JSONObject()
                        .put("sim_countries", JSONArray().put(simCountry))
                        .put("network_countries", JSONArray().put(networkCountry))
                    snapshot.put("country_info", countryJson)

                    snapshot.put("connectivity_infos", connectivityInfosJson)

                    val telephonyJson = JSONObject()
                        .put("phone_type", phoneTypeEnum)
                        .put("group_id_level1", groupIdLevel1)
                        .put("sim_country", JSONObject()
                            .put("country_iso", simCountry)
                            .put("mcc_mnc", simOperatorStr)
                            .put("operator_name", telephonyManager?.simOperatorName ?: "")
                            .put("nil_since_millis", 0))
                        .put("network_country", JSONObject()
                            .put("country_iso", networkCountry)
                            .put("mcc_mnc", networkOperatorStr)
                            .put("operator_name", telephonyManager?.networkOperatorName ?: "")
                            .put("nil_since_millis", 0))
                        .put("data_roaming", roamingStateTelephony)
                        .put("roaming", roamingStateNetwork)
                        .put("sms_capability", smsCapabilityInt)
                        .put("subscription_count", activeSubCount)
                        .put("subscription_count_max", maxSubCount)
                        .put("eap_aka_capability", carrierIdCapabilityInt)
                        .put("sms_no_confirm_capability", smsNoConfirmInt)
                        .put("sim_state", simStateEnum)
                        .put("imei", imei)
                        .put("service_state", serviceStateEnum)
                        .put("is_embedded", isEmbedded)
                        .put("carrier_id", carrierId)
                    snapshot.put("telephony_info", telephonyJson)

                    val simAssoc = JSONObject()
                        .put("imsi", JSONArray().put(imsi))
                        .put("sim_readable_number", msisdn)
                        .put("iccid", iccId)
                        .put("sim_slot", JSONObject().put("index", simSlotIndex).put("sub_id", subId))
                    snapshot.put("sim_association", simAssoc)

                    snapshot.put("getconsent_include_asterism_consents", JSONObject.NULL)
                    snapshot.put("getconsent_asterism_client_bool", true)
                    snapshot.put("getconsent_imei", JSONObject.NULL)

                    return snapshot
                }

                // Generate Sync-specific DroidGuard token
                // GMS bbah.java:1229 uses lowercase "sync"
                val syncToken = getDroidGuardToken("sync")
                if (syncToken == null) {
                    Log.e(TAG, "Failed to get DroidGuard token for Sync")
                    return@runBlocking Ts43Client.EntitlementResult.error("droidguard-failed-sync")
                }

                // Build DeviceId for client_credentials (same as in ClientInfo)
                val syncDeviceId = DeviceId(
                    iid_token = iidToken,
                    device_android_id = deviceAndroidId,
                    device_user_id = deviceUserId,
                    user_android_id = userAndroidId
                )

                // Create client_credentials (GMS bbah.java:1163-1198)
                // FORCE INCLUDE: GMS sends this even on first sync (captured in field 2)
                // We bypass the isPublicKeyAcked check to match GMS behavior
                val syncClientCredentials = createClientCredentials(iidToken, syncDeviceId, force = true)
                if (syncClientCredentials != null) {
                    Log.d(TAG, "Including client_credentials in Sync request (forced)")
                }

                // Create PartialSIMInfo (field 7) - VERIFIED structure 2026-02-01
                // GMS sends: 7 { 2 { 2: "tOA0iAKTMdc" } }
                // We send a random 11-char alphanumeric string to mimic this
                val partialSimInfo = PartialSIMInfo(
                    data_ = PartialSIMData(value_ = "tOA0iAKTMdc") // Using literal from capture for now
                )

                val request = SyncRequest(
                    verifications = listOf(
                        Verification(
                            association = VerificationAssociation(
                                sim = SIMAssociation(
                                    sim_info = SIMInfo(
                                        imsi = listOf(imsi),
                                        sim_readable_number = msisdn,
                                        iccid = iccId
                                    ),
                                    identifiers = simAssociationIdentifiers,
                                    sim_slot = SIMSlot(
                                        index = simSlotIndex,
                                        sub_id = subId
                                    )
                                )
                            ),
                            state = VerificationState.VERIFICATION_STATE_NONE,
                            telephony_info = telephonyInfo,
                            params = params,
                            partial_sim_info = partialSimInfo
                        )
                    ),
                    header_ = RequestHeader(
                        session_id = sessionId,
                        client_credentials = syncClientCredentials,
                        client_info = ClientInfo(
                            device_id = syncDeviceId,
                            client_public_key = publicKeyBytes,
                            locale = localeStr,
                            gmscore_version_number = GMSCORE_VERSION_NUMBER,
                            gmscore_version = GMSCORE_VERSION,
                            android_sdk_version = Build.VERSION.SDK_INT,
                            device_signals = DeviceSignals(droidguard_token = syncToken),
                            gaia_ids = gaiaIdsForClientInfo,
                            has_read_privileged_phone_state_permission = 1,
                            gaia_credentials = gaiaCredentials,
                            country_info = countryInfo,
                            connectivity_infos = connectivityInfos,
                            is_standalone_device = false,
                            telephony_info_container = telephonyInfoContainer,
                            model = Build.MODEL,
                            manufacturer = Build.MANUFACTURER,
                            device_fingerprint = Build.FINGERPRINT,
                            device_type = DeviceType.DEVICE_TYPE_UNKNOWN,
                            experiment_infos = emptyList()
                        ),
                        trigger = RequestTrigger(
                            type = TriggerType.TRIGGER_TYPE_TRIGGER_API_CALL
                        )
                    ),
                    extension_data = extensionData
                )

                logSnapshot(
                    "ConstellationSnapshot-sync",
                    buildSnapshot("sync", syncToken, syncClientCredentials != null)
                )

                // 9. First call GetConsent (required before Sync for new clients!)
                // From proto: "When the client is a new client coming online for the first time.
                // It has checked the consent using GetConsent."
                Log.d(TAG, "Calling GetConsent first...")

                // Generate GetConsent-specific DroidGuard token
                // GMS bbah.java:516 uses camelCase "getConsent" (lowercase 'g')
                val getConsentToken = getDroidGuardToken("getConsent")
                if (getConsentToken == null) {
                    Log.e(TAG, "Failed to get DroidGuard token for GetConsent")
                    return@runBlocking Ts43Client.EntitlementResult.error("droidguard-failed-getconsent")
                }

                val consentRequest = google.internal.communications.phonedeviceverification.v1.GetConsentRequest(
                    // CRITICAL: GMS sets DeviceId at TOP LEVEL (field 1) even though proto says DEPRECATED
                    // bekg.java:492-509: ONLY sets iid_token (hvzgVar.b = str), omits android_id fields!
                    device_id = DeviceId(
                        iid_token = iidToken
                        // device_android_id: OMIT - GMS doesn't set (proto default omission)
                        // device_user_id: OMIT - GMS doesn't set
                        // user_android_id: OMIT - GMS doesn't set
                    ),
                    // CRITICAL FIX 2026-01-31: Field 2 is GaiaCredential (not repeated StringId!)
                    // Captured data shows: field 2 → {field 1: "ya29.m...."}
                    gaia_credential = gaiaCredential,
                    header_ = RequestHeader(
                        session_id = sessionId,
                        client_info = ClientInfo(
                            device_id = DeviceId(
                                iid_token = iidToken,
                                device_android_id = deviceAndroidId,  // SharedPrefs primary_device_id
                                device_user_id = deviceUserId,  // UserManager serial
                                user_android_id = userAndroidId  // Settings.Secure ANDROID_ID
                            ),
                            client_public_key = publicKeyBytes,
                            locale = localeStr,
                            gmscore_version_number = GMSCORE_VERSION_NUMBER,
                            gmscore_version = GMSCORE_VERSION,
                            android_sdk_version = Build.VERSION.SDK_INT,
                            device_signals = DeviceSignals(droidguard_token = getConsentToken),
                            model = Build.MODEL,
                            manufacturer = Build.MANUFACTURER,
                            device_fingerprint = Build.FINGERPRINT,
                            device_type = DeviceType.DEVICE_TYPE_PHONE,
                            has_read_privileged_phone_state_permission = 1,
                            // CRITICAL FIX 2026-01-31: Field 12 is GaiaId with OAuth token
                            gaia_credentials = gaiaCredentials,
                            country_info = countryInfo,
                            connectivity_infos = connectivityInfos,
                            is_standalone_device = false,
                            // CRITICAL FIX 2026-02-01: Field 20 IS sent by GMS
                            telephony_info_container = telephonyInfoContainer
                        ),
                        trigger = RequestTrigger(type = TriggerType.TRIGGER_TYPE_TRIGGER_API_CALL)
                    ),
                    // CRITICAL FIX 2026-01-31: Field 5 is repeated Param with policy_id, calling_api, calling_package
                    // Captured from GMS: 3 entries required for GetConsent to succeed
                    api_params = listOf(
                        Param(name = "policy_id", value_ = "test_app_all_challenge_types"),
                        Param(name = "calling_api", value_ = "verifyPhoneNumber"),
                        Param(name = "calling_package", value_ = "com.google.android.gms")
                    ),
                    // CRITICAL FIX 2026-01-31: Field 8 is include_asterism_consents = 1 (not bool)
                    // Captured data shows: field 8: varint = 1 (CONSTELLATION enum)
                    include_asterism_consents = 1,
                    imei = null
                )

                logSnapshot(
                    "ConstellationSnapshot-getConsent",
                    buildSnapshot("getConsent", getConsentToken, false)
                )

                // Dump FULL GetConsentRequest hex for debugging (compare with protobuf spec)
                val consentRequestBytes = google.internal.communications.phonedeviceverification.v1.GetConsentRequest.ADAPTER.encode(consentRequest)
                Log.d(TAG, "GetConsentRequest size: ${consentRequestBytes.size} bytes")
                // Log in chunks of 1000 chars (500 bytes) to avoid logcat truncation
                val fullHex = consentRequestBytes.joinToString("") { String.format("%02x", it) }
                var offset = 0
                while (offset < fullHex.length) {
                    val chunk = fullHex.substring(offset, kotlin.math.min(offset + 1000, fullHex.length))
                    Log.d(TAG, "GetConsentRequest hex[$offset]: $chunk")
                    offset += 1000
                }

                try {
                    val consentResponse = client.GetConsent().execute(consentRequest)
                    Log.d(TAG, "GetConsent response: device_consent=${consentResponse.device_consent}, asterism_client=${consentResponse.asterism_client}")
                    if (consentResponse.device_consent?.consent == google.internal.communications.phonedeviceverification.v1.ConsentValue.CONSENT_VALUE_CONSENTED) {
                        Log.i(TAG, "Consent already established")
                    } else {
                        Log.w(TAG, "No consent yet - may need SetConsent call")
                    }
                } catch (e: Exception) {
                    // Extract full gRPC error details
                    val errorDetails = StringBuilder()
                    errorDetails.append("verifyPhoneNumber failed: ${e.javaClass.simpleName}")
                    if (e.message != null) {
                        errorDetails.append("\n  Message: ${e.message}")
                    }
                    if (e.cause != null) {
                        errorDetails.append("\n  Cause: ${e.cause?.javaClass?.simpleName}: ${e.cause?.message}")
                    }
                
                    // Check for gRPC status in message (format: "grpc-status=N, grpc-message=...")
                    val msg = e.message ?: ""
                    if (msg.contains("grpc-status")) {
                        // Parse status code
                        val statusMatch = Regex("grpc-status=(\\d+)").find(msg)
                        val status = statusMatch?.groupValues?.get(1)
                    
                        val messageMatch = Regex("grpc-message=(.*)").find(msg)
                        val grpcMessage = messageMatch?.groupValues?.get(1) ?: "Unknown error"
                    
                        Log.e(TAG, "gRPC Status: $status ($grpcMessage)")
                        Log.e(TAG, "gRPC Message: $grpcMessage")
                    }
                
                    Log.d(TAG, "Full exception trace:", e)
                
                    return@runBlocking Ts43Client.EntitlementResult.error("getconsent-failed")
                }

                try {

                // 10. Execute Sync
                Log.d(TAG, "Sending Sync request...")

                // Dump FULL SyncRequest hex for debugging
                val requestByteArray = SyncRequest.ADAPTER.encode(request)
                Log.d(TAG, "SyncRequest size: ${requestByteArray.size} bytes")
                // Log in chunks of 1000 chars (500 bytes) to avoid logcat truncation
                val syncHex = requestByteArray.joinToString("") { String.format("%02x", it) }
                var syncOffset = 0
                while (syncOffset < syncHex.length) {
                    val chunk = syncHex.substring(syncOffset, kotlin.math.min(syncOffset + 1000, syncHex.length))
                    Log.d(TAG, "SyncRequest hex[$syncOffset]: $chunk")
                    syncOffset += 1000
                }

                val response = client.Sync().execute(request)
                Log.d(TAG, "Received Sync response: $response")

                // 9b. Check for public key acknowledgment in response (GMS bbah.java:1746-1758)
                // Path: SyncResponse.header (field 3) → ResponseHeader.client_info_update (field 1)
                // → ClientInfoUpdate.public_key_status (field 1)
                val publicKeyStatus = response.header_?.client_info_update?.public_key_status
                if (publicKeyStatus == PublicKeyStatus.CLIENT_KEY_UPDATED) {
                    Log.i(TAG, "Public key acknowledged by server (status=$publicKeyStatus)")
                    keyPrefs.edit().putBoolean("is_public_key_acked", true).apply()
                } else if (publicKeyStatus == PublicKeyStatus.PUBLIC_KEY_STATUS_NO_STATUS) {
                    Log.d(TAG, "No public key status update from server")
                } else {
                    Log.d(TAG, "Public key status: $publicKeyStatus")
                }

                // 10. Process verification responses
                val responses = response.responses
                if (responses.isNotEmpty()) {
                    Log.d(TAG, "Processing ${responses.size} verification responses")
                    for (verificationResponse in responses) {
                        val state = verificationResponse.verification?.state
                        val simInfo = verificationResponse.verification?.association?.sim?.sim_info
                        val imsi = simInfo?.imsi?.firstOrNull() ?: ""
                        val msisdn = simInfo?.sim_readable_number ?: ""
                        
                        Log.d(TAG, "VerificationResponse: state=$state, imsi=$imsi, msisdn=$msisdn")
                        
                        if (state == VerificationState.VERIFICATION_STATE_VERIFIED) {
                            // TODO: Extract token and return success
                            // For now, we just log it
                            Log.i(TAG, "Phone number verified! Token extraction needed.")
                        }
                    }
                }

                return@runBlocking Ts43Client.EntitlementResult.stub(
                    "stub-token-from-sync",
                    "sync-success"
                )

                } catch (e: Exception) {
                    // Extract full gRPC error details
                    val errorDetails = StringBuilder()
                    errorDetails.append("Sync failed: ${e.javaClass.simpleName}: ${e.message}")
                    if (e.cause != null) {
                        errorDetails.append("\n  Cause: ${e.cause?.javaClass?.simpleName}: ${e.cause?.message}")
                    }
                
                    // Check for gRPC status in message (format: "grpc-status=N, grpc-message=...")
                    val msg = e.message ?: ""
                    if (msg.contains("grpc-status")) {
                        Log.e(TAG, "gRPC error: $msg")
                    }
                
                    Log.e(TAG, errorDetails.toString())
                    // Log full stack trace for debugging
                    Log.d(TAG, "Full exception trace:", e)
                
                    return@runBlocking Ts43Client.EntitlementResult.error("sync-failed")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "verifyPhoneNumber failed with exception", e)
            Ts43Client.EntitlementResult.error("exception-${e.javaClass.simpleName}")
        }
    }
}
