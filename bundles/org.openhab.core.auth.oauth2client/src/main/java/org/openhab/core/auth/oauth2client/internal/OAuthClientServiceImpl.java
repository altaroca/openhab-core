/**
 * Copyright (c) 2010-2024 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.core.auth.oauth2client.internal;

import static org.openhab.core.auth.oauth2client.internal.Keyword.*;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.UrlEncoded;
import org.openhab.core.auth.client.oauth2.AccessTokenRefreshListener;
import org.openhab.core.auth.client.oauth2.AccessTokenResponse;
import org.openhab.core.auth.client.oauth2.DeviceCodeResponseDTO;
import org.openhab.core.auth.client.oauth2.OAuthClientService;
import org.openhab.core.auth.client.oauth2.OAuthException;
import org.openhab.core.auth.client.oauth2.OAuthResponseException;
import org.openhab.core.io.net.http.HttpClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.GsonBuilder;

/**
 * Implementation of OAuthClientService.
 *
 * It requires the following services:
 *
 * org.openhab.core.storage.Storage (mandatory; for storing grant tokens, access tokens and refresh tokens)
 *
 * HttpClientFactory for http connections with Jetty
 *
 * The OAuthTokens, request parameters are stored and persisted using the "Storage" service.
 * This allows the token to be automatically refreshed when needed.
 *
 * @author Michael Bock - Initial contribution
 * @author Gary Tse - Initial contribution
 * @author Gaël L'hopital - Added capability for custom deserializer
 */
@NonNullByDefault
public class OAuthClientServiceImpl implements OAuthClientService {

    public static final int DEFAULT_TOKEN_EXPIRES_IN_BUFFER_SECOND = 10;

    private static final String EXCEPTION_MESSAGE_CLOSED = "Client service is closed";

    private final transient Logger logger = LoggerFactory.getLogger(OAuthClientServiceImpl.class);

    private final Object refreshTokenProcessLock = new Object();

    private @NonNullByDefault({}) OAuthStoreHandler storeHandler;

    // Constructor params - static
    private final String handle;
    private final int tokenExpiresInSeconds;
    private final HttpClientFactory httpClientFactory;
    private final @Nullable GsonBuilder gsonBuilder;
    private final List<AccessTokenRefreshListener> accessTokenRefreshListeners = new ArrayList<>();

    private PersistedParams persistedParams = new PersistedParams();

    private @Nullable Fields extraAuthFields = null;
    private @Nullable OAuthConnectorRFC8628 oAuthConnectorRFC8628 = null;

    private volatile boolean closed = false;

    private OAuthClientServiceImpl(String handle, int tokenExpiresInSeconds, HttpClientFactory httpClientFactory,
            @Nullable GsonBuilder gsonBuilder) {
        this.handle = handle;
        this.tokenExpiresInSeconds = tokenExpiresInSeconds;
        this.httpClientFactory = httpClientFactory;
        this.gsonBuilder = gsonBuilder;
    }

    /**
     * It should only be used internally, thus the access is package level
     *
     * @param handle The handle produced previously from
     *            {@link org.openhab.core.auth.client.oauth2.OAuthFactory#createOAuthClientService}
     * @param storeHandler Storage handler
     * @param tokenExpiresInSeconds Positive integer; a small time buffer in seconds. It is used to calculate the expiry
     *            of the access tokens. This allows the access token to expire earlier than the
     *            official stated expiry time; thus prevents the caller obtaining a valid token at the time of invoke,
     *            only to find the token immediately expired.
     * @param httpClientFactory Http client factory
     * @return new instance of OAuthClientServiceImpl or null if it doesn't exist
     * @throws IllegalStateException if store is not available.
     */
    static @Nullable OAuthClientServiceImpl getInstance(String handle, OAuthStoreHandler storeHandler,
            int tokenExpiresInSeconds, HttpClientFactory httpClientFactory) {
        // Load parameters from Store
        PersistedParams persistedParamsFromStore = storeHandler.loadPersistedParams(handle);
        if (persistedParamsFromStore == null) {
            return null;
        }
        OAuthClientServiceImpl clientService = new OAuthClientServiceImpl(handle, tokenExpiresInSeconds,
                httpClientFactory, null);
        clientService.storeHandler = storeHandler;
        clientService.persistedParams = persistedParamsFromStore;

        return clientService;
    }

    /**
     * It should only be used internally, thus the access is package level
     *
     * @param handle The handle produced previously from
     *            {@link org.openhab.core.auth.client.oauth2.OAuthFactory#createOAuthClientService}*
     * @param storeHandler Storage handler
     * @param httpClientFactory Http client factory
     * @param params These parameters are static with respect to the OAuth provider and thus can be persisted.
     * @return OAuthClientServiceImpl an instance
     */
    static OAuthClientServiceImpl createInstance(String handle, OAuthStoreHandler storeHandler,
            HttpClientFactory httpClientFactory, PersistedParams params) {
        OAuthClientServiceImpl clientService = new OAuthClientServiceImpl(handle, params.tokenExpiresInSeconds,
                httpClientFactory, null);

        clientService.storeHandler = storeHandler;
        clientService.persistedParams = params;
        storeHandler.savePersistedParams(handle, clientService.persistedParams);

        return clientService;
    }

    @Override
    public String getAuthorizationUrl(@Nullable String redirectURI, @Nullable String scope, @Nullable String state)
            throws OAuthException {
        if (state == null) {
            persistedParams.state = createNewState();
        } else {
            persistedParams.state = state;
        }
        String scopeToUse = scope == null ? persistedParams.scope : scope;
        // keep it to check against redirectUri in #getAccessTokenResponseByAuthorizationCode
        persistedParams.redirectUri = redirectURI;
        String authorizationUrl = persistedParams.authorizationUrl;
        if (authorizationUrl == null) {
            throw new OAuthException("Missing authorization url");
        }
        String clientId = persistedParams.clientId;
        if (clientId == null) {
            throw new OAuthException("Missing client ID");
        }

        GsonBuilder gsonBuilder = this.gsonBuilder;
        OAuthConnector connector = gsonBuilder == null ? new OAuthConnector(httpClientFactory, extraAuthFields)
                : new OAuthConnector(httpClientFactory, extraAuthFields, gsonBuilder);
        return connector.getAuthorizationUrl(authorizationUrl, clientId, redirectURI, persistedParams.state,
                scopeToUse);
    }

    @Override
    public String extractAuthCodeFromAuthResponse(String redirectURLwithParams) throws OAuthException {
        // parse the redirectURL
        try {
            URL redirectURLObject = new URL(redirectURLwithParams);
            UrlEncoded urlEncoded = new UrlEncoded(redirectURLObject.getQuery());

            String stateFromRedirectURL = urlEncoded.getValue(STATE, 0); // may contain multiple...
            if (stateFromRedirectURL == null) {
                if (persistedParams.state == null) {
                    // This should not happen as the state is usually set
                    return urlEncoded.getValue(CODE, 0);
                } // else
                throw new OAuthException(String.format("state from redirectURL is incorrect.  Expected: %s Found: %s",
                        persistedParams.state, stateFromRedirectURL));
            } else {
                if (stateFromRedirectURL.equals(persistedParams.state)) {
                    return urlEncoded.getValue(CODE, 0);
                } // else
                throw new OAuthException(String.format("state from redirectURL is incorrect.  Expected: %s Found: %s",
                        persistedParams.state, stateFromRedirectURL));
            }
        } catch (MalformedURLException e) {
            throw new OAuthException("Redirect URL is malformed", e);
        }
    }

    @Override
    public AccessTokenResponse getAccessTokenResponseByAuthorizationCode(String authorizationCode,
            @Nullable String redirectURI) throws OAuthException, IOException, OAuthResponseException {
        if (isClosed()) {
            throw new OAuthException(EXCEPTION_MESSAGE_CLOSED);
        }

        if (persistedParams.redirectUri != null && !persistedParams.redirectUri.equals(redirectURI)) {
            // check parameter redirectURI in #getAuthorizationUrl are the same as given
            throw new OAuthException(String.format(
                    "redirectURI should be the same from previous call #getAuthorizationUrl.  Expected: %s Found: %s",
                    persistedParams.redirectUri, redirectURI));
        }
        String tokenUrl = persistedParams.tokenUrl;
        if (tokenUrl == null) {
            throw new OAuthException("Missing token url");
        }
        String clientId = persistedParams.clientId;
        if (clientId == null) {
            throw new OAuthException("Missing client ID");
        }

        GsonBuilder gsonBuilder = this.gsonBuilder;
        OAuthConnector connector = gsonBuilder == null ? new OAuthConnector(httpClientFactory, extraAuthFields)
                : new OAuthConnector(httpClientFactory, extraAuthFields, gsonBuilder);
        AccessTokenResponse accessTokenResponse = connector.grantTypeAuthorizationCode(tokenUrl, authorizationCode,
                clientId, persistedParams.clientSecret, redirectURI,
                Boolean.TRUE.equals(persistedParams.supportsBasicAuth));

        // store it
        storeHandler.saveAccessTokenResponse(handle, accessTokenResponse);
        return accessTokenResponse;
    }

    /**
     * Implicit Grant (RFC 6749 section 4.2) is not implemented. It is directly interacting with user-agent
     * The implicit grant is not implemented. It usually involves browser/javascript redirection flows
     * and is out of openHAB scope.
     */
    @Override
    public AccessTokenResponse getAccessTokenByImplicit(@Nullable String redirectURI, @Nullable String scope,
            @Nullable String state) throws OAuthException, IOException, OAuthResponseException {
        throw new UnsupportedOperationException("Implicit Grant is not implemented");
    }

    @Override
    public AccessTokenResponse getAccessTokenByResourceOwnerPasswordCredentials(String username, String password,
            @Nullable String scope) throws OAuthException, IOException, OAuthResponseException {
        if (isClosed()) {
            throw new OAuthException(EXCEPTION_MESSAGE_CLOSED);
        }
        String tokenUrl = persistedParams.tokenUrl;
        if (tokenUrl == null) {
            throw new OAuthException("Missing token url");
        }

        GsonBuilder gsonBuilder = this.gsonBuilder;
        OAuthConnector connector = gsonBuilder == null ? new OAuthConnector(httpClientFactory, extraAuthFields)
                : new OAuthConnector(httpClientFactory, extraAuthFields, gsonBuilder);
        AccessTokenResponse accessTokenResponse = connector.grantTypePassword(tokenUrl, username, password,
                persistedParams.clientId, persistedParams.clientSecret, scope,
                Boolean.TRUE.equals(persistedParams.supportsBasicAuth));

        // store it
        storeHandler.saveAccessTokenResponse(handle, accessTokenResponse);
        return accessTokenResponse;
    }

    @Override
    public AccessTokenResponse getAccessTokenByClientCredentials(@Nullable String scope)
            throws OAuthException, IOException, OAuthResponseException {
        if (isClosed()) {
            throw new OAuthException(EXCEPTION_MESSAGE_CLOSED);
        }
        String tokenUrl = persistedParams.tokenUrl;
        if (tokenUrl == null) {
            throw new OAuthException("Missing token url");
        }
        String clientId = persistedParams.clientId;
        if (clientId == null) {
            throw new OAuthException("Missing client ID");
        }

        GsonBuilder gsonBuilder = this.gsonBuilder;
        OAuthConnector connector = gsonBuilder == null ? new OAuthConnector(httpClientFactory, extraAuthFields)
                : new OAuthConnector(httpClientFactory, extraAuthFields, gsonBuilder);
        // depending on usage, cannot guarantee every parameter is not null at the beginning
        AccessTokenResponse accessTokenResponse = connector.grantTypeClientCredentials(tokenUrl, clientId,
                persistedParams.clientSecret, scope, Boolean.TRUE.equals(persistedParams.supportsBasicAuth));

        // store it
        storeHandler.saveAccessTokenResponse(handle, accessTokenResponse);
        return accessTokenResponse;
    }

    @Override
    public AccessTokenResponse refreshToken() throws OAuthException, IOException, OAuthResponseException {
        if (isClosed()) {
            throw new OAuthException(EXCEPTION_MESSAGE_CLOSED);
        }
        return refreshTokenInner(true);
    }

    /**
     * Inner private method for refreshToken. If 'forceRefresh' is false then only fetch a new token if
     * the prior token is not expired, otherwise return the prior token. If 'forceRefresh' is true
     * then always fetch a new token.
     *
     * @param forceRefresh determines whether to force a refresh or check for token expiry
     * @return either the prior AccessTokenResponse or a new one
     */
    private AccessTokenResponse refreshTokenInner(boolean forceRefresh)
            throws OAuthException, IOException, OAuthResponseException {
        AccessTokenResponse accessTokenResponse = null;

        synchronized (refreshTokenProcessLock) {
            AccessTokenResponse lastAccessToken;
            try {
                lastAccessToken = storeHandler.loadAccessTokenResponse(handle);
            } catch (GeneralSecurityException e) {
                throw new OAuthException("Cannot decrypt access token from store", e);
            }
            if (lastAccessToken == null) {
                throw new OAuthException(
                        "Cannot refresh token because last access token is not available from handle: " + handle);
            }
            if (lastAccessToken.getRefreshToken() == null) {
                throw new OAuthException("Cannot refresh token because last access token did not have a refresh token");
            }
            String tokenUrl = persistedParams.tokenUrl;
            if (tokenUrl == null) {
                throw new OAuthException("tokenUrl is required but null");
            }

            if (forceRefresh || lastAccessToken.isExpired(Instant.now(), tokenExpiresInSeconds)) {
                GsonBuilder gsonBuilder = this.gsonBuilder;
                OAuthConnector connector = gsonBuilder == null ? new OAuthConnector(httpClientFactory, extraAuthFields)
                        : new OAuthConnector(httpClientFactory, extraAuthFields, gsonBuilder);
                accessTokenResponse = connector.grantTypeRefreshToken(tokenUrl, lastAccessToken.getRefreshToken(),
                        persistedParams.clientId, persistedParams.clientSecret, persistedParams.scope,
                        Boolean.TRUE.equals(persistedParams.supportsBasicAuth));

                // The service may not return the refresh token so use the last refresh token otherwise it's not stored.
                String refreshToken = accessTokenResponse.getRefreshToken();
                if (refreshToken == null || refreshToken.isBlank()) {
                    accessTokenResponse.setRefreshToken(lastAccessToken.getRefreshToken());
                }

                // store it
                storeHandler.saveAccessTokenResponse(handle, accessTokenResponse);
            } else {
                // No need to refresh the token, just return the last one
                return lastAccessToken;
            }
        }

        notifyAccessTokenResponse(accessTokenResponse);
        return accessTokenResponse;
    }

    @Override
    public @Nullable AccessTokenResponse getAccessTokenResponse()
            throws OAuthException, IOException, OAuthResponseException {
        if (isClosed()) {
            throw new OAuthException(EXCEPTION_MESSAGE_CLOSED);
        }

        AccessTokenResponse lastAccessToken;
        try {
            lastAccessToken = storeHandler.loadAccessTokenResponse(handle);
        } catch (GeneralSecurityException e) {
            throw new OAuthException("Cannot decrypt access token from store", e);
        }
        if (lastAccessToken == null) {
            return null;
        }

        if (lastAccessToken.isExpired(Instant.now(), tokenExpiresInSeconds)
                && lastAccessToken.getRefreshToken() != null) {
            return refreshTokenInner(false);
        }
        return lastAccessToken;
    }

    @Override
    public void importAccessTokenResponse(AccessTokenResponse accessTokenResponse) throws OAuthException {
        if (isClosed()) {
            throw new OAuthException(EXCEPTION_MESSAGE_CLOSED);
        }
        storeHandler.saveAccessTokenResponse(handle, accessTokenResponse);
    }

    /**
     * Access tokens have expiry times. They are given by authorization server.
     * This parameter introduces a buffer in seconds that deduct from the expires-in.
     * For example, if the expires-in = 3600 seconds ( 1hour ), then setExpiresInBuffer(60)
     * will remove 60 seconds from the expiry time. In other words, the expires-in
     * becomes 3540 ( 59 mins ) effectively.
     *
     * Calls to protected resources can reasonably assume that the token is not expired.
     *
     * @param tokenExpiresInBuffer The number of seconds to remove the expires-in. Default 0 seconds.
     */
    public void setTokenExpiresInBuffer(int tokenExpiresInBuffer) {
        this.persistedParams.tokenExpiresInSeconds = tokenExpiresInBuffer;
    }

    @Override
    public void remove() throws OAuthException {
        if (isClosed()) {
            throw new OAuthException(EXCEPTION_MESSAGE_CLOSED);
        }

        logger.debug("removing handle: {}", handle);
        storeHandler.remove(handle);
        close();
    }

    @Override
    public void close() {
        closed = true;
        storeHandler = null;
        logger.debug("closing oauth client, handle: {}", handle);
        closeOAuthConnectorRFC8628();
    }

    private synchronized void closeOAuthConnectorRFC8628() {
        OAuthConnectorRFC8628 connector = this.oAuthConnectorRFC8628;
        if (connector != null) {
            connector.close();
        }
        this.oAuthConnectorRFC8628 = null;
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void addAccessTokenRefreshListener(AccessTokenRefreshListener listener) {
        accessTokenRefreshListeners.add(listener);
    }

    @Override
    public boolean removeAccessTokenRefreshListener(AccessTokenRefreshListener listener) {
        return accessTokenRefreshListeners.remove(listener);
    }

    private String createNewState() {
        return UUID.randomUUID().toString();
    }

    /**
     * Adds extra fields to include in the form data when doing the token request
     *
     * @param key The name of the key to add to the auth form
     * @param value The value of the new auth form param
     */
    @Override
    public void addExtraAuthField(String key, String value) {
        if (extraAuthFields == null) {
            extraAuthFields = new Fields();
        }
        extraAuthFields.add(key, value);
    }

    @Override
    public OAuthClientService withGsonBuilder(GsonBuilder gsonBuilder) {
        OAuthClientServiceImpl clientService = new OAuthClientServiceImpl(handle, persistedParams.tokenExpiresInSeconds,
                httpClientFactory, gsonBuilder);
        clientService.persistedParams = persistedParams;
        clientService.storeHandler = storeHandler;

        storeHandler.savePersistedParams(handle, clientService.persistedParams);

        return clientService;
    }

    @Override
    public @Nullable DeviceCodeResponseDTO getDeviceCodeResponse() throws OAuthException {
        closeOAuthConnectorRFC8628();

        if (persistedParams.tokenUrl == null) {
            throw new OAuthException("Missing access token request url");
        }
        if (persistedParams.authorizationUrl == null) {
            throw new OAuthException("Missing device code request url");
        }
        if (persistedParams.clientId == null) {
            throw new OAuthException("Missing client id");
        }
        if (persistedParams.scope == null) {
            throw new OAuthException("Missing scope");
        }

        OAuthConnectorRFC8628 connector = new OAuthConnectorRFC8628(this, handle, storeHandler, httpClientFactory,
                gsonBuilder, persistedParams.tokenUrl, persistedParams.authorizationUrl, persistedParams.clientId,
                persistedParams.scope);

        oAuthConnectorRFC8628 = connector;
        return connector.getDeviceCodeResponse();
    }

    @Override
    public void notifyAccessTokenResponse(AccessTokenResponse atr) {
        accessTokenRefreshListeners.forEach(l -> l.onAccessTokenResponse(atr));
    }
}
