import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;

import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.Endpoint;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.MultiDbClient;
import redis.clients.jedis.MultiDbConfig;
import redis.clients.jedis.UnifiedJedis;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import redis.clients.jedis.MultiDbConfig.DatabaseConfig;
import redis.clients.jedis.Endpoint;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.MultiDbClient;
import redis.clients.jedis.MultiDbConfig;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.mcf.DatabaseSwitchEvent;

public class JedisMultiDbTlsExample {

    // ============================================================
    // MAIN
    // ============================================================

    public static void main(String[] args) {

        configureLogging();

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

        // --------------------------------------------------------
        // Load configuration
        // --------------------------------------------------------

        MultiDbConfigFile config;

        try {
            config = loadConfig(configFile);

        } catch (Exception e) {

            System.err.println();
            System.err.println(
                    "FAILED TO LOAD CONFIGURATION");
            System.err.println(
                    "----------------------------------------------");
            System.err.println(
                    getRootCauseMessage(e));
            System.err.println(
                    "----------------------------------------------");

            System.exit(1);
            return;
        }

        // --------------------------------------------------------
        // Validate configuration
        // --------------------------------------------------------

        try {

            validateConfiguration(config);

        } catch (Exception e) {

            System.err.println();
            System.err.println(
                    "INVALID CONFIGURATION");
            System.err.println(
                    "----------------------------------------------");
            System.err.println(
                    e.getMessage());
            System.err.println(
                    "----------------------------------------------");

            System.exit(1);
            return;
        }

        // --------------------------------------------------------
        // Build TLS configuration
        // --------------------------------------------------------

        SSLSocketFactory sslSocketFactory;

        try {

            File rootCa =
                    new File(config.tls.rootCa);

            File clientCrt =
                    new File(config.tls.clientCrt);

            File clientKey =
                    new File(config.tls.clientKey);

            sslSocketFactory =
                    createSslSocketFactory(
                            rootCa,
                            clientCrt,
                            clientKey);

        } catch (Exception e) {

            System.err.println();
            System.err.println(
                    "FAILED TO CREATE TLS CONFIGURATION");
            System.err.println(
                    "----------------------------------------------");
            System.err.println(
                    getRootCauseMessage(e));
            System.err.println(
                    "----------------------------------------------");

            System.exit(1);
            return;
        }

        // --------------------------------------------------------
        // Jedis client configuration
        // --------------------------------------------------------

        SSLParameters sslParameters =
                new SSLParameters();

        /*
         * Enable hostname verification.
         *
         * This is important because your successful redis-cli
         * connection uses:
         *
         *   test-db.redis-enterprise.example.com
         *
         * rather than the IP address.
         */
        sslParameters.setEndpointIdentificationAlgorithm(
                "HTTPS");

        // --------------------------------------------------------
        // PRE-FLIGHT ALL DATABASES
        // --------------------------------------------------------

        boolean allHealthy =
                validateAllEndpoints(
                        config.databases,
                        sslSocketFactory,
                        sslParameters);

        if (!allHealthy) {

            System.err.println();
            System.err.println(
                    "==============================================");
            System.err.println(
                    "STARTUP ABORTED");
            System.err.println(
                    "==============================================");
            System.err.println(
                    "One or more Redis endpoints are unavailable.");
            System.err.println(
                    "Application will NOT start.");
            System.err.println();

            System.exit(1);
            return;
        }

        // --------------------------------------------------------
        // Build MultiDbConfig
        // --------------------------------------------------------

        MultiDbConfig.Builder multiDbBuilder =
                MultiDbConfig.builder();

        for (RedisEndpoint endpoint :
                config.databases) {

            HostAndPort hostAndPort =
                    new HostAndPort(
                            endpoint.host,
                            endpoint.port);

            JedisClientConfig clientConfig =
                    createJedisClientConfig(
                            endpoint,
                            sslSocketFactory,
                            sslParameters);

            MultiDbConfig.DatabaseConfig databaseConfig =
                    MultiDbConfig.DatabaseConfig
                            .builder(
                                    hostAndPort,
                                    clientConfig)
                            .weight(endpoint.weight)
                            .build();

            multiDbBuilder.database(databaseConfig);
        }

        // --------------------------------------------------------
        // Configure Jedis failover behavior
        //
        // Jedis uses a circuit breaker for failure detection.
        // These values make the demo react quickly.
        // --------------------------------------------------------

        multiDbBuilder
                .failureDetector(
                        MultiDbConfig
                                .CircuitBreakerConfig
                                .builder()
                                .slidingWindowSize(2)
                                .failureRateThreshold(50.0f)
                                .minNumOfFailures(1)
                                .build())

                /*
                 * Disable command retries for the demo so that
                 * a connection failure can cause failover quickly.
                 */
                .commandRetry(
                        MultiDbConfig
                                .RetryConfig
                                .builder()
                                .maxAttempts(1)
                                .waitDuration(500)
                                .build())

                /*
                 * Equivalent to Lettuce:
                 *
                 * failbackSupported(true)
                 */
                .failbackSupported(true)

                /*
                 * Equivalent to:
                 *
                 * failbackCheckInterval(Duration.ofSeconds(10))
                 */
                .failbackCheckInterval(10_000)

                /*
                 * Equivalent to:
                 *
                 * gracePeriod(Duration.ofSeconds(5))
                 */
                .gracePeriod(5_000)

                /*
                 * Equivalent to:
                 *
                 * delayInBetweenFailoverAttempts(
                 *      Duration.ofSeconds(2))
                 */
                .delayInBetweenFailoverAttempts(2_000);

        MultiDbConfig multiDbConfig =
                multiDbBuilder.build();

        // --------------------------------------------------------
        // Build MultiDbClient
        // --------------------------------------------------------

        MultiDbClient client = null;

        try {

            client =
                    MultiDbClient.builder()
                            .multiDbConfig(multiDbConfig)
                            .databaseSwitchListener(
                                    JedisMultiDbTlsExample
                                            ::handleDatabaseSwitch)
                            .build();

            // ----------------------------------------------------
            // Application startup
            // ----------------------------------------------------

            System.out.println();
            System.out.println(
                    "==============================================");
            System.out.println(
                    "APPLICATION STARTED");
            System.out.println(
                    "==============================================");

            System.out.println(
                    "Current Database: "
                            + client
                            .getActiveDatabaseEndpoint());

            System.out.println(
                    "Configured Databases: "
                            + config.databases.size());

            System.out.println(
                    "==============================================");
            System.out.println();

            runApplicationLoop(client);

        } catch (Exception e) {

            System.err.println();
            System.err.println(
                    "APPLICATION FAILED");
            System.err.println(
                    "----------------------------------------------");
            System.err.println(
                    getRootCauseMessage(e));
            System.err.println(
                    "----------------------------------------------");

        } finally {

            System.out.println();
            System.out.println(
                    "Shutting down...");

            if (client != null) {

                try {
                    client.close();

                } catch (Exception e) {

                    System.err.println(
                            "Failed to shutdown Jedis client: "
                                    + getRootCauseMessage(e));
                }
            }

            System.out.println(
                    "Shutdown complete.");
        }
    }


    // ============================================================
    // CONFIGURATION
    // ============================================================

    private static MultiDbConfigFile loadConfig(
            String configFile)
            throws Exception {

        ObjectMapper mapper =
                new ObjectMapper();

        mapper.configure(
                DeserializationFeature
                        .FAIL_ON_UNKNOWN_PROPERTIES,
                true);

        return mapper.readValue(
                new File(configFile),
                MultiDbConfigFile.class);
    }


    private static void validateConfiguration(
            MultiDbConfigFile config) {

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

        for (int i = 0;
             i < config.databases.size();
             i++) {

            RedisEndpoint endpoint =
                    config.databases.get(i);

            if (endpoint == null) {

                throw new IllegalArgumentException(
                        "Database entry "
                                + i
                                + " is null");
            }

            if (isBlank(endpoint.name)) {

                throw new IllegalArgumentException(
                        "Database entry "
                                + i
                                + ": name is required");
            }

            if (isBlank(endpoint.host)) {

                throw new IllegalArgumentException(
                        "Database "
                                + endpoint.name
                                + ": host is required");
            }

            if (endpoint.port <= 0 ||
                    endpoint.port > 65535) {

                throw new IllegalArgumentException(
                        "Database "
                                + endpoint.name
                                + ": invalid port "
                                + endpoint.port);
            }

            // Username and password are optional.
            // Supported configurations are:
            //   1. neither username nor password
            //   2. password only (Redis default-user style)
            //   3. username + password
            // An empty username is treated the same as an omitted username.
            // An empty password is treated the same as an omitted password.

            if (endpoint.weight <= 0) {

                throw new IllegalArgumentException(
                        "Database "
                                + endpoint.name
                                + ": weight must be > 0");
            }
        }
    }


    // ============================================================
    // JEDIS CLIENT CONFIG
    // ============================================================

    private static JedisClientConfig createJedisClientConfig(
            RedisEndpoint endpoint,
            SSLSocketFactory sslSocketFactory,
            SSLParameters sslParameters) {

        DefaultJedisClientConfig.Builder builder =
                DefaultJedisClientConfig.builder()
                        .connectionTimeoutMillis(10_000)
                        .socketTimeoutMillis(10_000)
                        .ssl(true)
                        /*
                         * Existing PEM files are converted into an
                         * SSLContext / SSLSocketFactory.
                         */
                        .sslSocketFactory(sslSocketFactory)
                        .sslParameters(sslParameters);

        // Authentication is optional. Support:
        // - no authentication
        // - password only
        // - username + password
        if (!isBlank(endpoint.username)) {
            builder.user(endpoint.username);
        }

        if (!isBlank(endpoint.password)) {
            builder.password(endpoint.password);
        }

        return builder.build();
    }


    // ============================================================
    // PRE-FLIGHT
    // ============================================================

    private static boolean validateAllEndpoints(
            List<RedisEndpoint> endpoints,
            SSLSocketFactory sslSocketFactory,
            SSLParameters sslParameters) {

        boolean allHealthy = true;

        System.out.println();
        System.out.println(
                "==============================================");
        System.out.println(
                "REDIS PRE-FLIGHT CHECK");
        System.out.println(
                "==============================================");

        System.out.println(
                "Checking "
                        + endpoints.size()
                        + " configured endpoint(s)");

        System.out.println();

        for (RedisEndpoint endpoint :
                endpoints) {

            boolean healthy =
                    checkEndpoint(
                            endpoint,
                            sslSocketFactory,
                            sslParameters);

            if (!healthy) {
                allHealthy = false;
            }

            System.out.println();
        }

        System.out.println(
                "==============================================");

        if (allHealthy) {

            System.out.println(
                    "PRE-FLIGHT RESULT: "
                            + "ALL ENDPOINTS HEALTHY");

        } else {

            System.out.println(
                    "PRE-FLIGHT RESULT: FAILURE");
        }

        System.out.println(
                "==============================================");

        return allHealthy;
    }


    private static boolean checkEndpoint(
            RedisEndpoint endpoint,
            SSLSocketFactory sslSocketFactory,
            SSLParameters sslParameters) {

        System.out.println(
                "Checking: "
                        + endpoint.name
                        + " ["
                        + endpoint.host
                        + ":"
                        + endpoint.port
                        + "]");

        HostAndPort hostAndPort =
                new HostAndPort(
                        endpoint.host,
                        endpoint.port);

        JedisClientConfig config =
                createJedisClientConfig(
                        endpoint,
                        sslSocketFactory,
                        sslParameters);

        long start =
                System.currentTimeMillis();

        try (UnifiedJedis jedis =
                     new UnifiedJedis(
                             hostAndPort,
                             config)) {

            String response =
                    jedis.ping();

            long elapsed =
                    System.currentTimeMillis()
                            - start;

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
        }
    }


    // ============================================================
    // DATABASE SWITCH EVENT
    // ============================================================

    private static void handleDatabaseSwitch(
            DatabaseSwitchEvent event) {

        try {
            String databaseName = safeToString(
                    event.getDatabaseName());

            String endpoint = safeToString(
                    event.getEndpoint());

            String reason = friendlySwitchReason(
                    event.getReason());

            System.out.println();
            System.out.println(
                    "=================================================");
            System.out.println(
                    "DATABASE SWITCH DETECTED");
            System.out.println(
                    "Time      : " + LocalDateTime.now());
            System.out.println(
                    "Database  : " + databaseName);
            System.out.println(
                    "Endpoint  : " + endpoint);
            System.out.println(
                    "Reason    : " + reason);
            System.out.println();
            System.out.println(
                    "Traffic switched automatically to healthy database endpoint.");
            System.out.println(
                    "Application continues running.");
            System.out.println(
                    "=================================================");
            System.out.println();

        } catch (Exception handlerException) {
            // Never allow logging/formatting of a failover event to
            // interfere with the application or failover process.
            System.out.println();
            System.out.println(
                    "DATABASE SWITCH DETECTED");
            System.out.println(
                    "Traffic switched automatically to healthy database endpoint.");
            System.out.println(
                    "Application continues running.");
            System.out.println();
        }
    }


    private static String safeToString(Object value) {
        return value == null ? "unknown" : String.valueOf(value);
    }


    private static String friendlySwitchReason(Object reason) {
        String raw = safeToString(reason);
        String text = raw.toLowerCase();

        if (containsAny(text,
                "timeout",
                "timed out",
                "time out",
                "connecttimeoutexception")) {
            return "Endpoint health check timed out";
        }

        if (containsAny(text,
                "connection refused",
                "connectexception")) {
            return "Endpoint connection was refused";
        }

        if (containsAny(text,
                "connection reset",
                "connectionreset")) {
            return "Endpoint connection was reset";
        }

        if (containsAny(text,
                "unreachable",
                "no route to host",
                "unknownhost")) {
            return "Endpoint is unreachable";
        }

        if (containsAny(text,
                "authentication",
                "wrongpass",
                "noauth")) {
            return "Endpoint authentication failed";
        }

        if (containsAny(text,
                "ssl",
                "tls",
                "handshake",
                "certificate")) {
            return "Endpoint TLS connection failed";
        }

        if (containsAny(text,
                "health",
                "ping",
                "healthcheck")) {
            return "Endpoint health check failed";
        }

        return "Endpoint became unhealthy";
    }


    private static boolean containsAny(
            String text,
            String... values) {

        for (String value : values) {
            if (text.contains(value)) {
                return true;
            }
        }

        return false;
    }



    // ============================================================
    // APPLICATION LOOP
    // ============================================================

    private static void runApplicationLoop(
            MultiDbClient client)
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
                                client
                                        .getActiveDatabaseEndpoint());

                if (!currentEndpoint.equals(
                        lastEndpoint)) {

                    lastEndpoint =
                            currentEndpoint;

                    System.out.println(
                            "---------------------------------------------");

                    System.out.println(
                            "ACTIVE DATABASE : "
                                    + currentEndpoint);

                    System.out.println(
                            "---------------------------------------------");
                }

                String key =
                        "failover:test";

                String value =
                        "value-" + counter;

                client.set(
                        key,
                        value);

                String result =
                        client.get(key);

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
                        "*********** OPERATION FAILED "
                                + "***********");

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
    // TLS / PEM HANDLING
    //
    // Lettuce accepts PEM files directly through SslOptions.
    //
    // Jedis' modern SslOptions API is keystore/truststore based,
    // so this adapter converts:
    //
    //     rootca.crt
    //     client.crt
    //     client.key
    //
    // into an in-memory SSLContext.
    // ============================================================

    private static SSLSocketFactory createSslSocketFactory(
            File rootCaFile,
            File clientCertificateFile,
            File clientKeyFile)
            throws Exception {

        SecurityProviderInitializer.initialize();

        X509Certificate caCertificate =
                readCertificate(rootCaFile);

        List<X509Certificate> clientCertificateChain =
                readCertificates(clientCertificateFile);

        PrivateKey privateKey =
                readPrivateKey(clientKeyFile);

        // --------------------------------------------------------
        // Trust store
        // --------------------------------------------------------

        KeyStore trustStore =
                KeyStore.getInstance(
                        KeyStore.getDefaultType());

        trustStore.load(
                null,
                null);

        trustStore.setCertificateEntry(
                "redis-root-ca",
                caCertificate);

        TrustManagerFactory trustManagerFactory =
                TrustManagerFactory.getInstance(
                        TrustManagerFactory
                                .getDefaultAlgorithm());

        trustManagerFactory.init(
                trustStore);

        // --------------------------------------------------------
        // Client key store
        // --------------------------------------------------------

        KeyStore clientKeyStore =
                KeyStore.getInstance("PKCS12");

        clientKeyStore.load(
                null,
                null);

        Certificate[] certificateChain =
                clientCertificateChain
                        .toArray(
                                new Certificate[0]);

        clientKeyStore.setKeyEntry(
                "redis-client",
                privateKey,
                new char[0],
                certificateChain);

        KeyManagerFactory keyManagerFactory =
                KeyManagerFactory.getInstance(
                        KeyManagerFactory
                                .getDefaultAlgorithm());

        keyManagerFactory.init(
                clientKeyStore,
                new char[0]);

        // --------------------------------------------------------
        // SSL context
        // --------------------------------------------------------

        SSLContext sslContext =
                SSLContext.getInstance("TLS");

        sslContext.init(
                keyManagerFactory.getKeyManagers(),
                trustManagerFactory.getTrustManagers(),
                new SecureRandom());

        return sslContext.getSocketFactory();
    }


    // ============================================================
    // READ CA CERTIFICATE
    // ============================================================

    private static X509Certificate readCertificate(
            File certificateFile)
            throws Exception {

        try (FileReader reader =
                     new FileReader(
                             certificateFile,
                             StandardCharsets.UTF_8);

             PEMParser parser =
                     new PEMParser(reader)) {

            Object object =
                    parser.readObject();

            if (!(object instanceof
                    X509CertificateHolder)) {

                throw new IllegalArgumentException(
                        "File does not contain an X509 certificate: "
                                + certificateFile);
            }

            X509CertificateHolder holder =
                    (X509CertificateHolder) object;

            return new JcaX509CertificateConverter()
                    .setProvider("BC")
                    .getCertificate(holder);
        }
    }


    // ============================================================
    // READ CERTIFICATE CHAIN
    // ============================================================

    private static List<X509Certificate> readCertificates(
            File certificateFile)
            throws Exception {

        List<X509Certificate> certificates =
                new ArrayList<>();

        try (FileReader reader =
                     new FileReader(
                             certificateFile,
                             StandardCharsets.UTF_8);

             PEMParser parser =
                     new PEMParser(reader)) {

            Object object;

            while ((object =
                    parser.readObject()) != null) {

                if (object instanceof
                        X509CertificateHolder) {

                    X509CertificateHolder holder =
                            (X509CertificateHolder) object;

                    X509Certificate certificate =
                            new JcaX509CertificateConverter()
                                    .setProvider("BC")
                                    .getCertificate(holder);

                    certificates.add(certificate);
                }
            }
        }

        if (certificates.isEmpty()) {

            throw new IllegalArgumentException(
                    "No X509 certificate found in: "
                            + certificateFile);
        }

        return certificates;
    }


    // ============================================================
    // READ PRIVATE KEY
    //
    // Supports:
    //
    // -----BEGIN PRIVATE KEY-----
    //
    // and
    //
    // -----BEGIN RSA PRIVATE KEY-----
    // ============================================================

    private static PrivateKey readPrivateKey(
            File keyFile)
            throws Exception {

        SecurityProviderInitializer.initialize();

        try (FileReader reader =
                     new FileReader(
                             keyFile,
                             StandardCharsets.UTF_8);

             PEMParser parser =
                     new PEMParser(reader)) {

            Object object =
                    parser.readObject();

            JcaPEMKeyConverter converter =
                    new JcaPEMKeyConverter()
                            .setProvider("BC");

            if (object instanceof PEMKeyPair) {

                return converter
                        .getKeyPair(
                                (PEMKeyPair) object)
                        .getPrivate();
            }

            if (object instanceof PrivateKeyInfo) {

                return converter
                        .getPrivateKey(
                                (PrivateKeyInfo) object);
            }

            throw new IllegalArgumentException(
                    "Unsupported private key format: "
                            + keyFile);
        }
    }


    // ============================================================
    // BOUNCY CASTLE INITIALIZER
    // ============================================================

    private static class SecurityProviderInitializer {

        private static boolean initialized = false;

        private static synchronized void initialize() {

            if (!initialized) {

                if (java.security.Security
                        .getProvider("BC") == null) {

                    java.security.Security.addProvider(
                            new org.bouncycastle.jce.provider
                                    .BouncyCastleProvider());
                }

                initialized = true;
            }
        }
    }


    // ============================================================
    // LOGGING
    // ============================================================

    private static void configureLogging() {

        /*
         * Keep the demo output focused on application-level failover
         * status instead of noisy health-check WARN stack traces.
         * These properties must be set before the SLF4J SimpleLogger
         * initializes the relevant logger instances.
         */
        System.setProperty(
                "org.slf4j.simpleLogger.log.redis.clients.jedis.mcf",
                "error");

        System.setProperty(
                "org.slf4j.simpleLogger.log.io.github.resilience4j",
                "error");

        System.setProperty(
                "org.slf4j.simpleLogger.log.redis.clients.jedis",
                "warn");
    }


    // ============================================================
    // UTILS
    // ============================================================

    private static boolean isBlank(
            String value) {

        return value == null ||
                value.trim().isEmpty();
    }


    private static String getRootCauseMessage(
            Throwable throwable) {

        Throwable root =
                throwable;

        while (root.getCause() != null) {
            root = root.getCause();
        }

        String message =
                root.getMessage();

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
    // CONFIG CLASSES
    // ============================================================

    public static class MultiDbConfigFile {

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
