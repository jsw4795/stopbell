package com.stopbell.common.health;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Instant;

import com.stopbell.support.SharedMySqlExtension;
import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.transit.entity.BusMetadataSyncState;
import com.stopbell.transit.repository.BusMetadataSyncStateRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@ExtendWith(SharedMySqlExtension.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReadinessIntegrationTest {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        SharedMySqlExtension.database(registry, "readiness");
    }
    @Autowired MockMvc mvc;
    @Autowired BusMetadataSyncStateRepository metadata;

    @Test
    void fresh_database_is_not_ready_until_all_provider_bootstraps_are_recorded() throws Exception {
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN")).andExpect(jsonPath("$.components").doesNotExist());
        metadata.saveAndFlush(new BusMetadataSyncState(TransitProvider.TAGO, Instant.parse("2020-01-01T00:00:00Z")));
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isServiceUnavailable());
        metadata.saveAndFlush(new BusMetadataSyncState(TransitProvider.SEOUL_BUS, Instant.parse("2020-01-01T00:00:00Z")));
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP")).andExpect(jsonPath("$.components").doesNotExist());
    }
}
