package com.stopbell.support;

import java.sql.DriverManager;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.mysql.MySQLContainer;

/** Owns the pilot server for one JUnit test run, beyond each dirty Spring context. */
public class PilotMySqlExtension implements BeforeAllCallback {
    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(PilotMySqlExtension.class);
    private static volatile Server server;

    @Override
    public void beforeAll(ExtensionContext context) {
        server = context.getRoot().getStore(NAMESPACE).getOrComputeIfAbsent(Server.class, key -> new Server(), Server.class);
    }

    public static void database(DynamicPropertyRegistry registry, String database) {
        MySQLContainer mysql = server.mysql;
        registry.add("spring.datasource.url", () -> "jdbc:mysql://" + mysql.getHost() + ":"
                + mysql.getMappedPort(3306) + "/" + database);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    private static final class Server implements AutoCloseable {
        private final MySQLContainer mysql = new MySQLContainer("mysql:8.4.11");

        private Server() {
            try {
                mysql.start();
                try (var connection = DriverManager.getConnection(mysql.getJdbcUrl() + "?allowPublicKeyRetrieval=true&useSSL=false", "root", mysql.getPassword());
                        var statement = connection.createStatement()) {
                    for (String database : new String[] {"boarding_pilot", "device_pilot"}) {
                        statement.execute("CREATE DATABASE " + database);
                        statement.execute("GRANT ALL ON " + database + ".* TO '" + mysql.getUsername() + "'@'%'");
                    }
                }
            } catch (Exception e) {
                mysql.stop();
                throw new IllegalStateException("Failed to start pilot MySQL", e);
            }
        }

        @Override
        public void close() {
            System.out.println("Pilot MySQL stopping after Spring contexts");
            mysql.stop();
            server = null;
        }
    }
}
