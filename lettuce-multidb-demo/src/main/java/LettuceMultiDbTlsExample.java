import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SslOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.failover.MultiDbClient;
import io.lettuce.core.failover.api.DatabaseConfig;
import io.lettuce.core.failover.api.MultiDbOptions;
import io.lettuce.core.failover.api.StatefulRedisMultiDbConnection;

import java.io.File;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;


public class LettuceMultiDbTlsExample {

    // ============================================================
    // MAIN
    // ============================================================

    public static void main(String[] args) {

        String configFile;

        if (args.length == 0) {
                configFile = "redis-config.json";
        } else if (args.length == 1) {
                configFile = args[0];
        } else {
                System.err.println(
                        "Usage: gradle run --args=\"[config-file]\"");
                System.exit(1);
        return;
        }

        MultiDbConfig config;

        try {
            config = loadConfig(configFile);
        } catch (Exception e) {
            System.err.println();
            System.err.println("FAILED TO LOAD CONFIGURATION");
            System.err.println("----------------------------------------------");
            System.err.println(getRootCauseMessage(e));
            System.err.println("----------------------------------------------");
            System.exit(1);
            return;
        }

        // --------------------------------------------------------
        // Basic configuration validation
        // --------------------------------------------------------

        try {
            validateConfiguration(config);
        } catch (Exception e) {
            System.err.println();
            System.err.println("INVALID CONFIGURATION");
            System.err.println("----------------------------------------------");
            System.err.println(e.getMessage());
            System.err.println("----------------------------------------------");
            System.exit(1);
            return;
        }

        // --------------------------------------------------------
        // TLS
        // --------------------------------------------------------

        SslOptions sslOptions;

        try {
            File rootCa = new File(config.tls.rootCa);
            File clientCrt = new File(config.tls.clientCrt);
            File clientKey = new File(config.tls.clientKey);

            sslOptions = SslOptions.builder()
                    .jdkSslProvider()
                    .trustManager(rootCa)
                    .keyManager(clientCrt, clientKey, null)
                    .build();

        } catch (Exception e) {
            System.err.println();
            System.err.println("FAILED TO CREATE TLS CONFIGURATION");
            System.err.println("----------------------------------------------");
            System.err.println(getRootCauseMessage(e));
            System.err.println("----------------------------------------------");
            System.exit(1);
            return;
        }

        ClientOptions clientOptions = ClientOptions.builder()
                .sslOptions(sslOptions)
                .build();

        // --------------------------------------------------------
        // PRE-FLIGHT CHECK
        //
        // Do this BEFORE creating the MultiDbClient.
        // Every configured Redis endpoint must be reachable.
        // --------------------------------------------------------

        boolean allHealthy =
                validateAllEndpoints(
                        config.databases,
                        clientOptions);

        if (!allHealthy) {
            System.err.println();
            System.err.println("==============================================");
            System.err.println("STARTUP ABORTED");
            System.err.println("==============================================");
            System.err.println(
                    "One or more Redis endpoints are unavailable.");
            System.err.println(
                    "Application will NOT start.");
            System.err.println();

            System.exit(1);
            return;
        }

        // --------------------------------------------------------
        // Build Lettuce DatabaseConfig list
        // --------------------------------------------------------

        List<DatabaseConfig> databaseConfigs =
                new ArrayList<>();

        for (RedisEndpoint endpoint : config.databases) {

            RedisURI redisUri = createRedisUri(endpoint);

            DatabaseConfig databaseConfig =
                    DatabaseConfig.builder(redisUri)
                            .clientOptions(clientOptions)
                            .weight(endpoint.weight)
                            .build();

            databaseConfigs.add(databaseConfig);
        }

        // --------------------------------------------------------
        // MultiDb options
        // --------------------------------------------------------

        MultiDbOptions options = MultiDbOptions.builder()
                .failbackSupported(true)
                .failbackCheckInterval(Duration.ofSeconds(10))
                .gracePeriod(Duration.ofSeconds(5))
                .delayInBetweenFailoverAttempts(
                        Duration.ofSeconds(2))
                .build();

        // --------------------------------------------------------
        // Create MultiDbClient
        // --------------------------------------------------------

        MultiDbClient client = null;
        StatefulRedisMultiDbConnection<String, String> connection = null;

        try {

            client = MultiDbClient.create(
                    databaseConfigs,
                    options);

            subscribeToFailoverEvents(client);

            // ----------------------------------------------------
            // Connect
            // ----------------------------------------------------

            connection = client.connect();

            System.out.println();
            System.out.println("==============================================");
            System.out.println("APPLICATION STARTED");
            System.out.println("==============================================");
            System.out.println(
                    "Current Database: "
                            + connection.getCurrentEndpoint());
            System.out.println(
                    "Configured Databases: "
                            + config.databases.size());
            System.out.println("==============================================");
            System.out.println();

            runApplicationLoop(connection);

        } catch (Exception e) {

            System.err.println();
            System.err.println("APPLICATION FAILED");
            System.err.println("----------------------------------------------");
            System.err.println(getRootCauseMessage(e));
            System.err.println("----------------------------------------------");

        } finally {

            System.out.println();
            System.out.println("Shutting down...");

            if (connection != null) {
                try {
                    connection.close();
                } catch (Exception e) {
                    System.err.println(
                            "Failed to close connection: "
                                    + getRootCauseMessage(e));
                }
            }

            if (client != null) {
                try {
                    client.shutdown();
                } catch (Exception e) {
                    System.err.println(
                            "Failed to shutdown client: "
                                    + getRootCauseMessage(e));
                }
            }

            System.out.println("Shutdown complete.");
        }
    }


    // ============================================================
    // CONFIGURATION LOADING
    // ============================================================

    private static MultiDbConfig loadConfig(
            String configFile) throws Exception {

        ObjectMapper mapper = new ObjectMapper();

        mapper.configure(
                DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                true);

        return mapper.readValue(
                new File(configFile),
                MultiDbConfig.class);
    }


    // ============================================================
    // CONFIGURATION VALIDATION
    // ============================================================

    private static void validateConfiguration(
            MultiDbConfig config) {

        if (config == null) {
            throw new IllegalArgumentException(
                    "Configuration is null");
        }

        if (config.tls == null) {
            throw new IllegalArgumentException(
                    "TLS configuration is missing");
        }

        if (isBlank(config.tls.rootCa)) {
            throw new IllegalArgumentException(
                    "tls.rootCa is required");
        }

        if (isBlank(config.tls.clientCrt)) {
            throw new IllegalArgumentException(
                    "tls.clientCrt is required");
        }

        if (isBlank(config.tls.clientKey)) {
            throw new IllegalArgumentException(
                    "tls.clientKey is required");
        }

        if (config.databases == null ||
                config.databases.isEmpty()) {

            throw new IllegalArgumentException(
                    "At least one database must be configured");
        }

        for (int i = 0; i < config.databases.size(); i++) {

            RedisEndpoint db = config.databases.get(i);

            if (db == null) {
                throw new IllegalArgumentException(
                        "Database entry " + i + " is null");
            }

            if (isBlank(db.name)) {
                throw new IllegalArgumentException(
                        "Database entry " + i
                                + ": name is required");
            }

            if (isBlank(db.host)) {
                throw new IllegalArgumentException(
                        "Database " + db.name
                                + ": host is required");
            }

            if (db.port <= 0 || db.port > 65535) {
                throw new IllegalArgumentException(
                        "Database " + db.name
                                + ": invalid port "
                                + db.port);
            }

            if (isBlank(db.username)) {
                throw new IllegalArgumentException(
                        "Database " + db.name
                                + ": username is required");
            }

            if (db.password == null) {
                throw new IllegalArgumentException(
                        "Database " + db.name
                                + ": password is required");
            }

            if (db.weight < 0) {
                throw new IllegalArgumentException(
                        "Database " + db.name
                                + ": weight cannot be negative");
            }
        }
    }


    // ============================================================
    // PRE-FLIGHT
    // ============================================================

    private static boolean validateAllEndpoints(
            List<RedisEndpoint> endpoints,
            ClientOptions clientOptions) {

        boolean allHealthy = true;

        System.out.println();
        System.out.println("==============================================");
        System.out.println("REDIS PRE-FLIGHT CHECK");
        System.out.println("==============================================");
        System.out.println(
                "Checking " + endpoints.size()
                        + " configured endpoint(s)");
        System.out.println();

        for (RedisEndpoint endpoint : endpoints) {

            boolean healthy =
                    checkEndpoint(
                            endpoint,
                            clientOptions);

            if (!healthy) {
                allHealthy = false;
            }

            System.out.println();
        }

        System.out.println("==============================================");

        if (allHealthy) {
            System.out.println(
                    "PRE-FLIGHT RESULT: ALL ENDPOINTS HEALTHY");
        } else {
            System.out.println(
                    "PRE-FLIGHT RESULT: FAILURE");
        }

        System.out.println("==============================================");

        return allHealthy;
    }


    private static boolean checkEndpoint(
            RedisEndpoint endpoint,
            ClientOptions clientOptions) {

        System.out.println(
                "Checking: "
                        + endpoint.name
                        + " ["
                        + endpoint.host
                        + ":"
                        + endpoint.port
                        + "]");

        RedisClient redisClient = null;
        StatefulRedisConnection<String, String> connection =
                null;

        try {

            RedisURI redisUri =
                    createRedisUri(endpoint);

            redisClient =
                    RedisClient.create(redisUri);

            redisClient.setOptions(clientOptions);

            long start = System.currentTimeMillis();

            connection =
                    redisClient.connect();

            String response =
                    connection.sync().ping();

            long elapsed =
                    System.currentTimeMillis() - start;

            if ("PONG".equalsIgnoreCase(response)) {

                System.out.println(
                        "  STATUS : OK");

                System.out.println(
                        "  PING   : PONG");

                System.out.println(
                        "  TIME   : "
                                + elapsed
                                + " ms");

                return true;
            }

            System.err.println(
                    "  STATUS : FAILED");

            System.err.println(
                    "  PING   : Unexpected response: "
                            + response);

            return false;

        } catch (Exception e) {

            System.err.println(
                    "  STATUS : FAILED");

            System.err.println(
                    "  REASON : "
                            + getRootCauseMessage(e));

            return false;

        } finally {

            if (connection != null) {
                try {
                    connection.close();
                } catch (Exception ignored) {
                }
            }

            if (redisClient != null) {
                try {
                    redisClient.shutdown();
                } catch (Exception ignored) {
                }
            }
        }
    }


    // ============================================================
    // REDIS URI
    // ============================================================

    private static RedisURI createRedisUri(
            RedisEndpoint endpoint) {

        RedisURI uri = RedisURI.builder()
                .withHost(endpoint.host)
                .withPort(endpoint.port)
                .withSsl(true)
                .build();

        uri.setAuthentication(
                endpoint.username,
                endpoint.password);

        return uri;
    }


    // ============================================================
    // FAILOVER EVENT LISTENER
    // ============================================================

    private static void subscribeToFailoverEvents(
            MultiDbClient client) {

        client.getResources()
                .eventBus()
                .get()
                .subscribe(event -> {

                    if ("DatabaseSwitchEvent"
                            .equals(
                                    event.getClass()
                                            .getSimpleName())) {

                        try {

                            Method getFromDb =
                                    event.getClass()
                                            .getMethod(
                                                    "getFromDb");

                            Method getToDb =
                                    event.getClass()
                                            .getMethod(
                                                    "getToDb");

                            Method getReason =
                                    event.getClass()
                                            .getMethod(
                                                    "getReason");

                            Object fromDb =
                                    getFromDb.invoke(event);

                            Object toDb =
                                    getToDb.invoke(event);

                            Object reason =
                                    getReason.invoke(event);

                            System.out.println();
                            System.out.println(
                                    "=================================================");
                            System.out.println(
                                    "DATABASE SWITCH DETECTED");
                            System.out.println(
                                    "Time      : "
                                            + LocalDateTime.now());
                            System.out.println(
                                    "Switched  : "
                                            + fromDb
                                            + " -> "
                                            + toDb);
                            System.out.println(
                                    "Reason    : "
                                            + reason);
                            System.out.println(
                                    "=================================================");
                            System.out.println();

                        } catch (Exception ex) {

                            System.out.println(
                                    "DATABASE SWITCH DETECTED: "
                                            + event);
                        }
                    }
                });
    }


    // ============================================================
    // APPLICATION LOOP
    // ============================================================

    private static void runApplicationLoop(
            StatefulRedisMultiDbConnection<String, String>
                    connection)
            throws InterruptedException {

        System.out.println(
                "Press Ctrl+C to stop.");
        System.out.println();

        long counter = 0;
        String lastEndpoint = "";

        while (true) {

            try {

                String currentEndpoint =
                        String.valueOf(
                                connection.getCurrentEndpoint());

                if (!currentEndpoint.equals(lastEndpoint)) {

                    lastEndpoint = currentEndpoint;

                    System.out.println(
                            "---------------------------------------------");

                    System.out.println(
                            "ACTIVE DATABASE : "
                                    + currentEndpoint);

                    System.out.println(
                            "---------------------------------------------");
                }

                String key = "failover:test";

                String value =
                        "value-" + counter;

                connection.sync()
                        .set(key, value);

                String result =
                        connection.sync()
                                .get(key);

                System.out.printf(
                        "%s | %d | %s | %s%n",
                        LocalDateTime.now(),
                        counter,
                        currentEndpoint,
                        result);

                counter++;

            } catch (Exception e) {

                System.out.println();
                System.out.println(
                        "*********** OPERATION FAILED ***********");

                System.out.println(
                        LocalDateTime.now());

                System.out.println(
                        "Reason: "
                                + getRootCauseMessage(e));

                System.out.println(
                        "***************************************");
                System.out.println();
            }

            Thread.sleep(1000);
        }
    }


    // ============================================================
    // UTILS
    // ============================================================

    private static boolean isBlank(String value) {
        return value == null ||
                value.trim().isEmpty();
    }


    private static String getRootCauseMessage(
            Throwable throwable) {

        Throwable root = throwable;

        while (root.getCause() != null) {
            root = root.getCause();
        }

        String message = root.getMessage();

        if (message == null ||
                message.trim().isEmpty()) {

            return root.getClass()
                    .getSimpleName();
        }

        return root.getClass()
                .getSimpleName()
                + ": "
                + message;
    }


    // ============================================================
    // CONFIGURATION CLASSES
    // ============================================================

    public static class MultiDbConfig {

        public TlsConfig tls;

        public List<RedisEndpoint> databases;
    }


    public static class TlsConfig {

        public String rootCa;

        public String clientCrt;

        public String clientKey;
    }


    public static class RedisEndpoint {

        public String name;

        public String host;

        public int port;

        public String username;

        public String password;

        public float weight;
    }
}