package com.stopbell.transit.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

class TransitClientConfigurationTest {

    @Test
    @DisplayName("realtime과 TAGO metadata client에 서로 다른 response timeout을 적용한다")
    void applies_separate_response_timeouts() {
        TransitClientProperties properties = new TransitClientProperties(
                Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(30),
                new TransitClientProperties.Tago("https://example.test/tago", "https://example.test/metadata", "key"),
                new TransitClientProperties.Seoul("http://ws.bus.go.kr", "key")
        );
        TransitClientConfiguration configuration = new TransitClientConfiguration();

        assertThat(responseTimeout(configuration.tagoVehicleLocationClient(properties))).isEqualTo(Duration.ofSeconds(5));
        assertThat(responseTimeout(configuration.seoulBusVehicleLocationClient(properties))).isEqualTo(Duration.ofSeconds(5));
        assertThat(responseTimeout(configuration.tagoMetadataClient(properties))).isEqualTo(Duration.ofSeconds(30));
        RestClient seoul = (RestClient) ReflectionTestUtils.getField(
                configuration.seoulBusVehicleLocationClient(properties), "restClient");
        Object factory = ReflectionTestUtils.getField(seoul, "clientRequestFactory");
        java.net.http.HttpClient http = (java.net.http.HttpClient) ReflectionTestUtils.getField(factory, "httpClient");
        assertThat(http.followRedirects()).isEqualTo(java.net.http.HttpClient.Redirect.NEVER);
        assertThat(http.connectTimeout()).contains(Duration.ofSeconds(2));
    }

    private Duration responseTimeout(Object client) {
        RestClient restClient = (RestClient) ReflectionTestUtils.getField(client, "restClient");
        JdkClientHttpRequestFactory requestFactory = (JdkClientHttpRequestFactory) ReflectionTestUtils.getField(restClient, "clientRequestFactory");
        return (Duration) ReflectionTestUtils.getField(requestFactory, "readTimeout");
    }
}
