package com.stopbell.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.web.SecurityFilterChain;

class SecurityConfigurationNonWebApplicationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(SecurityConfiguration.class);

    @Test
    @DisplayName("non-web application에서는 Servlet Security 설정을 생성하지 않는다")
    void does_not_create_servlet_security_configuration_in_non_web_application() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(SecurityConfiguration.class);
            assertThat(context).doesNotHaveBean(SecurityFilterChain.class);
        });
    }
}
