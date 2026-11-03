package com.stopbell.user.auth.apple;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;

import com.nimbusds.jose.util.JSONObjectUtils;

/** Never logs credentials or upstream bodies. Repeating a revocation is safe after an ambiguous result. */
public final class AppleTokenRevocation {
    private static final int MAX_RESPONSE = 65536;
    private final URI endpoint;
    private final String clientId;
    private final AppleClientSecret secret;
    private final int connectTimeout;
    private final int readTimeout;

    public AppleTokenRevocation(String clientId, AppleClientSecret secret) {
        this(URI.create("https://appleid.apple.com/auth/revoke"), clientId, secret, 2000, 5000);
    }

    AppleTokenRevocation(URI endpoint, String clientId, AppleClientSecret secret, int connectTimeout, int readTimeout) {
        if (clientId == null || clientId.isBlank() || secret == null || connectTimeout <= 0 || readTimeout <= 0) {
            throw new IllegalArgumentException("Invalid Apple revocation configuration");
        }
        this.endpoint = endpoint;
        this.clientId = clientId;
        this.secret = secret;
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
    }

    public Outcome revoke(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank() || refreshToken.length() > 8192) {
            return Outcome.REJECTED;
        }
        final String clientSecret;
        try { clientSecret = secret.create(); }
        catch (RuntimeException failure) { return Outcome.CONFIGURATION_ERROR; }
        String form = "client_id=" + encode(clientId) + "&client_secret=" + encode(clientSecret)
                + "&token=" + encode(refreshToken) + "&token_type_hint=refresh_token";
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) endpoint.toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(connectTimeout);
            connection.setReadTimeout(readTimeout);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            connection.setDoOutput(true);
            byte[] data = form.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(data.length);
            try (var output = connection.getOutputStream()) { output.write(data); }
            int status = connection.getResponseCode();
            if (connection.getContentLengthLong() > MAX_RESPONSE) return Outcome.UNCERTAIN;
            try (var input = status < 400 ? connection.getInputStream() : connection.getErrorStream()) {
                byte[] body = input == null ? new byte[0]
                        : AppleHttpBody.read(connection, input, MAX_RESPONSE, readTimeout);
                // HTTP 200 confirms revocation, including tokens which were already invalidated.
                if (status == 200) return Outcome.REVOKED;
                if (status == 400) {
                    var error = JSONObjectUtils.parse(new String(body, StandardCharsets.UTF_8)).get("error");
                    return "invalid_client".equals(error) || "unauthorized_client".equals(error)
                            || "invalid_request".equals(error) ? Outcome.CONFIGURATION_ERROR : Outcome.REJECTED;
                }
                return Outcome.UNCERTAIN;
            }
        } catch (IOException | ParseException failure) {
            return Outcome.UNCERTAIN;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    public enum Outcome { REVOKED, CONFIGURATION_ERROR, REJECTED, UNCERTAIN, CREDENTIAL_UNAVAILABLE }
}
