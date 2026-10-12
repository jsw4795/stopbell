package com.stopbell.notification.client;

import java.time.Instant;

import com.stopbell.alarm.repository.AlarmRepository;
import com.stopbell.notification.entity.DevicePlatform;
import com.stopbell.notification.repository.DeviceRepository;
import com.stopbell.transit.domain.TransitEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Explicit local CLI smoke only; reads existing rows and never creates Event/Delivery records. */
@Component
@Profile("local")
@ConditionalOnProperty(name = "notification.fcm.smoke", havingValue = "true")
public class FcmSmokeRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(FcmSmokeRunner.class);
    private final FcmProviderClient provider;
    private final DeviceRepository devices;
    private final AlarmRepository alarms;
    private final TransactionTemplate read;
    private final Environment environment;
    private final ConfigurableApplicationContext context;

    public FcmSmokeRunner(FcmProviderClient provider, DeviceRepository devices, AlarmRepository alarms,
                          PlatformTransactionManager transactions, Environment environment,
                          ConfigurableApplicationContext context) {
        this.provider = provider;
        this.devices = devices;
        this.alarms = alarms;
        this.read = new TransactionTemplate(transactions);
        this.read.setReadOnly(true);
        this.environment = environment;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            if (!"true".equals(option(args, "notification.fcm.smoke"))
                    || !"none".equals(option(args, "spring.main.web-application-type"))
                    || environment.getProperty("transit.monitoring.enabled", Boolean.class, false)) {
                log.info("FCM smoke result=SKIPPED code=EXPLICIT_LOCAL_CLI_REQUIRED");
                return;
            }
            long deviceId = Long.parseLong(option(args, "notification.fcm.smoke-device-id"));
            long alarmId = Long.parseLong(option(args, "notification.fcm.smoke-alarm-id"));
            NotificationSendRequest request = read.execute(status -> {
                var device = devices.findById(deviceId).orElseThrow();
                var alarm = alarms.findById(alarmId).orElseThrow();
                if (!device.isEnabled() || device.getPlatform() != DevicePlatform.IOS
                        || !device.getUser().getId().equals(alarm.getUser().getId())) {
                    throw new IllegalArgumentException("Smoke recipient is not eligible");
                }
                // Synthetic navigation hint only: no Event row or Delivery acceptance is claimed.
                return new NotificationSendRequest(device.getCurrentPushTargetId(), alarmId,
                        TransitEventType.ARRIVED, 1, Instant.now().plusSeconds(60));
            });
            var result = provider.send(request);
            log.info("FCM smoke result={} code={} (device display must be checked separately)",
                    result.result(), result.failureCode());
        } catch (RuntimeException ignored) {
            log.info("FCM smoke result=SKIPPED code=INVALID_SMOKE_INPUT");
        } finally {
            context.close();
        }
    }

    private static String option(ApplicationArguments args, String name) {
        var values = args.getOptionValues(name);
        if (values == null || values.size() != 1) {
            throw new IllegalArgumentException("Explicit smoke option required");
        }
        return values.getFirst();
    }
}
