package com.stopbell.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
public class SchedulingConfiguration {

    @Configuration(proxyBeanMethods = false)
    @Profile("!metadata-admin")
    @EnableScheduling
    static class SchedulingActivation { }

    @Bean
    ThreadPoolTaskScheduler transitMonitoringScheduler() {
        return scheduler("transit-monitoring-");
    }

    @Bean
    ThreadPoolTaskScheduler notificationDispatchScheduler() {
        return scheduler("notification-dispatch-");
    }

    @Bean
    ThreadPoolTaskScheduler alarmCreationCleanupScheduler() {
        return scheduler("alarm-creation-cleanup-");
    }

    @Bean
    ThreadPoolTaskScheduler accountDeletionScheduler() {
        return scheduler("account-deletion-");
    }

    private ThreadPoolTaskScheduler scheduler(String threadNamePrefix) {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix(threadNamePrefix);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        // Keep Spring's coordinated lifecycle stop: reject new work on close and let
        // active tasks finish before dependent beans are destroyed (within the lifecycle budget).
        scheduler.setAcceptTasksAfterContextClose(false);
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        return scheduler;
    }
}
