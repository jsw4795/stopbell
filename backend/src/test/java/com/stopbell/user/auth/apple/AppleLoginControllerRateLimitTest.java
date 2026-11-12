package com.stopbell.user.auth.apple;

import java.time.Clock;

import com.stopbell.common.error.GlobalExceptionHandler;
import com.stopbell.user.auth.service.LoginService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AppleLoginControllerRateLimitTest {
    @Test
    void returns429ErrorEnvelopeAfterLimit() throws Exception {
        var authentication = mock(AppleAuthentication.class);
        when(authentication.issueNonce()).thenReturn("n");
        var controller = new AppleLoginController(authentication, mock(AppleCredentialStore.class),
                mock(LoginService.class), new AppleChallengeRateLimiter(Clock.systemUTC(), 2));
        var http = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
        for (int i = 0; i < 2; i++) http.perform(post("/auth/apple/challenge")).andExpect(status().isOk());
        http.perform(post("/auth/apple/challenge")).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }
}
