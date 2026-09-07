package com.stopbell.user.auth.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;

import com.stopbell.user.entity.AuthProvider;
import com.stopbell.user.auth.exception.InvalidSocialCredentialException;
import com.stopbell.user.auth.exception.SocialIdentityVerificationException;
import com.stopbell.user.auth.identity.ExternalIdentity;
import com.stopbell.user.auth.identity.GoogleIdentityVerifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Import(AuthControllerIntegrationTest.TestGoogleIdentityVerifierConfiguration.class)
class AuthControllerIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer mysql = new MySQLContainer("mysql:8.4.11");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private GoogleIdentityVerifier googleIdentityVerifier;

    @Test
    @DisplayName("Access Token 없이 Google Login Endpoint에 접근해 Access Token을 받는다")
    void allow_unauthenticated_google_login() throws Exception {
        when(googleIdentityVerifier.verify("valid-google-id-token"))
                .thenReturn(new ExternalIdentity(AuthProvider.GOOGLE, "controller-google-user"));

        mockMvc.perform(post("/auth/google")
                        .contentType("application/json")
                        .content("{\"idToken\":\"valid-google-id-token\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());
    }

    @Test
    @DisplayName("잘못된 Google Credential은 401 Unauthorized를 반환한다")
    void reject_invalid_google_credential() throws Exception {
        doThrow(new InvalidSocialCredentialException())
                .when(googleIdentityVerifier)
                .verify("invalid-google-id-token");

        mockMvc.perform(post("/auth/google")
                        .contentType("application/json")
                        .content("{\"idToken\":\"invalid-google-id-token\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Google Identity 검증 I/O 실패는 503 Service Unavailable을 반환한다")
    void return_service_unavailable_when_google_identity_verification_fails() throws Exception {
        doThrow(new SocialIdentityVerificationException(new IOException("Google public keys unavailable")))
                .when(googleIdentityVerifier)
                .verify("unavailable-google-id-token");

        mockMvc.perform(post("/auth/google")
                        .contentType("application/json")
                        .content("{\"idToken\":\"unavailable-google-id-token\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().string(not(containsString("Google public keys unavailable"))));
    }

    @Test
    @DisplayName("POST 이외의 Google Login 경로는 인증 없이 접근할 수 없다")
    void keep_non_post_google_login_path_protected() throws Exception {
        mockMvc.perform(get("/auth/google"))
                .andExpect(status().isUnauthorized());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestGoogleIdentityVerifierConfiguration {

        @Bean
        @Primary
        GoogleIdentityVerifier googleIdentityVerifier() {
            return mock(GoogleIdentityVerifier.class);
        }
    }
}
