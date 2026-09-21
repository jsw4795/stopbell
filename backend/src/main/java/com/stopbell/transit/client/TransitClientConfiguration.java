package com.stopbell.transit.client;

import java.net.http.HttpClient;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TransitClientProperties.class)
public class TransitClientConfiguration {

    @Bean
    TagoVehicleLocationClient tagoVehicleLocationClient(TransitClientProperties properties) {
        return new TagoVehicleLocationClient(restClient(properties), properties.tago());
    }

    @Bean
    SeoulBusVehicleLocationClient seoulBusVehicleLocationClient(TransitClientProperties properties) {
        return new SeoulBusVehicleLocationClient(restClient(properties), properties.seoul());
    }

    @Bean
    SeoulBusVehicleDetailClient seoulBusVehicleDetailClient(TransitClientProperties properties) {
        return new SeoulBusVehicleDetailClient(restClient(properties), properties.seoul());
    }

    private RestClient restClient(TransitClientProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.responseTimeout());
        return RestClient.builder().requestFactory(requestFactory).build();
    }
}
