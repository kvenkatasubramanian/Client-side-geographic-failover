import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.RedisClient;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
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

public class Jedis_conn {

    private static final String DEFAULT_HOST =
            "demo-db.redis-enterprise.example.com";

    private static final int DEFAULT_PORT = 443;

    private static final String DEFAULT_CA_CERT =
            "certs/demo-ca.crt";

    private static final String DEFAULT_CLIENT_CERT =
            "certs/client.crt";

    private static final String DEFAULT_CLIENT_KEY =
            "certs/client.key";

    private static final int DEFAULT_CONNECTION_TIMEOUT_MS = 5000;
    private static final int DEFAULT_SOCKET_TIMEOUT_MS = 5000;

    public static void main(String[] args) {
        try {
            String host = valueOrDefault(
                    "REDIS_HOST",
                    DEFAULT_HOST
            );

            int port = Integer.parseInt(
                    valueOrDefault(
                            "REDIS_PORT",
                            String.valueOf(DEFAULT_PORT)
                    )
            );

            String username = valueOrDefault(
                    "REDIS_USERNAME",
                    null
            );

            String password = valueOrDefault(
                    "REDIS_PASSWORD",
                    null
            );

            String caCertPath = valueOrDefault(
                    "REDIS_CA_CERT",
                    DEFAULT_CA_CERT
            );

            String clientCertPath = valueOrDefault(
                    "REDIS_CLIENT_CERT",
                    DEFAULT_CLIENT_CERT
            );

            String clientKeyPath = valueOrDefault(
                    "REDIS_CLIENT_KEY",
                    DEFAULT_CLIENT_KEY
            );

            validateFile(caCertPath, "CA certificate");
            validateFile(clientCertPath, "client certificate");
            validateFile(clientKeyPath, "client private key");

            SSLContext sslContext = buildSslContext(
                    caCertPath,
                    clientCertPath,
                    clientKeyPath
            );

            DefaultJedisClientConfig.Builder configBuilder =
                    DefaultJedisClientConfig.builder()
                            .ssl(true)
                            .sslSocketFactory(
                                    sslContext.getSocketFactory()
                            )
                            .connectionTimeoutMillis(
                                    DEFAULT_CONNECTION_TIMEOUT_MS
                            )
                            .socketTimeoutMillis(
                                    DEFAULT_SOCKET_TIMEOUT_MS
                            );

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

            KeyManagerFactory keyManagerFactory =
                    KeyManagerFactory.getInstance(
                            KeyManagerFactory.getDefaultAlgorithm()
                    );

            keyManagerFactory.init(
                    clientKeyStore,
                    pkcs12Password.toCharArray()
            );

            TrustManagerFactory trustManagerFactory =
                    TrustManagerFactory.getInstance(
                            TrustManagerFactory.getDefaultAlgorithm()
                    );

            trustManagerFactory.init(trustStore);

            SSLContext sslContext =
                    SSLContext.getInstance("TLS");

            sslContext.init(
                    keyManagerFactory.getKeyManagers(),
                    trustManagerFactory.getTrustManagers(),
                    new SecureRandom()
            );

            return sslContext;

        } finally {
            Files.deleteIfExists(temporaryPkcs12);
        }
    }

    private static void createPkcs12Keystore(
            Path outputFile,
            String outputPassword,
            String caCertPath,
            String clientCertPath,
            String clientKeyPath
    ) throws Exception {

        ProcessBuilder processBuilder = new ProcessBuilder(
                "openssl",
                "pkcs12",
                "-export",
                "-out", outputFile.toString(),
                "-inkey", clientKeyPath,
                "-in", clientCertPath,
                "-certfile", caCertPath,
                "-password", "pass:" + outputPassword
        );

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