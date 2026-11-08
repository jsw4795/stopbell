package com.stopbell.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

class MetadataAdminSchedulingTest {
    @Test @DisplayName("일반 실행은 스케줄러를 활성화하고 관리 명령 프로필은 예약 작업을 실행하지 않는다")
    void isolates_scheduling_from_local_admin_profile() {
        try (var ordinary = new AnnotationConfigApplicationContext()) {
            ordinary.register(SchedulingConfiguration.class); ordinary.refresh();
            assertThat(ordinary.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class)).hasSize(1);
        }
        try (var admin = new AnnotationConfigApplicationContext()) {
            admin.getEnvironment().setActiveProfiles("local", "metadata-admin");
            admin.register(SchedulingConfiguration.class); admin.refresh();
            assertThat(admin.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
        }
    }
    @Test @DisplayName("관리 명령 프로필은 기존 Bootstrap 및 FCM 실행 옵션을 차단한다")
    void excludes_other_one_shot_runners_in_admin_profile() {
        try (var admin = new AnnotationConfigApplicationContext()) {
            admin.getEnvironment().setActiveProfiles("local", "metadata-admin");
            org.springframework.test.context.support.TestPropertySourceUtils.addInlinedPropertiesToEnvironment(admin,
                    "transit.metadata.bootstrap.provider=SEOUL_BUS", "notification.fcm.smoke=true");
            admin.register(com.stopbell.transit.service.MetadataBootstrapRunner.class,
                    com.stopbell.notification.client.FcmSmokeRunner.class);
            admin.refresh();
            assertThat(admin.getBeansOfType(com.stopbell.transit.service.MetadataBootstrapRunner.class)).isEmpty();
            assertThat(admin.getBeansOfType(com.stopbell.notification.client.FcmSmokeRunner.class)).isEmpty();
        }
    }

    @Test @DisplayName("관리 명령은 시작 시 Flyway와 자동 스키마 변경을 강제로 비활성화한다")
    void prevents_implicit_schema_writes_for_admin() {
        try (var admin = new AnnotationConfigApplicationContext()) {
            admin.getEnvironment().setActiveProfiles("metadata-admin");
            org.springframework.test.context.support.TestPropertySourceUtils.addInlinedPropertiesToEnvironment(admin,
                    "spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=update", "spring.sql.init.mode=always");
            new MetadataAdminSafetyInitializer().initialize(admin);
            assertThat(admin.getEnvironment().getProperty("spring.flyway.enabled")).isEqualTo("false");
            assertThat(admin.getEnvironment().getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("none");
            assertThat(admin.getEnvironment().getProperty("spring.sql.init.mode")).isEqualTo("never");
        }
        try (var ordinary = new AnnotationConfigApplicationContext()) {
            new MetadataAdminSafetyInitializer().initialize(ordinary);
            assertThat(ordinary.getEnvironment().getProperty("spring.flyway.enabled")).isNull();
        }
    }

    @org.junit.jupiter.api.Test void preview_rejects_wrong_profile_before_context_start() {
        try (var context = new org.springframework.context.support.GenericApplicationContext()) {
            org.springframework.test.context.support.TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context, "metadata.command=preview", "spring.main.web-application-type=none",
                    "transit.metadata.admin.enabled=true");
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> new MetadataAdminSafetyInitializer().initialize(context)).isInstanceOf(IllegalStateException.class);
        }
    }
}
