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
import android.content.pm.PackageManager
import org.microg.gms.common.Constants
import java.io.File
import com.google.android.gms.tasks.Tasks
import com.squareup.wire.GrpcClient
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okio.ByteString
import okio.ByteString.Companion.decodeBase64
import org.microg.gms.common.PackageUtils
import org.microg.gms.droidguard.DroidGuardClientImpl
import com.google.android.gms.droidguard.DroidGuardHandle
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
import google.internal.communications.phonedeviceverification.v1.AsterismClient
import google.internal.communications.phonedeviceverification.v1.GrpcPhoneDeviceVerificationClient
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
import google.internal.communications.phonedeviceverification.v1.GrpcPhoneNumberClient
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
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.telephony.SmsMessage
import java.util.concurrent.CompletableFuture
import android.content.Intent

class GoogleConstellationClient(private val context: Context) {
    companion object {
        private const val TAG = "GmsConstellationClient"
        // GMS uses API key + Spatula auth (NO OAuth Bearer on gRPC transport - bewt.c bdpb has no account/scopes)
        private const val API_KEY = "AIzaSyAP-gfH3qvi6vgHZbSYwQ_XHqV_mXHhzIk"
        private const val GMSCORE_VERSION_NUMBER = 260233
        private const val GMSCORE_VERSION = "26.02.33 (190400-858744110)"
        private const val GAIA_TOKEN_SCOPE = "oauth2:https://www.googleapis.com/auth/numberer"

        // Debug-only DG flow experiment knobs (default behavior unchanged when unset)
        // settings put global microg_constellation_dg_flow_override <flow>
        // settings put global microg_constellation_dg_flow_rpc_map "getConsent=...,sync=...,proceed=..."
        private const val DG_FLOW_OVERRIDE_GLOBAL_KEY = "microg_constellation_dg_flow_override"
        private const val DG_FLOW_OVERRIDE_RPC_MAP_KEY = "microg_constellation_dg_flow_rpc_map"
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

        /** OTP SMS data: full body + sender, matching stock GMS bfqy.e(originatingAddress, messageBody) */
        data class OtpSmsResult(val messageBody: String, val originatingAddress: String)

        /**
         * SMS inbox that buffers messages arriving before PENDING is detected.
         * Pre-register receivers BEFORE Sync so SMS arriving during the
         * Sync RPC isn't missed.
         *
         * Lifecycle: prepare() before Sync → awaitMatch() after PENDING → dispose() in finally.
         * Uses both silent (createAppSpecificSmsToken PendingIntent) and noisy (SMS_RECEIVED) paths.
         */
        object SmsInbox {
            private val lock = Any()
            private val bufferedMessages = mutableListOf<OtpSmsResult>()
            private var pendingFuture: CompletableFuture<OtpSmsResult>? = null
            private var silentReceiver: BroadcastReceiver? = null
            private var noisyReceiver: BroadcastReceiver? = null

            fun prepare(context: Context) {
                dispose(context)
                Log.i(TAG, "SmsInbox: preparing receivers before Sync")

                silentReceiver = object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context, intent: Intent) {
                        Log.i(TAG, "SILENT_SMS_RECEIVED onReceive! action=${intent.action} extras=${intent.extras?.keySet()}")
                        extractSmsFromIntent(intent)?.let { onSmsReceived(it, "silent") }
                    }
                }
                if (Build.VERSION.SDK_INT >= 33) {
                    context.registerReceiver(silentReceiver!!,
                        IntentFilter("com.google.android.gms.constellation.SILENT_SMS_RECEIVED"),
                        Context.RECEIVER_NOT_EXPORTED)
                } else {
                    context.registerReceiver(silentReceiver!!,
                        IntentFilter("com.google.android.gms.constellation.SILENT_SMS_RECEIVED"))
                }

                val hasReceiveSms = context.checkSelfPermission(android.Manifest.permission.RECEIVE_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED
                if (hasReceiveSms) {
                    noisyReceiver = object : BroadcastReceiver() {
                        override fun onReceive(ctx: Context, intent: Intent) {
                            if (intent.action == "android.provider.Telephony.SMS_RECEIVED") {
                                extractSmsFromIntent(intent)?.let { onSmsReceived(it, "noisy") }
                            }
                        }
                    }
                    if (Build.VERSION.SDK_INT >= 33) {
                        context.registerReceiver(noisyReceiver!!,
                            IntentFilter("android.provider.Telephony.SMS_RECEIVED").apply { priority = 1000 },
                            Context.RECEIVER_EXPORTED)
                    } else {
                        context.registerReceiver(noisyReceiver!!,
                            IntentFilter("android.provider.Telephony.SMS_RECEIVED").apply { priority = 1000 })
                    }
                    Log.i(TAG, "SmsInbox: noisy + silent receivers registered")
                } else {
                    Log.i(TAG, "SmsInbox: silent-only (RECEIVE_SMS not granted)")
                }
            }

            private fun extractSmsFromIntent(intent: Intent): OtpSmsResult? {
                val pdus = intent.extras?.get("pdus") as? Array<*>
                if (pdus != null) {
                    for (pdu in pdus) {
                        val msg = if (Build.VERSION.SDK_INT >= 23) {
                            SmsMessage.createFromPdu(pdu as ByteArray, intent.extras?.getString("format"))
                        } else {
                            @Suppress("DEPRECATION")
                            SmsMessage.createFromPdu(pdu as ByteArray)
                        }
                        val body = msg?.messageBody ?: continue
                        val sender = msg.originatingAddress ?: ""
                        return OtpSmsResult(body, sender)
                    }
                }
                // Fallback for silent path without PDUs
                val body = intent.getStringExtra("message_body")
                    ?: intent.getStringExtra("body")
                    ?: intent.getStringExtra("sms_body")
                val sender = intent.getStringExtra("originating_address")
                    ?: intent.getStringExtra("address")
                    ?: ""
                if (body != null) return OtpSmsResult(body, sender)
                // Last resort: token matched by Android (PendingIntent fired = correct SMS)
                return OtpSmsResult("TOKEN_MATCHED_SMS", sender)
            }

            private fun onSmsReceived(sms: OtpSmsResult, source: String) {
                Log.d(TAG, "SmsInbox: SMS from ${sms.originatingAddress} via $source, length=${sms.messageBody.length}")
                Log.i(TAG, "SmsInbox: body='${sms.messageBody}'")
                synchronized(lock) {
                    bufferedMessages.add(sms)
                    pendingFuture?.let { future ->
                        if (!future.isDone) {
                            Log.i(TAG, "SmsInbox: completing pending future via $source")
                            future.complete(sms)
                        }
                    }
                }
            }

            /**
             * Wait for OTP SMS. Checks buffer first (SMS may have arrived during Sync),
             * then blocks until new SMS arrives or timeout.
             * Silent path accepts unconditionally (Android pre-verified via token).
             * Noisy path: all SMS are buffered - caller can filter if needed.
             */
            fun awaitMatch(timeoutSeconds: Long = 120): OtpSmsResult? {
                synchronized(lock) {
                    if (bufferedMessages.isNotEmpty()) {
                        val first = bufferedMessages.first()
                        Log.i(TAG, "SmsInbox: found buffered SMS from ${first.originatingAddress}")
                        return first
                    }
                    pendingFuture = CompletableFuture<OtpSmsResult>()
                }
                Log.i(TAG, "SmsInbox: waiting ${timeoutSeconds}s for OTP SMS...")
                return try {
                    pendingFuture!!.get(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS).also {
                        Log.i(TAG, "SmsInbox: OTP received from ${it.originatingAddress}")
                    }
                } catch (e: java.util.concurrent.TimeoutException) {
                    Log.w(TAG, "SmsInbox: OTP timeout after ${timeoutSeconds}s")
                    null
                } catch (e: Exception) {
                    Log.e(TAG, "SmsInbox: error waiting for SMS: ${e.message}")
                    null
                }
            }

            fun dispose(context: Context) {
                synchronized(lock) {
                    bufferedMessages.clear()
                    pendingFuture = null
                }
                silentReceiver?.let { r -> try { context.unregisterReceiver(r) } catch (_: Exception) {} }
                noisyReceiver?.let { r -> try { context.unregisterReceiver(r) } catch (_: Exception) {} }
                silentReceiver = null
                noisyReceiver = null
            }
        }
    }

    // ======== CHALLENGE VERIFIERS ========

    /**
     * Send MO SMS to proxy_number from server challenge. Returns ChallengeResponse.
     * Sends SMS and waits for delivery confirmation.
     */
    private suspend fun sendMoSms(
        context: Context,
        moChallenge: google.internal.communications.phonedeviceverification.v1.MOChallenge,
        subId: Int
    ): google.internal.communications.phonedeviceverification.v1.ChallengeResponse {
        val proxyNumber = moChallenge.proxy_number
        val smsText = moChallenge.sms
        if (proxyNumber.isEmpty() || smsText.isEmpty()) {
            Log.w(TAG, "MO SMS: empty proxy_number or sms text")
            return google.internal.communications.phonedeviceverification.v1.ChallengeResponse(
                mo_challenge_response = google.internal.communications.phonedeviceverification.v1.MOChallengeResponse(
                    status = google.internal.communications.phonedeviceverification.v1.MOChallengeStatus.MO_STATUS_FAILED_TO_SEND
                )
            )
        }
        if (context.checkCallingOrSelfPermission(android.Manifest.permission.SEND_SMS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "MO SMS: SEND_SMS permission not granted")
            return google.internal.communications.phonedeviceverification.v1.ChallengeResponse(
                mo_challenge_response = google.internal.communications.phonedeviceverification.v1.MOChallengeResponse(
                    status = google.internal.communications.phonedeviceverification.v1.MOChallengeStatus.MO_STATUS_FAILED_TO_SEND
                )
            )
        }

        val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(android.telephony.SmsManager::class.java)
                ?.let { if (subId > 0) it.createForSubscriptionId(subId) else it }
        } else {
            @Suppress("DEPRECATION")
            if (subId > 0) android.telephony.SmsManager.getSmsManagerForSubscriptionId(subId)
            else android.telephony.SmsManager.getDefault()
        }
        if (smsManager == null) {
            Log.e(TAG, "MO SMS: cannot resolve SmsManager for subId=$subId")
            return google.internal.communications.phonedeviceverification.v1.ChallengeResponse(
                mo_challenge_response = google.internal.communications.phonedeviceverification.v1.MOChallengeResponse(
                    status = google.internal.communications.phonedeviceverification.v1.MOChallengeStatus.MO_STATUS_NO_SMS_MANAGER
                )
            )
        }

        val messageId = java.util.UUID.randomUUID().toString()
        val action = "org.microg.gms.constellation.MO_SMS_SENT"
        val sentIntent = android.content.Intent(action).apply {
            `package` = context.packageName
            putExtra("message_id", messageId)
        }
        val pendingIntent = android.app.PendingIntent.getBroadcast(
            context, messageId.hashCode(), sentIntent,
            android.app.PendingIntent.FLAG_ONE_SHOT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        Log.i(TAG, "MO SMS: sending to $proxyNumber (messageId=$messageId, subId=$subId)")

        val port = moChallenge.data_sms_info?.port ?: 0
        return kotlinx.coroutines.withTimeoutOrNull(30_000L) {
            kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
                val receiver = object : android.content.BroadcastReceiver() {
                    override fun onReceive(ctx: android.content.Context, intent: android.content.Intent) {
                        if (intent.getStringExtra("message_id") != messageId) return
                        val resultCode = resultCode
                        val errorCode = intent.getIntExtra("errorCode", -1)
                        Log.d(TAG, "MO SMS sent result: code=$resultCode error=$errorCode")
                        try { ctx.unregisterReceiver(this) } catch (_: Exception) {}
                        if (continuation.isActive) {
                            val status = if (resultCode == -1) // Activity.RESULT_OK
                                google.internal.communications.phonedeviceverification.v1.MOChallengeStatus.MO_STATUS_COMPLETED
                            else
                                google.internal.communications.phonedeviceverification.v1.MOChallengeStatus.MO_STATUS_FAILED_TO_SEND
                            continuation.resume(google.internal.communications.phonedeviceverification.v1.ChallengeResponse(
                                mo_challenge_response = google.internal.communications.phonedeviceverification.v1.MOChallengeResponse(
                                    status = status,
                                    sms_result_code = resultCode.toLong(),
                                    sms_error_code = errorCode.toLong()
                                )
                            )) {}
                        }
                    }
                }
                androidx.core.content.ContextCompat.registerReceiver(
                    context, receiver, android.content.IntentFilter(action),
                    androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
                )
                continuation.invokeOnCancellation {
                    try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
                }
                try {
                    if (port > 0) {
                        smsManager.sendDataMessage(proxyNumber, null, port.toShort(), smsText.toByteArray(), pendingIntent, null)
                    } else {
                        smsManager.sendTextMessage(proxyNumber, null, smsText, pendingIntent, null)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "MO SMS send failed: ${e.message}")
                    try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
                    if (continuation.isActive) {
                        continuation.resume(google.internal.communications.phonedeviceverification.v1.ChallengeResponse(
                            mo_challenge_response = google.internal.communications.phonedeviceverification.v1.MOChallengeResponse(
                                status = google.internal.communications.phonedeviceverification.v1.MOChallengeStatus.MO_STATUS_FAILED_TO_SEND
                            )
                        )) {}
                    }
                }
            }
        } ?: google.internal.communications.phonedeviceverification.v1.ChallengeResponse(
            mo_challenge_response = google.internal.communications.phonedeviceverification.v1.MOChallengeResponse(
                status = google.internal.communications.phonedeviceverification.v1.MOChallengeStatus.MO_STATUS_FAILED_TO_SEND
            )
        )
    }

    /**
     * Verify via Carrier ID: send SIM ISIM challenge to TelephonyManager.getIccAuthentication().
     * Pure SIM-level crypto, no DG needed.
     */
    private fun verifyCarrierId(
        context: Context,
        challenge: google.internal.communications.phonedeviceverification.v1.CarrierIDChallenge,
        subId: Int
    ): google.internal.communications.phonedeviceverification.v1.ChallengeResponse {
        val challengeData = challenge.isim_request.takeIf { it.isNotEmpty() }
        if (challengeData == null) {
            Log.w(TAG, "CarrierID: empty isim_request")
            return carrierIdError(google.internal.communications.phonedeviceverification.v1.CarrierIdError.CARRIER_ID_ERROR_UNKNOWN_ERROR)
        }
        if (subId <= 0) {
            Log.w(TAG, "CarrierID: invalid subId=$subId")
            return carrierIdError(google.internal.communications.phonedeviceverification.v1.CarrierIdError.CARRIER_ID_ERROR_NO_SIM)
        }
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
            ?: return carrierIdError(google.internal.communications.phonedeviceverification.v1.CarrierIdError.CARRIER_ID_ERROR_NOT_SUPPORTED)
        val targetTm = tm.createForSubscriptionId(subId)
        val appType = challenge.app_type.takeIf { it != 0 } ?: android.telephony.TelephonyManager.APPTYPE_USIM

        return try {
            val response = targetTm.getIccAuthentication(appType, challenge.auth_type, challengeData)
            if (response.isNullOrEmpty()) {
                Log.w(TAG, "CarrierID: null/empty ISIM response")
                carrierIdError(google.internal.communications.phonedeviceverification.v1.CarrierIdError.CARRIER_ID_ERROR_NULL_RESPONSE)
            } else {
                Log.i(TAG, "CarrierID: ISIM response ${response.length} chars")
                google.internal.communications.phonedeviceverification.v1.ChallengeResponse(
                    carrier_id_challenge_response = google.internal.communications.phonedeviceverification.v1.CarrierIDChallengeResponse(
                        isim_response = response,
                        carrier_id_error = google.internal.communications.phonedeviceverification.v1.CarrierIdError.CARRIER_ID_ERROR_NO_ERROR
                    )
                )
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "CarrierID: SecurityException - ${e.message}")
            carrierIdError(google.internal.communications.phonedeviceverification.v1.CarrierIdError.CARRIER_ID_ERROR_UNABLE_TO_READ_SUBSCRIPTION)
        } catch (e: Exception) {
            Log.e(TAG, "CarrierID: failed - ${e.message}")
            carrierIdError(google.internal.communications.phonedeviceverification.v1.CarrierIdError.CARRIER_ID_ERROR_REFLECTION_ERROR)
        }
    }

    private fun carrierIdError(error: google.internal.communications.phonedeviceverification.v1.CarrierIdError): google.internal.communications.phonedeviceverification.v1.ChallengeResponse {
        return google.internal.communications.phonedeviceverification.v1.ChallengeResponse(
            carrier_id_challenge_response = google.internal.communications.phonedeviceverification.v1.CarrierIDChallengeResponse(
                isim_response = "",
                carrier_id_error = error
            )
        )
    }

    /**
     * Handle TS.43 challenge from server. Server provides entitlement_url + eap_aka_realm.
     * Delegates to Ts43Client for EAP-AKA authentication with the SIM.
     * Returns Ts43ChallengeResponse with auth token or error.
     */
    private fun handleTs43Challenge(
        context: Context,
        ts43Challenge: google.internal.communications.phonedeviceverification.v1.Ts43Challenge,
        subId: Int,
        phoneNumber: String?
    ): google.internal.communications.phonedeviceverification.v1.ChallengeResponse? {
        try {
            val entitlementUrl = ts43Challenge.entitlement_url
            if (entitlementUrl.isNullOrEmpty()) {
                Log.w(TAG, "  TS43: empty entitlement_url in challenge")
                return google.internal.communications.phonedeviceverification.v1.ChallengeResponse(
                    ts43_challenge_response = google.internal.communications.phonedeviceverification.v1.Ts43ChallengeResponse(
                        ts43_type = ts43Challenge.ts43_type,
                        error = google.internal.communications.phonedeviceverification.v1.Error(
                            error_type = google.internal.communications.phonedeviceverification.v1.ErrorType.ERROR_TYPE_INTERNAL_ERROR
                        )
                    )
                )
            }

            val ts43Client = Ts43Client(context)
            val result = ts43Client.performEntitlementCheckResult(
                subId, phoneNumber ?: "", "", "",
                entitlementUrl, ts43Challenge.eap_aka_realm
            )
            Log.i(TAG, "  TS43: result ineligible=${result.ineligible} error=${result.isError} token=${result.token?.length ?: 0} chars")

            if (result.isError) {
                return google.internal.communications.phonedeviceverification.v1.ChallengeResponse(
                    ts43_challenge_response = google.internal.communications.phonedeviceverification.v1.Ts43ChallengeResponse(
                        ts43_type = ts43Challenge.ts43_type,
                        error = google.internal.communications.phonedeviceverification.v1.Error(
                            error_type = google.internal.communications.phonedeviceverification.v1.ErrorType.ERROR_TYPE_INTERNAL_ERROR
                        )
                    )
                )
            }

            // Route result to correct proto field based on challenge type
            val responseToken = result.token ?: ""
            val ts43Response = if (ts43Challenge.server_challenge != null) {
                // Server challenge: return in server_challenge_response
                google.internal.communications.phonedeviceverification.v1.Ts43ChallengeResponse(
                    ts43_type = ts43Challenge.ts43_type,
                    server_challenge_response = google.internal.communications.phonedeviceverification.v1.ServerChallengeResponse(
                        acquire_temporary_token_response = responseToken
                    )
                )
            } else {
                // Client challenge (default): return in client_challenge_response
                google.internal.communications.phonedeviceverification.v1.Ts43ChallengeResponse(
                    ts43_type = ts43Challenge.ts43_type,
                    client_challenge_response = google.internal.communications.phonedeviceverification.v1.ClientChallengeResponse(
                        get_phone_number_response = responseToken
                    )
                )
            }
            return google.internal.communications.phonedeviceverification.v1.ChallengeResponse(
                ts43_challenge_response = ts43Response
            )
        } catch (e: Exception) {
            Log.e(TAG, "  TS43: exception: ${e.javaClass.simpleName}: ${e.message}")
            return google.internal.communications.phonedeviceverification.v1.ChallengeResponse(
                ts43_challenge_response = google.internal.communications.phonedeviceverification.v1.Ts43ChallengeResponse(
                    ts43_type = ts43Challenge.ts43_type,
                    error = google.internal.communications.phonedeviceverification.v1.Error(
                        error_type = google.internal.communications.phonedeviceverification.v1.ErrorType.ERROR_TYPE_INTERNAL_ERROR
                    )
                )
            )
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

    private fun logSnapshot(label: String, json: JSONObject) {
        val payload = json.toString(2)
        val dir = File(context.filesDir, "constellation_logs")
        dir.mkdirs()
        val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        val file = File(dir, "${label}_${ts}.json")
        try {
            file.writeText(payload)
            // Log summary only
            val dgLen = json.optJSONObject("device_signals")?.optInt("droidguard_token_length", 0) ?: 0
            val phase = json.optString("phase", "?")
            Log.i(TAG, "$label: phase=$phase dgTokenLen=$dgLen written to ${file.absolutePath} (${payload.length} chars)")
        } catch (e: Exception) {
            Log.w(TAG, "$label: failed to write snapshot: ${e.message}")
        }
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
                Log.d(TAG, "Auth response: auth=${response.auth?.take(30) ?: "null"}..., expiry=${response.expiry}")
                Log.d(TAG, "Auth response IT field: ${response.auths?.take(50) ?: "null"}")
                Log.d(TAG, "Auth response itMetadata: ${response.itMetadata ?: "null"}")
                Log.d(TAG, "Auth response capabilities: ${response.capabilities ?: "null"}")
                Log.d(TAG, "Auth response full: $response")
                // If we got an intermediate token (it field), use that instead of regular auth
                val effectiveToken = response.auths ?: response.auth
                Log.d(TAG, "Using token: ${effectiveToken?.take(20) ?: "null"}... (${if (response.auths != null) "IT" else "regular auth"})")
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

    fun verifyPhoneNumber(request: AidlVerifyPhoneNumberRequest?, callingPackage: String?, imsiOverride: String?, msisdnOverride: String?): Ts43Client.EntitlementResult {
        val requestedNumber = request?.policyId ?: msisdnOverride
        Log.i(TAG, "========================================")
        Log.i(TAG, "Starting Google Constellation verification")
        Log.i(TAG, "========================================")
        Log.i(TAG, "Phone number: $requestedNumber")

        // Check DroidGuard cache status at start
        val dgPrefs = context.getSharedPreferences("constellation_prefs", Context.MODE_PRIVATE)
        val cachedDgToken = dgPrefs.getString("droidguard_token", null)
        val cachedDgTtl = dgPrefs.getLong("droidguard_token_ttl", 0L)
        val now = System.currentTimeMillis()
        if (cachedDgToken != null) {
            val ttlRemaining = cachedDgTtl - now
            val isValid = ttlRemaining > 0
            Log.i(TAG, "DroidGuard cache status at entry:")
            Log.i(TAG, "  - Cached token: ${cachedDgToken.length} chars, prefix=${cachedDgToken.take(10)}...")
            Log.i(TAG, "  - TTL: ${java.util.Date(cachedDgTtl)}")
            Log.i(TAG, "  - Status: ${if (isValid) "VALID (${ttlRemaining / 3600000}h remaining)" else "EXPIRED"}")
        } else {
            Log.i(TAG, "DroidGuard cache status at entry: EMPTY (no cached token)")
        }

        return try {
            // Before any Constellation RPC, log whether signature spoofing looks complete.
            // Constellation_verify appears to be stricter than other DroidGuard flows; if the VM/server
            // validates GmsCore identity using SigningInfo, partial spoofing can cause INVALID_ARGUMENT.
            logGmsCoreSignatureSpoofStatus()

            // 1. Get package info for headers
            val packageName = context.packageName
            val certSha1 = PackageUtils.firstSignatureDigest(context, packageName)
            Log.d(TAG, "Using API key auth with package=$packageName, cert=$certSha1")

            runBlocking {
                // S163: DG handle declared at runBlocking scope for finally cleanup
                var dgHandle: DroidGuardHandle? = null
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

                // ============================================================
                // DroidGuard Token Caching (per GMS bewt.java:1111-1128, 180-191)
                // ============================================================
                // GMS caches processed tokens from GetConsentResponse.field_9:
                //   - First request: sends raw ~43K token (CgZ... protobuf format)
                //   - Server returns processed ~6.8K token (ARfb... custom format) + TTL
                //   - Subsequent requests: use cached processed token
                // We implement the same caching to match GMS behavior.

                val dgCachePrefs = context.getSharedPreferences("constellation_prefs", Context.MODE_PRIVATE)

                fun parseRpcFlowOverrides(raw: String?): Map<String, String> {
                    if (raw.isNullOrBlank()) return emptyMap()
                    val out = mutableMapOf<String, String>()
                    raw.split(',', ';', '\n').forEach { entry ->
                        val trimmed = entry.trim()
                        if (trimmed.isEmpty()) return@forEach
                        val idx = trimmed.indexOf('=')
                        if (idx <= 0 || idx >= trimmed.length - 1) return@forEach
                        val rpc = trimmed.substring(0, idx).trim()
                        val flow = trimmed.substring(idx + 1).trim()
                        if (rpc.isNotEmpty() && flow.isNotEmpty()) {
                            out[rpc] = flow
                        }
                    }
                    return out
                }

                val globalFlowOverride = Settings.Global.getString(
                    context.contentResolver,
                    DG_FLOW_OVERRIDE_GLOBAL_KEY
                )?.trim()?.takeIf { it.isNotEmpty() }

                val rpcFlowOverrides = parseRpcFlowOverrides(
                    Settings.Global.getString(context.contentResolver, DG_FLOW_OVERRIDE_RPC_MAP_KEY)
                )

                if (globalFlowOverride != null) {
                    Log.w(TAG, "DG flow experiment: global override enabled '$globalFlowOverride' (key=$DG_FLOW_OVERRIDE_GLOBAL_KEY)")
                }
                if (rpcFlowOverrides.isNotEmpty()) {
                    Log.w(TAG, "DG flow experiment: per-RPC override map enabled $rpcFlowOverrides (key=$DG_FLOW_OVERRIDE_RPC_MAP_KEY)")
                }

                fun resolveDroidGuardFlow(rpcMethod: String): String {
                    return rpcFlowOverrides[rpcMethod]
                        ?: globalFlowOverride
                        ?: "constellation_verify"
                }

                fun flowCacheKeys(flow: String): Triple<String, String, String> {
                    if (flow == "constellation_verify") {
                        return Triple("droidguard_token", "droidguard_token_ttl", "droidguard_token_iid")
                    }
                    val safeFlow = flow.replace(Regex("[^A-Za-z0-9_.-]"), "_")
                    return Triple("droidguard_token_$safeFlow", "droidguard_token_ttl_$safeFlow", "droidguard_token_iid_$safeFlow")
                }

                // Check for cached DroidGuard token (GMS bewt.java reads from SharedPrefs)
                fun getCachedDroidGuardToken(flow: String): Triple<String?, Long, String?> {
                    val (tokenKey, ttlKey, iidKey) = flowCacheKeys(flow)
                    val cachedToken = dgCachePrefs.getString(tokenKey, null)
                    val cachedTtl = dgCachePrefs.getLong(ttlKey, 0L)
                    val cachedIid = dgCachePrefs.getString(iidKey, null)
                    return Triple(cachedToken, cachedTtl, cachedIid)
                }

                // Cache DroidGuard token from server response (GMS bewt.java:1111-1128)
                // Also stores the IID token used to generate this DG token, so we can
                // invalidate the cache if the IID changes (DG tokens are session-bound via iidHash).
                fun cacheDroidGuardToken(flow: String, token: String, ttlMillis: Long, currentIid: String) {
                    val (tokenKey, ttlKey, iidKey) = flowCacheKeys(flow)
                    Log.i(TAG, "Caching DroidGuard token for flow '$flow': ${token.length} chars, TTL=${java.util.Date(ttlMillis)}, IID=${currentIid.take(20)}...")
                    dgCachePrefs.edit()
                        .putString(tokenKey, token)
                        .putLong(ttlKey, ttlMillis)
                        .putString(iidKey, currentIid)
                        .apply()
                }

                // Clear cached token on auth errors (GMS bewt.java:180-191)
                fun clearDroidGuardTokenCache(flow: String, reason: String) {
                    val (tokenKey, ttlKey, iidKey) = flowCacheKeys(flow)
                    Log.w(TAG, "Clearing DroidGuard token cache for flow '$flow': $reason")
                    dgCachePrefs.edit()
                        .remove(tokenKey)
                        .remove(ttlKey)
                        .remove(iidKey)
                        .apply()
                }

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

                // S163: DG Handle Reuse - stock GMS (bfox.java) creates ONE handle per session
                // and calls snapshot() with different rpc bindings. microG was creating a new handle
                // per RPC, which loses VM session state. Now we init once and reuse.
                // dgHandle declared at runBlocking scope (above) for finally cleanup
                var dgHandleFlow: String? = null

                fun openOrReuseDgHandle(dgFlow: String): DroidGuardHandle? {
                    val existing = dgHandle
                    if (existing != null && existing.isOpened() && dgHandleFlow == dgFlow) {
                        Log.d(TAG, "DG handle REUSE (flow=$dgFlow)")
                        return existing
                    }
                    // Close stale handle if any
                    if (existing != null) {
                        try { existing.close() } catch (_: Exception) {}
                        dgHandle = null
                        dgHandleFlow = null
                    }
                    Log.i(TAG, "DG handle OPEN (flow=$dgFlow) - calling DroidGuardClientImpl.init()")
                    val droidGuard = DroidGuardClientImpl(context)
                    Log.i(TAG, "DG client created, calling init($dgFlow)")
                    val handleTask = droidGuard.init(dgFlow, null)
                    Log.i(TAG, "DG init() returned Task, isComplete=${handleTask.isComplete}, isCanceled=${handleTask.isCanceled}")
                    return try {
                        Log.i(TAG, "DG Tasks.await() START (30s timeout)")
                        val handle = Tasks.await(handleTask, 30, TimeUnit.SECONDS)
                        Log.i(TAG, "DG Tasks.await() COMPLETED - handle=${handle?.javaClass?.simpleName}")
                        dgHandle = handle
                        dgHandleFlow = dgFlow
                        handle
                    } catch (e: Exception) {
                        Log.e(TAG, "DG handle init failed: ${e.javaClass.simpleName}: ${e.message}")
                        Log.e(TAG, "DG stack trace:", e)
                        null
                    }
                }

                fun closeDgHandle() {
                    dgHandle?.let {
                        try { it.close() } catch (_: Exception) {}
                        Log.d(TAG, "DG handle CLOSED")
                    }
                    dgHandle = null
                    dgHandleFlow = null
                }

                // Helper function to generate RPC-specific DroidGuard token
                // CRITICAL: Each API method REQUIRES its own token with matching RPC binding!
                // GMS bewt.java (v26.02.33) passes LOWERCASE METHOD NAME as "rpc" binding:
                //   bewt.java:480 → "getConsent"
                //   bewt.java:694 → "sync"
                // Then bfox.java:80 → goyu.o("iidHash", c(str), "rpc", str2)
                // DroidGuard HMAC-binds the token to these inputs - wrong value = server rejection
                //
                // S163: Uses handle reuse - init once, snapshot() per RPC (matches stock bfox pattern)
                fun getDroidGuardToken(rpcMethod: String): String? {
                    Log.d(TAG, "getDroidGuardToken($rpcMethod) called")

                    val dgFlow = resolveDroidGuardFlow(rpcMethod)
                    if (dgFlow != "constellation_verify") {
                        Log.w(TAG, "DG flow experiment ACTIVE: rpc=$rpcMethod flow=$dgFlow")
                    }

                    // Step 1: Check cache first (like GMS does)
                    val (cachedToken, cachedTtl, cachedIid) = getCachedDroidGuardToken(dgFlow)
                    val now = System.currentTimeMillis()
                    val currentIid = iidToken

                    if (cachedToken != null && cachedTtl > 0) {
                        // Check IID binding: DG tokens are session-bound via iidHash
                        if (cachedIid != null && currentIid != null && cachedIid != currentIid) {
                            Log.w(TAG, "DG token cache invalidated: IID changed from ${cachedIid.take(20)}... to ${currentIid.take(20)}...")
                            clearDroidGuardTokenCache(dgFlow, "IID token changed (iidHash won't match)")
                        } else {
                            val ttlRemaining = cachedTtl - now
                            val ttlRemainingHours = ttlRemaining / (1000 * 60 * 60)

                            if (ttlRemaining > 0) {
                                Log.i(TAG, "DroidGuard cache HIT for $rpcMethod:")
                                Log.i(TAG, "  - Cached token: ${cachedToken.length} chars, prefix=${cachedToken.take(10)}...")
                                Log.i(TAG, "  - TTL expires: ${java.util.Date(cachedTtl)} (~${ttlRemainingHours}h remaining)")
                                if (cachedIid != null) Log.d(TAG, "  - DG token cache valid: IID matches")
                                Log.i(TAG, "  - Using CACHED token instead of calling DroidGuard VM")
                                return cachedToken
                            } else {
                                Log.w(TAG, "DroidGuard cache EXPIRED for $rpcMethod:")
                                Log.w(TAG, "  - Expired at: ${java.util.Date(cachedTtl)} (${-ttlRemainingHours}h ago)")
                                Log.w(TAG, "  - Will generate fresh token from VM")
                                clearDroidGuardTokenCache(dgFlow, "TTL expired")
                            }
                        }
                    } else {
                        Log.d(TAG, "DroidGuard cache MISS for $rpcMethod (no cached token)")
                    }

                    // Step 2: Generate fresh token via reused DG handle (S163: matches stock bfox pattern)
                    Log.d(TAG, "Calling DroidGuard VM for $rpcMethod...")
                    Log.d(TAG, "  - Flow: $dgFlow")
                    Log.d(TAG, "  - Bindings: iidHash=${iidHash.take(10)}..., rpc=$rpcMethod")

                    val handle = openOrReuseDgHandle(dgFlow) ?: return null
                    val dgBindings = mapOf(
                        "iidHash" to iidHash,
                        "rpc" to rpcMethod
                    )
                    return try {
                        val token = handle.snapshot(dgBindings)
                        if (token != null) {
                            Log.i(TAG, "DroidGuard VM returned token for $rpcMethod:")
                            Log.i(TAG, "  - Length: ${token.length} chars")
                            Log.i(TAG, "  - Prefix: ${token.take(20)}...")
                            val format = when {
                                token.startsWith("CgZ") || token.startsWith("Cg") -> "RAW_PROTOBUF (from VM)"
                                token.startsWith("ARfb") -> "PROCESSED (should be from cache!)"
                                token.startsWith("ERROR") -> "ERROR"
                                else -> "UNKNOWN"
                            }
                            Log.i(TAG, "  - Format: $format")
                            // DG quality gate: warn if token is suspiciously small
                            if (token.length < 30000) {
                                Log.w(TAG, "  ⚠ DG TOKEN QUALITY WARNING: ${token.length} chars < 30000")
                                Log.w(TAG, "    Stock GMS produces ~43K. If .unstable is in Magisk denylist,")
                                Log.w(TAG, "    CleveresTricky can't inject → smaller tokens → server may reject.")
                            } else {
                                Log.i(TAG, "  ✓ DG token size OK (${token.length} chars)")
                            }
                        } else {
                            Log.e(TAG, "DroidGuard VM returned NULL for $rpcMethod")
                        }
                        token
                    } catch (e: Exception) {
                        Log.e(TAG, "DroidGuard VM failed for $rpcMethod: ${e.javaClass.simpleName}: ${e.message}")
                        null
                    }
                }

                // Now call the actual Constellation API with the DroidGuard token
                // 3. Setup Grpc Client with API Key + Spatula auth
                // Stock GMS Constellation uses: X-Goog-Api-Key, X-Android-Package, X-Android-Cert, X-Goog-Spatula
                // Stock GMS does NOT use OAuth Bearer on gRPC transport (bewt.c bdpb has no account/scopes,
                // bedm.n() returns null, iluw Authorization interceptor is never created).
                // GetConsent has OAuth in proto body (gaia_ids field 2). Sync has no proto-level OAuth.
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

                val okHttpClient = OkHttpClient.Builder()
                    // Stock GMS uses 60s timeouts (decompiled). OkHttp default is 10s.
                    // Server regularly takes 11-15s during MO SMS challenge flows.
                    .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                    .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                    .addInterceptor { chain ->
                        val original = chain.request()
                        val requestBuilder = original.newBuilder()
                            .header("X-Goog-Api-Key", API_KEY)
                            .header("X-Android-Package", packageName)
                            .header("X-Android-Cert", certSha1 ?: "")
                            // Stock GMS user-agent: "grpc-java-cronet/1.79.0-SNAPSHOT" (imcx.java:189-192)
                            // microG default is "okhttp/4.12.0" which is NOT a gRPC user-agent
                            .header("User-Agent", "grpc-java-cronet/1.79.0-SNAPSHOT")
                        // Stock GMS sends X-Goog-Spatula on every Constellation RPC (beeb.java:217-219 → ilup interceptor)
                        if (!spatulaHeader.isNullOrEmpty()) {
                            requestBuilder.header("X-Goog-Spatula", spatulaHeader)
                        }
                        val request = requestBuilder
                            .method(original.method, original.body)
                            .build()
                        chain.proceed(request)
                    }
                    .build()

                val grpcClient = GrpcClient.Builder()
                    .client(okHttpClient)
                    .baseUrl("https://phonedeviceverification-pa.googleapis.com/")
                    // Stock GMS (Cronet gRPC) does NOT compress outgoing messages.
                    // Wire default minMessageToCompress=0 sends grpc-encoding:gzip on ALL requests.
                    // Server may reject gzip-encoded protos with INVALID_ARGUMENT.
                    .minMessageToCompress(Long.MAX_VALUE)
                    .build()

                val client = GrpcPhoneDeviceVerificationClient(grpcClient)

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

                // ============================================================
                // CREDENTIAL_AUDIT: One-stop summary of all session-critical state.
                // grep "CREDENTIAL_AUDIT" to instantly see if Q1+Q3 fixes worked.
                // ============================================================
                run {
                    val now = System.currentTimeMillis()

                    // IID token source
                    val iidDesc = when {
                        iidSource == "seeded-from-stock-gms-appid" -> "SEEDED from stock GMS appid.xml"
                        iidSource == "seeded-from-stock-gms" -> "SEEDED from stock GMS constellation_prefs gcm_token"
                        iidSource.startsWith("cached") -> "CACHED (previously registered)"
                        else -> "REGISTERED NEW ($iidSource)"
                    }

                    // EC key source
                    val ecKeySource = if (!storedPublicKeyBase64.isNullOrEmpty() && !storedPrivateKeyBase64.isNullOrEmpty()) "LOADED from constellation_prefs" else "GENERATED NEW"

                    // DG cache for each RPC
                    fun dgCacheSummary(rpc: String): String {
                        val flow = resolveDroidGuardFlow(rpc)
                        val (tokenKey, ttlKey) = flowCacheKeys(flow)
                        val token = dgCachePrefs.getString(tokenKey, null)
                        val ttl = dgCachePrefs.getLong(ttlKey, 0L)
                        if (token == null) return "MISS (no cached token)"
                        val remaining = ttl - now
                        val prefix = token.take(10)
                        return if (remaining > 0) "HIT ${token.length}ch ${prefix}... TTL=${remaining / 3600000}h${(remaining % 3600000) / 60000}m" else "EXPIRED ${token.length}ch (${-remaining / 3600000}h ago)"
                    }

                    // constellation_prefs key inventory
                    val allKeys = keyPrefs.all.keys.sorted()

                    Log.i(TAG, "═══ CREDENTIAL_AUDIT ═══")
                    Log.i(TAG, "  IID token: $iidDesc (${iidToken.take(20)}...)")
                    Log.i(TAG, "  EC keys:   $ecKeySource (pub=${publicKeyBytes.size}B)")
                    Log.i(TAG, "  EC acked:  $isPublicKeyAcked")
                    Log.i(TAG, "  DG cache getConsent: ${dgCacheSummary("getConsent")}")
                    Log.i(TAG, "  DG cache sync:       ${dgCacheSummary("sync")}")
                    Log.i(TAG, "  DG cache proceed:    ${dgCacheSummary("proceed")}")
                    Log.i(TAG, "  constellation_prefs keys: $allKeys")
                    Log.i(TAG, "═══ END CREDENTIAL_AUDIT ═══")
                }

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
                // CRITICAL FIX (S179): Match subscription to IMSI from request, not just firstOrNull()
                // On dual-SIM, firstOrNull() picks slot 0 (wrong SIM). Must match by IMSI/MSISDN.
                val targetImsi = request?.imsiRequests?.firstOrNull()?.imsi
                val targetMsisdn = request?.imsiRequests?.firstOrNull()?.msisdn
                val allSubs = subscriptionManager?.activeSubscriptionInfoList ?: emptyList()
                val subscriptionInfo = if (targetImsi != null && allSubs.size > 1) {
                    // Try matching by IMSI prefix (MCC+MNC from subscription)
                    allSubs.find { sub ->
                        val subMccMnc = "${sub.mcc}${String.format("%02d", sub.mnc)}"
                        targetImsi.startsWith(subMccMnc)
                    } ?: allSubs.find { sub ->
                        // Fallback: match by phone number
                        targetMsisdn != null && sub.number != null && sub.number.isNotEmpty() &&
                            (targetMsisdn.endsWith(sub.number.takeLast(8)) || sub.number.endsWith(targetMsisdn.takeLast(8)))
                    } ?: allSubs.firstOrNull().also {
                        Log.w(TAG, "Could not match IMSI $targetImsi to any subscription, using first")
                    }
                } else {
                    allSubs.firstOrNull()
                }
                Log.d(TAG, "Subscription match: target IMSI=${targetImsi?.take(5)}***, matched subId=${subscriptionInfo?.subscriptionId}, slot=${subscriptionInfo?.simSlotIndex}, mcc=${subscriptionInfo?.mcc}, mnc=${subscriptionInfo?.mnc}")
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

                // TelephonyInfo - verified against v26 bfqi.java:d() (S210)
                val phoneTypeInt = when (telephonyManagerSub?.phoneType) {
                    TelephonyManager.PHONE_TYPE_GSM -> 1
                    TelephonyManager.PHONE_TYPE_CDMA -> 2
                    TelephonyManager.PHONE_TYPE_SIP -> 3
                    else -> 0
                }

                val dataRoamingInt = if (telephonyManagerSub?.isNetworkRoaming == true) 2 else 1

                val activeNetworkInfo = connectivityManager?.activeNetworkInfo
                val networkRoamingInt = when {
                    activeNetworkInfo == null -> 0
                    activeNetworkInfo.isRoaming -> 2
                    else -> 1
                }

                val hasReadSms = context.checkSelfPermission(android.Manifest.permission.READ_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED
                val hasSendSms = context.checkSelfPermission(android.Manifest.permission.SEND_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED
                val smsCapabilityInt = if (!hasReadSms || !hasSendSms) {
                    3 // DEFAULT_CAPABILITY (no SMS perms, GMS: i2=5, 5-2=3)
                } else {
                    val userManager = context.getSystemService(Context.USER_SERVICE) as? android.os.UserManager
                    val userRestricted = userManager?.userRestrictions?.getBoolean("no_sms") == true
                    val isSmsCapable = telephonyManagerSub?.isSmsCapable == true
                    if (userRestricted) 4 else if (!isSmsCapable) 1 else 2
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
                    phone_type = phoneTypeInt,
                    group_id_level1 = groupIdLevel1,
                    sim_country = MobileOperatorCountry(
                        country_iso = simCountry,
                        mcc_mnc = simOperatorStr,
                        operator_name = telephonyManager?.simOperatorName ?: "",
                        nil_since_millis = 0
                    ),
                    network_country = MobileOperatorCountry(
                        country_iso = networkCountry,
                        mcc_mnc = networkOperatorStr,
                        operator_name = telephonyManager?.networkOperatorName ?: "",
                        nil_since_millis = 0
                    ),
                    data_roaming = dataRoamingInt,
                    network_roaming = networkRoamingInt,
                    sms_capability = smsCapabilityInt,
                    subscription_count = activeSubCount,
                    subscription_count_max = maxSubCount,
                    eap_aka_capability = carrierIdCapabilityInt,
                    sms_no_confirm_capability = smsNoConfirmInt,
                    sim_state = simStateEnum,
                    imei = imei.takeIf { it.isNotEmpty() },
                    service_state = serviceStateEnum,
                    is_embedded = isEmbedded,
                    sim_carrier_id = carrierId
                )
                Log.d(TAG, "TelephonyInfo: phoneType=$phoneTypeInt, gid1=$groupIdLevel1, simState=$simStateEnum, serviceState=$serviceStateEnum, subs=$activeSubCount/$maxSubCount")

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
                // Fallback to TelephonyManager if IMSI not provided in request/override
                // CRITICAL FIX (S103): Use subscription-specific telephonyManagerSub (line 984),
                // NOT a new default TelephonyManager. Default TM reads wrong SIM on dual-SIM.
                val telephonyImsi = try {
                    telephonyManagerSub?.subscriberId ?: ""
                } catch (e: SecurityException) {
                    Log.w(TAG, "Cannot read IMSI (no READ_PHONE_STATE permission): ${e.message}")
                    ""
                }
                val imsi = requestImsi ?: imsiOverride ?: telephonyImsi
                // MSISDN resolution: use SubscriptionInfo.number (reliable per-SIM) NOT
                // getLine1Number() which returns wrong SIM's number on Samsung dual-SIM (S181).
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

                // ══════ PRE-FLIGHT PARANOID VALIDATION ══════
                // Cross-check all resolved values before burning a server attempt.
                // Each check logs WARN on mismatch but does NOT abort - server attempt
                // proceeds so we can observe the failure mode.
                Log.i(TAG, "╔══ PRE-FLIGHT CHECKS ══╗")

                // 1. MSISDN cross-validation: AIDL vs SIM vs telephony
                val allMsisdnSources = mutableMapOf<String, String>()
                if (!requestMsisdn.isNullOrEmpty()) allMsisdnSources["AIDL.ImsiRequest"] = requestMsisdn
                if (!msisdnOverride.isNullOrEmpty()) allMsisdnSources["ServiceImpl.override"] = msisdnOverride
                if (telephonyMsisdn.isNotEmpty()) allMsisdnSources["SubscriptionInfo"] = telephonyMsisdn
                if (msisdn.isNotEmpty()) allMsisdnSources["resolved"] = msisdn
                val distinctNumbers = allMsisdnSources.values.toSet()
                if (distinctNumbers.size > 1) {
                    Log.w(TAG, "║ ⚠ MSISDN MISMATCH across sources:")
                    allMsisdnSources.forEach { (src, num) -> Log.w(TAG, "║   $src = $num") }
                    Log.w(TAG, "║   Using: $msisdn - verify this is correct!")
                } else if (msisdn.isNotEmpty()) {
                    Log.i(TAG, "║ ✓ MSISDN consistent: $msisdn (${allMsisdnSources.keys.joinToString("+")})")
                } else {
                    Log.w(TAG, "║ ⚠ MSISDN EMPTY from all sources - server will see empty number")
                }

                // 2. IMSI cross-validation
                val allImsiSources = mutableMapOf<String, String>()
                if (!requestImsi.isNullOrEmpty()) allImsiSources["AIDL.ImsiRequest"] = requestImsi
                if (!imsiOverride.isNullOrEmpty()) allImsiSources["ServiceImpl.override"] = imsiOverride
                if (telephonyImsi.isNotEmpty()) allImsiSources["TelephonyManager"] = telephonyImsi
                val distinctImsis = allImsiSources.values.toSet()
                if (distinctImsis.size > 1) {
                    Log.w(TAG, "║ ⚠ IMSI MISMATCH: ${allImsiSources.entries.joinToString(", ") { "${it.key}=${it.value.take(5)}..." }}")
                } else if (imsi.isNotEmpty()) {
                    Log.i(TAG, "║ ✓ IMSI consistent: ${imsi.take(5)}... (${allImsiSources.keys.joinToString("+")})")
                }

                // 3. Subscription validity
                if (subscriptionInfo != null) {
                    Log.i(TAG, "║ ✓ Subscription: subId=$subId, slot=${subscriptionInfo.simSlotIndex}, carrier=${subscriptionInfo.carrierName}, iccId=${subscriptionInfo.iccId?.takeLast(5) ?: "null"}")
                } else {
                    Log.w(TAG, "║ ⚠ NO SubscriptionInfo for subId=$subId - SIM may be inactive")
                }

                // 4. Phone number format validation (E.164)
                if (msisdn.isNotEmpty()) {
                    if (!msisdn.startsWith("+")) {
                        Log.w(TAG, "║ ⚠ MSISDN '$msisdn' missing + prefix (not E.164)")
                    }
                    if (msisdn.length < 8 || msisdn.length > 16) {
                        Log.w(TAG, "║ ⚠ MSISDN '$msisdn' length=${msisdn.length} outside normal range [8-16]")
                    }
                    // Austrian numbers: +43 + 3-4 digit area + 7-8 digit subscriber = 12-15 chars
                    if (msisdn.startsWith("+43") && msisdn.length > 14) {
                        Log.w(TAG, "║ ⚠ MSISDN '$msisdn' suspiciously long for Austrian number (${msisdn.length} chars)")
                    }
                }

                // 5. DG token quality warning
                // (actual size checked after generation, but log intent here)
                Log.i(TAG, "║ DG denylist note: .unstable ${if (msisdn.isNotEmpty()) "should NOT be in Magisk denylist for best DG quality" else ""}")

                Log.i(TAG, "╚══ PRE-FLIGHT DONE ══╝")

                // Stock GMS V2 (bevr.java:132,142) clones caller extras bundle, adds calling_api.
                // V1 (bevr.java:259) also adds calling_package, but Messages uses V2.
                // bevm.k(bundle) converts ALL keys to proto Params.
                // Messages extras include: policy_id, session_id, required_consumer_consent,
                // one_time_verification, consent_type.
                val mergedBundle = if (request?.extras != null) android.os.Bundle(request.extras) else android.os.Bundle()
                // V2 path (bevr.java:142): stock GMS adds only calling_api, NOT calling_package.
                // calling_package is only added in V1 path (bevr.java:259). Messages uses V2.
                mergedBundle.putString("calling_api", "verifyPhoneNumber")
                // S180: force_provisioning=true tells server the user confirmed their number.
                // S182: Was disabled (suspected of suppressing reason=5). S201: Re-enabled - reason=5
                // never comes anyway (server returns reason=0), and S174 PENDING had force_provisioning.
                if (!phoneNumber.isNullOrEmpty() && !mergedBundle.containsKey("force_provisioning")) {
                    mergedBundle.putString("force_provisioning", "true")
                    Log.i(TAG, "Added force_provisioning=true (MSISDN present, not in extras)")
                }
                // S182: Always declare OTP support. Without this, server returns reason=0
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
                // S164: Stock GMS does NOT add policy_id as a Param - it goes into
                // Verification.carrier_info.phone_number (bevm.java:1177). Remove test fallback.
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

                // S164: Stock GMS (bevm.java:1170-1253) populates ALL 5 CarrierInfo fields
                // and puts it in Verification.carrier_info (field 8). This tells the server
                // the carrier policy (upi_policy_id), which is critical for NONE→PENDING transition.
                val carrierInfo = CarrierInfo(
                    phone_number = phoneNumber,  // = upi_policy_id from Messages (e.g., "upi-carrier-id-mt-non-priority")
                    subscription_id = request?.timeout ?: 0L,
                    id_token_request = if (idTokenCertificateHash.isNotEmpty() || idTokenNonce.isNotEmpty()) {
                        IdTokenRequest(
                            certificate_hash = idTokenCertificateHash,
                            token_nonce = idTokenNonce
                        )
                    } else null,
                    calling_package = idTokenCallingPackage,
                    imsi_requests = imsiRequests
                )
                Log.d(TAG, "CarrierInfo: phone_number=$phoneNumber, sub_id=${request?.timeout}, calling_package=$idTokenCallingPackage, imsi_requests=${imsiRequests.size}")

                // Get Android IDs for DeviceId (GMS bejs.java:93-126)
                // S201 FIX: Settings.Secure.ANDROID_ID is per-app/per-signing-key on Android 8+.
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

                fun buildSnapshot(
                    phase: String,
                    droidGuardToken: String?,
                    clientCredentialsIncluded: Boolean?,
                    requestParams: List<Param>,
                    verificationTokens: List<google.internal.communications.phonedeviceverification.v1.VerificationToken>,
                    carrierInfoForSnapshot: CarrierInfo?,
                    smsTokenForSnapshot: String?
                ): JSONObject {
                    fun sha256_8(value: String): String {
                        return MessageDigest.getInstance("SHA-256")
                            .digest(value.toByteArray(Charsets.UTF_8))
                            .joinToString("") { "%02x".format(it) }
                            .take(8)
                    }

                    fun summarizeOpaqueString(value: String): JSONObject {
                        return JSONObject()
                            .put("length", value.length)
                            .put("prefix", value.take(16))
                            .put("sha256_8", sha256_8(value))
                    }

                    val snapshot = JSONObject()
                    snapshot.put("phase", phase)
                    snapshot.put("timestamp_ms", System.currentTimeMillis())
                    snapshot.put("api_key", API_KEY)
                    snapshot.put("package_name", packageName)
                    snapshot.put("cert_sha1", certSha1)
                    snapshot.put("iid_token", iidToken)
                    snapshot.put("iid_source", iidSource)
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
                    snapshot.put("registered_app_ids_source", if (gaiaTokens.isNotEmpty()) "auth_manager" else "placeholder")
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

                    val spatulaJson = JSONObject()
                        .put("source", spatulaHeaderSource)
                        .put("present", !spatulaHeader.isNullOrEmpty())
                        .put("length", spatulaHeader?.length ?: 0)
                    if (!spatulaHeader.isNullOrEmpty()) {
                        spatulaJson.put("sha256_8", sha256_8(spatulaHeader))
                        spatulaJson.put("prefix", spatulaHeader.take(16))
                    }
                    snapshot.put("spatula", spatulaJson)

                    val gaiaIdsJson = JSONArray()
                    gaiaIdsList.forEach { gaiaId -> gaiaIdsJson.put(gaiaId) }
                    snapshot.put("gaia_ids", gaiaIdsJson)

                    val registeredAppIdsJson = JSONArray()
                    effectiveTokens.forEach { token ->
                        registeredAppIdsJson.put(summarizeOpaqueString(token))
                    }
                    snapshot.put("registered_app_ids", registeredAppIdsJson)

                    val telephonyInfoContainerJson = JSONArray()
                    telephonyInfoContainer?.entries?.forEach { entry ->
                        val entryTimestamp = entry.timestamp
                        telephonyInfoContainerJson.put(
                            JSONObject()
                                .put("gaia_id", entry.gaia_id)
                                .put("state", entry.state)
                                .put("timestamp_seconds", entryTimestamp?.seconds ?: JSONObject.NULL)
                                .put("timestamp_nanos", entryTimestamp?.nanos ?: JSONObject.NULL)
                        )
                    }
                    snapshot.put("telephony_info_container_entries", telephonyInfoContainerJson)

                    val countryJson = JSONObject()
                        .put("sim_countries", JSONArray().put(simCountry))
                        .put("network_countries", JSONArray().put(networkCountry))
                    snapshot.put("country_info", countryJson)

                    snapshot.put("connectivity_infos", connectivityInfosJson)

                    val telephonyJson = JSONObject()
                        .put("phone_type", phoneTypeInt)
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
                        .put("data_roaming", dataRoamingInt)
                        .put("network_roaming", networkRoamingInt)
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

                    val aidlRequestJson = JSONObject()
                        .put("policy_id", request?.policyId ?: JSONObject.NULL)
                        .put("timeout", request?.timeout ?: JSONObject.NULL)
                        .put("allow_fallback", request?.allowFallback ?: JSONObject.NULL)
                        .put("verification_type", request?.verificationType ?: JSONObject.NULL)
                        .put("verification_capabilities_count", request?.verificationCapabilities?.size ?: 0)
                    val idTokenRequestJson = JSONObject()
                        .put("certificate_hash", idTokenCertificateHash)
                        .put("certificate_hash_length", idTokenCertificateHash.length)
                        .put("token_nonce", idTokenNonce)
                        .put("token_nonce_length", idTokenNonce.length)
                        .put("calling_package", idTokenCallingPackage)
                    aidlRequestJson.put("id_token_request", idTokenRequestJson)
                    val extrasJson = JSONObject()
                    for (key in mergedBundle.keySet().sorted()) {
                        extrasJson.put(key, mergedBundle.get(key)?.toString() ?: JSONObject.NULL)
                    }
                    aidlRequestJson.put("extras", extrasJson)
                    snapshot.put("aidl_request", aidlRequestJson)

                    val paramsJson = JSONArray()
                    requestParams.forEach { param ->
                        paramsJson.put(
                            JSONObject()
                                .put("name", param.name)
                                .put("value", param.value_)
                        )
                    }
                    snapshot.put("api_params", paramsJson)

                    val verificationTokensJson = JSONArray()
                    verificationTokens.forEach { token ->
                        verificationTokensJson.put(
                            android.util.Base64.encodeToString(token.encode(), android.util.Base64.NO_WRAP)
                        )
                    }
                    snapshot.put("verification_tokens_count", verificationTokens.size)
                    snapshot.put("verification_tokens", verificationTokensJson)

                    snapshot.put("sms_token", smsTokenForSnapshot ?: JSONObject.NULL)
                    snapshot.put("sms_token_length", smsTokenForSnapshot?.length ?: 0)

                    if (carrierInfoForSnapshot != null) {
                        val carrierInfoJson = JSONObject()
                            .put("phone_number", carrierInfoForSnapshot.phone_number)
                            .put("subscription_id", carrierInfoForSnapshot.subscription_id)
                            .put("calling_package", carrierInfoForSnapshot.calling_package)
                        val imsiRequestsJson = JSONArray()
                        carrierInfoForSnapshot.imsi_requests.forEach { imsiRequest ->
                            imsiRequestsJson.put(
                                JSONObject()
                                    .put("imsi", imsiRequest.imsi)
                                    .put("phone_number_hint", imsiRequest.phone_number_hint)
                            )
                        }
                        carrierInfoJson.put("imsi_requests", imsiRequestsJson)
                        carrierInfoJson.put("imsi_requests_count", carrierInfoForSnapshot.imsi_requests.size)
                        val carrierIdTokenRequest = carrierInfoForSnapshot.id_token_request
                        if (carrierIdTokenRequest != null) {
                            carrierInfoJson.put(
                                "id_token_request",
                                JSONObject()
                                    .put("certificate_hash", carrierIdTokenRequest.certificate_hash)
                                    .put("certificate_hash_length", carrierIdTokenRequest.certificate_hash.length)
                                    .put("token_nonce", carrierIdTokenRequest.token_nonce)
                                    .put("token_nonce_length", carrierIdTokenRequest.token_nonce.length)
                            )
                        } else {
                            carrierInfoJson.put("id_token_request", JSONObject.NULL)
                        }
                        snapshot.put("carrier_info", carrierInfoJson)
                    } else {
                        snapshot.put("carrier_info", JSONObject.NULL)
                    }

                    snapshot.put("getconsent_include_asterism_consents", JSONObject.NULL)
                    snapshot.put("getconsent_asterism_client_bool", true)
                    snapshot.put("getconsent_imei", JSONObject.NULL)

                    return snapshot
                }

                // Generate Sync-specific DroidGuard token
                // GMS bewt.java:694 passes lowercase method name as DG rpc binding
                // S167: Try raw DG first; if PERMISSION_DENIED, retry without DG (coverage of both paths)
                val syncTokenRaw = getDroidGuardToken("sync")
                val (cachedArfb, _, _) = getCachedDroidGuardToken(resolveDroidGuardFlow("sync"))
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

                val verificationMethodInfo = VerificationMethodInfo(
                    // Stock GMS sends EMPTY methods list (Phenotype default=0 → no method added).
                    // Verified: bevm.java:246-261, captured hzdb_syncrequest has no field 7.1.
                    // S108 discovery: we were sending [MT_SMS, TS43] - stock does NOT.
                    methods = emptyList(),
                    data_ = VerificationMethodData(value_ = smsToken)
                )

                val loadedVerificationTokens = loadVerificationTokens(keyPrefs)

                // ══ SYNC PRE-FLIGHT: final field validation ══
                Log.i(TAG, "╔══ SYNC PRE-FLIGHT ══╗")
                Log.i(TAG, "║ sim_readable_number = '$msisdn'")
                Log.i(TAG, "║ imsi = '${imsi.take(5)}...' (${imsi.length} chars)")
                Log.i(TAG, "║ iccid = '${iccId?.takeLast(5) ?: "null"}'")
                Log.i(TAG, "║ sim_slot = index=$simSlotIndex, sub_id=$subId")
                Log.i(TAG, "║ syncToken = ${syncToken?.length ?: 0} chars (${if (syncToken == null) "NO DG" else if (syncToken.startsWith("ARfb")) "ARfb" else "RAW"})")
                if (msisdn.isNotEmpty() && telephonyMsisdn.isNotEmpty() && msisdn != telephonyMsisdn) {
                    Log.e(TAG, "║ ⛔ FINAL MSISDN≠SIM: sending '$msisdn' but SIM has '$telephonyMsisdn'")
                }
                if (msisdn.isNotEmpty() && msisdn.startsWith("+43") && msisdn.length > 14) {
                    Log.e(TAG, "║ ⛔ MSISDN '$msisdn' too long for Austrian number!")
                }
                Log.i(TAG, "╚══ SYNC PRE-FLIGHT DONE ══╝")

                val request = SyncRequest(
                    verifications = listOf(
                        Verification(
                            association = VerificationAssociation(
                                sim = SIMAssociation(
                                    sim_info = SIMInfo(
                                        // CRITICAL FIX (S103): listOf("") encodes as field-present-but-empty on wire,
                                        // causing server error "imsi[0] empty". Send emptyList() when IMSI is blank.
                                        imsi = listOf(imsi).filter { it.isNotEmpty() },
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
                            verification_method_info = verificationMethodInfo,
                            carrier_info = carrierInfo
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
                            device_signals = if (syncToken != null) DeviceSignals(droidguard_token = syncToken) else DeviceSignals(),
                            // NOTE: Do NOT set gaia_ids here - stock GMS never populates
                            // ClientInfo.field_9 in SyncRequest (verified via protoc --decode_raw
                            // comparison, 2026-02-16). Only field_12 (registered_app_ids) is used.
                            has_read_privileged_phone_state_permission = 1,
                            registered_app_ids = registeredAppIds,
                            country_info = countryInfo,
                            connectivity_infos = connectivityInfos,
                            is_standalone_device = false,
                            telephony_info_container = telephonyInfoContainer,
                            model = Build.MODEL,
                            manufacturer = Build.MANUFACTURER,
                            device_fingerprint = Build.FINGERPRINT,
                            // Stock GMS capture: ClientInfo.field_18 = 1 (PHONE)
                            device_type = DeviceType.DEVICE_TYPE_PHONE,
                            experiment_infos = emptyList()
                        ),
                        trigger = RequestTrigger(
                            type = TriggerType.TRIGGER_TYPE_TRIGGER_API_CALL
                        )
                    ),
                    verification_tokens = loadedVerificationTokens
                )

                logSnapshot(
                    "ConstellationSnapshot-sync",
                    buildSnapshot(
                        "sync",
                        syncToken,
                        syncClientCredentials != null,
                        params,
                        loadedVerificationTokens,
                        carrierInfo,
                        smsToken
                    )
                )

                // 9. First call GetConsent (required before Sync for new clients!)
                // From proto: "When the client is a new client coming online for the first time.
                // It has checked the consent using GetConsent."
                Log.d(TAG, "Calling GetConsent first...")

                // Generate GetConsent-specific DroidGuard token
                // GMS bewt.java:480 passes lowercase method name as DG rpc binding
                val getConsentToken = getDroidGuardToken("getConsent")
                if (getConsentToken == null) {
                    Log.w(TAG, "DroidGuard token for GetConsent FAILED - proceeding WITHOUT DG (no-DG-first strategy, S163 proved this works)")
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
                    // Field 2: repeated StringId (OAuth tokens)
                    gaia_ids = registeredAppIds,
                    header_ = RequestHeader(
                        session_id = sessionId,
                        client_info = ClientInfo(
                            device_id = DeviceId(
                                iid_token = iidToken,
                                device_android_id = deviceAndroidId,  // SharedPrefs primary_device_id
                                user_android_id = userAndroidId  // Settings.Secure ANDROID_ID
                            ),
                            client_public_key = publicKeyBytes,
                            locale = localeStr,
                            gmscore_version_number = GMSCORE_VERSION_NUMBER,
                            gmscore_version = GMSCORE_VERSION,
                            android_sdk_version = Build.VERSION.SDK_INT,
                            device_signals = if (getConsentToken != null) DeviceSignals(droidguard_token = getConsentToken) else DeviceSignals(),
                            model = Build.MODEL,
                            manufacturer = Build.MANUFACTURER,
                            device_fingerprint = Build.FINGERPRINT,
                            device_type = DeviceType.DEVICE_TYPE_PHONE,
                            has_read_privileged_phone_state_permission = 1,
                            registered_app_ids = registeredAppIds,
                            country_info = countryInfo,
                            connectivity_infos = connectivityInfos,
                            is_standalone_device = false,
                            // CRITICAL FIX 2026-02-01: Field 20 IS sent by GMS
                            telephony_info_container = telephonyInfoContainer
                        ),
                        trigger = RequestTrigger(type = TriggerType.TRIGGER_TYPE_TRIGGER_API_CALL)
                    ),
                    // Field 5: same merged params as Sync (stock GMS uses same bundle for both)
                    api_params = params,
                    // Field 6: enum (typically UNKNOWN=0 which is omitted on wire)
                    include_asterism_consents = AsterismClient.ASTERISM_CLIENT_UNKNOWN,
                    // Field 8: bool, always true in GMS
                    asterism_client_bool = true,
                    imei = null
                )

                logSnapshot(
                    "ConstellationSnapshot-getConsent",
                    buildSnapshot(
                        "getConsent",
                        getConsentToken,
                        false,
                        params,
                        loadedVerificationTokens,
                        carrierInfo,
                        null
                    )
                )

                // Dump FULL GetConsentRequest hex for debugging (compare with protobuf spec)
                val consentRequestBytes = google.internal.communications.phonedeviceverification.v1.GetConsentRequest.ADAPTER.encode(consentRequest)
                Log.d(TAG, "GetConsentRequest size: ${consentRequestBytes.size} bytes")
                // Log in chunks of 1000 chars (500 bytes) to avoid logcat truncation
                val fullHex = consentRequestBytes.joinToString("") { String.format("%02x", it) }
                var offset = 0
                while (offset < fullHex.length) {
                    val chunk = fullHex.substring(offset, kotlin.math.min(offset + 1000, fullHex.length))
                     // Log.d(TAG, "GetConsentRequest hex[$offset]: $chunk")
                    offset += 1000
                }

                try {
                    val consentResponse = client.GetConsent().execute(consentRequest)
                    Log.i(TAG, "GetConsent SUCCESS!")

                    // S110: Dump ALL response fields to understand what server returns
                    Log.i(TAG, "GetConsentResponse FULL DUMP:")
                    Log.i(TAG, "  field 1 (device_consent): ${consentResponse.device_consent}")
                    Log.i(TAG, "  field 2 (app_specific_consents): ${consentResponse.app_specific_consents.size} items")
                    Log.i(TAG, "  field 3 (header): ${consentResponse.header_}")
                    Log.i(TAG, "  field 5 (next_sync_time): ${consentResponse.next_sync_time}")
                    Log.i(TAG, "  field 6 (client_behavior): ${consentResponse.client_behavior}")
                    Log.i(TAG, "  field 7 (asterism_client): ${consentResponse.asterism_client}")
                    Log.i(TAG, "  field 8 (asterism_consents): ${consentResponse.asterism_consents.size} items")
                    Log.i(TAG, "  field 9 (dg_token_response): ${consentResponse.droidguard_token_response}")
                    Log.i(TAG, "  field 10 (device_permission_info): ${consentResponse.device_permission_info}")

                    // S110: Dump raw response bytes so we can see ALL fields including unknown ones
                    try {
                        val responseBytes = consentResponse.encode()
                        val responseHex = responseBytes.joinToString("") { String.format("%02x", it) }
                        Log.i(TAG, "GetConsentResponse raw size: ${responseBytes.size} bytes")
                        var hexOffset = 0
                        while (hexOffset < responseHex.length) {
                            val chunk = responseHex.substring(hexOffset, kotlin.math.min(hexOffset + 200, responseHex.length))
                             // Log.d(TAG, "GetConsentResponse hex[$hexOffset]: $chunk")
                            hexOffset += 200
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to encode response for hex dump: ${e.message}")
                    }

                    // ============================================================
                    // CRITICAL: Cache DroidGuard token from response (GMS bewt.java:1111-1128)
                    // Server returns processed token (ARfb...) + TTL in field 9
                    // ============================================================
                    val dgTokenResponse = consentResponse.droidguard_token_response
                    if (dgTokenResponse != null) {
                        val serverToken = dgTokenResponse.droidguard_token
                        val serverTtl = dgTokenResponse.droidguard_token_ttl

                        Log.i(TAG, "Server returned DroidGuard token in response!")
                        Log.i(TAG, "  - Token length: ${serverToken?.length ?: 0} chars")
                        Log.i(TAG, "  - Token prefix: ${serverToken?.take(20) ?: "null"}...")

                        if (!serverToken.isNullOrEmpty()) {
                            val ttlMillis = try {
                                serverTtl?.toEpochMilli() ?: 0L
                            } catch (e: Exception) {
                                Log.w(TAG, "  - Failed to parse TTL as Instant, trying raw access", e)
                                0L
                            }

                            Log.i(TAG, "  - TTL: ${java.util.Date(ttlMillis)}")

                            val wasRaw = serverToken.startsWith("CgZ") || serverToken.startsWith("Cg")
                            val isProcessed = serverToken.startsWith("ARfb")
                            Log.i(TAG, "  - Format: ${if (isProcessed) "PROCESSED (ARfb)" else if (wasRaw) "RAW (unexpected!)" else "UNKNOWN"}")

                            // Cache it globally (stock GMS uses single key for all RPCs)
                            cacheDroidGuardToken(resolveDroidGuardFlow("getConsent"), serverToken, ttlMillis, iidToken)
                            Log.i(TAG, "  - CACHED for future requests (including Sync)!")
                        } else {
                            Log.w(TAG, "  - Token is empty, not caching")
                        }
                    } else {
                        Log.w(TAG, "No DroidGuard token in GetConsent response (field 9 empty)")
                        Log.w(TAG, "  Server did NOT return ARfb. Sync will use raw DG token (may fail).")
                    }

                    if (consentResponse.device_consent?.consent == ConsentValue.CONSENT_VALUE_CONSENTED) {
                        Log.i(TAG, "Consent already established")
                    } else {
                        // S162: Auto-call SetConsent when consent is not established.
                        // Stock GMS (bevm.java:1426-1428) throws bfpx and ABORTS here.
                        // Messages handles consent via Asterism service 199 (which microG doesn't implement).
                        // Instead, we call SetConsent directly to establish consent server-side.
                        //
                        // S163: API discovery doc says DeviceSignals "Required for Sync and Proceed calls
                        // before client starts signing" - NOT listed for SetConsent. GMS has Phenotype flag
                        // Rpc__is_droid_guard_enabled_for_get_set_consent (default=true) proving server
                        // handles DG-free SetConsent. Try without DG first; if PERMISSION_DENIED, retry with DG.
                        Log.w(TAG, "Consent NOT established (${consentResponse.device_consent?.consent}) - calling SetConsent(CONSENTED)...")
                        try {
                            // S163: Build SetConsent request builder lambda (reused for both attempts)
                            fun buildSetConsentRequest(dgToken: String?): SetConsentRequest {
                                return SetConsentRequest(
                                    header_ = RequestHeader(
                                        session_id = sessionId,
                                        client_info = ClientInfo(
                                            device_id = DeviceId(
                                                iid_token = iidToken,
                                                device_android_id = deviceAndroidId,
                                                user_android_id = userAndroidId
                                            ),
                                            client_public_key = publicKeyBytes,
                                            locale = localeStr,
                                            gmscore_version_number = GMSCORE_VERSION_NUMBER,
                                            gmscore_version = GMSCORE_VERSION,
                                            android_sdk_version = Build.VERSION.SDK_INT,
                                            device_signals = if (dgToken != null) DeviceSignals(droidguard_token = dgToken) else null,
                                            model = Build.MODEL,
                                            manufacturer = Build.MANUFACTURER,
                                            device_fingerprint = Build.FINGERPRINT,
                                            device_type = DeviceType.DEVICE_TYPE_PHONE,
                                            has_read_privileged_phone_state_permission = 1,
                                            registered_app_ids = registeredAppIds,
                                            country_info = countryInfo,
                                            connectivity_infos = connectivityInfos,
                                            is_standalone_device = false,
                                            telephony_info_container = telephonyInfoContainer
                                        ),
                                        trigger = RequestTrigger(type = TriggerType.TRIGGER_TYPE_TRIGGER_API_CALL)
                                    ),
                                    // GMS bewt.y(): asterism_client = RCS (via Asterism AIDL from Messages)
                                    asterism_client = AsterismClient.ASTERISM_CLIENT_RCS,
                                    // GMS bewt.y(): field 10 = DeviceVerificationConsent with consent+source+version
                                    device_verification_consent = DeviceVerificationConsent(
                                        consent_value = ConsentValue.CONSENT_VALUE_CONSENTED,
                                        consent_source = DeviceVerificationConsentSource.DEVICE_VERIFICATION_CONSENT_SOURCE_ANDROID_DEVICE_SETTINGS,
                                        consent_version = DeviceVerificationConsentVersion.DEVICE_VERIFICATION_CONSENT_VERSION_PHONE_VERIFICATION_DEFAULT
                                    )
                                )
                            }

                            // S163: Try SetConsent WITHOUT DG first (API doc says DG only required for Sync/Proceed)
                            // If PERMISSION_DENIED, retry WITH DG token
                            var setConsentSucceeded = false
                            Log.d(TAG, "SetConsent attempt 1: WITHOUT DroidGuard (API doc: DG not required for SetConsent)")
                            try {
                                val noDgRequest = buildSetConsentRequest(null)
                                Log.d(TAG, "SetConsent request size (no DG): ${noDgRequest.encode().size} bytes")
                                client.SetConsent().execute(noDgRequest)
                                Log.i(TAG, "SetConsent SUCCESS (no DG)! Server accepted consent without DroidGuard.")
                                setConsentSucceeded = true
                            } catch (e1: Exception) {
                                val code1 = if (e1 is com.squareup.wire.GrpcException) e1.grpcStatus.code else -1
                                Log.w(TAG, "SetConsent without DG failed: grpc-status=$code1 ${e1.message}")
                                if (code1 == 7) {
                                    // PERMISSION_DENIED - retry with DG token
                                    Log.d(TAG, "SetConsent attempt 2: WITH DroidGuard token")
                                    val setConsentToken = getDroidGuardToken("setConsent")
                                    if (setConsentToken != null) {
                                        try {
                                            val dgRequest = buildSetConsentRequest(setConsentToken)
                                            Log.d(TAG, "SetConsent request size (with DG): ${dgRequest.encode().size} bytes")
                                            client.SetConsent().execute(dgRequest)
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
                                val retryToken = getDroidGuardToken("getConsent")
                                if (retryToken != null) {
                                    val retryRequest = GetConsentRequest(
                                        device_id = DeviceId(iid_token = iidToken),
                                        gaia_ids = registeredAppIds,
                                        header_ = RequestHeader(
                                            session_id = sessionId,
                                            client_info = ClientInfo(
                                                device_id = DeviceId(
                                                    iid_token = iidToken,
                                                    device_android_id = deviceAndroidId,
                                                    user_android_id = userAndroidId
                                                ),
                                                client_public_key = publicKeyBytes,
                                                locale = localeStr,
                                                gmscore_version_number = GMSCORE_VERSION_NUMBER,
                                                gmscore_version = GMSCORE_VERSION,
                                                android_sdk_version = Build.VERSION.SDK_INT,
                                                device_signals = DeviceSignals(droidguard_token = retryToken),
                                                model = Build.MODEL,
                                                manufacturer = Build.MANUFACTURER,
                                                device_fingerprint = Build.FINGERPRINT,
                                                device_type = DeviceType.DEVICE_TYPE_PHONE,
                                                has_read_privileged_phone_state_permission = 1,
                                                registered_app_ids = registeredAppIds,
                                                country_info = countryInfo,
                                                connectivity_infos = connectivityInfos,
                                                is_standalone_device = false,
                                                telephony_info_container = telephonyInfoContainer
                                            ),
                                            trigger = RequestTrigger(type = TriggerType.TRIGGER_TYPE_TRIGGER_API_CALL)
                                        ),
                                        api_params = params,
                                        include_asterism_consents = AsterismClient.ASTERISM_CLIENT_UNKNOWN,
                                        asterism_client_bool = true,
                                        imei = null
                                    )
                                    val retryResponse = client.GetConsent().execute(retryRequest)
                                    Log.i(TAG, "GetConsent retry: consent=${retryResponse.device_consent?.consent}")

                                    val retryDg = retryResponse.droidguard_token_response
                                    if (retryDg != null && !retryDg.droidguard_token.isNullOrEmpty()) {
                                        val ttl = try { retryDg.droidguard_token_ttl?.toEpochMilli() ?: 0L } catch (_: Exception) { 0L }
                                        cacheDroidGuardToken(resolveDroidGuardFlow("getConsent"), retryDg.droidguard_token!!, ttl, iidToken)
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
                    // S109: Structured GrpcException logging (Wire 4.9.9)
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
                            clearDroidGuardTokenCache(resolveDroidGuardFlow("getConsent"), "Auth error (grpc-status=${e.grpcStatus.code})")
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

                // Pre-register SMS receivers BEFORE Sync (S210 race fix).
                // SMS may arrive during the Sync RPC if server issues PENDING immediately.
                SmsInbox.prepare(context)

                try {

                // 10. Execute Sync with retry on PERMISSION_DENIED
                // S130: Stock GMS retries transient PERMISSION_DENIED ~43s later and succeeds (S113 proof).
                // Fresh install triggers server-side transient block that clears within seconds to minutes.
                val MAX_SYNC_ATTEMPTS = 3
                val SYNC_RETRY_DELAY_MS = 45_000L  // 45s between retries (stock GMS observed ~43s)

                var currentSyncRequest = request
                var syncRetryResponse: SyncResponse? = null

                for (syncAttempt in 1..MAX_SYNC_ATTEMPTS) {
                    Log.d(TAG, "Sending Sync request (attempt $syncAttempt/$MAX_SYNC_ATTEMPTS)...")
                    val requestByteArray = SyncRequest.ADAPTER.encode(currentSyncRequest)
                    Log.d(TAG, "SyncRequest size: ${requestByteArray.size} bytes")

                    try {
                        syncRetryResponse = client.Sync().execute(currentSyncRequest)
                        Log.i(TAG, "Sync SUCCESS on attempt $syncAttempt/$MAX_SYNC_ATTEMPTS")
                        break
                    } catch (retryEx: Exception) {
                        val isPermissionDenied = retryEx is com.squareup.wire.GrpcException &&
                            retryEx.grpcStatus.code == 7

                        if (isPermissionDenied && syncAttempt < MAX_SYNC_ATTEMPTS) {
                            // S167: On first PERMISSION_DENIED, retry WITHOUT DG to get NONE/PENDING state
                            // On second PERMISSION_DENIED, retry WITH fresh DG after delay
                            if (syncAttempt == 1) {
                                Log.w(TAG, "Sync PERMISSION_DENIED (attempt 1/$MAX_SYNC_ATTEMPTS), retrying WITHOUT DG...")
                                val noDgClientInfo = currentSyncRequest.header_?.client_info?.let { ci ->
                                    ClientInfo(
                                        device_id = ci.device_id,
                                        client_public_key = ci.client_public_key,
                                        locale = ci.locale,
                                        gmscore_version_number = ci.gmscore_version_number,
                                        gmscore_version = ci.gmscore_version,
                                        android_sdk_version = ci.android_sdk_version,
                                        device_signals = DeviceSignals(),  // Empty - no DG
                                        has_read_privileged_phone_state_permission = ci.has_read_privileged_phone_state_permission,
                                        registered_app_ids = ci.registered_app_ids,
                                        country_info = ci.country_info,
                                        connectivity_infos = ci.connectivity_infos,
                                        is_standalone_device = ci.is_standalone_device,
                                        telephony_info_container = ci.telephony_info_container,
                                        model = ci.model,
                                        manufacturer = ci.manufacturer,
                                        device_fingerprint = ci.device_fingerprint,
                                        device_type = ci.device_type,
                                        experiment_infos = ci.experiment_infos
                                    )
                                }
                                currentSyncRequest = SyncRequest(
                                    verifications = request.verifications,
                                    header_ = RequestHeader(
                                        client_info = noDgClientInfo,
                                        client_credentials = request.header_?.client_credentials,
                                        session_id = request.header_?.session_id ?: "",
                                        trigger = request.header_?.trigger
                                    ),
                                    verification_tokens = request.verification_tokens
                                )
                                continue
                            }
                            Log.w(TAG, "Sync PERMISSION_DENIED (attempt $syncAttempt/$MAX_SYNC_ATTEMPTS), retrying with fresh DG in ${SYNC_RETRY_DELAY_MS / 1000}s...")
                            // Clear DG cache + key ack before retry
                            clearDroidGuardTokenCache(resolveDroidGuardFlow("sync"), "Sync PERMISSION_DENIED retry $syncAttempt")
                            keyPrefs.edit().putBoolean("is_public_key_acked", false).apply()

                            Thread.sleep(SYNC_RETRY_DELAY_MS)

                            // Refresh DG token for next attempt
                            val freshSyncToken = getDroidGuardToken("sync")
                            if (freshSyncToken == null) {
                                Log.e(TAG, "Failed to get fresh DroidGuard token for Sync retry")
                                throw retryEx
                            }
                            Log.d(TAG, "Got fresh DG token for retry (${freshSyncToken.length} chars)")

                            // Rebuild request with fresh DG token via newBuilder
                            val freshClientInfo = currentSyncRequest.header_?.client_info?.let { ci ->
                                ClientInfo(
                                    device_id = ci.device_id,
                                    client_public_key = ci.client_public_key,
                                    locale = ci.locale,
                                    gmscore_version_number = ci.gmscore_version_number,
                                    gmscore_version = ci.gmscore_version,
                                    android_sdk_version = ci.android_sdk_version,
                                    device_signals = DeviceSignals(droidguard_token = freshSyncToken),
                                    has_read_privileged_phone_state_permission = ci.has_read_privileged_phone_state_permission,
                                    registered_app_ids = ci.registered_app_ids,
                                    country_info = ci.country_info,
                                    connectivity_infos = ci.connectivity_infos,
                                    is_standalone_device = ci.is_standalone_device,
                                    telephony_info_container = ci.telephony_info_container,
                                    model = ci.model,
                                    manufacturer = ci.manufacturer,
                                    device_fingerprint = ci.device_fingerprint,
                                    device_type = ci.device_type,
                                    experiment_infos = ci.experiment_infos
                                )
                            }
                            currentSyncRequest = SyncRequest(
                                verifications = request.verifications,
                                header_ = RequestHeader(
                                    client_info = freshClientInfo,
                                    client_credentials = request.header_?.client_credentials,
                                    session_id = request.header_?.session_id ?: "",
                                    trigger = request.header_?.trigger
                                ),
                                verification_tokens = request.verification_tokens
                            )
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

                // 10. Cache SyncResponse DroidGuard token (Bug 3 fix)
                val syncDgTokenResponse = response.droidguard_token_response
                if (syncDgTokenResponse != null) {
                    val serverToken = syncDgTokenResponse.droidguard_token
                    val serverTtl = syncDgTokenResponse.droidguard_token_ttl
                    if (!serverToken.isNullOrEmpty()) {
                        val ttlMillis = serverTtl?.toEpochMilli() ?: 0L
                        cacheDroidGuardToken(resolveDroidGuardFlow("sync"), serverToken, ttlMillis, iidToken)
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
                            hasVerified = true
                        }
                        VerificationState.VERIFICATION_STATE_PENDING -> {
                            Log.w(TAG, "PENDING state - requires Proceed RPC with OTP")
                            hasPending = true
                        }
                        VerificationState.VERIFICATION_STATE_NONE -> {
                            // FIX (S103): NONE means "no verification record exists for this SIM".
                            // Stock GMS does NOT treat NONE as VERIFIED (bfqe.d() returns true only for VERIFIED).
                            // Likely cause: IMSI was empty (S101 showed "imsi[0] empty" → NONE).
                            // With IMSI fix (S103), server should return PENDING instead on next attempt.
                            // Try GPNV as best-effort (for stock→microG swap where server has cached verification).
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
                        val phoneNumberClient = GrpcPhoneNumberClient(grpcClient)
                        val tokensResponse = phoneNumberClient.GetVerifiedPhoneNumbers().execute(getTokensRequest)
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

                // 12b. FIX (S103): NONE state - best-effort GPNV for stock→microG swap, then check reason
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
                        val phoneNumberClient = GrpcPhoneNumberClient(grpcClient)
                        val tokensResponse = phoneNumberClient.GetVerifiedPhoneNumbers().execute(getTokensRequest)
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

                    // S169: Check UnverifiedInfo.reason from the NONE-state verification.
                    // GMS enum hzdf: 0=UNKNOWN, 1=THROTTLED, 2=FAILED, 3=SKIPPED, 4=NOT_REQUIRED,
                    // 5=PHONE_NUMBER_ENTRY_REQUIRED, 6=INELIGIBLE, 7=DENIED, 8=NOT_IN_SERVICE.
                    // GMS (bevm.a EnumMap) maps reason→status code sent to Messages:
                    //   5→7 (WaitingForManualMsisdnEntryState - phone number input UI)
                    val noneVerification = responses.firstOrNull {
                        it.verification?.state == VerificationState.VERIFICATION_STATE_NONE
                    }?.verification
                    val unverifiedReason = noneVerification?.unverified_info?.reason_enum_1 ?: 0
                    Log.i(TAG, "NONE state UnverifiedInfo: reason=$unverifiedReason")

                    // S169: Only reason=5 (PHONE_NUMBER_ENTRY_REQUIRED) maps to status 7.
                    // Stock GMS (bevm.a EnumMap) maps each reason to a specific status code:
                    //   0→0, 1→3(retry), 2→2(retry), 3→4, 4→5, 5→7(phone input), 6→8, 7→9, 8→10
                    // Returning status 7 for other reasons (e.g. THROTTLED=1) would show phone input
                    // when Messages should be retrying - incorrect behavior.
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

                // 13. Challenge dispatch loop (S211 - multi-type, multi-round)
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
                                    sendMoSms(context, moChallenge, subId)
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
                                        verifyCarrierId(context, carrierChallenge, subId)
                                    }
                                }
                            }
                            google.internal.communications.phonedeviceverification.v1.ChallengeType.CHALLENGE_TYPE_TS43 -> {
                                val ts43Challenge = challenge.ts43_challenge
                                if (ts43Challenge == null) {
                                    Log.w(TAG, "  TS43: no ts43_challenge data"); null
                                } else {
                                    Log.i(TAG, "  TS43: entitlement_url=${ts43Challenge.entitlement_url} realm=${ts43Challenge.eap_aka_realm}")
                                    handleTs43Challenge(context, ts43Challenge, subId, phoneNumber)
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
                        val proceedDgToken = getDroidGuardToken("proceed")
                        val proceedHeader = RequestHeader(
                            session_id = sessionId,
                            client_credentials = proceedClientCredentials,
                            client_info = ClientInfo(
                                device_id = proceedDeviceId,
                                client_public_key = publicKeyBytes,
                                locale = localeStr,
                                gmscore_version_number = GMSCORE_VERSION_NUMBER,
                                gmscore_version = GMSCORE_VERSION,
                                android_sdk_version = Build.VERSION.SDK_INT,
                                device_signals = if (proceedDgToken != null) DeviceSignals(droidguard_token = proceedDgToken) else DeviceSignals(),
                                model = Build.MODEL,
                                manufacturer = Build.MANUFACTURER,
                                device_fingerprint = Build.FINGERPRINT,
                                device_type = DeviceType.DEVICE_TYPE_PHONE,
                                has_read_privileged_phone_state_permission = 1,
                                registered_app_ids = registeredAppIds,
                                country_info = countryInfo,
                                connectivity_infos = connectivityInfos,
                                telephony_info_container = telephonyInfoContainer
                            ),
                            trigger = RequestTrigger(type = TriggerType.TRIGGER_TYPE_TRIGGER_API_CALL)
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
                            proceedResponse = client.Proceed().execute(noDgRequest)
                            Log.i(TAG, "Round $round: Proceed SUCCESS (no-DG)")
                        } catch (e: Exception) {
                            if (e is com.squareup.wire.GrpcException && proceedDgToken != null) {
                                Log.w(TAG, "Round $round: Proceed no-DG failed (${e.grpcStatus.name}), retrying with DG")
                                try {
                                    proceedResponse = client.Proceed().execute(proceedRequest)
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
                                cacheDroidGuardToken(resolveDroidGuardFlow("proceed"), dgResp.droidguard_token, dgResp.droidguard_token_ttl?.toEpochMilli() ?: 0L, iidToken)
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
                            val phoneNumberClient = GrpcPhoneNumberClient(grpcClient)
                            val tokensResponse = phoneNumberClient.GetVerifiedPhoneNumbers().execute(getTokensRequest)
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
                    // S109: Structured GrpcException logging (Wire 4.9.9)
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
                            clearDroidGuardTokenCache(resolveDroidGuardFlow("sync"), "Sync auth error (grpc-status=${e.grpcStatus.code})")
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
                        val fallbackPhoneNumberClient = GrpcPhoneNumberClient(grpcClient)
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
                        val fallbackResponse = fallbackPhoneNumberClient.GetVerifiedPhoneNumbers().execute(fallbackRequest)
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
                    // S210: Dispose SMS receivers (pre-registered before Sync)
                    SmsInbox.dispose(context)
                    // S163: Close reused DG handle at end of session
                    try { dgHandle?.close() } catch (_: Exception) {}
                    dgHandle = null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "verifyPhoneNumber failed with exception", e)
            Ts43Client.EntitlementResult.error("exception-${e.javaClass.simpleName}", e)
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
    }

    private fun logGmsCoreSignatureSpoofStatus() {
        // This is intentionally opinionated logging: we want an immediate on-device conclusion.
        // We test BOTH legacy signatures (GET_SIGNATURES) and SigningInfo (GET_SIGNING_CERTIFICATES).
        val fakeSigPermission = "android.permission.FAKE_PACKAGE_SIGNATURE"

        val knowsFakeSigPermission = try {
            context.packageManager.getPermissionInfo(fakeSigPermission, 0)
            true
        } catch (_: Exception) {
            false
        }

        val hasFakeSigPermission = try {
            if (Build.VERSION.SDK_INT >= 23) {
                context.checkSelfPermission(fakeSigPermission) == PackageManager.PERMISSION_GRANTED
            } else {
                // Pre-Marshmallow has no runtime permissions; treat as unknown.
                false
            }
        } catch (_: Exception) {
            false
        }

        val legacyDigest = try {
            org.microg.gms.common.PackageUtils.firstSignatureDigest(context, Constants.GMS_PACKAGE_NAME, false)
        } catch (_: Exception) {
            null
        }

        val signingInfoDigest = try {
            org.microg.gms.common.PackageUtils.firstSignatureDigest(context, Constants.GMS_PACKAGE_NAME, true)
        } catch (_: Exception) {
            null
        }

        Log.i(TAG, "GmsCore signature spoof check:")
        Log.i(TAG, "  - fakeSig permission known=$knowsFakeSigPermission granted=$hasFakeSigPermission")
        Log.i(TAG, "  - legacy cert sha1 (GET_SIGNATURES) = ${legacyDigest ?: "null"}")
        Log.i(TAG, "  - signingInfo sha1 (GET_SIGNING_CERTIFICATES) = ${signingInfoDigest ?: "null"}")

        val googleSha1 = Constants.GMS_PACKAGE_SIGNATURE_SHA1
        val microgSha1 = Constants.MICROG_PACKAGE_SIGNATURE_SHA1

        // Extra breadcrumbs: if constellation_verify VM is doing filesystem checks, correlate them.
        try {
            val paths = listOf(
                "/debug_ramdisk/.magisk",
                "/sbin/.magisk",
                "/data/adb/modules",
                "/data/adb/lspd",
            )
            for (p in paths) {
                val f = File(p)
                Log.i(TAG, "DG env check: path=$p exists=${f.exists()} isDir=${f.isDirectory} isFile=${f.isFile}")
            }
        } catch (_: Throwable) {
        }

        // Print a direct conclusion to avoid any ambiguity during log review.
        when {
            legacyDigest == null || signingInfoDigest == null -> {
                Log.w(TAG, "Conclusion: INCONCLUSIVE (could not read one or both signature digests). This is not good; constellation_verify may reject tokens if it depends on these signals.")
            }
            legacyDigest == googleSha1 && signingInfoDigest == googleSha1 -> {
                Log.i(TAG, "Conclusion: OK (both signature APIs report Google cert sha1=$googleSha1). If constellation_verify still fails, the cause is not 'partial signature spoofing'.")
            }
            legacyDigest == googleSha1 && signingInfoDigest != googleSha1 -> {
                Log.w(TAG, "Conclusion: NOT GOOD (legacy looks spoofed to Google, but SigningInfo does NOT). This strongly suggests PARTIAL spoofing; constellation_verify may validate SigningInfo/signing lineage and reject microG.")
            }
            legacyDigest != googleSha1 && signingInfoDigest == googleSha1 -> {
                Log.w(TAG, "Conclusion: WEIRD (SigningInfo is Google but legacy is not). This indicates inconsistent spoofing or API behavior; treat as suspicious for constellation_verify.")
            }
            legacyDigest == microgSha1 && signingInfoDigest == microgSha1 -> {
                Log.w(TAG, "Conclusion: BAD (both signature APIs report microG cert sha1=$microgSha1, not Google). constellation_verify is very likely to reject these DroidGuard tokens.")
            }
            else -> {
                Log.w(TAG, "Conclusion: SUSPICIOUS (signatures do not match Google sha1=$googleSha1; legacy=$legacyDigest signingInfo=$signingInfoDigest). constellation_verify may reject.")
            }
        }
    }
}
