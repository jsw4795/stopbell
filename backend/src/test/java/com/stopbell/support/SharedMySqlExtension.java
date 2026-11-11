package com.stopbell.support;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.Container;
import org.testcontainers.mysql.MySQLContainer;

/** Owns one server per JUnit run; Spring contexts never own or stop the container. */
public class SharedMySqlExtension implements BeforeAllCallback, ParameterResolver {
    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(SharedMySqlExtension.class);
    private static volatile Server server;

    @Override
    public void beforeAll(ExtensionContext context) {
        server = context.getRoot().getStore(NAMESPACE)
                .computeIfAbsent(Server.class, key -> new Server(), Server.class);
    }

    /** A class keeps its database until its dirty Spring context and pools have closed. */
    public static Database database(DynamicPropertyRegistry registry, String name) {
        Database database = currentServer().classDatabase(name);
        registry.add("spring.datasource.url", database::getJdbcUrl);
        registry.add("spring.datasource.username", database::getUsername);
        registry.add("spring.datasource.password", database::getPassword);
        return database;
    }

    static void retainContext(ConfigurableApplicationContext context) {
        currentServer().contexts.add(context);
    }

    @Override
    public boolean supportsParameter(ParameterContext parameter, ExtensionContext context) {
        return parameter.getParameter().getType() == Database.class;
    }

    /** Method-store ownership also isolates every parameterized invocation and failed migration. */
    @Override
    public Object resolveParameter(ParameterContext parameter, ExtensionContext context) {
        return context.getStore(NAMESPACE).computeIfAbsent(Database.class,
                key -> currentServer().createDatabase(context.getRequiredTestClass().getSimpleName()),
                Database.class);
    }

    private static Server currentServer() {
        Server current = server;
        if (current == null) {
            throw new IllegalStateException("Register SharedMySqlExtension before requesting a database");
        }
        return current;
    }

    public static final class Database implements AutoCloseable {
        private final Server owner;
        private final String name;
        private boolean closed;

        private Database(Server owner, String name) {
            this.owner = owner;
            this.name = name;
        }

        public String getJdbcUrl() {
            return owner.mysql.getJdbcUrl().replace("/" + owner.mysql.getDatabaseName(), "/" + name);
        }

        public String getUsername() {
            return owner.mysql.getUsername();
        }

        public String getPassword() {
            return owner.mysql.getPassword();
        }

        public int getMappedPort(int port) {
            return owner.mysql.getMappedPort(port);
        }

        /** Run the actual client inside the source server, against only this database. */
        public Container.ExecResult dump() throws Exception {
            return owner.mysql.execInContainer("sh", "-c",
                    "MYSQL_PWD=\"$MYSQL_PASSWORD\" mysqldump -u\"$MYSQL_USER\" "
                            + "--single-transaction --no-tablespaces \"$1\"", "dump", name);
        }

        @Override
        public synchronized void close() throws SQLException {
            if (!closed) {
                owner.execute("DROP DATABASE `" + name + "`",
                        "REVOKE ALL ON `" + name + "`.* FROM '" + getUsername() + "'@'%'");
                owner.databases.remove(this);
                closed = true;
            }
        }
    }

    private static final class Server implements AutoCloseable {
        // Failure-injection triggers need this option; constraints and transaction semantics are unchanged.
        private final MySQLContainer mysql = new MySQLContainer("mysql:8.4.11")
                .withCommand("--log-bin-trust-function-creators=1");
        private final Map<String, Database> classDatabases = new LinkedHashMap<>();
        private final Set<Database> databases = new LinkedHashSet<>();
        private final Set<ConfigurableApplicationContext> contexts = new LinkedHashSet<>();
        private int sequence;

        private Server() {
            try {
                mysql.start();
                System.out.println("Shared MySQL started: " + mysql.getContainerId());
            } catch (Exception e) {
                mysql.stop();
                throw new IllegalStateException("Failed to start shared MySQL", e);
            }
        }

        private synchronized Database classDatabase(String name) {
            return classDatabases.computeIfAbsent(name, this::createDatabase);
        }

        private synchronized Database createDatabase(String prefix) {
            String label = prefix.replaceAll("[^a-zA-Z0-9_]", "_");
            String name = "sb_" + label.substring(0, Math.min(label.length(), 48)) + "_" + ++sequence;
            try {
                execute("CREATE DATABASE `" + name + "`",
                        "GRANT ALL ON `" + name + "`.* TO '" + mysql.getUsername() + "'@'%'");
                Database database = new Database(this, name);
                databases.add(database);
                return database;
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to create isolated test database " + name, e);
            }
        }

        private void execute(String... commands) throws SQLException {
            try (var connection = DriverManager.getConnection(mysql.getJdbcUrl(), "root", mysql.getPassword());
                    var statement = connection.createStatement()) {
                for (String command : commands) {
                    statement.execute(command);
                }
            }
        }

        @Override
        public void close() throws SQLException {
            try {
                // Cached Spring contexts outlive their classes, but must release pools before MySQL.
                for (ConfigurableApplicationContext context : contexts) {
                    context.close();
                }
                try (var connection = DriverManager.getConnection(mysql.getJdbcUrl(), "root", mysql.getPassword());
                        var statement = connection.prepareStatement(
                                "SELECT COUNT(*) FROM information_schema.processlist WHERE user = ?")) {
                    statement.setString(1, mysql.getUsername());
                    try (var result = statement.executeQuery()) {
                        result.next();
                        int connections = result.getInt(1);
                        System.out.println("Shared MySQL stopping after Spring contexts; active test connections="
                                + connections);
                        if (connections != 0) {
                            throw new IllegalStateException("Test connections remain before shared MySQL shutdown");
                        }
                    }
                }
                for (Database database : Set.copyOf(databases)) {
                    database.close();
                }
                System.out.println("Shared MySQL databases cleaned: " + sequence);
            } finally {
                mysql.stop();
                server = null;
            }
        }
    }
}
