package com.stopbell.user.auth.apple;

import java.time.Clock;

import com.stopbell.user.auth.exception.InvalidSocialCredentialException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(prefix = "apple.notifications", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AppleNotificationController {
    private final AppleNotificationVerifier verifier;
    private final AppleNotificationStore store;
    AppleNotificationController(AppleNotificationVerifier verifier, AppleNotificationStore store) {
        this.verifier = verifier;
        this.store = store;
    }
    @PostMapping("/auth/apple/notifications")
    public ResponseEntity<Void> receive(@RequestBody Request request) {
        if (request == null) throw new InvalidSocialCredentialException();
        var event = verifier.verify(request.payload());
        try { store.store(event); }
        catch (DataAccessException failure) { throw new IllegalStateException("Apple notification storage failed"); }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).build();
    }
    public record Request(String payload) {
        @Override public String toString() { return "Apple notification request [redacted]"; }
    }
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "apple.notifications", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class Config {
        @Bean AppleNotificationVerifier appleNotificationVerifier(@Value("${apple.notifications.client-id:}") String clientId, Clock jwtClock) {
            return new AppleNotificationVerifier(new AppleJwksDecoder(), clientId, jwtClock);
        }
    }
}
