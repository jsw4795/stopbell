package com.stopbell.transit.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void properties_strings_do_not_expose_keys_or_urls() {
        var tago = new TransitClientProperties.Tago("https://synthetic.example/path?key=secret-marker",
                "https://synthetic.example/metadata", "synthetic-key");
        var seoul = new TransitClientProperties.Seoul("http://ws.bus.go.kr", "synthetic-key");
        var properties = new TransitClientProperties(Duration.ofSeconds(2), Duration.ofSeconds(5),
                Duration.ofSeconds(30), tago, seoul);
        assertThat(tago.toString()).isEqualTo("Tago[redacted]");
        assertThat(seoul.toString()).isEqualTo("Seoul[redacted]");
        assertThat(properties.toString()).isEqualTo("TransitClientProperties[redacted]");
    }

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
        assertThat(properties.seoul().baseUrl()).isEqualTo("http://ws.bus.go.kr");
        assertThat(properties.tago().serviceKey()).isEqualTo("shared-service-key");
        assertThat(properties.seoul().serviceKey()).isEqualTo("shared-service-key");
        assertThat(properties.responseTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(properties.metadataResponseTimeout()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    @DisplayName("개발과 운영 profile 모두 공식 서울 HTTP 설정과 동일한 TAGO HTTPS 설정을 사용한다")
    void official_http_works_in_local_and_production() throws IOException {
        for (String profile : new String[] {"local", "test", "prod", "production"}) {
            MockEnvironment environment = new MockEnvironment().withProperty("PUBLIC_DATA_SERVICE_KEY", "fixture-key");
            environment.setActiveProfiles(profile);
            environment.getPropertySources().addLast(new YamlPropertySourceLoader()
                    .load("application.yml", new ClassPathResource("application.yml")).getFirst());
            ClassPathResource profileResource = new ClassPathResource("application-" + profile + ".yml");
            if (profileResource.exists()) {
                environment.getPropertySources().addFirst(new YamlPropertySourceLoader()
                        .load("profile", profileResource).getFirst());
            }
            new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                    .withInitializer(context -> {
                        context.getEnvironment().setActiveProfiles(profile);
                        context.getEnvironment().getPropertySources().addFirst(
                                new org.springframework.core.env.MapPropertySource("fixture", java.util.Map.of(
                                        "PUBLIC_DATA_SERVICE_KEY", "fixture-key")));
                        environment.getPropertySources().forEach(source ->
                                context.getEnvironment().getPropertySources().addLast(source));
                    })
                    .withUserConfiguration(TransitClientConfiguration.class)
                    .run(context -> {
                        assertThat(context).hasNotFailed().hasSingleBean(SeoulBusVehicleLocationClient.class)
                                .hasSingleBean(SeoulBusVehicleDetailClient.class);
                        var properties = context.getBean(TransitClientProperties.class);
                        assertThat(properties.seoul().baseUrl()).isEqualTo("http://ws.bus.go.kr");
                        assertThat(properties.tago().baseUrl()).isEqualTo("test".equals(profile)
                                ? "https://example.test/tago"
                                : "https://apis.data.go.kr/1613000/BusLcInfoInqireService");
                    });
        }
    }

    @Test
    @DisplayName("서울 설정은 임의 host와 port 및 인증정보가 섞인 origin을 거부한다")
    void reject_non_official_origins() {
        for (String url : new String[] {"https://ws.bus.go.kr", "https://ws.bus.go.kr:443",
                "http://example.test", "http://ws.bus.go.kr.example.test",
                "http://ws.bus.go.kr@evil.test", "http://ws.bus.go.kr:8080", "http://ws.bus.go.kr/api",
                "http://ws.bus.go.kr?serviceKey=secret", "http://ws.bus.go.kr#fragment", "file:///tmp/xml"}) {
            assertThatThrownBy(() -> new TransitClientProperties.Seoul(url, "key"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(new TransitClientProperties.Seoul("http://ws.bus.go.kr:80", "key").baseUrl())
                .isEqualTo("http://ws.bus.go.kr:80");
    }

}
