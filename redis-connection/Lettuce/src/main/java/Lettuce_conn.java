
import java.nio.file.Path;
import java.time.Duration;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SslOptions;
import io.lettuce.core.api.StatefulRedisConnection;

public class Lettuce_conn {

    private static final int DEFAULT_PORT = 443;

    public static void main(String[] args) {
        RedisClient client = null;

        try {
            // Required: host and port.
            String host = valueOrDefault("REDIS_HOST", null);
            if (host == null) {
                throw new IllegalArgumentException(
                        "Redis host is required (REDIS_HOST)");
            }
            int port = Integer.parseInt(
                    valueOrDefault("REDIS_PORT", String.valueOf(DEFAULT_PORT))
            );

            String username = valueOrDefault("REDIS_USERNAME", null);
            String password = valueOrDefault("REDIS_PASSWORD", null);

            // Optional: null when not configured.
            String caCertPath =
                    valueOrDefault("REDIS_CA_CERT", null);
            String clientCertPath =
                    valueOrDefault("REDIS_CLIENT_CERT", null);
            String clientKeyPath =
                    valueOrDefault("REDIS_CLIENT_KEY", null);

            /*
             * TLS is enabled only if at least one certificate path is set.
             * clientCertPath must contain an X.509 certificate chain in PEM.
             * clientKeyPath must contain a PKCS#8 private key in PEM.
             */
            boolean hasClientAuth = clientCertPath != null || clientKeyPath != null;
            if (hasClientAuth && (clientCertPath == null || clientKeyPath == null)) {
                throw new IllegalArgumentException(
                        "Client cert and client key must be provided together");
            }
            boolean useSsl = caCertPath != null || hasClientAuth;

            RedisURI.Builder uriBuilder = RedisURI.builder()
                    .withHost(host)
                    .withPort(port)
                    .withSsl(useSsl)
                    .withTimeout(Duration.ofSeconds(10));

            if (username != null && !username.isBlank()) {
                char[] passwordChars =
                        password == null ? new char[0] : password.toCharArray();

                uriBuilder.withAuthentication(username, passwordChars);
            } else if (password != null && !password.isBlank()) {
                uriBuilder.withPassword(password.toCharArray());
            }

            RedisURI redisURI = uriBuilder.build();

            client = RedisClient.create(redisURI);

            if (useSsl) {
                SslOptions.Builder sslBuilder = SslOptions.builder()
                        .jdkSslProvider();
                if (caCertPath != null) {
                    sslBuilder.trustManager(Path.of(caCertPath).toFile());
                }
                if (hasClientAuth) {
                    sslBuilder.keyManager(
                            Path.of(clientCertPath).toFile(),
                            Path.of(clientKeyPath).toFile(),
                            null
                    );
                }
                client.setOptions(ClientOptions.builder()
                        .sslOptions(sslBuilder.build())
                        .build());
            }

            try (StatefulRedisConnection<String, String> connection =
                         client.connect()) {

                var commands = connection.sync();

                System.out.printf(
                        "Pinging Redis at %s:%d...%n",
                        host,
                        port
                );
                System.out.println("PING => " + commands.ping());

                String key = "demo_key";
                String value = "hello-from-lettuce";

                System.out.println("SET => " + commands.set(key, value));
                System.out.println("GET " + key + " => " + commands.get(key));
            }

        } catch (Exception e) {
            System.err.println("Connection failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        } finally {
            if (client != null) {
                client.shutdown();
            }
        }
    }

    private static String valueOrDefault(
            String environmentVariable,
            String defaultValue
    ) {
        String value = System.getenv(environmentVariable);

        return value == null || value.isBlank()
                ? defaultValue
                : value;
    }
}