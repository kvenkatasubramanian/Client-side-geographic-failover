#pragma warning disable SER007

using System.Text.Json;
using System.Text.Json.Serialization;
using StackExchange.Redis;
using StackExchange.Redis.Availability;

var configFile = args.Length == 0 ? "redis-config.json" : args[0];

if (args.Length > 1)
{
    Console.Error.WriteLine("Usage: dotnet run -- [config-file]");
    return 1;
}

AppConfig config;

try
{
    config = await LoadConfigAsync(configFile);
    ValidateConfiguration(config);
}
catch (Exception ex)
{
    Console.Error.WriteLine();
    Console.Error.WriteLine("INVALID CONFIGURATION");
    Console.Error.WriteLine("----------------------------------------------");
    Console.Error.WriteLine(GetRootCauseMessage(ex));
    Console.Error.WriteLine("----------------------------------------------");
    return 1;
}

if (!await ValidateAllEndpointsAsync(config))
{
    Console.Error.WriteLine();
    Console.Error.WriteLine("==============================================");
    Console.Error.WriteLine("STARTUP ABORTED");
    Console.Error.WriteLine("==============================================");
    Console.Error.WriteLine("One or more Redis endpoints are unavailable.");
    Console.Error.WriteLine("Application will NOT start.");
    Console.Error.WriteLine();
    return 1;
}

ConnectionGroupMember[] members = config.Databases
    .Select(endpoint => new ConnectionGroupMember(CreateConfigurationOptions(config.Tls, endpoint), endpoint.Name)
    {
        Weight = endpoint.Weight
    })
    .ToArray();

var groupOptions = new MultiGroupOptions.Builder
{
    HealthCheck = new HealthCheck.Builder
    {
        ProbeCount = 3,
        ProbeTimeout = TimeSpan.FromSeconds(3),
        ProbeInterval = TimeSpan.FromMilliseconds(500),
        Probe = HealthCheckProbe.Ping,
        ProbePolicy = HealthCheckProbePolicy.AllSuccess
    },
    CircuitBreaker = new CircuitBreaker.Builder
    {
        FailureRateThreshold = 50,
        MinimumNumberOfFailures = 1,
        MetricsWindowSize = TimeSpan.FromSeconds(2)
    },
    RetryPolicy = new RetryPolicy.Builder
    {
        MaxAttempts = 3,
        MaxAttemptsBeforeFailover = 1,
        RetryDelay = TimeSpan.FromMilliseconds(500),
        FailoverDelay = TimeSpan.FromSeconds(2)
    },
    HealthCheckInterval = TimeSpan.FromSeconds(5),
    FailbackDelay = TimeSpan.FromSeconds(10)
};

await using IConnectionGroup connection =
    await ConnectionMultiplexer.ConnectGroupAsync(members, groupOptions);

connection.ConnectionChanged += (_, eventArgs) =>
{
    if (eventArgs.Type != GroupConnectionChangedEventArgs.ChangeType.ActiveChanged)
    {
        return;
    }

    Console.WriteLine();
    Console.WriteLine("=================================================");
    Console.WriteLine("DATABASE SWITCH DETECTED");
    Console.WriteLine($"Time      : {DateTime.Now:O}");
    Console.WriteLine($"Switched  : {eventArgs.PreviousGroup?.Name ?? "none"} -> {eventArgs.Group.Name}");
    Console.WriteLine("Reason    : Active member changed");
    Console.WriteLine();
    Console.WriteLine("Traffic switched automatically to healthy database endpoint.");
    Console.WriteLine("Application continues running.");
    Console.WriteLine("=================================================");
    Console.WriteLine();
};

Console.WriteLine();
Console.WriteLine("==============================================");
Console.WriteLine("APPLICATION STARTED");
Console.WriteLine("==============================================");
Console.WriteLine($"Configured Databases: {config.Databases.Count}");
Console.WriteLine($"Current Database: {connection.ActiveMember?.Name ?? "unknown"}");
Console.WriteLine($"TLS Enabled: {config.Tls.IsEnabled}");
Console.WriteLine("==============================================");
Console.WriteLine();

var multiplexer = (IConnectionMultiplexer)connection;
await RunApplicationLoopAsync(multiplexer.GetDatabase().WithRetry(), connection);
return 0;

static async Task<AppConfig> LoadConfigAsync(string configFile)
{
    await using FileStream stream = File.OpenRead(configFile);

    var options = new JsonSerializerOptions
    {
        PropertyNameCaseInsensitive = true,
        ReadCommentHandling = JsonCommentHandling.Skip,
        AllowTrailingCommas = true
    };

    return await JsonSerializer.DeserializeAsync<AppConfig>(stream, options)
        ?? throw new InvalidOperationException("Configuration is null");
}

static void ValidateConfiguration(AppConfig config)
{
    if (config.Tls.IsEnabled)
    {
        if (IsBlank(config.Tls.RootCa))
        {
            throw new InvalidOperationException("tls.rootCa is required when TLS is enabled");
        }
        if (IsBlank(config.Tls.ClientCrt))
        {
            throw new InvalidOperationException("tls.clientCrt is required when TLS is enabled");
        }
        if (IsBlank(config.Tls.ClientKey))
        {
            throw new InvalidOperationException("tls.clientKey is required when TLS is enabled");
        }
    }

    if (config.Databases.Count == 0)
    {
        throw new InvalidOperationException("At least one database must be configured");
    }

    for (int i = 0; i < config.Databases.Count; i++)
    {
        RedisEndpoint endpoint = config.Databases[i];

        if (IsBlank(endpoint.Name))
        {
            throw new InvalidOperationException($"Database entry {i}: name is required");
        }
        if (IsBlank(endpoint.Host))
        {
            throw new InvalidOperationException($"Database {endpoint.Name}: host is required");
        }
        if (endpoint.Port <= 0 || endpoint.Port > 65535)
        {
            throw new InvalidOperationException($"Database {endpoint.Name}: invalid port {endpoint.Port}");
        }
        if (endpoint.Weight <= 0)
        {
            throw new InvalidOperationException($"Database {endpoint.Name}: weight must be > 0");
        }
    }
}

static async Task<bool> ValidateAllEndpointsAsync(AppConfig config)
{
    bool allHealthy = true;

    Console.WriteLine();
    Console.WriteLine("==============================================");
    Console.WriteLine("REDIS PRE-FLIGHT CHECK");
    Console.WriteLine("==============================================");
    Console.WriteLine($"Checking {config.Databases.Count} configured endpoint(s)");
    Console.WriteLine();

    foreach (RedisEndpoint endpoint in config.Databases)
    {
        if (!await CheckEndpointAsync(config.Tls, endpoint))
        {
            allHealthy = false;
        }

        Console.WriteLine();
    }

    Console.WriteLine("==============================================");
    Console.WriteLine(allHealthy
        ? "PRE-FLIGHT RESULT: ALL ENDPOINTS HEALTHY"
        : "PRE-FLIGHT RESULT: FAILURE");
    Console.WriteLine("==============================================");

    return allHealthy;
}

static async Task<bool> CheckEndpointAsync(TlsConfig tls, RedisEndpoint endpoint)
{
    Console.WriteLine($"Checking: {endpoint.Name} [{endpoint.Host}:{endpoint.Port}]");

    try
    {
        await using ConnectionMultiplexer connection =
            await ConnectionMultiplexer.ConnectAsync(CreateConfigurationOptions(tls, endpoint));

        TimeSpan elapsed = await connection.GetDatabase().PingAsync();

        Console.WriteLine("  STATUS : OK");
        Console.WriteLine("  PING   : PONG");
        Console.WriteLine($"  TIME   : {(long)elapsed.TotalMilliseconds} ms");

        return true;
    }
    catch (Exception ex)
    {
        Console.WriteLine("  STATUS : FAILED");
        Console.WriteLine($"  REASON : {GetRootCauseMessage(ex)}");
        return false;
    }
}

static ConfigurationOptions CreateConfigurationOptions(TlsConfig tls, RedisEndpoint endpoint)
{
    var options = new ConfigurationOptions
    {
        User = BlankToNull(endpoint.Username),
        Password = BlankToNull(endpoint.Password),
        AbortOnConnectFail = false,
        ConnectTimeout = 10_000,
        SyncTimeout = 10_000,
        AsyncTimeout = 10_000,
        Ssl = tls.IsEnabled
    };

    options.EndPoints.Add(endpoint.Host, endpoint.Port);

    if (tls.IsEnabled)
    {
        options.SslHost = endpoint.Host;

        if (!IsBlank(tls.RootCa))
        {
            options.TrustIssuer(tls.RootCa!);
        }

        if (!IsBlank(tls.ClientCrt) && !IsBlank(tls.ClientKey))
        {
            options.SetUserPemCertificate(
                userCertificatePath: tls.ClientCrt!,
                userKeyPath: tls.ClientKey);
        }
    }

    return options;
}

static async Task RunApplicationLoopAsync(
    IDatabaseAsync database,
    IConnectionGroup connection)
{
    Console.WriteLine("Press Ctrl+C to stop.");
    Console.WriteLine();

    long counter = 0;
    string lastEndpoint = "";

    while (true)
    {
        try
        {
            RedisKey key = "failover:test";
            RedisValue value = $"value-{counter}";

            await database.StringSetAsync(key, value);
            RedisValue result = await database.StringGetAsync(key);
            string currentEndpoint = connection.ActiveMember?.Name ?? "unknown";

            if (currentEndpoint != lastEndpoint)
            {
                lastEndpoint = currentEndpoint;

                Console.WriteLine("---------------------------------------------");
                Console.WriteLine($"ACTIVE DATABASE : {currentEndpoint}");
                Console.WriteLine("---------------------------------------------");
            }

            Console.WriteLine($"{DateTime.Now:O} | {counter} | {currentEndpoint} | {result}");
            counter++;
        }
        catch (Exception ex)
        {
            Console.WriteLine();
            Console.WriteLine("*********** OPERATION FAILED ***********");
            Console.WriteLine($"{DateTime.Now:O}");
            Console.WriteLine($"Reason: {GetRootCauseMessage(ex)}");
            Console.WriteLine("***************************************");
            Console.WriteLine();
        }

        await Task.Delay(TimeSpan.FromSeconds(1));
    }
}

static string? BlankToNull(string? value)
{
    return IsBlank(value) ? null : value;
}

static bool IsBlank(string? value)
{
    return string.IsNullOrWhiteSpace(value);
}

static string GetRootCauseMessage(Exception exception)
{
    Exception root = exception;

    while (root.InnerException is not null)
    {
        root = root.InnerException;
    }

    return $"{root.GetType().Name}: {root.Message}";
}

sealed class AppConfig
{
    [JsonPropertyName("tls")]
    public TlsConfig Tls { get; set; } = new();

    [JsonPropertyName("databases")]
    public List<RedisEndpoint> Databases { get; set; } = [];
}

sealed class TlsConfig
{
    [JsonPropertyName("enabled")]
    public bool? Enabled { get; set; }

    [JsonPropertyName("rootCa")]
    public string? RootCa { get; set; }

    [JsonPropertyName("clientCrt")]
    public string? ClientCrt { get; set; }

    [JsonPropertyName("clientKey")]
    public string? ClientKey { get; set; }

    [JsonIgnore]
    public bool IsEnabled => Enabled ?? true;
}

sealed class RedisEndpoint
{
    [JsonPropertyName("name")]
    public string Name { get; set; } = "";

    [JsonPropertyName("host")]
    public string Host { get; set; } = "";

    [JsonPropertyName("port")]
    public int Port { get; set; }

    [JsonPropertyName("username")]
    public string? Username { get; set; }

    [JsonPropertyName("password")]
    public string? Password { get; set; }

    [JsonPropertyName("weight")]
    public double Weight { get; set; }
}
