package com.stopbell.notification.client;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FcmClientProperties.class)
public class FcmClientConfiguration {
    @Bean
    FcmProviderClient fcmProviderClient(FcmClientProperties properties) {
        return new FcmProviderClient(properties);
    }
}
