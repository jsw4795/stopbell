package com.stopbell.transit.client;

import java.net.http.HttpClient;
import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import com.stopbell.transit.metadata.SeoulCsvMetadataSource;
import com.stopbell.transit.metadata.TagoMetadataClient;
import com.stopbell.transit.metadata.TagoMetadataSource;
import com.stopbell.transit.service.BusMetadataSource;

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

    @Bean
    TagoMetadataClient tagoMetadataClient(TransitClientProperties properties) {
        return new TagoMetadataClient(restClient(properties), properties.tago());
    }

    @Bean
    BusMetadataSource tagoMetadataSource(TagoMetadataClient client) {
        return new TagoMetadataSource(client, 100);
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "transit.metadata.seoul",
            name = {"route-master", "route-stop-master", "stop-master"}
    )
    BusMetadataSource seoulCsvMetadataSource(
            @org.springframework.beans.factory.annotation.Value("${transit.metadata.seoul.route-master}")
            org.springframework.core.io.Resource routeMaster,
            @org.springframework.beans.factory.annotation.Value("${transit.metadata.seoul.route-stop-master}")
            org.springframework.core.io.Resource routeStopMaster,
            @org.springframework.beans.factory.annotation.Value("${transit.metadata.seoul.stop-master}")
            org.springframework.core.io.Resource stopMaster
    ) {
        return new SeoulCsvMetadataSource(routeMaster, routeStopMaster, stopMaster);
    }

    @Bean
    Clock transitMetadataClock() {
        return Clock.systemUTC();
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
