package com.stopbell.user.auth.config;

import java.util.List;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GoogleProperties.class)
public class GoogleIdentityVerificationConfiguration {

    @Bean
    GoogleIdTokenVerifier googleIdTokenVerifier(GoogleProperties googleProperties) {
        return new GoogleIdTokenVerifier.Builder(
                new NetHttpTransport(),
                GsonFactory.getDefaultInstance()
        ).setAudience(List.of(googleProperties.serverClientId())).build();
    }
}
