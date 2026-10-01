using System.ComponentModel;
using System.Diagnostics;
using System.Net.Security;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using StackExchange.Redis;

internal static class Program
{
    private const string DefaultHost =
        "demo-db.redis-enterprise.example.com";

    private const int DefaultPort = 443;

    private const string DefaultCaCertificate =
        "certs/demo-ca.crt";

    private const string DefaultClientCertificate =
        "certs/client.crt";

    private const string DefaultClientPrivateKey =
        "certs/client.key";

    private const int DefaultConnectTimeoutMilliseconds = 5000;
    private const int DefaultOperationTimeoutMilliseconds = 5000;

    private static async Task<int> Main()
    {
        X509Certificate2Collection? trustedCaCertificates = null;

        try
        {
            string host = GetEnvironmentValue(
                "REDIS_HOST",
                DefaultHost
            )!;

            int port = int.Parse(
                GetEnvironmentValue(
                    "REDIS_PORT",
                    DefaultPort.ToString()
                )!
            );

            string? username = GetEnvironmentValue(
                "REDIS_USERNAME",
                null
            );

            string? password = GetEnvironmentValue(
                "REDIS_PASSWORD",
                null
            );

            string caCertificatePath = GetEnvironmentValue(
                "REDIS_CA_CERT",
                DefaultCaCertificate
            )!;

            string clientCertificatePath = GetEnvironmentValue(
                "REDIS_CLIENT_CERT",
                DefaultClientCertificate
            )!;

            string clientPrivateKeyPath = GetEnvironmentValue(
                "REDIS_CLIENT_KEY",
                DefaultClientPrivateKey
            )!;

            ValidateFile(
                caCertificatePath,
                "CA certificate"
            );

            ValidateFile(
                clientCertificatePath,
                "client certificate"
            );

            ValidateFile(
                clientPrivateKeyPath,
                "client private key"
            );

            trustedCaCertificates =
                LoadCaCertificates(caCertificatePath);

            /*
             * Convert the PEM client certificate and private key to
             * a temporary PKCS#12 file using openssl.
             *
             * This avoids the macOS/.NET 7 CreateFromPemFile
             * keychain issue.
             */
            using X509Certificate2 clientCertificate =
                await LoadClientCertificateAsync(
                    caCertificatePath,
                    clientCertificatePath,
                    clientPrivateKeyPath
                );

            ConfigurationOptions options = new()
            {
                Ssl = true,

                // Enforce this hostname against the server certificate.
                SslHost = host,

                AbortOnConnectFail = true,
                ConnectRetry = 3,

                ConnectTimeout =
                    DefaultConnectTimeoutMilliseconds,

                SyncTimeout =
                    DefaultOperationTimeoutMilliseconds,

                AsyncTimeout =
                    DefaultOperationTimeoutMilliseconds,

                KeepAlive = 60
            };

            options.EndPoints.Add(host, port);

            if (!string.IsNullOrWhiteSpace(username))
            {
                options.User = username;
            }

            if (!string.IsNullOrWhiteSpace(password))
            {
                options.Password = password;
            }

            /*
             * Supply the client certificate for mutual TLS.
             *
             * clientCertificate remains alive until the
             * ConnectionMultiplexer is disposed.
             */
            options.CertificateSelection +=
                (_, _, _, _, _) => clientCertificate;

            /*
             * Validate the Redis server certificate against the
             * configured CA certificate.
             */
            options.CertificateValidation +=
                (_, certificate, chain, policyErrors) =>
                    ValidateServerCertificate(
                        certificate,
                        chain,
                        policyErrors,
                        trustedCaCertificates
                    );

            Console.WriteLine(
                $"Connecting to Redis at {host}:{port}..."
            );

            using ConnectionMultiplexer redis =
                await ConnectionMultiplexer.ConnectAsync(options);

            IDatabase database = redis.GetDatabase();

            TimeSpan pingTime = await database.PingAsync();

            Console.WriteLine(
                $"PING => PONG ({pingTime.TotalMilliseconds:F2} ms)"
            );

            const string key = "demo_key";
            const string value = "hello-from-csharp";

            bool setResult = await database.StringSetAsync(
                key,
                value
            );

            Console.WriteLine(
                $"SET {key} => {(setResult ? "OK" : "FAILED")}"
            );

            RedisValue retrievedValue =
                await database.StringGetAsync(key);

            Console.WriteLine(
                $"GET {key} => {retrievedValue}"
            );

            return 0;
        }
        catch (FormatException exception)
        {
            Console.Error.WriteLine(
                $"Invalid REDIS_PORT value: {exception.Message}"
            );

            return 1;
        }
        catch (RedisConnectionException exception)
        {
            Console.Error.WriteLine(
                $"Redis connection failed: {exception.Message}"
            );

            Console.Error.WriteLine(exception);

            return 1;
        }
        catch (Exception exception)
        {
            Console.Error.WriteLine(
                $"Application failed: {exception.Message}"
            );

            Console.Error.WriteLine(exception);

            return 1;
        }
        finally
        {
            if (trustedCaCertificates is not null)
            {
                foreach (
                    X509Certificate2 certificate
                    in trustedCaCertificates
                )
                {
                    certificate.Dispose();
                }
            }
        }
    }

    private static async Task<X509Certificate2>
        LoadClientCertificateAsync(
            string caCertificatePath,
            string clientCertificatePath,
            string clientPrivateKeyPath
        )
    {
        string temporaryPkcs12Path = Path.Combine(
            Path.GetTempPath(),
            $"redis-client-{Guid.NewGuid():N}.p12"
        );

        string temporaryPassword = Convert.ToBase64String(
            RandomNumberGenerator.GetBytes(32)
        );

        try
        {
            ProcessStartInfo startInfo = new()
            {
                FileName = "openssl",
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                RedirectStandardInput = true,
                UseShellExecute = false,
                CreateNoWindow = true
            };

            startInfo.ArgumentList.Add("pkcs12");
            startInfo.ArgumentList.Add("-export");

            startInfo.ArgumentList.Add("-out");
            startInfo.ArgumentList.Add(temporaryPkcs12Path);

            startInfo.ArgumentList.Add("-inkey");
            startInfo.ArgumentList.Add(clientPrivateKeyPath);

            startInfo.ArgumentList.Add("-in");
            startInfo.ArgumentList.Add(clientCertificatePath);

            startInfo.ArgumentList.Add("-certfile");
            startInfo.ArgumentList.Add(caCertificatePath);

            startInfo.ArgumentList.Add("-passout");
            startInfo.ArgumentList.Add(
                $"pass:{temporaryPassword}"
            );

            using Process process =
                Process.Start(startInfo)
                ?? throw new InvalidOperationException(
                    "Unable to start openssl."
                );

            /*
             * Prevent openssl from waiting indefinitely for an
             * interactive password if the private key is encrypted.
             */
            process.StandardInput.Close();

            Task<string> standardOutputTask =
                process.StandardOutput.ReadToEndAsync();

            Task<string> standardErrorTask =
                process.StandardError.ReadToEndAsync();

            await process.WaitForExitAsync();

            string standardOutput =
                await standardOutputTask;

            string standardError =
                await standardErrorTask;

            if (process.ExitCode != 0)
            {
                throw new InvalidOperationException(
                    "openssl pkcs12 failed with exit code "
                    + process.ExitCode
                    + Environment.NewLine
                    + standardError
                    + standardOutput
                );
            }

            X509Certificate2 certificate = new(
                temporaryPkcs12Path,
                temporaryPassword,
                X509KeyStorageFlags.DefaultKeySet
            );

            if (!certificate.HasPrivateKey)
            {
                certificate.Dispose();

                throw new InvalidOperationException(
                    "The generated client certificate does not "
                    + "contain a private key."
                );
            }

            return certificate;
        }
        catch (Win32Exception exception)
        {
            throw new InvalidOperationException(
                "Unable to execute openssl. Verify that openssl "
                + "is installed and available in PATH.",
                exception
            );
        }
        finally
        {
            if (File.Exists(temporaryPkcs12Path))
            {
                File.Delete(temporaryPkcs12Path);
            }
        }
    }

    private static bool ValidateServerCertificate(
        X509Certificate? certificate,
        X509Chain? suppliedChain,
        SslPolicyErrors policyErrors,
        X509Certificate2Collection trustedCaCertificates
    )
    {
        if (certificate is null)
        {
            Console.Error.WriteLine(
                "TLS validation failed: the server did not "
                + "provide a certificate."
            );

            return false;
        }

        if (
            policyErrors.HasFlag(
                SslPolicyErrors.RemoteCertificateNameMismatch
            )
        )
        {
            Console.Error.WriteLine(
                "TLS validation failed: server certificate "
                + "hostname mismatch."
            );

            return false;
        }

        if (
            policyErrors.HasFlag(
                SslPolicyErrors.RemoteCertificateNotAvailable
            )
        )
        {
            Console.Error.WriteLine(
                "TLS validation failed: server certificate "
                + "is unavailable."
            );

            return false;
        }

        using X509Certificate2 serverCertificate =
            new(certificate);

        using X509Chain customChain = new();

        customChain.ChainPolicy.TrustMode =
            X509ChainTrustMode.CustomRootTrust;

        customChain.ChainPolicy.CustomTrustStore.AddRange(
            trustedCaCertificates
        );

        /*
         * Private/internal CAs commonly do not expose an online
         * certificate revocation endpoint.
         */
        customChain.ChainPolicy.RevocationMode =
            X509RevocationMode.NoCheck;

        customChain.ChainPolicy.VerificationFlags =
            X509VerificationFlags.NoFlag;

        /*
         * Add intermediate certificates supplied by the server.
         */
        if (suppliedChain is not null)
        {
            foreach (
                X509ChainElement element
                in suppliedChain.ChainElements
            )
            {
                if (
                    !string.Equals(
                        element.Certificate.Thumbprint,
                        serverCertificate.Thumbprint,
                        StringComparison.OrdinalIgnoreCase
                    )
                )
                {
                    customChain.ChainPolicy.ExtraStore.Add(
                        element.Certificate
                    );
                }
            }
        }

        bool valid = customChain.Build(serverCertificate);

        if (!valid)
        {
            Console.Error.WriteLine(
                "TLS validation failed: the server certificate "
                + "was not signed by the configured CA."
            );

            foreach (
                X509ChainStatus status
                in customChain.ChainStatus
            )
            {
                Console.Error.WriteLine(
                    $"  {status.Status}: "
                    + status.StatusInformation.Trim()
                );
            }
        }

        return valid;
    }

    private static X509Certificate2Collection
        LoadCaCertificates(
            string caCertificatePath
        )
    {
        X509Certificate2Collection certificates = new();

        certificates.ImportFromPemFile(
            caCertificatePath
        );

        if (certificates.Count == 0)
        {
            throw new InvalidOperationException(
                $"No certificates were found in "
                + $"{caCertificatePath}."
            );
        }

        return certificates;
    }

    private static string? GetEnvironmentValue(
        string variableName,
        string? defaultValue
    )
    {
        string? value =
            Environment.GetEnvironmentVariable(variableName);

        return string.IsNullOrWhiteSpace(value)
            ? defaultValue
            : value;
    }

    private static void ValidateFile(
        string filename,
        string description
    )
    {
        string absolutePath =
            Path.GetFullPath(filename);

        if (!File.Exists(absolutePath))
        {
            throw new FileNotFoundException(
                $"{description} was not found.",
                absolutePath
            );
        }
    }
}