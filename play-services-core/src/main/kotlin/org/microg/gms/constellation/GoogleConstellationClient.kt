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
import org.microg.gms.common.Constants
import java.io.File
import kotlinx.coroutines.runBlocking
import okio.ByteString
import okio.ByteString.Companion.decodeBase64
import org.microg.gms.common.PackageUtils
import org.microg.gms.gcm.GcmDatabase
import org.microg.gms.auth.AuthConstants
import org.microg.gms.auth.AuthManager
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import google.internal.communications.phonedeviceverification.v1.AsterismClient
import google.internal.communications.phonedeviceverification.v1.RequestHeader
import google.internal.communications.phonedeviceverification.v1.RequestTrigger
import google.internal.communications.phonedeviceverification.v1.SIMAssociation
import google.internal.communications.phonedeviceverification.v1.SIMInfo
import google.internal.communications.phonedeviceverification.v1.StringId
import google.internal.communications.phonedeviceverification.v1.PartialSIMInfo
import google.internal.communications.phonedeviceverification.v1.PartialSIMData
import google.internal.communications.phonedeviceverification.v1.IMSIRequest
import google.internal.communications.phonedeviceverification.v1.Param
import google.internal.communications.phonedeviceverification.v1.SyncRequest
import google.internal.communications.phonedeviceverification.v1.SyncResponse
import google.internal.communications.phonedeviceverification.v1.Verification
import google.internal.communications.phonedeviceverification.v1.VerificationAssociation
import google.internal.communications.phonedeviceverification.v1.VerificationState
import google.internal.communications.phonedeviceverification.v1.VerificationMethodInfo
import google.internal.communications.phonedeviceverification.v1.VerificationMethodData
import google.internal.communications.phonedeviceverification.v1.VerificationMethod
import google.internal.communications.phonedeviceverification.v1.TriggerType
import google.internal.communications.phonedeviceverification.v1.ExperimentInfo
import google.internal.communications.phonedeviceverification.v1.TelephonyInfo
import google.internal.communications.phonedeviceverification.v1.TelephonyInfoContainer
import google.internal.communications.phonedeviceverification.v1.TelephonyInfoEntry
import google.internal.communications.phonedeviceverification.v1.Timestamp
import google.internal.communications.phonedeviceverification.v1.ClientCredentials
import google.internal.communications.phonedeviceverification.v1.PublicKeyStatus
import google.internal.communications.phonedeviceverification.v1.MobileOperatorCountry
import google.internal.communications.phonedeviceverification.v1.CredentialMetadata
import google.internal.communications.phonedeviceverification.v1.SIMSlot
import google.internal.communications.phonedeviceverification.v1.CarrierInfo
import google.internal.communications.phonedeviceverification.v1.IdTokenRequest
import com.google.android.gms.constellation.VerifyPhoneNumberRequest as AidlVerifyPhoneNumberRequest
import google.internal.communications.phonedeviceverification.v1.DroidGuardTokenResponse
import google.internal.communications.phonedeviceverification.v1.GetVerifiedPhoneNumbersRequest
import google.internal.communications.phonedeviceverification.v1.ClientCredentialsProto
import google.internal.communications.phonedeviceverification.v1.IdTokenRequestProto
import google.internal.communications.phonedeviceverification.v1.ProceedRequest
import google.internal.communications.phonedeviceverification.v1.ChallengeResponse
import google.internal.communications.phonedeviceverification.v1.MTChallengeResponse
import google.internal.communications.phonedeviceverification.v1.ProceedResponse
import google.internal.communications.phonedeviceverification.v1.SetConsentRequest
import google.internal.communications.phonedeviceverification.v1.GetConsentRequest
import google.internal.communications.phonedeviceverification.v1.ConsentValue
import google.internal.communications.phonedeviceverification.v1.DeviceVerificationConsent
import google.internal.communications.phonedeviceverification.v1.DeviceVerificationConsentSource
import google.internal.communications.phonedeviceverification.v1.DeviceVerificationConsentVersion

class GoogleConstellationClient(private val context: Context) {
    companion object {
        private const val TAG = "GmsConstellationClient"
        // GMS uses API key + Spatula auth (NO OAuth Bearer on gRPC transport - bewt.c bdpb has no account/scopes)
        private const val API_KEY = "AIzaSyAP-gfH3qvi6vgHZbSYwQ_XHqV_mXHhzIk"
        private const val GMSCORE_VERSION_NUMBER = 260233
        private const val GMSCORE_VERSION = "26.02.33 (190400-858744110)"
        private const val GAIA_TOKEN_SCOPE = "oauth2:https://www.googleapis.com/auth/numberer"

        // settings put global microg_constellation_force_one_time_verification off
        private const val FORCE_ONE_TIME_VERIFICATION_KEY = "microg_constellation_force_one_time_verification"

        // Constellation FCM sender ID (from GMS Phenotype IidToken__default_project_number)
        const val CONSTELLATION_SENDER_ID = "496232013492"
        // Read-only PhoneNumber API sender ID (from GMS Phenotype IidToken__read_only_project_number)
        const val READ_ONLY_SENDER_ID = "745476177629"

        /**
         * Get or register the IID token for Constellation.
         * This is used by both verifyPhoneNumber() and the getIidToken() AIDL method.
         *
         * @param context Application context
         * @param packageName Calling package name (usually "com.google.android.gms")
         * @return Pair of (token, source) where source indicates where token came from
         */
        @JvmStatic
        @JvmOverloads
        fun getOrRegisterIidToken(
            context: Context,
            packageName: String,
            senderId: String = CONSTELLATION_SENDER_ID
        ): Pair<String, String> {
            val prefs = context.getSharedPreferences("constellation_iid", Context.MODE_PRIVATE)

            // Check for cached token (either self-registered or seeded from stock GMS)
            val hasKeyPair = prefs.getString("key_private", null) != null
            val cachedToken = prefs.getString("iid_token_$senderId", null)
            val cachedSource = prefs.getString("iid_source_$senderId", null)
            val isSeededFromStock = cachedSource?.startsWith("seeded-from-stock") == true
            if (cachedToken != null && (hasKeyPair || isSeededFromStock)) {
                val reason = if (isSeededFromStock) "seeded-from-stock (skip pub2/sig check)" else "cached-$senderId"
                Log.d(TAG, "Using cached IID token for sender=$senderId ($reason)")
                return Pair(cachedToken, cachedSource ?: "cached-$senderId")
            }
            if (cachedToken != null && !hasKeyPair) {
                Log.w(TAG, "Invalidating cached token (registered without pub2/sig)")
                prefs.edit().remove("iid_token_$senderId").remove("iid_source_$senderId").apply()
            }

            // Try to seed from stock GMS's preserved data (GMS->microG swap).
            // Stock GMS stores per-sender IID tokens in appid.xml as "|T|{senderId}|GCM"
            // and the Constellation primary sender token also in constellation_prefs.xml as "gcm_token".

            // 1. Check appid.xml (covers ALL senders including read-only 745476177629)
            val appIdPrefs = context.getSharedPreferences("com.google.android.gms.appid", Context.MODE_PRIVATE)
            val appIdToken = appIdPrefs.getString("|T|$senderId|GCM", null)
            if (!appIdToken.isNullOrEmpty()) {
                Log.i(TAG, "Using preserved stock GMS IID token for sender $senderId (from appid.xml): ${appIdToken.take(20)}...")
                prefs.edit().putString("iid_token_$senderId", appIdToken).putString("iid_source_$senderId", "seeded-from-stock-gms-appid").apply()
                return Pair(appIdToken, "seeded-from-stock-gms-appid")
            }

            // 2. For Constellation primary sender, also check constellation_prefs.xml gcm_token
            if (senderId == CONSTELLATION_SENDER_ID) {
                val stockPrefs = context.getSharedPreferences("constellation_prefs", Context.MODE_PRIVATE)
                val stockGcmToken = stockPrefs.getString("gcm_token", null)
                if (!stockGcmToken.isNullOrEmpty()) {
                    Log.i(TAG, "Using preserved stock GMS IID token for sender $senderId (from constellation_prefs gcm_token): ${stockGcmToken.take(20)}...")
                    prefs.edit().putString("iid_token_$senderId", stockGcmToken).putString("iid_source_$senderId", "seeded-from-stock-gms").apply()
                    return Pair(stockGcmToken, "seeded-from-stock-gms")
                }
            }

            // No preserved token found - register fresh
            Log.i(TAG, "No preserved IID token found for sender $senderId, registering new")

            try {
                // Generate or retrieve Instance ID key pair
                var instanceId = prefs.getString("instance_id", null)
                var keyPair: java.security.KeyPair? = null

                if (instanceId == null) {
                    val rsaGenerator = java.security.KeyPairGenerator.getInstance("RSA")
                    rsaGenerator.initialize(2048)
                    keyPair = rsaGenerator.generateKeyPair()

                    // Calculate Instance ID: SHA1 of public key, modified first byte, base64
                    val digest = MessageDigest.getInstance("SHA1").digest(keyPair.public.encoded)
                    digest[0] = ((112 + (0xF and digest[0].toInt())) and 0xFF).toByte()
                    instanceId = android.util.Base64.encodeToString(digest, 0, 8,
                        android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
                    // Persist key pair for pub2/sig on future registrations
                    prefs.edit()
                        .putString("instance_id", instanceId)
                        .putString("key_public", android.util.Base64.encodeToString(keyPair.public.encoded, android.util.Base64.NO_WRAP))
                        .putString("key_private", android.util.Base64.encodeToString(keyPair.private.encoded, android.util.Base64.NO_WRAP))
                        .apply()
                    Log.d(TAG, "Generated new Instance ID + key pair: $instanceId")
                } else {
                    // Reload persisted key pair
                    val pubBytes = prefs.getString("key_public", null)?.let { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }
                    val privBytes = prefs.getString("key_private", null)?.let { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }
                    if (pubBytes != null && privBytes != null) {
                        val kf = java.security.KeyFactory.getInstance("RSA")
                        keyPair = java.security.KeyPair(
                            kf.generatePublic(java.security.spec.X509EncodedKeySpec(pubBytes)),
                            kf.generatePrivate(java.security.spec.PKCS8EncodedKeySpec(privBytes))
                        )
                    } else {
                        // Key pair missing - regenerate
                        Log.w(TAG, "Key pair missing for existing instance ID, regenerating")
                        prefs.edit().remove("instance_id").apply()
                        return getOrRegisterIidToken(context, packageName, senderId)
                    }
                }

                // Compute pub2 and sig for registration (matches InstanceIdRpc.sendRegisterMessage)
                val pubKeyBase64 = android.util.Base64.encodeToString(keyPair!!.public.encoded,
                    android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
                val signaturePayload = (packageName + "\n" + pubKeyBase64).toByteArray(Charsets.UTF_8)
                val sig = java.security.Signature.getInstance("SHA256withRSA")
                sig.initSign(keyPair.private)
                sig.update(signaturePayload)
                val signatureBase64 = android.util.Base64.encodeToString(sig.sign(),
                    android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)

                Log.d(TAG, "pub2 length=${pubKeyBase64.length}, sig length=${signatureBase64.length}")

                // Try to get a registration token from Google for this sender ID
                val checkinInfo = LastCheckinInfo.read(context)
                if (checkinInfo.androidId != 0L && checkinInfo.securityToken != 0L) {
                    try {
                        val certSha1 = PackageUtils.firstSignatureDigest(context, packageName)
                        val versionCode = org.microg.gms.common.Constants.GMS_VERSION_CODE
                        val versionName = "%09d".format(versionCode).let {
                            "${it.substring(0, 2)}.${it.substring(2, 4)}.${it.substring(4, 6)} (190400-858744110)"
                        }
                        val clientLibVersion = "iid-${(versionCode / 1000) * 1000}"

                        val response: RegisterResponse = RegisterRequest()
                            .build(context)
                            .checkin(checkinInfo)
                            .app(packageName, certSha1, versionCode)
                            .sender(senderId)
                            .extraParam("subscription", senderId)
                            .extraParam("X-subscription", senderId)
                            .extraParam("subtype", senderId)
                            .extraParam("X-subtype", senderId)
                            .extraParam("scope", "GCM")
                            .extraParam("gmsv", versionCode.toString())
                            .extraParam("osv", Build.VERSION.SDK_INT.toString())
                            .extraParam("app_ver", versionCode.toString())
                            .extraParam("app_ver_name", versionName)
                            .extraParam("cliv", clientLibVersion)
                            .extraParam("appid", instanceId!!)
                            .extraParam("pub2", pubKeyBase64)
                            .extraParam("sig", signatureBase64)
                            .getResponse()

                        if (response.token != null) {
                            Log.i(TAG, "Got IID registration token from Google for sender=$senderId (with pub2/sig): ${response.token.take(20)}...")
                            prefs.edit().putString("iid_token_$senderId", response.token).apply()
                            return Pair(response.token, "registered-$senderId")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Registration failed: ${e.message}, using Instance ID")
                    }
                } else {
                    Log.w(TAG, "Device not checked in yet, cannot register FCM token")
                }

                // Use Instance ID if registration failed
                Log.d(TAG, "Using Instance ID as fallback: $instanceId")
                return Pair(instanceId!!, "instance-id")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to get IID token", e)
                // Final fallback - random ID
                val randomId = java.util.UUID.randomUUID().toString().take(11).replace("-", "")
                Log.w(TAG, "Using random ID as last resort: $randomId")
                return Pair(randomId, "random-fallback")
            }
        }

    }

    /**
     * Find verified phone number matching target, or fall back to first.
     * Stock GMS matches by phone; we were using firstOrNull() blindly (wrong on multi-SIM).
     */
    private fun findMatchingVerifiedNumber(
        numbers: List<google.internal.communications.phonedeviceverification.v1.VerifiedPhoneNumber>,
        targetPhone: String?
    ): google.internal.communications.phonedeviceverification.v1.VerifiedPhoneNumber? {
        if (numbers.isEmpty()) return null
        if (!targetPhone.isNullOrEmpty()) {
            val match = numbers.firstOrNull { it.phone_number == targetPhone }
            if (match != null) return match
            Log.w(TAG, "No exact phone match for $targetPhone in ${numbers.size} numbers, using first")
        }
        return numbers.firstOrNull()
    }

    private suspend fun getGaiaTokens(packageName: String): List<String> {
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
            // CRITICAL FIX: Explicitly permit this scope for GMS so token isn't discarded
            // AuthManager.requestAuth() discards tokens if scope isn't permitted (lines 366-368)
            authManager.setPermitted(true)
            authManager.forceRefreshToken = true  // Skip cache to get fresh token
            val token = authManager.getAuthToken() ?: try {
                Log.d(TAG, "No cached token, requesting from Google for ${account.name}...")
                // AuthManager may perform network I/O. On some devices/threads (including Binder threads)
                // StrictMode can throw NetworkOnMainThreadException. Always run this on Dispatchers.IO.
                val response = withContext(Dispatchers.IO) {
                    authManager.requestAuthWithBackgroundResolution(false)
                }
                val effectiveToken = response.auths ?: response.auth
                Log.d(TAG, "Auth response for ${account.name}: ${effectiveToken?.length ?: 0} chars (${if (response.auths != null) "IT" else "auth"})")
                effectiveToken
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get gaia token for ${account.name}: ${e.javaClass.simpleName}: ${e.message}")
                e.printStackTrace()
                null
            }
            if (!token.isNullOrEmpty()) {
                tokens.add(token)
                Log.d(TAG, "Gaia token for ${account.name}: ${token.take(20)}... (${token.length} chars)")
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

    fun verifyPhoneNumber(request: AidlVerifyPhoneNumberRequest?, callingPackage: String?, imsiOverride: String?, msisdnOverride: String?): Ts43Client.EntitlementResult {
        val requestedNumber = request?.policyId ?: msisdnOverride
        Log.i(TAG, "verifyPhoneNumber: phone=$requestedNumber")

        return (try {
            // 1. Get package info for headers
            val packageName = context.packageName
            val certSha1 = PackageUtils.firstSignatureDigest(context, packageName)
            Log.d(TAG, "Using API key auth with package=$packageName, cert=$certSha1")

            runBlocking {
                var rpcClient: ConstellationRpcClient? = null
                try {
                // 2. Get IID token - MUST be registered with Constellation project ID!
                // Uses shared method that handles caching, registration, and fallbacks
                val (iidToken, iidSource) = getOrRegisterIidToken(context, packageName, CONSTELLATION_SENDER_ID)
                Log.d(TAG, "IID token source: $iidSource, token prefix: ${iidToken.take(20)}...")

                // GPNV (PhoneNumber/GetVerifiedPhoneNumbers) uses the read-only IID sender in stock GMS
                // (icet.e(): IidToken__read_only_project_number = 745476177629).
                val (readOnlyIidToken, readOnlyIidSource) = getOrRegisterIidToken(context, packageName, READ_ONLY_SENDER_ID)
                Log.d(TAG, "Read-only IID token source: $readOnlyIidSource, token prefix: ${readOnlyIidToken.take(20)}...")

                // 3. Calculate iidHash for DroidGuard content bindings
                // GMS bfck.java:24-30: SHA-256 hash, pad to 64 bytes, base64 NO_PADDING|NO_WRAP (NOT URL_SAFE!), truncate to 32 chars
                // CRITICAL FIX: GMS uses flag 3 = NO_PADDING(1) | NO_WRAP(2), NOT URL_SAFE!
                val iidHashDigest = MessageDigest.getInstance("SHA-256").digest(iidToken.toByteArray(Charsets.UTF_8))
                val iidHashPadded = iidHashDigest.copyOf(64)  // Pad to 64 bytes with zeros
                val iidHashFull = android.util.Base64.encodeToString(iidHashPadded,
                    android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)  // Flag 3 = NO_PADDING | NO_WRAP
                val iidHash = iidHashFull.substring(0, 32)  // Truncate to 32 chars like GMS

                // Load stored verification_tokens for SyncRequest (stock GMS bewt.java:702-704)
                fun loadVerificationTokens(prefs: android.content.SharedPreferences): List<google.internal.communications.phonedeviceverification.v1.VerificationToken> {
                    val stored = prefs.getStringSet("verification_tokens", null) ?: return emptyList()
                    return stored.mapNotNull { b64 ->
                        try {
                            google.internal.communications.phonedeviceverification.v1.VerificationToken.ADAPTER.decode(
                                android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)
                            )
                        } catch (e: Exception) { null }
                    }.also {
                        if (it.isNotEmpty()) Log.d(TAG, "Loaded ${it.size} verification_tokens from storage")
                    }
                }

                // 3. Setup gRPC + DG via ConstellationRpcClient
                val gaiaTokens = getGaiaTokens(packageName)
                if (gaiaTokens.isNotEmpty()) {
                    Log.i(TAG, "OAuth tokens for proto gaia_ids: ${gaiaTokens.first().take(15)}... (${gaiaTokens.first().length} chars)")
                }

                // Get real Spatula token from AppCertManager (upstream microG implementation)
                // Stock GMS: ajom.b("com.google.android.gms") → IAppCertService.getSpatulaHeader()
                // Returns Base64-encoded protobuf with HMAC, deviceId, keyCert
                var spatulaHeaderSource = "unattempted"
                val spatulaHeader = try {
                    Log.d(TAG, "Fetching Spatula header (10s timeout)...")
                    val spatula = runBlocking {
                        kotlinx.coroutines.withTimeoutOrNull(10_000L) {
                            org.microg.gms.auth.appcert.AppCertManager(context).getSpatulaHeader(packageName)
                        }
                    }
                    if (spatula != null) {
                        spatulaHeaderSource = "appcert"
                        Log.d(TAG, "Spatula header obtained (${spatula.length} chars)")
                    } else {
                        spatulaHeaderSource = "timeout_or_null"
                        Log.w(TAG, "Spatula header returned null (timeout or device key not available)")
                    }
                    spatula
                } catch (e: Exception) {
                    spatulaHeaderSource = "exception:${e.javaClass.simpleName}"
                    Log.w(TAG, "Failed to get Spatula header: ${e.message}")
                    null
                }

                rpcClient = ConstellationRpcClient(
                    context = context,
                    apiKey = API_KEY,
                    packageName = packageName,
                    certSha1 = certSha1,
                    spatulaHeader = spatulaHeader,
                    iidHash = iidHash
                )
                @Suppress("UnnecessaryVariable")
                val rpc = rpcClient!!  // Non-null local ref for use within this try block

                // 5. Get or generate client key pair (MUST persist BOTH keys like GMS does!)
                // GMS: bekg.java:841-856 - reads from SharedPrefs "public_key", generates if missing
                // GMS: bekf.java:92 - signs with SHA256withECDSA using private key
                // We need to persist BOTH keys so we can sign client_credentials after server acknowledges
                val keyPrefs = context.getSharedPreferences("constellation_prefs", Context.MODE_PRIVATE)
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

                // GetVerifiedPhoneNumbersRequest uses a separate auth container (IIDTokenAuth in discovery;
                // hzdq.java in GMS) that carries the IID token plus an ECDSA signature + timestamp.
                // The discovery doc explicitly warns non-allowlisted requests may require these fields,
                // so we generate them when we have a persisted Constellation private key.
                fun createIidTokenAuth(iidTokenForSig: String): ClientCredentialsProto {
                    if (privateKey == null) {
                        Log.w(TAG, "GPNV auth: private key missing; sending iid_token without client_signature")
                        return ClientCredentialsProto(
                            iid_token = iidTokenForSig,
                            client_signature = ByteString.EMPTY,
                            signature_timestamp = null
                        )
                    }

                    val nowMillis = System.currentTimeMillis()
                    val seconds = nowMillis / 1000
                    val nanos = ((nowMillis % 1000) * 1_000_000).toInt()
                    val signingString = "$iidTokenForSig:$seconds:$nanos"

                    return try {
                        val signature = java.security.Signature.getInstance("SHA256withECDSA")
                        signature.initSign(privateKey)
                        signature.update(signingString.toByteArray(Charsets.UTF_8))
                        val signatureBytes = signature.sign()
                        val ts = Instant.ofEpochSecond(seconds, nanos.toLong())

                        ClientCredentialsProto(
                            iid_token = iidTokenForSig,
                            client_signature = ByteString.of(*signatureBytes),
                            signature_timestamp = ts
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "GPNV auth: failed to generate client_signature; sending iid_token without signature", e)
                        ClientCredentialsProto(
                            iid_token = iidTokenForSig,
                            client_signature = ByteString.EMPTY,
                            signature_timestamp = null
                        )
                    }
                }

                // 6-8. Gather telephony data and build proto objects
                val targetImsi = request?.imsiRequests?.firstOrNull()?.imsi
                val targetMsisdn = request?.imsiRequests?.firstOrNull()?.msisdn
                val td = gatherTelephonyData(context, targetImsi, targetMsisdn)
                val subscriptionInfo = td.subscriptionInfo
                val telephonyManagerSub = td.telephonyManagerSub
                val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                val subId = td.subId
                val simCountry = td.simCountry
                val networkCountry = td.networkCountry
                val iccId = td.iccId
                val simSlotIndex = td.simSlotIndex
                Log.d(TAG, "CountryInfo: simCountry=$simCountry, networkCountry=$networkCountry")

                val countryInfo = buildCountryInfo(td)
                val connectivityInfos = gatherConnectivityInfos(context)
                val telephonyInfo = buildTelephonyInfo(td)
                Log.d(TAG, "TelephonyInfo: phoneType=${td.phoneTypeInt}, gid1=${td.groupIdLevel1}, simState=${td.simStateEnum}, serviceState=${td.serviceStateEnum}, subs=${td.activeSubCount}/${td.maxSubCount}")

                // 9. Build Sync Request with ALL required fields
                val sessionId = UUID.randomUUID().toString()
                // GMS uses "language_country" format explicitly (e.g., "en_US")
                val localeStr = Locale.getDefault().toString()
                Log.d(TAG, "Locale: '$localeStr'")

                // gaiaTokens already obtained above (used for proto body gaia_ids in GetConsent)
                Log.i(TAG, "OAuth tokens for proto body: ${gaiaTokens.size} available, format: ${gaiaTokens.firstOrNull()?.take(10) ?: "none"}...")

                // CRITICAL: GMS ALWAYS sends OAuth tokens in field 2 and field 12
                // If we don't have real tokens, send a placeholder to match the structure
                // This tests whether the API requires VALID tokens or just the field presence
                val effectiveTokens = if (gaiaTokens.isNotEmpty()) {
                    gaiaTokens
                } else {
                    Log.w(TAG, "No OAuth tokens available - sending placeholder to test structure requirement")
                    listOf("placeholder_oauth_token")  // Will likely fail, but tests the structure
                }

                // Field 12 in ClientInfo: repeated StringId (registered_app_ids)
                val registeredAppIds = effectiveTokens.map { StringId(value_ = it) }

                // SIMAssociation.identifiers (field 2) uses repeated StringId
                val simAssociationIdentifiers = registeredAppIds

                // Build TelephonyInfoContainer (field 20 of ClientInfo) from Gaia IDs
                val gaiaIdsList = getGaiaIds()
                val telephonyInfoContainer = buildTelephonyInfoContainer(gaiaIdsList)
                Log.d(TAG, "TelephonyInfoContainer: ${gaiaIdsList.size} Gaia IDs")

                val requestImsi = request?.imsiRequests?.firstOrNull()?.imsi
                val requestMsisdn = request?.imsiRequests?.firstOrNull()?.msisdn
                // Fallback to TelephonyManager if IMSI not provided in request/override
                // Use subscription-specific telephonyManagerSub, NOT a new default TelephonyManager.
                // Default TM reads wrong SIM on dual-SIM.
                val telephonyImsi = try {
                    telephonyManagerSub?.subscriberId ?: ""
                } catch (e: SecurityException) {
                    Log.w(TAG, "Cannot read IMSI (no READ_PHONE_STATE permission): ${e.message}")
                    ""
                }
                val imsi = requestImsi ?: imsiOverride ?: telephonyImsi
                // MSISDN resolution: use SubscriptionInfo.number (reliable per-SIM) NOT
                // getLine1Number() which returns wrong SIM's number on Samsung dual-SIM.
                val subscriptionMsisdn = try {
                    @Suppress("DEPRECATION")
                    subscriptionInfo?.number?.takeIf { it.isNotEmpty() } ?: ""
                } catch (e: Exception) { "" }
                val telephonyMsisdn = if (subscriptionMsisdn.isNotEmpty()) {
                    subscriptionMsisdn
                } else {
                    // SubscriptionInfo has no number - SIM truly has no stored number.
                    // Do NOT fall back to getLine1Number() - Samsung returns wrong SIM's number.
                    Log.d(TAG, "SIM subId=$subId has no stored number (SubscriptionInfo.number empty)")
                    ""
                }
                val msisdn = requestMsisdn?.takeIf { it.isNotEmpty() }
                    ?: msisdnOverride?.takeIf { it.isNotEmpty() && it.startsWith("+") }
                    ?: requestedNumber?.takeIf { it.isNotEmpty() && it.startsWith("+") }
                    ?: telephonyMsisdn.takeIf { it.isNotEmpty() }
                    ?: ""
                val phoneNumber = request?.policyId ?: msisdn
                Log.i(TAG, "IMSI/MSISDN resolution: imsi=${if (imsi.isNotEmpty()) "${imsi.take(5)}..." else "EMPTY"} (src=${when { requestImsi != null -> "AIDL"; imsiOverride != null -> "override"; telephonyImsi.isNotEmpty() -> "TelephonyManager(sub=$subId)"; else -> "NONE" }}), msisdn=${if (msisdn.isNotEmpty()) "${msisdn.take(5)}..." else "EMPTY"}")

                // Pre-flight: warn on MSISDN/IMSI mismatches across sources
                val allMsisdnSources = mutableMapOf<String, String>()
                if (!requestMsisdn.isNullOrEmpty()) allMsisdnSources["AIDL"] = requestMsisdn
                if (!msisdnOverride.isNullOrEmpty()) allMsisdnSources["override"] = msisdnOverride
                if (telephonyMsisdn.isNotEmpty()) allMsisdnSources["SIM"] = telephonyMsisdn
                if (allMsisdnSources.values.toSet().size > 1) {
                    Log.w(TAG, "MSISDN MISMATCH: ${allMsisdnSources.entries.joinToString { "${it.key}=${it.value}" }}, using: $msisdn")
                }
                if (msisdn.isNotEmpty() && !msisdn.startsWith("+")) {
                    Log.w(TAG, "MSISDN '$msisdn' missing + prefix (not E.164)")
                }

                // Stock GMS V2 (bevr.java:132,142) clones caller extras bundle, adds calling_api.
                // V1 (bevr.java:259) also adds calling_package, but Messages uses V2.
                // bevm.k(bundle) converts ALL keys to proto Params.
                // Messages extras include: policy_id, session_id, required_consumer_consent,
                // one_time_verification, consent_type.
                val mergedBundle = if (request?.extras != null) android.os.Bundle(request.extras) else android.os.Bundle()
                // V2 path (bevr.java:142): stock GMS adds only calling_api, NOT calling_package.
                // calling_package is only added in V1 path (bevr.java:259). Messages uses V2.
                mergedBundle.putString("calling_api", "verifyPhoneNumber")
                // force_provisioning=true tells server the user confirmed their number.
                if (!phoneNumber.isNullOrEmpty() && !mergedBundle.containsKey("force_provisioning")) {
                    mergedBundle.putString("force_provisioning", "true")
                    Log.i(TAG, "Added force_provisioning=true (MSISDN present, not in extras)")
                }
                // Always declare OTP support. Without this, server returns reason=0
                // (UNKNOWN) instead of reason=5 (PHONE_NUMBER_ENTRY_REQUIRED) or PENDING.
                // Messages only includes one_time_verification for fresh SIMs, not previously
                // provisioned ones - but server needs it to guide the OTP flow.
                val forceOneTimeVerificationSetting = Settings.Global.getString(
                    context.contentResolver,
                    FORCE_ONE_TIME_VERIFICATION_KEY
                )?.trim()?.lowercase(Locale.US)
                val shouldAutoAddOneTimeVerification = when (forceOneTimeVerificationSetting) {
                    null, "", "on", "true", "1" -> true
                    "off", "false", "0" -> false
                    else -> {
                        Log.w(TAG, "Unknown $FORCE_ONE_TIME_VERIFICATION_KEY='$forceOneTimeVerificationSetting' - defaulting to auto-add")
                        true
                    }
                }
                if (!mergedBundle.containsKey("one_time_verification")) {
                    if (shouldAutoAddOneTimeVerification) {
                        mergedBundle.putString("one_time_verification", "True")
                        Log.i(TAG, "Added one_time_verification=True (not in extras, key=$FORCE_ONE_TIME_VERIFICATION_KEY setting=${forceOneTimeVerificationSetting ?: "<unset>"})")
                    } else {
                        Log.w(TAG, "Skipping one_time_verification auto-add due to $FORCE_ONE_TIME_VERIFICATION_KEY=${forceOneTimeVerificationSetting}")
                    }
                } else {
                    Log.i(TAG, "Caller already supplied one_time_verification=${mergedBundle.getString("one_time_verification")}")
                }
                // Stock GMS does NOT add policy_id as a Param - it goes into
                // Verification.carrier_info.phone_number (bevm.java:1177).
                val params = bundleToParams(mergedBundle)
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

                // Messages passes an IdTokenRequest (2 strings) into VerifyPhoneNumberRequest.
                // Stock GMS threads these through to PhoneNumber/GetVerifiedPhoneNumbers as:
                //   certificate_hash = idTokenRequest.a
                //   token_nonce      = idTokenRequest.b
                // (see GMS bevv.java:286-288 and the hzdo/hzds proto builders).
                // Messages passes audience (= certificate_hash) + nonce via IdTokenRequest.
                // From captured JWT: aud = "357317899610-64uqvc4ala96muamloactrflpdcdcere.apps.googleusercontent.com"
                // Server uses certificate_hash as JWT audience, token_nonce as JWT nonce claim.
                // Override via: adb shell settings put global microg_constellation_id_token_audience <value>
                val audienceOverride = try {
                    android.provider.Settings.Global.getString(context.contentResolver, "microg_constellation_id_token_audience") ?: ""
                } catch (e: Exception) { "" }
                val nonceOverride = try {
                    android.provider.Settings.Global.getString(context.contentResolver, "microg_constellation_id_token_nonce") ?: ""
                } catch (e: Exception) { "" }
                val idTokenCertificateHash = request?.idTokenRequest?.audience
                    ?: audienceOverride.ifEmpty { null }
                    ?: ""
                val idTokenNonce = request?.idTokenRequest?.nonce
                    ?: nonceOverride.ifEmpty { null }
                    ?: ""
                val idTokenCallingPackage = callingPackage ?: ""
                Log.i(TAG, "IdTokenRequest: certificate_hash_len=${idTokenCertificateHash.length}, calling_package=$idTokenCallingPackage, token_nonce_len=${idTokenNonce.length}, source=${if (request?.idTokenRequest != null) "aidl" else if (audienceOverride.isNotEmpty()) "override" else "empty"}")

                // Stock GMS (bevm.java:1170-1253) populates ALL 5 CarrierInfo fields
                val carrierInfo = buildCarrierInfo(
                    phoneNumber = phoneNumber,
                    subscriptionId = request?.timeout ?: 0L,
                    idTokenCertificateHash = idTokenCertificateHash,
                    idTokenNonce = idTokenNonce,
                    callingPackage = idTokenCallingPackage,
                    imsiRequests = imsiRequests
                )
                Log.d(TAG, "CarrierInfo: phone_number=$phoneNumber, sub_id=${request?.timeout}, calling_package=$idTokenCallingPackage, imsi_requests=${imsiRequests.size}")

                // Get Android IDs for DeviceId (GMS bejs.java:93-126)
                // Settings.Secure.ANDROID_ID is per-app/per-signing-key on Android 8+.
                // microG's value differs from checkin android_id → server cross-validates with DG token → mismatch = rejected.
                // Stock GMS uses its own android_id which matches checkin. We must use checkin android_id for consistency.
                val checkinAndroidId = org.microg.gms.checkin.LastCheckinInfo.read(context).androidId
                val androidIdStr = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                val androidIdFromSettings = if (checkinAndroidId != 0L) {
                    Log.i(TAG, "Using checkin androidId ($checkinAndroidId) instead of Settings.Secure.ANDROID_ID for DG consistency")
                    checkinAndroidId
                } else {
                    Log.w(TAG, "Checkin androidId=0, falling back to Settings.Secure.ANDROID_ID")
                    try { java.lang.Long.parseUnsignedLong(androidIdStr, 16) } catch (e: Exception) { 0L }
                }

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

                // device_android_id = SharedPreferences "primary_device_id" with fallback (GMS bejs.java:116-123, beob.java:57-59)
                // For new clients: primary_device_id=0, then if flag.l() && isSystemUser() && 0==0, falls back to user_android_id
                val devicePrefs = context.getSharedPreferences("constellation_prefs", Context.MODE_PRIVATE)
                val primaryDeviceId = devicePrefs.getLong("primary_device_id", 0L)

                // user_android_id = Settings.Secure.ANDROID_ID (GMS bejs.java:42-54, bdzc.d)
                // CRITICAL: In stock GMS, user_android_id == device_android_id (both come from same ANDROID_ID).
                // After stock→microG swap, Settings.Secure.ANDROID_ID may differ (per-signing-key on Android 8+).
                // When primary_device_id is preserved from stock, use it for BOTH to maintain consistency.
                val userAndroidId = if (primaryDeviceId != 0L) {
                    Log.d(TAG, "Using primary_device_id ($primaryDeviceId) for user_android_id (Settings.Secure.ANDROID_ID=$androidIdFromSettings)")
                    primaryDeviceId
                } else {
                    androidIdFromSettings
                }

                var deviceAndroidId = primaryDeviceId
                if (deviceAndroidId == 0L) {
                    // Fallback logic: if system user and primary_device_id not set, use user_android_id
                    val isSystemUser = userManager?.isSystemUser ?: true
                    if (isSystemUser) {
                        deviceAndroidId = userAndroidId
                    }
                }

                Log.d(TAG, "Android IDs: device=$deviceAndroidId, deviceUser=$deviceUserId, userAndroid=$userAndroidId (settings=${androidIdFromSettings})")

                // Common proto context shared across all request builders in this call
                val protoCtx = RequestProtoContext(
                    iidToken = iidToken,
                    deviceAndroidId = deviceAndroidId,
                    userAndroidId = userAndroidId,
                    publicKeyBytes = publicKeyBytes,
                    localeStr = localeStr,
                    gmscoreVersionNumber = GMSCORE_VERSION_NUMBER,
                    gmscoreVersion = GMSCORE_VERSION,
                    registeredAppIds = registeredAppIds,
                    countryInfo = countryInfo,
                    connectivityInfos = connectivityInfos,
                    telephonyInfoContainer = telephonyInfoContainer
                )

                // Generate Sync-specific DroidGuard token
                // GMS bewt.java:694 passes lowercase method name as DG rpc binding
                // Try raw DG first; if PERMISSION_DENIED, retry without DG
                val syncTokenRaw = rpc.getDroidGuardToken("sync", iidToken)
                val (cachedArfb, _, _) = rpc.getCachedDroidGuardToken(rpc.resolveDroidGuardFlow("sync"))
                val syncToken = cachedArfb ?: syncTokenRaw  // Prefer ARfb, fall back to raw DG
                Log.i(TAG, "Sync DG: using ${if (cachedArfb != null) "cached ARfb" else if (syncToken != null) "raw DG" else "NONE (will retry)"} (${syncToken?.length ?: 0} chars)")

                // Build DeviceId for client_credentials (same as in ClientInfo)
                val syncDeviceId = DeviceId(
                    iid_token = iidToken,
                    device_android_id = deviceAndroidId,
                    user_android_id = userAndroidId
                )

                // Create client_credentials (GMS bewt.java h() method, lines 284-315)
                // Stock GMS only includes credentials when is_public_key_acked=true.
                // First Sync: no credentials → server acks key → CLIENT_KEY_UPDATED.
                // Subsequent Syncs: credentials included with ECDSA signature.
                val syncClientCredentials = createClientCredentials(iidToken, syncDeviceId)
                if (syncClientCredentials != null) {
                    Log.d(TAG, "Including client_credentials in Sync request (forced)")
                }

                // VerificationMethodInfo (field 7 of Verification / hyze in GMS)
                // Stock GMS populates this via SmsManager.createAppSpecificSmsToken() (bfrb.java:66)
                // for silent SMS verification. The token goes into VerificationMethodData.value (hyzo.b).
                val smsToken = try {
                    val subId = subscriptionInfo?.subscriptionId ?: -1
                    val smsManager = if (subId != -1 && Build.VERSION.SDK_INT >= 22) {
                        android.telephony.SmsManager.getSmsManagerForSubscriptionId(subId)
                    } else {
                        @Suppress("DEPRECATION")
                        android.telephony.SmsManager.getDefault()
                    }
                    val intent = android.content.Intent("com.google.android.gms.constellation.SILENT_SMS_RECEIVED")
                        .setPackage(context.packageName)
                    // FLAG_MUTABLE is REQUIRED: createAppSpecificSmsToken fires PendingIntent via
                    // send(ctx, code, fillInIntent) where fillInIntent carries PDU extras.
                    // FLAG_IMMUTABLE causes fillIn extras to be ignored → receiver gets empty intent.
                    val pendingIntentFlags = android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                        if (Build.VERSION.SDK_INT >= 31) android.app.PendingIntent.FLAG_MUTABLE else 0
                    val pendingIntent = android.app.PendingIntent.getBroadcast(
                        context, 0, intent, pendingIntentFlags
                    )
                    smsManager.createAppSpecificSmsToken(pendingIntent) ?: ""
                } catch (e: Exception) {
                    Log.w(TAG, "createAppSpecificSmsToken failed: ${e.message}")
                    ""
                }
                Log.d(TAG, "SMS app-specific token: '${smsToken}' (${smsToken.length} chars)")

                val verificationMethodInfo = buildVerificationMethodInfo(smsToken)

                val loadedVerificationTokens = loadVerificationTokens(keyPrefs)

                val simInfo = buildSIMInfo(imsi, msisdn, iccId)
                val verification = buildVerification(
                    simInfo = simInfo,
                    simAssociationIdentifiers = simAssociationIdentifiers,
                    simSlotIndex = simSlotIndex,
                    subId = subId,
                    telephonyInfo = telephonyInfo,
                    params = params,
                    verificationMethodInfo = verificationMethodInfo,
                    carrierInfo = carrierInfo
                )
                val request = buildSyncRequest(
                    sessionId = sessionId,
                    ctx = protoCtx,
                    syncToken = syncToken,
                    syncClientCredentials = syncClientCredentials,
                    verification = verification,
                    verificationTokens = loadedVerificationTokens
                )

                // 9. First call GetConsent (required before Sync for new clients!)
                // From proto: "When the client is a new client coming online for the first time.
                // It has checked the consent using GetConsent."
                Log.d(TAG, "Calling GetConsent first...")

                // Generate GetConsent-specific DroidGuard token
                // GMS bewt.java:480 passes lowercase method name as DG rpc binding
                val getConsentToken = rpc.getDroidGuardToken("getConsent", iidToken)
                if (getConsentToken == null) {
                    Log.w(TAG, "DroidGuard token for GetConsent FAILED - proceeding without DG (no-DG-first strategy)")
                }

                val consentRequest = buildGetConsentRequest(
                    sessionId = sessionId,
                    ctx = protoCtx,
                    getConsentToken = getConsentToken,
                    registeredAppIds = registeredAppIds,
                    params = params
                )

                try {
                    val consentResponse = rpc.getConsent(consentRequest)
                    Log.i(TAG, "GetConsent SUCCESS: consent=${consentResponse.device_consent?.consent} dg_resp=${consentResponse.droidguard_token_response != null}")

                    // Cache DroidGuard token from response (GMS bewt.java:1111-1128)
                    // Server returns processed token (ARfb...) + TTL in field 9
                    val dgTokenResponse = consentResponse.droidguard_token_response
                    if (dgTokenResponse != null) {
                        val serverToken = dgTokenResponse.droidguard_token
                        if (!serverToken.isNullOrEmpty()) {
                            val ttlMillis = try { dgTokenResponse.droidguard_token_ttl?.toEpochMilli() ?: 0L } catch (_: Exception) { 0L }
                            rpc.cacheDroidGuardToken(rpc.resolveDroidGuardFlow("getConsent"), serverToken, ttlMillis, iidToken)
                            Log.i(TAG, "Cached ARfb from GetConsent: ${serverToken.length} chars, TTL=${java.util.Date(ttlMillis)}")
                        } else {
                            Log.w(TAG, "GetConsent DG token response present but empty")
                        }
                    } else {
                        Log.w(TAG, "No DG token in GetConsent response - Sync will use raw DG")
                    }

                    if (consentResponse.device_consent?.consent == ConsentValue.CONSENT_VALUE_CONSENTED) {
                        Log.i(TAG, "Consent already established")
                    } else {
                        // Auto-call SetConsent when consent is not established.
                        // Stock GMS (bevm.java:1426-1428) throws bfpx and ABORTS here.
                        // Messages handles consent via Asterism service 199.
                        // API doc says DG only required for Sync/Proceed, not SetConsent.
                        // Try without DG first; if PERMISSION_DENIED, retry with DG.
                        Log.w(TAG, "Consent NOT established (${consentResponse.device_consent?.consent}) - calling SetConsent(CONSENTED)...")
                        try {
                            // Try SetConsent WITHOUT DG first
                            // If PERMISSION_DENIED, retry WITH DG token
                            var setConsentSucceeded = false
                            Log.d(TAG, "SetConsent attempt 1: WITHOUT DroidGuard (API doc: DG not required for SetConsent)")
                            try {
                                val noDgRequest = buildSetConsentRequest(sessionId, protoCtx, null)
                                Log.d(TAG, "SetConsent request size (no DG): ${noDgRequest.encode().size} bytes")
                                rpc.setConsent(noDgRequest)
                                Log.i(TAG, "SetConsent SUCCESS (no DG)! Server accepted consent without DroidGuard.")
                                setConsentSucceeded = true
                            } catch (e1: Exception) {
                                val code1 = if (e1 is com.squareup.wire.GrpcException) e1.grpcStatus.code else -1
                                Log.w(TAG, "SetConsent without DG failed: grpc-status=$code1 ${e1.message}")
                                if (code1 == 7) {
                                    // PERMISSION_DENIED - retry with DG token
                                    Log.d(TAG, "SetConsent attempt 2: WITH DroidGuard token")
                                    val setConsentToken = rpc.getDroidGuardToken("setConsent", iidToken)
                                    if (setConsentToken != null) {
                                        try {
                                            val dgRequest = buildSetConsentRequest(sessionId, protoCtx, setConsentToken)
                                            Log.d(TAG, "SetConsent request size (with DG): ${dgRequest.encode().size} bytes")
                                            rpc.setConsent(dgRequest)
                                            Log.i(TAG, "SetConsent SUCCESS (with DG)!")
                                            setConsentSucceeded = true
                                        } catch (e2: Exception) {
                                            Log.e(TAG, "SetConsent with DG also failed: ${e2.message}")
                                            if (e2 is com.squareup.wire.GrpcException) {
                                                Log.e(TAG, "  gRPC status: code=${e2.grpcStatus.code} name=${e2.grpcStatus.name} msg=${e2.grpcMessage}")
                                            }
                                        }
                                    } else {
                                        Log.e(TAG, "Failed to get DroidGuard token for SetConsent retry")
                                    }
                                } else {
                                    Log.e(TAG, "SetConsent failed with non-PERMISSION_DENIED error, not retrying")
                                    Log.d(TAG, "SetConsent exception trace:", e1)
                                }
                            }

                            // If SetConsent succeeded, retry GetConsent to get ARfb
                            if (setConsentSucceeded) {
                                Log.d(TAG, "Retrying GetConsent to obtain ARfb token...")
                                val retryToken = rpc.getDroidGuardToken("getConsent", iidToken)
                                if (retryToken != null) {
                                    val retryRequest = buildGetConsentRequest(
                                        sessionId = sessionId,
                                        ctx = protoCtx,
                                        getConsentToken = retryToken,
                                        registeredAppIds = registeredAppIds,
                                        params = params
                                    )
                                    val retryResponse = rpc.getConsent(retryRequest)
                                    Log.i(TAG, "GetConsent retry: consent=${retryResponse.device_consent?.consent}")

                                    val retryDg = retryResponse.droidguard_token_response
                                    if (retryDg != null && !retryDg.droidguard_token.isNullOrEmpty()) {
                                        val ttl = try { retryDg.droidguard_token_ttl?.toEpochMilli() ?: 0L } catch (_: Exception) { 0L }
                                        rpc.cacheDroidGuardToken(rpc.resolveDroidGuardFlow("getConsent"), retryDg.droidguard_token!!, ttl, iidToken)
                                        Log.i(TAG, "  ARfb cached from retry! ${retryDg.droidguard_token!!.length} chars")
                                    } else {
                                        Log.w(TAG, "  No ARfb in retry response - Sync will use raw DG token")
                                    }
                                }
                            } else {
                                Log.w(TAG, "SetConsent failed - continuing to Sync (may fail)")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "SetConsent block failed: ${e.javaClass.simpleName}: ${e.message}")
                            Log.d(TAG, "SetConsent block exception trace:", e)
                            Log.w(TAG, "Continuing to Sync despite SetConsent failure")
                        }
                    }
                } catch (e: Exception) {
                    if (e is com.squareup.wire.GrpcException) {
                        Log.e(TAG, "GetConsent gRPC error: code=${e.grpcStatus.code} name=${e.grpcStatus.name}")
                        Log.e(TAG, "GetConsent gRPC message: ${e.grpcMessage}")
                        val details = e.grpcStatusDetails
                        if (details != null) {
                            val b64 = android.util.Base64.encodeToString(details, android.util.Base64.NO_WRAP)
                            Log.e(TAG, "GetConsent gRPC statusDetails (${details.size} bytes): $b64")
                        } else {
                            Log.e(TAG, "GetConsent gRPC statusDetails: null")
                        }
                        // Clear DroidGuard cache on auth errors (GMS bewt.java:180-191)
                        if (e.grpcStatus.code == 7 || e.grpcStatus.code == 16) {
                            rpc.clearDroidGuardTokenCache(rpc.resolveDroidGuardFlow("getConsent"), "Auth error (grpc-status=${e.grpcStatus.code})")
                        }
                    } else {
                        Log.e(TAG, "GetConsent failed: ${e.javaClass.simpleName}: ${e.message}")
                        if (e.cause != null) {
                            Log.e(TAG, "  Cause: ${e.cause?.javaClass?.simpleName}: ${e.cause?.message}")
                        }
                    }
                    Log.d(TAG, "Full exception trace:", e)
                    Log.w(TAG, "GetConsent failed; continuing to Sync for experiment")
                }

                // Pre-register SMS receivers BEFORE Sync (race fix: SMS may arrive during RPC).
                // SMS may arrive during the Sync RPC if server issues PENDING immediately.
                SmsInbox.prepare(context)

                try {

                // 10. Execute Sync with retry on PERMISSION_DENIED
                // Stock GMS retries transient PERMISSION_DENIED ~43s later.
                val MAX_SYNC_ATTEMPTS = 3
                val SYNC_RETRY_DELAY_MS = 45_000L  // 45s between retries (stock GMS observed ~43s)

                var currentSyncRequest = request
                var syncRetryResponse: SyncResponse? = null

                for (syncAttempt in 1..MAX_SYNC_ATTEMPTS) {
                    Log.d(TAG, "Sending Sync request (attempt $syncAttempt/$MAX_SYNC_ATTEMPTS)...")
                    val requestByteArray = SyncRequest.ADAPTER.encode(currentSyncRequest)
                    Log.d(TAG, "SyncRequest size: ${requestByteArray.size} bytes")

                    try {
                        syncRetryResponse = rpc.sync(currentSyncRequest)
                        Log.i(TAG, "Sync SUCCESS on attempt $syncAttempt/$MAX_SYNC_ATTEMPTS")
                        break
                    } catch (retryEx: Exception) {
                        val isPermissionDenied = retryEx is com.squareup.wire.GrpcException &&
                            retryEx.grpcStatus.code == 7

                        if (isPermissionDenied && syncAttempt < MAX_SYNC_ATTEMPTS) {
                            // On first PERMISSION_DENIED, retry WITHOUT DG to get NONE/PENDING state
                            // On second PERMISSION_DENIED, retry WITH fresh DG after delay
                            if (syncAttempt == 1) {
                                Log.w(TAG, "Sync PERMISSION_DENIED (attempt 1/$MAX_SYNC_ATTEMPTS), retrying WITHOUT DG...")
                                currentSyncRequest = rebuildSyncRequestWithDg(currentSyncRequest, null)
                                continue
                            }
                            Log.w(TAG, "Sync PERMISSION_DENIED (attempt $syncAttempt/$MAX_SYNC_ATTEMPTS), retrying with fresh DG in ${SYNC_RETRY_DELAY_MS / 1000}s...")
                            // Clear DG cache + key ack before retry
                            rpc.clearDroidGuardTokenCache(rpc.resolveDroidGuardFlow("sync"), "Sync PERMISSION_DENIED retry $syncAttempt")
                            keyPrefs.edit().putBoolean("is_public_key_acked", false).apply()

                            Thread.sleep(SYNC_RETRY_DELAY_MS)

                            // Refresh DG token for next attempt
                            val freshSyncToken = rpc.getDroidGuardToken("sync", iidToken)
                            if (freshSyncToken == null) {
                                Log.e(TAG, "Failed to get fresh DroidGuard token for Sync retry")
                                throw retryEx
                            }
                            Log.d(TAG, "Got fresh DG token for retry (${freshSyncToken.length} chars)")

                            // Rebuild request with fresh DG token
                            currentSyncRequest = rebuildSyncRequestWithDg(currentSyncRequest, freshSyncToken)
                            continue
                        }

                        // Non-retryable error or last attempt - rethrow for outer catch
                        throw retryEx
                    }
                }

                val response = syncRetryResponse
                    ?: throw Exception("Sync failed: no response after $MAX_SYNC_ATTEMPTS attempts")
                // Write full response to file, log summary only
                try {
                    val respStr = response.toString()
                    val dir = File(context.filesDir, "constellation_logs")
                    dir.mkdirs()
                    val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
                    val respFile = File(dir, "SyncResponse_${ts}.txt")
                    respFile.writeText(respStr)
                    val verStates = response.responses.map { it.verification?.state?.name ?: "null" }
                    Log.d(TAG, "Received Sync response: ${response.responses.size} verifications, states=$verStates, next_sync=${response.next_sync_time?.timestamp}, file=${respFile.absolutePath}")
                } catch (e: Exception) {
                    Log.d(TAG, "Received Sync response: ${response.responses.size} verifications (file write failed: ${e.message})")
                }

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

                // 9c. Store verification_tokens from SyncResponse (stock GMS bewt.java:192-201)
                // These are echoed back in subsequent SyncRequests for backup/restore acquisition flows.
                val responseVerificationTokens = response.verification_tokens
                if (responseVerificationTokens.isNotEmpty()) {
                    try {
                        val tokenBytes = responseVerificationTokens.map { android.util.Base64.encodeToString(it.encode(), android.util.Base64.NO_WRAP) }
                        keyPrefs.edit().putStringSet("verification_tokens", tokenBytes.toSet()).apply()
                        Log.i(TAG, "Stored ${responseVerificationTokens.size} verification_tokens from SyncResponse")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to store verification_tokens: ${e.message}")
                    }
                }

                // 10. Cache SyncResponse DroidGuard token
                val syncDgTokenResponse = response.droidguard_token_response
                if (syncDgTokenResponse != null) {
                    val serverToken = syncDgTokenResponse.droidguard_token
                    val serverTtl = syncDgTokenResponse.droidguard_token_ttl
                    if (!serverToken.isNullOrEmpty()) {
                        val ttlMillis = serverTtl?.toEpochMilli() ?: 0L
                        rpc.cacheDroidGuardToken(rpc.resolveDroidGuardFlow("sync"), serverToken, ttlMillis, iidToken)
                        Log.i(TAG, "Cached DroidGuard token from SyncResponse (${serverToken.length} chars, TTL: ${java.util.Date(ttlMillis)})")
                    }
                }

                // 11. Check verification responses for state
                val responses = response.responses
                if (responses.isEmpty()) {
                    Log.w(TAG, "No verification responses in SyncResponse")
                    return@runBlocking Ts43Client.EntitlementResult.error("sync-no-responses")
                }

                Log.d(TAG, "Processing ${responses.size} verification responses")
                var hasVerified = false
                var hasPending = false
                var hasNone = false

                for (verificationResponse in responses) {
                    val verification = verificationResponse.verification
                    val state = verification?.state
                    val simInfo = verification?.association?.sim?.sim_info
                    val imsi = simInfo?.imsi?.firstOrNull() ?: ""
                    val msisdn = simInfo?.sim_readable_number ?: ""

                    Log.d(TAG, "VerificationResponse: state=$state, imsi=${if (imsi.isNotEmpty()) "***${imsi.takeLast(4)}" else "empty"}, msisdn=${if (msisdn.isNotEmpty()) "***${msisdn.takeLast(4)}" else "empty"}")

                    when (state) {
                        VerificationState.VERIFICATION_STATE_VERIFIED -> {
                            Log.i(TAG, "Phone number VERIFIED!")
                            Log.i("MicroGRcs", "constellation sync result=VERIFIED reason=0")
                            hasVerified = true
                        }
                        VerificationState.VERIFICATION_STATE_PENDING -> {
                            Log.w(TAG, "PENDING state - requires Proceed RPC with OTP")
                            Log.i("MicroGRcs", "constellation sync result=PENDING reason=0")
                            hasPending = true
                        }
                        VerificationState.VERIFICATION_STATE_NONE -> {
                            // NONE means "no verification record exists for this SIM".
                            // Stock GMS does NOT treat NONE as VERIFIED (bfqe.d()).
                            // Try GPNV as best-effort (for stock->microG swap where server has cached verification).
                            Log.w(TAG, "NONE state - no verification exists for this SIM. IMSI was: ${if (imsi.isNotEmpty()) "present" else "EMPTY (likely cause)"}")
                            hasNone = true
                        }
                        else -> {
                            Log.w(TAG, "Unexpected state: $state")
                        }
                    }
                }

                // 12. If verified (or NONE with possible cached verification), call GetVerifiedPhoneNumbers
                if (hasVerified) {
                    Log.i(TAG, "Calling GetVerifiedPhoneNumbers to retrieve JWT token...")

                    try {
                        // Build request (GMS bevv.java:52-183, hzdo proto structure)
                        val getTokensRequest = google.internal.communications.phonedeviceverification.v1.GetVerifiedPhoneNumbersRequest(
                            session_id = sessionId,
                            client_credentials = createIidTokenAuth(readOnlyIidToken),
                            // GMS bevv.java:224-235: default = [1] (VERIFIED).
                            selection_types = listOf(1),
                            id_token_request = google.internal.communications.phonedeviceverification.v1.IdTokenRequestProto(
                                certificate_hash = idTokenCertificateHash,
                                calling_package = idTokenCallingPackage,
                                token_nonce = idTokenNonce
                            ),
                            droidguard_result = ""  // Optional for read
                        )

                        // Create PhoneNumber client (separate service from PhoneDeviceVerification)
                        val tokensResponse = rpc.getVerifiedPhoneNumbers(getTokensRequest)
                        Log.i(TAG, "GetVerifiedPhoneNumbers response: ${tokensResponse.verified_phone_numbers.size} numbers")

                        val firstNumber = findMatchingVerifiedNumber(tokensResponse.verified_phone_numbers, phoneNumber)
                        if (firstNumber != null && !firstNumber.token.isNullOrEmpty()) {
                            val jwt = firstNumber.token
                            logJwtSummary("GPNV_POST_SYNC", jwt, firstNumber.phone_number)
                            return@runBlocking Ts43Client.EntitlementResult.success(jwt)
                        } else {
                            Log.w(TAG, "GetVerifiedPhoneNumbers returned empty token")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "GetVerifiedPhoneNumbers failed: ${e.message}", e)
                        // Fall through to stub
                    }
                }

                // 12b. NONE state - best-effort GPNV for stock->microG swap, then check reason
                // Stock GMS handles NONE separately: stores record with state=NONE, calls GPNV as
                // independent read-only query (bevv.java). If GPNV returns nothing, no token is provided.
                if (hasNone && !hasVerified && !hasPending) {
                    Log.i(TAG, "NONE state: trying GPNV as best-effort (stock→microG swap scenario)...")
                    try {
                        val getTokensRequest = google.internal.communications.phonedeviceverification.v1.GetVerifiedPhoneNumbersRequest(
                            session_id = sessionId,
                            client_credentials = createIidTokenAuth(readOnlyIidToken),
                            selection_types = listOf(1),
                            id_token_request = google.internal.communications.phonedeviceverification.v1.IdTokenRequestProto(
                                certificate_hash = idTokenCertificateHash,
                                calling_package = idTokenCallingPackage,
                                token_nonce = idTokenNonce
                            ),
                            droidguard_result = ""
                        )
                        val tokensResponse = rpc.getVerifiedPhoneNumbers(getTokensRequest)
                        val firstNumber = findMatchingVerifiedNumber(tokensResponse.verified_phone_numbers, phoneNumber)
                        if (firstNumber != null && !firstNumber.token.isNullOrEmpty()) {
                            val jwt = firstNumber.token
                            logJwtSummary("GPNV_NONE_BEST_EFFORT", jwt, firstNumber.phone_number)
                            return@runBlocking Ts43Client.EntitlementResult.success(jwt)
                        } else {
                            Log.w(TAG, "GPNV returned nothing for NONE state - no cached verification exists")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "GPNV failed for NONE state: ${e.message}")
                    }

                    // Check UnverifiedInfo.reason from the NONE-state verification.
                    // GMS enum hzdf maps reason->status code sent to Messages:
                    //   5->7 (WaitingForManualMsisdnEntryState - phone number input UI)
                    val noneVerification = responses.firstOrNull {
                        it.verification?.state == VerificationState.VERIFICATION_STATE_NONE
                    }?.verification
                    val unverifiedReason = noneVerification?.unverified_info?.reason_enum_1 ?: 0
                    Log.i(TAG, "NONE state UnverifiedInfo: reason=$unverifiedReason")
                    Log.i("MicroGRcs", "constellation sync result=NONE reason=$unverifiedReason")

                    // Only reason=5 (PHONE_NUMBER_ENTRY_REQUIRED) maps to status 7.
                    // GMS maps: 0->0, 1->3(retry), 2->2(retry), 3->4, 4->5, 5->7(phone input), 6->8, 7->9, 8->10
                    if (unverifiedReason == 5) {
                        Log.i(TAG, "Server requests manual phone number entry (reason=5) - returning status 7")
                        return@runBlocking Ts43Client.EntitlementResult.phoneNumberEntryRequired("none-phone-number-entry-required")
                    }

                    // Retryable reasons: UNKNOWN(0), THROTTLED(1), FAILED(2) - return 5002 so Messages retries
                    // UNKNOWN(0) = server has no definitive answer yet, retry may get reason=5
                    if (unverifiedReason in listOf(0, 1, 2)) {
                        Log.w(TAG, "NONE state retryable reason=$unverifiedReason - returning Status(5002) for retry")
                        return@runBlocking Ts43Client.EntitlementResult.error("5002:none-state-reason-$unverifiedReason")
                    }

                    // Non-retryable: INELIGIBLE(6), DENIED(7), NOT_IN_SERVICE(8), etc.
                    Log.w(TAG, "NONE state non-retryable reason=$unverifiedReason - returning Status(5001)")
                    return@runBlocking Ts43Client.EntitlementResult.error("5001:none-state-reason-$unverifiedReason")
                }

                // 13. Challenge dispatch loop (multi-type, multi-round)
                if (hasPending) {
                    Log.i(TAG, "Verification is PENDING - entering challenge dispatch loop")

                    val pendingResponses = responses.filter {
                        it.verification?.state == VerificationState.VERIFICATION_STATE_PENDING
                    }
                    var currentVerification = if (!phoneNumber.isNullOrEmpty()) {
                        pendingResponses.firstOrNull {
                            it.verification?.association?.sim?.sim_info?.sim_readable_number == phoneNumber
                        }?.verification ?: pendingResponses.firstOrNull()?.verification
                    } else {
                        pendingResponses.firstOrNull()?.verification
                    }

                    if (currentVerification == null) {
                        Log.e(TAG, "hasPending=true but no pending verification found")
                        return@runBlocking Ts43Client.EntitlementResult.error("pending-inconsistent")
                    }

                    val proceedDeviceId = DeviceId(iid_token = iidToken, device_android_id = deviceAndroidId, user_android_id = userAndroidId)
                    val proceedClientCredentials = createClientCredentials(iidToken, proceedDeviceId)

                    // Session tracking for challenge types that span multiple rounds
                    val carrierIdAttempts = mutableMapOf<String, Int>()  // challengeId → attempt count
                    var moSmsSent = false  // MO SMS only sent once; subsequent rounds are polling

                    for (round in 1..16) {
                        val challenge = currentVerification?.pending_challenge?.challenge
                        if (challenge == null) {
                            Log.w(TAG, "Round $round: no challenge in pending verification")
                            break
                        }
                        val challengeType = challenge.type
                        val challengeId = challenge.challenge_id?.id ?: "unknown"
                        Log.i(TAG, "Round $round/16: challenge type=$challengeType id=$challengeId")

                        // Compute timeout from server's expiry_time (if available)
                        // ServerTimestamp fields are Wire Instant (epochSecond + nano)
                        val expiryTimeMs = challenge.expiry_time?.let { exp ->
                            val serverNowMs = exp.now?.let { it.epochSecond * 1000L + it.nano / 1_000_000L } ?: 0L
                            val serverExpiryMs = exp.timestamp?.let { it.epochSecond * 1000L + it.nano / 1_000_000L } ?: 0L
                            if (serverNowMs > 0 && serverExpiryMs > serverNowMs) {
                                serverExpiryMs - serverNowMs
                            } else null
                        }

                        // Dispatch to appropriate verifier
                        val challengeResponse: google.internal.communications.phonedeviceverification.v1.ChallengeResponse? = when (challengeType) {
                            google.internal.communications.phonedeviceverification.v1.ChallengeType.CHALLENGE_TYPE_MT_SMS -> {
                                // Use server expiry_time if available, otherwise 120s default
                                val timeoutSec = (expiryTimeMs?.div(1000) ?: 120L).coerceIn(10, 300)
                                Log.i(TAG, "  MT_SMS: waiting ${timeoutSec}s for incoming OTP...")
                                val otpSms = SmsInbox.awaitMatch(timeoutSeconds = timeoutSec)
                                if (otpSms != null) {
                                    Log.i(TAG, "  MT_SMS: received from ${otpSms.originatingAddress} (${otpSms.messageBody.length} chars)")
                                    google.internal.communications.phonedeviceverification.v1.ChallengeResponse(
                                        mt_challenge_response = google.internal.communications.phonedeviceverification.v1.MTChallengeResponse(
                                            sms_body = otpSms.messageBody,
                                            originating_address = otpSms.originatingAddress
                                        )
                                    )
                                } else {
                                    Log.w(TAG, "  MT_SMS: timeout after ${timeoutSec}s - proceeding with empty response")
                                    // On timeout: proceed with empty response (server may transition to next challenge)
                                    google.internal.communications.phonedeviceverification.v1.ChallengeResponse(
                                        mt_challenge_response = google.internal.communications.phonedeviceverification.v1.MTChallengeResponse(
                                            sms_body = "", originating_address = ""
                                        )
                                    )
                                }
                            }
                            google.internal.communications.phonedeviceverification.v1.ChallengeType.CHALLENGE_TYPE_MO_SMS -> {
                                val moChallenge = challenge.mo_challenge
                                if (moChallenge == null) {
                                    Log.w(TAG, "  MO_SMS: no mo_challenge data"); null
                                } else if (!moSmsSent) {
                                    // First round: actually send the SMS
                                    Log.i(TAG, "  MO_SMS: sending to ${moChallenge.proxy_number}")
                                    moSmsSent = true
                                    ChallengeProcessor.sendMoSms(context, moChallenge, subId)
                                } else {
                                    // Subsequent rounds with same MO_SMS: polling - server checks if SMS arrived.
                                    // polling_intervals is comma-separated ms values (e.g., "4000,1000,1000,3000,5000")
                                    val pollDelays = moChallenge.polling_intervals?.split(",")?.mapNotNull { it.trim().toLongOrNull() } ?: emptyList()
                                    val pollIndex = (round - 2).coerceAtLeast(0)  // round 1 = send, round 2+ = poll
                                    val pollDelay = pollDelays.getOrElse(pollIndex) { pollDelays.lastOrNull() ?: 5000L }
                                    Log.i(TAG, "  MO_SMS: polling round (SMS already sent), waiting ${pollDelay}ms")
                                    Thread.sleep(pollDelay.coerceIn(1000, 30000))
                                    // Return completed status for poll
                                    google.internal.communications.phonedeviceverification.v1.ChallengeResponse(
                                        mo_challenge_response = google.internal.communications.phonedeviceverification.v1.MOChallengeResponse(
                                            status = google.internal.communications.phonedeviceverification.v1.MOChallengeStatus.MO_STATUS_COMPLETED
                                        )
                                    )
                                }
                            }
                            google.internal.communications.phonedeviceverification.v1.ChallengeType.CHALLENGE_TYPE_CARRIER_ID -> {
                                val carrierChallenge = challenge.carrier_id_challenge
                                if (carrierChallenge == null) {
                                    Log.w(TAG, "  CARRIER_ID: no carrier_id_challenge data"); null
                                } else {
                                    // Track retry attempts per challengeId (server may resend same challenge)
                                    val attempts = carrierIdAttempts.getOrDefault(challengeId, 0) + 1
                                    carrierIdAttempts[challengeId] = attempts
                                    if (attempts > 3) {
                                        Log.w(TAG, "  CARRIER_ID: retry exceeded ($attempts) for $challengeId")
                                        google.internal.communications.phonedeviceverification.v1.ChallengeResponse(
                                            carrier_id_challenge_response = google.internal.communications.phonedeviceverification.v1.CarrierIDChallengeResponse(
                                                carrier_id_error = google.internal.communications.phonedeviceverification.v1.CarrierIdError.CARRIER_ID_ERROR_RETRY_ATTEMPT_EXCEEDED
                                            )
                                        )
                                    } else {
                                        Log.i(TAG, "  CARRIER_ID: attempt $attempts/3, isim_request=${carrierChallenge.isim_request.length} chars")
                                        ChallengeProcessor.verifyCarrierId(context, carrierChallenge, subId)
                                    }
                                }
                            }
                            google.internal.communications.phonedeviceverification.v1.ChallengeType.CHALLENGE_TYPE_TS43 -> {
                                val ts43Challenge = challenge.ts43_challenge
                                if (ts43Challenge == null) {
                                    Log.w(TAG, "  TS43: no ts43_challenge data"); null
                                } else {
                                    Log.i(TAG, "  TS43: entitlement_url=${ts43Challenge.entitlement_url} realm=${ts43Challenge.eap_aka_realm}")
                                    ChallengeProcessor.handleTs43Challenge(context, ts43Challenge, subId, phoneNumber)
                                }
                            }
                            else -> {
                                Log.w(TAG, "  Unsupported challenge type: $challengeType")
                                null
                            }
                        }

                        if (challengeResponse == null) {
                            Log.w(TAG, "Round $round: verifier returned null, exiting loop")
                            break
                        }

                        // Build and execute Proceed RPC (no-DG-first)
                        val proceedDgToken = rpc.getDroidGuardToken("proceed", iidToken)
                        val proceedClientInfo = buildClientInfo(
                            ctx = protoCtx,
                            droidGuardToken = proceedDgToken
                        )
                        val proceedHeader = buildRequestHeader(
                            sessionId = sessionId,
                            clientInfo = proceedClientInfo,
                            clientCredentials = proceedClientCredentials
                        )
                        val proceedRequest = google.internal.communications.phonedeviceverification.v1.ProceedRequest(
                            verification = currentVerification,
                            challenge_response = challengeResponse,
                            header_ = proceedHeader
                        )

                        Log.i(TAG, "Round $round: calling Proceed (challenge_id=$challengeId)")

                        var proceedResponse: google.internal.communications.phonedeviceverification.v1.ProceedResponse? = null
                        // No-DG-first strategy
                        val noDgRequest = proceedRequest.copy(
                            header_ = proceedHeader.copy(
                                client_info = proceedHeader.client_info?.copy(device_signals = DeviceSignals())
                            )
                        )
                        try {
                            proceedResponse = rpc.proceed(noDgRequest)
                            Log.i(TAG, "Round $round: Proceed SUCCESS (no-DG)")
                        } catch (e: Exception) {
                            if (e is com.squareup.wire.GrpcException && proceedDgToken != null) {
                                Log.w(TAG, "Round $round: Proceed no-DG failed (${e.grpcStatus.name}), retrying with DG")
                                try {
                                    proceedResponse = rpc.proceed(proceedRequest)
                                    Log.i(TAG, "Round $round: Proceed SUCCESS (with DG)")
                                } catch (e2: Exception) {
                                    Log.e(TAG, "Round $round: Proceed with DG also failed: ${e2.message}")
                                    return@runBlocking Ts43Client.EntitlementResult.error("proceed-failed-round-$round", e2)
                                }
                            } else {
                                Log.e(TAG, "Round $round: Proceed failed: ${e.message}")
                                return@runBlocking Ts43Client.EntitlementResult.error("proceed-failed-round-$round", e)
                            }
                        }

                        // Cache DG token from response
                        proceedResponse?.droidguard_token_response?.let { dgResp ->
                            if (!dgResp.droidguard_token.isNullOrEmpty()) {
                                rpc.cacheDroidGuardToken(rpc.resolveDroidGuardFlow("proceed"), dgResp.droidguard_token, dgResp.droidguard_token_ttl?.toEpochMilli() ?: 0L, iidToken)
                            }
                        }

                        // Check outcome
                        val newVerification = proceedResponse?.verification
                        val newState = newVerification?.state
                        Log.i(TAG, "Round $round: post-Proceed state=$newState")

                        if (newState == VerificationState.VERIFICATION_STATE_VERIFIED) {
                            Log.i(TAG, "VERIFIED after round $round! Calling GPNV for JWT...")
                            val getTokensRequest = google.internal.communications.phonedeviceverification.v1.GetVerifiedPhoneNumbersRequest(
                                session_id = sessionId,
                                client_credentials = createIidTokenAuth(readOnlyIidToken),
                                selection_types = listOf(1),
                                id_token_request = google.internal.communications.phonedeviceverification.v1.IdTokenRequestProto(
                                    certificate_hash = idTokenCertificateHash,
                                    calling_package = idTokenCallingPackage,
                                    token_nonce = idTokenNonce
                                ),
                                droidguard_result = ""
                            )
                            val tokensResponse = rpc.getVerifiedPhoneNumbers(getTokensRequest)
                            val firstNumber = findMatchingVerifiedNumber(tokensResponse.verified_phone_numbers, phoneNumber)
                            if (firstNumber != null && !firstNumber.token.isNullOrEmpty()) {
                                logJwtSummary("GPNV_POST_PROCEED", firstNumber.token, firstNumber.phone_number)
                                return@runBlocking Ts43Client.EntitlementResult.success(firstNumber.token)
                            } else {
                                Log.e(TAG, "VERIFIED but GPNV returned empty token")
                                return@runBlocking Ts43Client.EntitlementResult.error("proceed-no-token")
                            }
                        } else if (newState == VerificationState.VERIFICATION_STATE_PENDING) {
                            Log.i(TAG, "Still PENDING after round $round, looping...")
                            currentVerification = newVerification
                            // MO_SMS polling: delay before next round
                            if (challengeType == google.internal.communications.phonedeviceverification.v1.ChallengeType.CHALLENGE_TYPE_MO_SMS) {
                                val intervals = challenge.mo_challenge?.polling_intervals?.split(",")?.mapNotNull { it.trim().toLongOrNull() } ?: emptyList()
                                val delay = intervals.getOrNull(round - 1) ?: 5000L
                                Log.d(TAG, "MO_SMS polling delay: ${delay}ms")
                                kotlinx.coroutines.delay(delay)
                            }
                            continue
                        } else {
                            Log.w(TAG, "Unexpected post-Proceed state: $newState (round $round)")
                            break
                        }
                    }
                    Log.w(TAG, "Challenge loop exhausted (16 rounds) or exited early")
                }

                // If we got here, no token was found
                Log.w(TAG, "No token extracted from sync flow")
                return@runBlocking Ts43Client.EntitlementResult.error("sync-success-no-token")

                } catch (e: Exception) {
                    if (e is com.squareup.wire.GrpcException) {
                        Log.e(TAG, "Sync gRPC error: code=${e.grpcStatus.code} name=${e.grpcStatus.name}")
                        Log.e(TAG, "Sync gRPC message: ${e.grpcMessage}")
                        val details = e.grpcStatusDetails
                        if (details != null) {
                            val b64 = android.util.Base64.encodeToString(details, android.util.Base64.NO_WRAP)
                            Log.e(TAG, "Sync gRPC statusDetails (${details.size} bytes): $b64")
                        } else {
                            Log.e(TAG, "Sync gRPC statusDetails: null")
                        }
                        // Clear DroidGuard cache on auth errors (GMS bewt.java:180-191)
                        if (e.grpcStatus.code == 7 || e.grpcStatus.code == 16) {
                            rpc.clearDroidGuardTokenCache(rpc.resolveDroidGuardFlow("sync"), "Sync auth error (grpc-status=${e.grpcStatus.code})")
                            keyPrefs.edit().putBoolean("is_public_key_acked", false).apply()
                            Log.w(TAG, "Cleared is_public_key_acked due to grpc-status=${e.grpcStatus.code}")
                        }
                    } else {
                        Log.e(TAG, "Sync failed: ${e.javaClass.simpleName}: ${e.message}")
                        if (e.cause != null) {
                            Log.e(TAG, "  Cause: ${e.cause?.javaClass?.simpleName}: ${e.cause?.message}")
                        }
                    }
                    Log.d(TAG, "Full exception trace:", e)

                    // ============================================================
                    // GPNV Fallback: after GetConsent/Sync fail, try to retrieve a
                    // cached JWT via PhoneNumber/GetVerifiedPhoneNumbers.
                    //
                    // This covers the stock GMS -> microG swap scenario: if a prior
                    // stock GMS run left a VERIFIED phone number on the server, we
                    // can retrieve a fresh JWT without needing GetConsent/Sync to
                    // succeed. This is intentionally AFTER GetConsent/Sync so that
                    // we follow stock GMS call ordering and only fall back to GPNV
                    // when the primary flow fails.
                    //
                    // Logging uses a stable marker so we can grep for success:
                    //   - "GPNV_FALLBACK: SUCCESS" means this approach worked.
                    // ============================================================
                    Log.i(TAG, "GPNV_FALLBACK: START - GetConsent/Sync failed, checking for cached verification")
                    try {
                        val fallbackRequest = GetVerifiedPhoneNumbersRequest(
                            session_id = sessionId,
                            client_credentials = createIidTokenAuth(readOnlyIidToken),
                            // GMS bevv.java:224-235: default = [1] (VERIFIED).
                            selection_types = listOf(1),
                            id_token_request = IdTokenRequestProto(
                                certificate_hash = idTokenCertificateHash,
                                calling_package = idTokenCallingPackage,
                                token_nonce = idTokenNonce
                            ),
                            // No DG token: relies on server-side
                            // VerifyPhoneNumberApi__ignore_droid_guard_requirement (default true).
                            droidguard_result = ""
                        )

                        Log.i(TAG, "GPNV_FALLBACK: Calling GetVerifiedPhoneNumbers after verification state: sync-failed")
                        val fallbackResponse = rpc.getVerifiedPhoneNumbers(fallbackRequest)
                        Log.i(TAG, "GPNV_FALLBACK: returned ${fallbackResponse.verified_phone_numbers.size} verified numbers")

                        val firstNumber = findMatchingVerifiedNumber(fallbackResponse.verified_phone_numbers, phoneNumber)
                        val jwt = firstNumber?.token
                        if (!jwt.isNullOrEmpty()) {
                            logJwtSummary("GPNV_FALLBACK", jwt, firstNumber.phone_number)
                            Log.i(TAG, "GPNV_FALLBACK: returned JWT token (${jwt.length} chars)")
                            // Persist a minimal breadcrumb so success is visible even if logcat rolls.
                            try {
                                val shaPrefix = jwtSha256HexPrefix(jwt)
                                context.getSharedPreferences("constellation_prefs", Context.MODE_PRIVATE)
                                    .edit()
                                    .putString("last_fallback_jwt_sha256_8", shaPrefix)
                                    .putLong("last_fallback_jwt_time_ms", System.currentTimeMillis())
                                    .apply()
                            } catch (_: Throwable) {
                            }

                            Log.i(TAG, "GPNV_FALLBACK: SUCCESS - returning JWT from cached verification")
                            return@runBlocking Ts43Client.EntitlementResult.success(jwt)
                        }

                        Log.i(TAG, "GPNV_FALLBACK: MISS (verified_phone_numbers=${fallbackResponse.verified_phone_numbers.size}, token_empty=${jwt.isNullOrEmpty()}) - no cached verification found")
                    } catch (gpnvEx: Exception) {
                        val gpnvMsg = gpnvEx.message ?: ""
                        val gpnvStatus = Regex("grpc-status=(\\d+)").find(gpnvMsg)?.groupValues?.get(1)
                        val gpnvGrpcMessage = Regex("grpc-message=([^,]+)").find(gpnvMsg)?.groupValues?.get(1)
                        Log.w(TAG, "GPNV_FALLBACK: ERROR (grpc-status=${gpnvStatus ?: "?"} grpc-message=${gpnvGrpcMessage ?: "?"}) - no cached verification available")
                        Log.d(TAG, "GPNV_FALLBACK exception trace:", gpnvEx)
                    }

                    return@runBlocking Ts43Client.EntitlementResult.error("sync-failed", e)
                }
                } finally {
                    // Dispose SMS receivers (pre-registered before Sync)
                    SmsInbox.dispose(context)
                    // Close RPC client (closes DG handle + gRPC resources)
                    rpcClient?.close()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "verifyPhoneNumber failed with exception", e)
            Ts43Client.EntitlementResult.error("exception-${e.javaClass.simpleName}", e)
        }).also { result ->
            val s = if (!result.token.isNullOrEmpty()) "VERIFIED" else if (result.isError()) "ERROR" else if (result.needsManualMsisdn) "MANUAL_MSISDN" else if (result.ineligible) "INELIGIBLE" else "UNKNOWN"
            Log.i("MicroGRcs", "provision status=$s reason=${result.reason ?: "none"}")
        }
    }

    private fun jwtSha256HexPrefix(jwt: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(jwt.toByteArray(Charsets.UTF_8))
        // 4 bytes => 8 hex chars (compact but unique enough for logs)
        val sb = StringBuilder(8)
        for (b in digest.take(4)) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }

    private fun decodeJwtPayloadJson(jwt: String): JSONObject? {
        val parts = jwt.split('.')
        if (parts.size < 2) return null
        val payloadB64 = parts[1]
        val padLen = (4 - (payloadB64.length % 4)) % 4
        val padded = payloadB64 + "=".repeat(padLen)
        return try {
            val decoded = Base64.getUrlDecoder().decode(padded)
            JSONObject(String(decoded, Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }

    private fun logJwtSummary(marker: String, jwt: String, phoneFromResponse: String?) {
        val sha8 = try {
            jwtSha256HexPrefix(jwt)
        } catch (_: Exception) {
            "????????"
        }

        val payload = decodeJwtPayloadJson(jwt)
        val iss = payload?.optString("iss")
        val expSec = payload?.optLong("exp")?.takeIf { it != null && it > 0 } ?: 0L
        val expDate = if (expSec > 0) java.util.Date(expSec * 1000L).toString() else "?"
        val phoneClaim = payload?.optString("phone_number")
        val phoneSuffix = phoneClaim?.takeLast(4)
        val method = payload?.optJSONObject("google")?.optString("phone_number_verification_method")
        val respSuffix = phoneFromResponse?.takeLast(4)

        Log.i(TAG, "$marker: JWT len=${jwt.length} sha256_8=$sha8 iss=${iss ?: "?"} exp=${if (expSec > 0) expSec else "?"} ($expDate) phone_suffix=${phoneSuffix ?: "?"} method=${method ?: "?"} resp_phone_suffix=${respSuffix ?: "?"}")
        Log.i("MicroGRcs", "constellation JWT len=${jwt.length} phone=***${phoneSuffix ?: "?"}")
    }

}
