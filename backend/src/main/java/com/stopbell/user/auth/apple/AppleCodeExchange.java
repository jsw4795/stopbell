package com.stopbell.user.auth.apple;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.Map;

import com.nimbusds.jose.util.JSONObjectUtils;
import com.stopbell.user.auth.exception.InvalidSocialCredentialException;
import com.stopbell.user.auth.exception.SocialIdentityVerificationException;

/** Exchanges a code once; never retries an ambiguous request. */
public final class AppleCodeExchange {
    private static final int MAX_RESPONSE = 65536;
    private final URI endpoint;
    private final String clientId;
    private final AppleClientSecret clientSecret;
    private final int connectTimeout;
    private final int readTimeout;

    public AppleCodeExchange(String clientId, AppleClientSecret clientSecret) {
        this(URI.create("https://appleid.apple.com/auth/token"), clientId, clientSecret, 2000, 5000);
    }

    AppleCodeExchange(URI endpoint, String clientId, AppleClientSecret clientSecret, int connectTimeout, int readTimeout) {
        if (clientId == null || clientId.isBlank() || clientId.length() > 255 || clientSecret == null) {
            throw new IllegalArgumentException("Invalid Apple client configuration");
        }
        this.endpoint = endpoint;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
    }

    public Tokens exchange(String code) {
        if (code == null || code.isBlank() || code.length() > 8192) throw new InvalidSocialCredentialException();
        String form = "grant_type=authorization_code&client_id=" + encode(clientId)
                + "&client_secret=" + encode(clientSecret.create()) + "&code=" + encode(code);
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
            if (connection.getContentLengthLong() > MAX_RESPONSE) throw new IOException("Response too large");
            try (var input = status == 200 ? connection.getInputStream() : connection.getErrorStream()) {
                if (input == null) throw new IOException("Missing response");
                byte[] body = AppleHttpBody.read(connection, input, MAX_RESPONSE, readTimeout);
                Map<String, Object> json = JSONObjectUtils.parse(new String(body, StandardCharsets.UTF_8));
                if (status == 400 && "invalid_grant".equals(json.get("error"))) {
                    throw new InvalidSocialCredentialException();
                }
                if (status == 400 && ("invalid_client".equals(json.get("error"))
                        || "unauthorized_client".equals(json.get("error"))
                        || "unsupported_grant_type".equals(json.get("error"))
                        || "invalid_request".equals(json.get("error")))) {
                    throw new IllegalStateException("Apple client configuration was rejected");
                }
                if (status != 200) throw new IOException("Apple token exchange unavailable");
                Object idToken = json.get("id_token");
                Object refreshToken = json.get("refresh_token");
                if (!(idToken instanceof String id) || id.isBlank() || id.length() > 16384
                        || !(refreshToken instanceof String refresh) || refresh.isBlank() || refresh.length() > 8192) {
                    throw new IOException("Invalid Apple token response");
                }
                return new Tokens(id, refresh);
            }
        } catch (IOException | ParseException failure) {
            throw new SocialIdentityVerificationException(new IllegalStateException("Apple token exchange unavailable"));
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    public static final class Tokens {
        private final String idToken;
        private final String refreshToken;
        Tokens(String idToken, String refreshToken) { this.idToken = idToken; this.refreshToken = refreshToken; }
        public String idToken() { return idToken; }
        public String refreshToken() { return refreshToken; }
        @Override public String toString() { return "Apple tokens [redacted]"; }
    }
}
