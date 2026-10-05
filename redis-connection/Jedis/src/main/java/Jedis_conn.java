import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.RedisClient;

public class Jedis_conn {

    private static final int DEFAULT_CONNECTION_TIMEOUT_MS = 5000;
    private static final int DEFAULT_SOCKET_TIMEOUT_MS = 5000;

    public static void main(String[] args) {
        try {
            String host = requireEnv("REDIS_HOST");

            int port = Integer.parseInt(requireEnv("REDIS_PORT"));

            String username = valueOrDefault(
                    "REDIS_USERNAME", null
            );

            String password = valueOrDefault(
                    "REDIS_PASSWORD",null
            );

            String caCertPath = valueOrDefault(
                    "REDIS_CA_CERT", null
            );

            String clientCertPath = valueOrDefault(
                    "REDIS_CLIENT_CERT",null
            );

            String clientKeyPath = valueOrDefault(
                    "REDIS_CLIENT_KEY", null
            );

            if ((clientCertPath == null) != (clientKeyPath == null)) {
                throw new IllegalArgumentException(
                        "REDIS_CLIENT_CERT and REDIS_CLIENT_KEY "
                                + "must be set together"
                );
            }

            if (caCertPath != null) {
                validateFile(caCertPath, "CA certificate");
            }
            if (clientCertPath != null) {
                validateFile(clientCertPath, "client certificate");
                validateFile(clientKeyPath, "client private key");
            }

            // TLS is on when REDIS_TLS=true, or when any cert is supplied
            String tlsSetting = valueOrDefault("REDIS_TLS", null);
            boolean useTls = tlsSetting != null
                    ? Boolean.parseBoolean(tlsSetting)
                    : (caCertPath != null || clientCertPath != null);

            DefaultJedisClientConfig.Builder configBuilder =
                    DefaultJedisClientConfig.builder()
                            .connectionTimeoutMillis(
                                    DEFAULT_CONNECTION_TIMEOUT_MS
                            )
                            .socketTimeoutMillis(
                                    DEFAULT_SOCKET_TIMEOUT_MS
                            );

            if (useTls) {
                SSLContext sslContext = buildSslContext(
                        caCertPath,
                        clientCertPath,
                        clientKeyPath
                );

                configBuilder.ssl(true)
                        .sslSocketFactory(sslContext.getSocketFactory());
            }

            if (username != null && !username.isBlank()) {
                configBuilder.user(username);
            }

            if (password != null && !password.isBlank()) {
                configBuilder.password(password);
            }

            HostAndPort endpoint = new HostAndPort(host, port);

            try (RedisClient jedis = RedisClient.builder()
                    .hostAndPort(endpoint)
                    .clientConfig(configBuilder.build())
                    .build()) {

                System.out.printf(
                        "Connecting to Redis at %s:%d...%n",
                        host,
                        port
                );

                System.out.println("PING => " + jedis.ping());

                String key = "demo_key";
                String value = "hello-from-java";

                String setResult = jedis.set(key, value);

                System.out.println(
                        "SET " + key + " => " + setResult
                );

                String retrievedValue = jedis.get(key);

                System.out.println(
                        "GET " + key + " => " + retrievedValue
                );
            }

        } catch (NumberFormatException e) {
            System.err.println(
                    "Invalid REDIS_PORT value: " + e.getMessage()
            );
            System.exit(1);

        } catch (Exception e) {
            System.err.println(
                    "Redis connection failed: " + e.getMessage()
            );
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static String valueOrDefault(
            String environmentVariable,
            String defaultValue
    ) {
        String value = System.getenv(environmentVariable);

        if (value == null || value.isBlank()) {
            return defaultValue;
        }

        return value;
    }

    private static String requireEnv(String environmentVariable) {
        String value = valueOrDefault(environmentVariable, null);

        if (value == null) {
            throw new IllegalArgumentException(
                    environmentVariable + " must be set"
            );
        }

        return value;
    }

    private static void validateFile(
            String filename,
            String description
    ) {
        Path path = Path.of(filename);

        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException(
                    description + " was not found: "
                            + path.toAbsolutePath()
            );
        }

        if (!Files.isReadable(path)) {
            throw new IllegalArgumentException(
                    description + " is not readable: "
                            + path.toAbsolutePath()
            );
        }
    }

    private static SSLContext buildSslContext(
            String caCertPath,
            String clientCertPath,
            String clientKeyPath
    ) throws Exception {

        // null managers make SSLContext fall back to the JVM defaults
        KeyManager[] keyManagers = null;
        TrustManager[] trustManagers = null;

        if (clientCertPath != null) {
            Path temporaryPkcs12 =
                    Files.createTempFile("redis-client-", ".p12");

            String pkcs12Password =
                    "temporary-redis-keystore-password";

            try {
                createPkcs12Keystore(
                        temporaryPkcs12,
                        pkcs12Password,
                        caCertPath,
                        clientCertPath,
                        clientKeyPath
                );

                KeyStore clientKeyStore =
                        KeyStore.getInstance("PKCS12");

                try (InputStream input =
                             Files.newInputStream(temporaryPkcs12)) {

                    clientKeyStore.load(
                            input,
                            pkcs12Password.toCharArray()
                    );
                }

                KeyManagerFactory keyManagerFactory =
                        KeyManagerFactory.getInstance(
                                KeyManagerFactory.getDefaultAlgorithm()
                        );

                keyManagerFactory.init(
                        clientKeyStore,
                        pkcs12Password.toCharArray()
                );

                keyManagers = keyManagerFactory.getKeyManagers();

            } finally {
                Files.deleteIfExists(temporaryPkcs12);
            }
        }

        if (caCertPath != null) {
            KeyStore trustStore =
                    KeyStore.getInstance(
                            KeyStore.getDefaultType()
                    );

            trustStore.load(null, null);

            CertificateFactory certificateFactory =
                    CertificateFactory.getInstance("X.509");

            int certificateCount = 0;

            try (InputStream input =
                         Files.newInputStream(Path.of(caCertPath))) {

                for (Certificate certificate :
                        certificateFactory.generateCertificates(input)) {

                    trustStore.setCertificateEntry(
                            "redis-ca-" + certificateCount,
                            certificate
                    );

                    certificateCount++;
                }
            }

            if (certificateCount == 0) {
                throw new IllegalStateException(
                        "No certificates were found in "
                                + caCertPath
                );
            }

            TrustManagerFactory trustManagerFactory =
                    TrustManagerFactory.getInstance(
                            TrustManagerFactory.getDefaultAlgorithm()
                    );

            trustManagerFactory.init(trustStore);

            trustManagers = trustManagerFactory.getTrustManagers();
        }

        SSLContext sslContext = SSLContext.getInstance("TLS");

        sslContext.init(keyManagers, trustManagers, new SecureRandom());

        return sslContext;
    }

    private static void createPkcs12Keystore(
            Path outputFile,
            String outputPassword,
            String caCertPath,
            String clientCertPath,
            String clientKeyPath
    ) throws Exception {

        List<String> command = new ArrayList<>(List.of(
                "openssl",
                "pkcs12",
                "-export",
                "-out", outputFile.toString(),
                "-inkey", clientKeyPath,
                "-in", clientCertPath,
                "-password", "pass:" + outputPassword
        ));

        if (caCertPath != null) {
            command.add("-certfile");
            command.add(caCertPath);
        }

        ProcessBuilder processBuilder = new ProcessBuilder(command);

        processBuilder.redirectErrorStream(true);

        Process process;

        try {
            process = processBuilder.start();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Unable to execute openssl. Verify that "
                            + "openssl is installed and available in PATH.",
                    e
            );
        }

        String processOutput =
                readFully(process.getInputStream());

        int exitCode = process.waitFor();

        if (exitCode != 0) {
            throw new IllegalStateException(
                    "openssl pkcs12 failed with exit code "
                            + exitCode
                            + System.lineSeparator()
                            + processOutput
            );
        }
    }

    private static String readFully(InputStream inputStream)
            throws Exception {

        StringBuilder output = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                        inputStream,
                        StandardCharsets.UTF_8
                )
        )) {
            String line;

            while ((line = reader.readLine()) != null) {
                output.append(line)
                        .append(System.lineSeparator());
            }
        }

        return output.toString();
    }
}