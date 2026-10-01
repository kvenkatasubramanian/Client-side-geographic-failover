
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SslOptions;
import io.lettuce.core.api.StatefulRedisConnection;

import java.nio.file.Path;
import java.time.Duration;

public class Lettuce_conn {

    private static final String DEFAULT_HOST =
            "demo-db.redis-enterprise.example.com";
    private static final int DEFAULT_PORT = 443;

    private static final String DEFAULT_CA_CERT =
            "certs/demo-ca.crt";
    private static final String DEFAULT_CLIENT_CERT =
            "certs/client.crt";
    private static final String DEFAULT_CLIENT_KEY =
            "certs/client.key";

    public static void main(String[] args) {
        RedisClient client = null;

        try {
            String host = valueOrDefault("REDIS_HOST", DEFAULT_HOST);
            int port = Integer.parseInt(
                    valueOrDefault(
                            "REDIS_PORT",
                            String.valueOf(DEFAULT_PORT)
                    )
            );

            String username = valueOrDefault("REDIS_USERNAME", null);
            String password = valueOrDefault("REDIS_PASSWORD", null);

            String caCertPath =
                    valueOrDefault("REDIS_CA_CERT", DEFAULT_CA_CERT);
            String clientCertPath =
                    valueOrDefault("REDIS_CLIENT_CERT", DEFAULT_CLIENT_CERT);
            String clientKeyPath =
                    valueOrDefault("REDIS_CLIENT_KEY", DEFAULT_CLIENT_KEY);

            /*
             * Configure TLS directly from PEM files.
             *
             * clientCertPath must contain an X.509 certificate chain in PEM.
             * clientKeyPath must contain a PKCS#8 private key in PEM.
             */
            SslOptions sslOptions = SslOptions.builder()
                    .jdkSslProvider()
                    .trustManager(Path.of(caCertPath).toFile())
                    .keyManager(
                            Path.of(clientCertPath).toFile(),
                            Path.of(clientKeyPath).toFile(),
                            null
                    )
                    .build();

            RedisURI.Builder uriBuilder = RedisURI.builder()
                    .withHost(host)
                    .withPort(port)
                    .withSsl(true)
                    .withTimeout(Duration.ofSeconds(10));

            if (username != null && !username.isBlank()) {
                char[] passwordChars =
                        password == null ? new char[0] : password.toCharArray();

                uriBuilder.withAuthentication(username, passwordChars);
            } else if (password != null && !password.isBlank()) {
                uriBuilder.withPassword(password.toCharArray());
            }

            RedisURI redisURI = uriBuilder.build();

            ClientOptions clientOptions = ClientOptions.builder()
                    .sslOptions(sslOptions)
                    .build();

            client = RedisClient.create(redisURI);
            client.setOptions(clientOptions);

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