/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License.
 */

package com.microsoft.intune.samples.taskr.authentication;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.Nullable;

import com.microsoft.aad.adal.ADALError;
import com.microsoft.aad.adal.AuthenticationCallback;
import com.microsoft.aad.adal.AuthenticationContext;
import com.microsoft.aad.adal.AuthenticationException;
import com.microsoft.aad.adal.AuthenticationResult;
import com.microsoft.aad.adal.PromptBehavior;
import com.microsoft.intune.mam.client.app.MAMComponents;
import com.microsoft.intune.mam.policy.MAMEnrollmentManager;
import com.microsoft.intune.mam.policy.MAMUserInfo;

/**
 * Manages authentication for the app. Methods are safe to call from any class.
 *
 * Deals with both ADAL and MAM, significantly.
 */
public final class AuthManager {
    /**
     * The authority that AuthenticationContexts should use. Sign in will use this URL.
     */
    public static final String AUTHORITY = "https://login.microsoftonline.com/common";
    /**
     * Indicates a handler should let ADAL decide to prompt the user for sign in or not.
     */
    public static final int MSG_PROMPT_AUTO = 1;
    /**
     * Indicates a handler should force ADAL to prompt the user for sign in.
     */
    public static final int MSG_PROMPT_ALWAYS = 2;

    /* The AAD client ID registered at https://apps.dev.microsoft.com.
     * This ID is unique to this application and should be replaced for yours. */
    private static final String CLIENT_ID = "5f2d2fe8-00a9-4456-9fc9-42773d4b4aef";
    private static final String REDIRECT_URI = "http://localhost";
    private static final String RESOURCE_ID = "https://graph.microsoft.com/";

    private static final String SHARED_PREFERENCES = "com.microsoft.intune.samples.taskr";
    private static final String SP_RESOURCE_ID = "resourceId";
    private static final String SP_AAD_ID = "aadId";
    private static final String SP_UPN = "upn";
    private static final String SP_SHOULD_UPDATE_TOKEN = "updateToken";

    private static final String SAVE_IS_AUTHED = "isAuthenticated";
    private static boolean sIsAuthenticated;


    /**
     * Required private, empty constructor.
     */
    private AuthManager() {
    }

    /**
     * Try to sign the user in using ADAL silently.
     *
     * @param authContext the AuthenticationContext stored by the calling process
     * @param listener    the AuthListener to call when the method completes
     * @param handler     the handler of the calling activity that will handle repeated sign-in attempts
     */
    public static void signInSilent(final AuthenticationContext authContext,
                                    final AuthListener listener,
                                    final Handler handler) {
        authContext.acquireTokenSilentAsync(RESOURCE_ID, CLIENT_ID, REDIRECT_URI,
                new AuthenticationCallback<AuthenticationResult>() {
                    @Override
                    public void onSuccess(final AuthenticationResult result) {
                        handleSignInSuccess(authContext, listener, result);
                    }

                    @Override
                    public void onError(final Exception exc) {
                        handler.sendEmptyMessage(MSG_PROMPT_AUTO);
                    }
                });
    }

    /**
     * Try to sign the user in using ADAL allowing for a prompt.
     *
     * @param authContext the AuthenticationContext stored by the calling process
     * @param activity    the calling activity. Lets ADAL control the UI to show a prompt
     * @param listener    the AuthListener to call when the method completes
     * @param behavior    the ADAL prompt behavior - auto or always
     * @param handler     the handler of the calling activity that will handle repeated sign-in attempts
     */
    public static void signInWithPrompt(final AuthenticationContext authContext, final Activity activity,
                                        final AuthListener listener,
                                        final PromptBehavior behavior, final Handler handler) {
        /* Setting instance_aware to true means that the callback wil receive an AuthenticationResult with
         * the authority set, allowing the app to register it with MAM and be sovereign cloud aware:
         * https://docs.microsoft.com/en-us/intune/app-sdk-android#sovereign-cloud-registration */
        authContext.acquireToken(activity, RESOURCE_ID, CLIENT_ID, REDIRECT_URI, behavior,
                "instance_aware=true", new AuthenticationCallback<AuthenticationResult>() {
                    @Override
                    public void onSuccess(final AuthenticationResult result) {
                        handleSignInSuccess(authContext, listener, result);
                    }

                    @Override
                    public void onError(final Exception exc) {
                        listener.onError(exc);
                    }
                });
    }

    /**
     * Unregisters the user's account from MAM.
     *
     * @param listener the AuthListener to call when the method completes
     */
    public static void signOut(final AuthListener listener) {
        String user = getUser();
        if (user != null) {
            MAMEnrollmentManager mgr = MAMComponents.get(MAMEnrollmentManager.class);
            mgr.unregisterAccountForMAM(user);
        }

        sIsAuthenticated = false;

        // Finally, call the AuthListener callback
        listener.onSignedOut();
    }

    private static void handleSignInSuccess(final AuthenticationContext authContext,
                                            final AuthListener listener,
                                            final AuthenticationResult result) {
        sIsAuthenticated = true;
        String user = result.getUserInfo().getDisplayableId();
        String aadId = result.getUserInfo().getUserId();

        MAMEnrollmentManager mgr = MAMComponents.get(MAMEnrollmentManager.class);
        mgr.registerAccountForMAM(user, aadId, result.getTenantId(), result.getAuthority());

        // Call the AuthListener callback
        listener.onSignedIn();

        // And now that we're signed in, get a token for MAM if we previously returned null to the MAM callback
        // Done last because it is not urgent, and the user can be signed in even if something goes wrong with this
        SharedPreferences prefs =
                listener.getContext().getSharedPreferences(SHARED_PREFERENCES, Context.MODE_PRIVATE);
        if (prefs.getBoolean(SP_SHOULD_UPDATE_TOKEN, false)
                && aadId.equals(prefs.getString(SP_AAD_ID, null))) {
            prefs.edit().putBoolean(SP_SHOULD_UPDATE_TOKEN, false).apply();

            String resourceId = prefs.getString(SP_RESOURCE_ID, null);
            mgr.updateToken(user, aadId, resourceId,
                    getAccessTokenForMAM(authContext, listener.getContext(), user, aadId, resourceId));
        }
    }

    /**
     * Gets an AAD (Azure Active Directory) access token for the user. Should only ever be called
     * by code dealing with MAM.
     *
     * @param authContext the AuthenticationContext stored by the calling process
     * @param context     the context that this method is called in. Required to access shared preferences
     * @param upn         the upn specified by MAM. Not used here, may be in more complex apps
     * @param aadId       the AAD ID specified by MAM
     * @param resourceId  the resource specified by MAM
     * @return the user's AAD access token, null if it hasn't been retrieved yet
     */
    @Nullable
    public static String getAccessTokenForMAM(final AuthenticationContext authContext,
                                              final Context context, final String upn,
                                              final String aadId, final String resourceId) {
        try {
            String token =
                    authContext.acquireTokenSilentSync(resourceId, CLIENT_ID, aadId).getAccessToken();
            SharedPreferences.Editor prefs =
                    context.getSharedPreferences(SHARED_PREFERENCES, Context.MODE_PRIVATE).edit();
            prefs.putBoolean(SP_SHOULD_UPDATE_TOKEN, token == null);
            if (token == null) {
                prefs.putString(SP_AAD_ID, aadId)
                        .putString(SP_RESOURCE_ID, resourceId)
                        .putString(SP_UPN, upn);
            }

            prefs.apply();
            return token;
        } catch (InterruptedException | AuthenticationException e) {
            context.getSharedPreferences(SHARED_PREFERENCES, Context.MODE_PRIVATE).edit()
                    .putBoolean(SP_SHOULD_UPDATE_TOKEN, true)
                    .putString(SP_AAD_ID, aadId)
                    .putString(SP_RESOURCE_ID, resourceId)
                    .putString(SP_UPN, upn)
                    .apply();
            return null;
        }
    }

    /**
     * Returns the current username, for MAM methods.
     *
     * @return the current user's username, null if it hasn't been found yet
     */
    @Nullable
    public static String getUser() {
        MAMUserInfo info = MAMComponents.get(MAMUserInfo.class);
        return info == null ? null : info.getPrimaryUser();
    }

    /**
     * Saves the current state of authentication to outState. Used by MainActivity to ensure the
     * user stays signed in when MainActivity is reinitialized.
     *
     * @param outState the bundle to save state to
     */
    public static void onSaveInstanceState(final Bundle outState) {
        outState.putBoolean(SAVE_IS_AUTHED, sIsAuthenticated);
    }

    /**
     * Reads inState and returns true if the user was signed in when inState was written to.
     *
     * @param inState the bundle to read state from
     * @return true if the user was signed in when inState was written to, false otherwise
     */
    public static boolean shouldRestoreSignIn(final Bundle inState) {
        return inState.getBoolean(SAVE_IS_AUTHED);
    }
}
