/*
 * SPDX-FileCopyrightText: 2023 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.auth;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.NonNull;
import com.google.android.gms.tasks.Tasks;
import org.microg.gms.accountaction.ErrorResolverKt;
import org.microg.gms.accountaction.Resolution;
import org.microg.gms.checkin.LastCheckinInfo;
import org.microg.gms.common.Constants;
import org.microg.gms.common.NotOkayException;
import org.microg.gms.common.PackageUtils;
import org.microg.gms.droidguard.DroidGuardClientImpl;
import org.microg.gms.settings.SettingsContract;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static android.content.pm.ApplicationInfo.FLAG_SYSTEM;
import static android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP;
import static android.os.Build.VERSION.SDK_INT;
import static org.microg.gms.auth.AuthPrefs.isTrustGooglePermitted;

public class AuthManager {

    private static final String TAG = "GmsAuthManager";
    public static final String PERMISSION_TREE_BASE = "com.google.android.googleapps.permission.GOOGLE_AUTH.";
    public static final String PREF_AUTH_VISIBLE = SettingsContract.Auth.VISIBLE;
    public static final int ONE_HOUR_IN_SECONDS = 60 * 60;
    public Map<Object, Object> dynamicFields = new HashMap<>();
    private final Context context;
    private final String accountName;
    private final String packageName;
    private final String service;
    private AccountManager accountManager;
    private Account account;
    private String packageSignature;
    private String accountType;


    private int delegationType;
    private String delegateeUserId;
    private String oauth2Foreground;
    private String oauth2Prompt;
    private String itCaveatTypes;
    private String tokenRequestOptions;
    public String includeEmail;
    public String includeProfile;
    public boolean isGmsApp;
    public boolean ignoreStoredPermission = false;
    public boolean forceRefreshToken = false;

    public AuthManager(Context context, String accountName, String packageName, String service) {
        this.context = context;
        this.accountName = accountName;
        this.packageName = packageName;
        this.service = service;
    }

    public String getAccountType() {
        if (accountType == null)
            accountType = AuthConstants.DEFAULT_ACCOUNT_TYPE;
        return accountType;
    }

    public AccountManager getAccountManager() {
        if (accountManager == null)
            accountManager = AccountManager.get(context);
        return accountManager;
    }

    public Account getAccount() {
        if (account == null)
            account = new Account(accountName, getAccountType());
        return account;
    }

    public void setPackageSignature(String packageSignature) {
        this.packageSignature = packageSignature;
    }

    public String getPackageSignature() {
        if (packageSignature == null)
            packageSignature = PackageUtils.firstSignatureDigest(context, packageName);
        return packageSignature;
    }

    public String buildTokenKey(String service) {
        Uri.Builder builder = Uri.EMPTY.buildUpon();
        if (delegationType != 0 && delegateeUserId != null)
            builder.appendQueryParameter("delegation_type", Integer.toString(delegationType))
                    .appendQueryParameter("delegatee_user_id", delegateeUserId);
        if (tokenRequestOptions != null) builder.appendQueryParameter("token_request_options", tokenRequestOptions);
        if (includeEmail != null) builder.appendQueryParameter("include_email", includeEmail);
        if (includeProfile != null) builder.appendQueryParameter("include_profile", includeEmail);
        String query = builder.build().getEncodedQuery();
        return packageName + ":" + getPackageSignature() + ":" + service + (query != null ? ("?" + query) : "");
    }

    public String buildTokenKey() {
        return buildTokenKey(service);
    }

    public String buildPermKey() {
        return "perm." + buildTokenKey();
    }

    public void setPermitted(boolean value) {
        setUserData(buildPermKey(), value ? "1" : "0");
        if (SDK_INT >= 26 && value && packageName != null) {
            // Make account persistently visible as we already granted access
            accountManager.setAccountVisibility(getAccount(), packageName, AccountManager.VISIBILITY_VISIBLE);
        }
    }

    public boolean isPermitted() {
        if (!service.startsWith("oauth")) {
            if (context.getPackageManager().checkPermission(PERMISSION_TREE_BASE + service, packageName) == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "isPermitted: YES (system permission granted)");
                return true;
            }
        }
        String permKey = buildPermKey();
        String perm = getUserData(permKey);
        Log.d(TAG, "isPermitted: Checking key=" + permKey);
        Log.d(TAG, "isPermitted: Got value=" + perm + " (need '1' to pass)");
        if (!"1".equals(perm)) {
            Log.d(TAG, "isPermitted: NO - permission not granted or value mismatch");
            return false;
        }
        Log.d(TAG, "isPermitted: YES - permission flag found with value=1");
        return true;
    }

    public void setExpiry(long expiry) {
        setUserData(buildExpireKey(), Long.toString(expiry));
    }

    public String getUserData(String key) {
        return getAccountManager().getUserData(getAccount(), key);
    }

    public void setUserData(String key, String value) {
        getAccountManager().setUserData(getAccount(), key, value);
    }

    public void setDelegation(int delegationType, String delegateeUserId) {
        if (delegationType != 0 && delegateeUserId != null) {
            this.delegationType = delegationType;
            this.delegateeUserId = delegateeUserId;
        } else {
            this.delegationType = 0;
            this.delegateeUserId = null;
        }
    }

    public void setOauth2Foreground(String oauth2Foreground) {
        this.oauth2Foreground = oauth2Foreground;
    }

    public void setOauth2Prompt(String oauth2Prompt) {
        this.oauth2Prompt = oauth2Prompt;
    }

    public void setItCaveatTypes(String itCaveatTypes) {
        this.itCaveatTypes = itCaveatTypes;
    }

    public void setTokenRequestOptions(String tokenRequestOptions) {
        this.tokenRequestOptions = tokenRequestOptions;
    }

    public void putDynamicFiled(Object key, Object value) {
        this.dynamicFields.put(key, value);
    }

    public boolean accountExists() {
        for (Account refAccount : getAccountManager().getAccountsByType(accountType)) {
            if (refAccount.name.equalsIgnoreCase(accountName)) return true;
        }
        return false;
    }

    public String peekAuthToken() {
        Log.d(TAG, "peekAuthToken: " + buildTokenKey());
        return getAccountManager().peekAuthToken(getAccount(), buildTokenKey());
    }

    public String getAuthToken() {
        if (service.startsWith("weblogin:")) return null;

        String tokenKey = buildTokenKey();
        Log.d(TAG, "getAuthToken: Checking for cached token (expiry check DISABLED to match stock GMS)");
        Log.d(TAG, "  Token key: " + tokenKey);
        Log.d(TAG, "  Package: " + packageName);
        Log.d(TAG, "  Service: " + service);

        // Stock GMS does NOT enforce local expiry timestamps (EXP: fields in AccountManager extras).
        // Evidence (Session 89, Feb 14 2026):
        //   - Stock GMS OAuth token expired at 13:47
        //   - Stock GMS continued working at 14:27+ (40min past expiry)
        //   - No auth refresh attempts observed in logs
        //   - RCS code 2000 (working) throughout
        // Conclusion: Google's servers validate token expiry server-side, client-side
        // EXP: timestamps are advisory only. Stock GMS ignores them and uses tokens
        // until server rejection occurs.
        //
        // microG's original expiry check caused:
        //   - Immediate refresh attempts when swapping from stock GMS
        //   - BadAuthentication errors (can't refresh stock GMS tokens)
        //   - Cascading failures leading to RCS breaking after 12h
        //
        // By matching stock GMS behavior (ignoring local expiry), microG can use
        // stock GMS's OAuth tokens seamlessly without triggering refresh failures.
        // This enables proper account transfer during GMS swap.
        /*
        if (System.currentTimeMillis() / 1000L >= getExpiry() - 300L) {
            Log.d(TAG, "token present, but expired");
            return null;
        }
        */

        String token = peekAuthToken();
        if (token != null) {
            Log.d(TAG, "getAuthToken: Using cached token (length=" + token.length() + "), expiry check bypassed");
        } else {
            Log.d(TAG, "getAuthToken: No cached token found, will request fresh");
        }
        return token;
    }

    public String buildExpireKey() {
        return "EXP." + buildTokenKey();
    }

    public long getExpiry() {
        String exp = getUserData(buildExpireKey());
        if (exp == null) return -1;
        return Long.parseLong(exp);
    }

    public void setAuthToken(String auth) {
        setAuthToken(service, auth);
    }

    public void setAuthToken(String service, String auth) {
        getAccountManager().setAuthToken(getAccount(), buildTokenKey(service), auth);
        if (SDK_INT >= 26 && packageName != null && auth != null) {
            // Make account persistently visible as we already granted access
            accountManager.setAccountVisibility(getAccount(), packageName, AccountManager.VISIBILITY_VISIBLE);
        }
    }

    public void invalidateAuthToken() {
        String authToken = peekAuthToken();
        invalidateAuthToken(authToken);
    }

    @SuppressLint("MissingPermission")
    public void invalidateAuthToken(String auth) {
        getAccountManager().invalidateAuthToken(accountType, auth);
    }

    public void storeResponse(AuthResponse response) {
        if (service.startsWith("weblogin:")) return;
        if (response.accountId != null)
            setUserData("GoogleUserId", response.accountId);
        if (response.Sid != null)
            setAuthToken("SID", response.Sid);
        if (response.LSid != null)
            setAuthToken("LSID", response.LSid);
        if (response.auth != null && (response.expiry != 0 || response.storeConsentRemotely)) {
            setAuthToken(response.auth);
            if (response.expiry > 0) {
                setExpiry(response.expiry);
            } else {
                setExpiry(System.currentTimeMillis() / 1000 + ONE_HOUR_IN_SECONDS); // make valid for one hour by default
            }
        }
    }

    private boolean isSystemApp() {
        try {
            int flags = context.getPackageManager().getApplicationInfo(packageName, 0).flags;
            return (flags & FLAG_SYSTEM) > 0 || (flags & FLAG_UPDATED_SYSTEM_APP) > 0;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    @NonNull
    public AuthResponse requestAuthWithBackgroundResolution(boolean legacy) throws IOException {
        try {
            return requestAuth(legacy);
        } catch (NotOkayException e) {
            if (e.getMessage() != null) {
                Resolution errorResolution = ErrorResolverKt.resolveAuthErrorMessage(context, e.getMessage());
                if (errorResolution != null) {
                    AuthResponse response = ErrorResolverKt.initiateFromBackgroundBlocking(
                            errorResolution,
                            context,
                            getAccount(),
                            // infinite loop is prevented
                            () -> requestAuth(legacy)
                    );
                    if (response == null) throw new IOException(e);
                    return response;
                } else {
                    throw new IOException(e);
                }
            } else {
                throw new IOException(e);
            }
        }
    }

    @NonNull
    public AuthResponse requestAuthWithForegroundResolution(boolean legacy) throws IOException {
        try {
            return requestAuth(legacy);
        } catch (NotOkayException e) {
            if (e.getMessage() != null) {
                Resolution errorResolution = ErrorResolverKt.resolveAuthErrorMessage(context, e.getMessage());
                if (errorResolution != null) {
                    AuthResponse response = ErrorResolverKt.initiateFromForegroundBlocking(
                            errorResolution,
                            context,
                            getAccount(),
                            // infinite loop is prevented
                            () -> requestAuth(legacy)
                    );
                    if (response == null) throw new IOException(e);
                    return response;
                } else {
                    throw new IOException(e);
                }
            } else {
                throw new IOException(e);
            }
        }
    }

    @NonNull
    public AuthResponse requestAuth(boolean legacy) throws IOException {
        if (service.equals(AuthConstants.SCOPE_GET_ACCOUNT_ID)) {
            AuthResponse response = new AuthResponse();
            response.accountId = response.auth = getAccountManager().getUserData(getAccount(), "GoogleUserId");
            return response;
        }
        if (isPermitted() || isTrustGooglePermitted(context)) {
            String token = getAuthToken();
            if (token != null && !forceRefreshToken) {
                AuthResponse response = new AuthResponse();
                response.issueAdvice = "stored";
                response.auth = token;
                if (service.startsWith("oauth2:")) {
                    response.grantedScopes = service.substring(7);
                }
                response.expiry = getExpiry();
                return response;
            }
        }
        // Get DroidGuard token for auth (GMS does this - see aeoe.java:543-548)
        String droidGuardToken = getDroidGuardForAuth();

        AuthRequest request = new AuthRequest().fromContext(context)
                .source("android")
                .app(packageName, getPackageSignature())
                .email(accountName)
                .token(getAccountManager().getPassword(getAccount()))
                .service(service)
                .delegation(delegationType, delegateeUserId)
                .oauth2Foreground(oauth2Foreground)
                .oauth2Prompt(oauth2Prompt)
                .oauth2IncludeProfile(includeProfile)
                .oauth2IncludeEmail(includeEmail)
                .itCaveatTypes(itCaveatTypes)
                .tokenRequestOptions(tokenRequestOptions)
                .systemPartition(isSystemApp())
                .hasPermission(!ignoreStoredPermission && isPermitted())
                .droidguardResults(droidGuardToken)
                .putDynamicFiledMap(dynamicFields);
        if (isGmsApp) {
            request.appIsGms();
        }
        if (legacy) {
            request.callerIsGms().calledFromAccountManager();
        } else {
            request.callerIsApp();
        }
        AuthResponse response = request.getResponse();
        if (!isPermitted() && !isTrustGooglePermitted(context)) {
            response.auth = null;
        } else {
            storeResponse(response);
        }
        return response;
    }

    public String getService() {
        return service;
    }

    /**
     * Get DroidGuard token for auth requests.
     * GMS uses flow "addAccount" with bindings: dg_email, dg_androidId, dg_gmsCoreVersion, dg_package
     * See: aeho.java:21-55, aeoe.java:543-548 in GMS decompilation
     */
    private String getDroidGuardForAuth() {
        try {
            DroidGuardClientImpl droidGuard = new DroidGuardClientImpl(context);

            // Build bindings matching GMS aeho.java:45-54
            Map<String, String> bindings = new HashMap<>();
            if (accountName != null) {
                bindings.put("dg_email", accountName);
            }
            // Get Android ID like GMS does
            String androidId = Long.toHexString(LastCheckinInfo.read(context).getAndroidId());
            bindings.put("dg_androidId", androidId);
            bindings.put("dg_gmsCoreVersion", String.valueOf(Constants.GMS_VERSION_CODE));
            bindings.put("dg_package", context.getPackageName());

            // Flow name from GMS: "addAccount"
            String token = Tasks.await(droidGuard.getResults("addAccount", bindings, null), 30, TimeUnit.SECONDS);
            if (token != null) {
                Log.d(TAG, "Got DroidGuard token for auth (" + token.length() + " chars)");
            }
            return token;
        } catch (Exception e) {
            Log.w(TAG, "Failed to get DroidGuard for auth", e);
            return null;
        }
    }
}
