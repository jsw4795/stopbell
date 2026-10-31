package com.stopbell;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.stopbell.boarding.repository.BoardingSessionRepository;
import com.stopbell.user.repository.UserRepository;
import com.stopbell.user.repository.RefreshTokenRepository;
import com.stopbell.alarm.repository.AlarmRepository;
import com.stopbell.transit.repository.BusRouteRepository;
import com.stopbell.transit.repository.BusRouteStopOccurrenceRepository;
import com.stopbell.transit.repository.BusStopRepository;
import com.stopbell.transit.repository.BusMetadataSyncStateRepository;

import static org.mockito.Mockito.mock;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stopbell.notification.repository.DeviceRepository;
import com.stopbell.notification.repository.NotificationDeliveryRepository;
import com.stopbell.notification.repository.NotificationEventRepository;
import org.springframework.transaction.PlatformTransactionManager;

@SpringBootTest(properties = "spring.autoconfigure.exclude="
        + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
        + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(StopBellApplicationTests.TestRepositoryConfiguration.class)
class StopBellApplicationTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Spring ApplicationContext가 정상적으로 로드된다")
    void application_context_loads() {
    }

    @Test
    @DisplayName("Health endpoint는 UP 상태를 반환한다")
    void health_endpoint_returns_up() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestRepositoryConfiguration {

        @Bean
        com.stopbell.alarm.repository.AlarmCreationRequestRepository alarmCreationRequestRepository() {
            return mock(com.stopbell.alarm.repository.AlarmCreationRequestRepository.class);
        }

        @Bean
        org.springframework.jdbc.core.JdbcTemplate jdbcTemplate() {
            return mock(org.springframework.jdbc.core.JdbcTemplate.class);
        }

        @Bean
        UserRepository userRepository() {
            return mock(UserRepository.class);
        }

        @Bean
        BoardingSessionRepository boardingSessionRepository() {
            return mock(BoardingSessionRepository.class);
        }

        @Bean
        AlarmRepository alarmRepository() {
            return mock(AlarmRepository.class);
        }

        @Bean
        RefreshTokenRepository refreshTokenRepository() {
            return mock(RefreshTokenRepository.class);
        }

        @Bean
        BusRouteRepository busRouteRepository() {
            return mock(BusRouteRepository.class);
        }

        @Bean
        BusStopRepository busStopRepository() {
            return mock(BusStopRepository.class);
        }

        @Bean
        BusRouteStopOccurrenceRepository busRouteStopOccurrenceRepository() {
            return mock(BusRouteStopOccurrenceRepository.class);
        }

        @Bean
        BusMetadataSyncStateRepository busMetadataSyncStateRepository() {
            return mock(BusMetadataSyncStateRepository.class);
        }
        
        @Bean
        DeviceRepository deviceRepository() {
            return mock(DeviceRepository.class);
        }

        @Bean
        NotificationDeliveryRepository notificationDeliveryRepository() {
            return mock(NotificationDeliveryRepository.class);
        }

        @Bean
        NotificationEventRepository notificationEventRepository() {
            return mock(NotificationEventRepository.class);
        }

        @Bean
        PlatformTransactionManager transactionManager() {
            return mock(PlatformTransactionManager.class);
        }
    }
}
