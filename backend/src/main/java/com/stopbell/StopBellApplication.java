package com.stopbell;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class StopBellApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(StopBellApplication.class);
        application.addInitializers(new com.stopbell.common.config.MetadataAdminSafetyInitializer());
        application.run(args);
    }
}

