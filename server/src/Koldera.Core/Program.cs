using Koldera.Core;
using Koldera.Core.Cli;
using Koldera.Core.Workers;
using Microsoft.Extensions.DependencyInjection.Extensions;

// `koldera <command>` runs the command-line tool; no arguments, `serve` or only host options (--urls ...) start the server.
if (!CliApp.StartsServer(args))
{
    using var cancel = new CancellationTokenSource();
    Console.CancelKeyPress += (_, e) => { e.Cancel = true; cancel.Cancel(); };
    var cliConfig = new ConfigurationBuilder().AddEnvironmentVariables().Build();
    var cliWorkers = (cliConfig.GetSection("Workers").Get<WorkerOptions>() ?? new WorkerOptions()).ApplyDefaults(Environment.GetEnvironmentVariable);
    return await CliApp.RunAsync(args, Console.Out, Console.Error, new ProcessWorkerFactory(cliWorkers), ct: cancel.Token);
}

var builder = WebApplication.CreateBuilder(args.Length > 0 && args[0] == "serve" ? args[1..] : args);

builder.Logging.ClearProviders().AddJsonConsole();
// Request logs carry the path with its query string, which may hold tokens or file names.
foreach (var category in new[] { "Microsoft.AspNetCore.Hosting.Diagnostics", "Microsoft.AspNetCore.Routing", "Microsoft.AspNetCore.HttpLogging" })
    builder.Logging.AddFilter(category, LogLevel.Warning);
builder.Services.AddSingleton(sp =>
{
    var config = sp.GetRequiredService<IConfiguration>();
    return CoreOptions.From(name => config[name]);
});

builder.Services.AddCoreAuthentication();
builder.Services.TryAddSingleton(TimeProvider.System);
builder.Services.AddSingleton(sp => new DataStore(sp.GetRequiredService<CoreOptions>().DataDir, sp.GetRequiredService<TimeProvider>()));
builder.Services.AddSingleton(sp => new UrlSigner(SigningKeys.Resolve(sp.GetRequiredService<CoreOptions>()), sp.GetRequiredService<TimeProvider>()));

builder.Services.AddSingleton(sp => new ClientSessionTokens(SigningKeys.Resolve(sp.GetRequiredService<CoreOptions>()), sp.GetRequiredService<TimeProvider>()));
builder.Services.AddSingleton<ITokenResolver>(sp => sp.GetRequiredService<ClientSessionTokens>());
builder.Services.AddSingleton(sp => new SessionBudgets(sp.GetRequiredService<TimeProvider>()));
builder.Services.AddSingleton(sp => new Idempotency(sp.GetRequiredService<TimeProvider>()));
builder.Services.AddSingleton(sp => new JobQueue(sp.GetRequiredService<CoreOptions>().QueueCapacity));
builder.Services.AddSingleton<JobRunner>();
builder.Services.AddHostedService(sp => sp.GetRequiredService<JobRunner>());
builder.Services.AddSingleton(sp => new RetentionService(sp.GetRequiredService<DataStore>(), sp.GetRequiredService<CoreOptions>().RetentionHours,
    sp.GetRequiredService<ILoggerFactory>().CreateLogger<RetentionService>()));
builder.Services.AddHostedService(sp => new RetentionWorker(sp.GetRequiredService<RetentionService>(), sp.GetRequiredService<TimeProvider>(),
    sp.GetRequiredService<ILoggerFactory>().CreateLogger<RetentionWorker>()));

// The "Workers" section wins; whatever it leaves empty comes from the simple KOLDERA_* variables, else the container layout.
builder.Services.AddSingleton((builder.Configuration.GetSection("Workers").Get<WorkerOptions>() ?? new WorkerOptions()).ApplyDefaults(Environment.GetEnvironmentVariable));
builder.Services.AddSingleton<IWorkerFactory>(sp => new ProcessWorkerFactory(sp.GetRequiredService<WorkerOptions>()));
builder.Services.AddSingleton(sp => sp.GetRequiredService<IWorkerFactory>().Pdf());
builder.Services.AddSingleton(sp => sp.GetRequiredService<IWorkerFactory>().Html());
builder.Services.AddSingleton(sp => sp.GetRequiredService<IWorkerFactory>().Web());
builder.Services.AddSingleton(sp => sp.GetRequiredService<IWorkerFactory>().Document());
builder.Services.AddExecutors();
builder.Services.AddSingleton(sp =>
{
    var w = sp.GetRequiredService<WorkerOptions>();
    return new OrderWorkers(SubprocessWorker.ForRequestResult(w.PdfReorderCommand), SubprocessWorker.ForNodeVerb(w.HtmlReorderCommand, w.ContractsDir));
});
builder.Services.AddSingleton<IJobExecutor>(sp => new OrderPdfExecutor(sp.GetRequiredService<DataStore>(), sp.GetRequiredService<PdfWorkers>(),
    sp.GetRequiredService<OrderWorkers>(), sp.GetRequiredService<CoreOptions>(), sp.GetRequiredService<TimeProvider>()));
builder.Services.AddSingleton<IJobExecutor>(sp => new OrderHtmlExecutor(sp.GetRequiredService<DataStore>(), sp.GetRequiredService<HtmlWorkers>(),
    sp.GetRequiredService<PdfWorkers>(), sp.GetRequiredService<OrderWorkers>(), sp.GetRequiredService<CoreOptions>(), sp.GetRequiredService<TimeProvider>()));
builder.Services.AddSingleton(sp => new ArtifactIndex(sp.GetRequiredService<DataStore>()));

var app = builder.Build();
app.Logger.LogInformation("koldera ready; {Report}", StartupReport.Describe(app.Services.GetRequiredService<CoreOptions>(), app.Services.GetRequiredService<WorkerOptions>()));
app.Services.GetRequiredService<CoreOptions>().EnsureDataDirWritable();
app.Services.GetRequiredService<UrlSigner>(); // fails fast on a bad signing key
app.Services.GetRequiredService<ArtifactIndex>(); // rebuilds the artifact lookup from the data directory
app.Services.GetRequiredService<ServerTokens>(); // resolves (and, if needed, generates and logs) the tokens at startup

// Host filter (KOLDERA_AUTH=none only), then CORS so preflights and error responses carry its headers; the policy and
// idempotency layers need the principal.
app.UseMiddleware<HostFilterMiddleware>();
app.UseMiddleware<CorsMiddleware>();
app.UseAuthentication();
app.UseAuthorization();
app.UseMiddleware<JsonContentTypeMiddleware>();
app.UseMiddleware<SessionPolicyMiddleware>();
app.UseMiddleware<IdempotencyMiddleware>();

app.MapGet("/healthz", () => Results.Json(new { status = "ok" }));
// Every /v1 route requires authorization by default.
var v1 = app.MapGroup("/v1").RequireAuthorization();
v1.MapInfoEndpoints();
v1.MapClientSessionEndpoints();
v1.MapUploadEndpoints();
v1.MapJobEndpoints();
v1.MapDocumentEndpoints();
v1.MapJobReadEndpoints();
v1.MapReadingOrderEndpoints();
app.Run();
return 0;

public partial class Program;
