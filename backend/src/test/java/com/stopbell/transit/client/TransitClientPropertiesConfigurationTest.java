package com.stopbell.transit.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

class TransitClientPropertiesConfigurationTest {

    @Test
    @DisplayName("하나의 공공데이터 Service Key와 기본 Base URL을 두 Provider에 바인딩한다")
    void bind_public_data_service_key_and_default_base_urls() throws IOException {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("PUBLIC_DATA_SERVICE_KEY", "shared-service-key");
        PropertySource<?> applicationProperties = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"))
                .getFirst();
        environment.getPropertySources().addLast(applicationProperties);

        TransitClientProperties properties = Binder.get(environment)
                .bind("transit.client", TransitClientProperties.class)
                .orElseThrow(IllegalStateException::new);

        assertThat(properties.tago().baseUrl())
                .isEqualTo("https://apis.data.go.kr/1613000/BusLcInfoInqireService");
        assertThat(properties.seoul().baseUrl()).isEqualTo("http://ws.bus.go.kr/api/rest/buspos");
        assertThat(properties.tago().serviceKey()).isEqualTo("shared-service-key");
        assertThat(properties.seoul().serviceKey()).isEqualTo("shared-service-key");
        assertThat(properties.responseTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(properties.metadataResponseTimeout()).isEqualTo(Duration.ofSeconds(30));
    }
}
