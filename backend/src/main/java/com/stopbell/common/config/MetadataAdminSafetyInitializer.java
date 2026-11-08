package com.stopbell.common.config;

import java.util.Map;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.Profiles;

/** Prevent implicit schema writes before a local review command is invoked. */
public final class MetadataAdminSafetyInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    @Override public void initialize(ConfigurableApplicationContext context) {
        var environment = context.getEnvironment();
        if ("preview".equals(environment.getProperty("metadata.command"))
                && (!environment.acceptsProfiles(Profiles.of("local & metadata-admin"))
                || !"none".equals(environment.getProperty("spring.main.web-application-type"))
                || !"true".equals(environment.getProperty("transit.metadata.admin.enabled")))) {
            throw new IllegalStateException("Preview requires explicit local metadata-admin non-web execution");
        }
        if (environment.acceptsProfiles(Profiles.of("metadata-admin"))) {
            environment.getPropertySources().addFirst(new MapPropertySource("metadataAdminSafety", Map.of(
                    "spring.flyway.enabled", false,
                    "spring.jpa.hibernate.ddl-auto", "none",
                    "spring.sql.init.mode", "never")));
        }
    }
}
