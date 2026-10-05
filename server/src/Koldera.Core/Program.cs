using Koldera.Core;
using Koldera.Core.Workers;
using Microsoft.Extensions.DependencyInjection.Extensions;

var builder = WebApplication.CreateBuilder(args);

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

builder.Services.AddSingleton(sp => new JobQueue(sp.GetRequiredService<CoreOptions>().QueueCapacity));
builder.Services.AddSingleton<JobRunner>();
builder.Services.AddHostedService(sp => sp.GetRequiredService<JobRunner>());
builder.Services.AddSingleton(sp => new RetentionService(sp.GetRequiredService<DataStore>(), sp.GetRequiredService<CoreOptions>().RetentionHours,
    sp.GetRequiredService<ILoggerFactory>().CreateLogger<RetentionService>()));
builder.Services.AddHostedService(sp => new RetentionWorker(sp.GetRequiredService<RetentionService>(), sp.GetRequiredService<TimeProvider>(),
    sp.GetRequiredService<ILoggerFactory>().CreateLogger<RetentionWorker>()));

builder.Services.AddSingleton(builder.Configuration.GetSection("Workers").Get<WorkerOptions>() ?? new WorkerOptions());
builder.Services.AddSingleton(sp =>
{
    var w = sp.GetRequiredService<WorkerOptions>();
    return new PdfWorkers(
        SubprocessWorker.ForPdfProcess(w.PdfCommand, w.VeraPdfCommand),
        w.PdfStructureCommand.Length > 0 ? SubprocessWorker.ForProbe(w.PdfStructureCommand) : null);
});
builder.Services.AddSingleton(sp =>
{
    var w = sp.GetRequiredService<WorkerOptions>();
    return new HtmlWorkers(SubprocessWorker.ForNodeVerb(w.HtmlRenderCommand, w.ContractsDir),
        SubprocessWorker.ForProbe(w.PdfInspectCommand),
        w.HtmlSemanticCommand.Length > 0 ? SubprocessWorker.ForNodeVerb(w.HtmlSemanticCommand, w.ContractsDir) : null);
});
builder.Services.AddSingleton(sp =>
{
    var w = sp.GetRequiredService<WorkerOptions>();
    return new WebWorkers(SubprocessWorker.ForWeb(w.WebCommand, w.ContractsDir, w.AllowLoopbackForTests));
});
builder.Services.AddSingleton(sp =>
{
    var w = sp.GetRequiredService<WorkerOptions>();
    return new DocumentWorkers(SubprocessWorker.ForNodeVerb(w.DocumentRenderCommand, w.ContractsDir));
});
builder.Services.AddSingleton<IJobExecutor>(sp => new DocumentExecutor(sp.GetRequiredService<DataStore>(), sp.GetRequiredService<DocumentWorkers>(),
    sp.GetRequiredService<PdfWorkers>(), sp.GetRequiredService<CoreOptions>(), sp.GetRequiredService<TimeProvider>()));
builder.Services.AddSingleton<IJobExecutor>(sp => new WebJobExecutor(sp.GetRequiredService<DataStore>(), sp.GetRequiredService<WebWorkers>(),
    sp.GetRequiredService<CoreOptions>(), sp.GetRequiredService<TimeProvider>()));
builder.Services.AddSingleton<IJobExecutor>(sp => new HtmlToPdfExecutor(sp.GetRequiredService<DataStore>(), sp.GetRequiredService<HtmlWorkers>(),
    sp.GetRequiredService<PdfWorkers>(), sp.GetRequiredService<CoreOptions>(), sp.GetRequiredService<TimeProvider>()));
builder.Services.AddSingleton<IJobExecutor>(sp => new PdfJobExecutor(sp.GetRequiredService<DataStore>(), sp.GetRequiredService<PdfWorkers>(),
    sp.GetRequiredService<CoreOptions>(), sp.GetRequiredService<TimeProvider>()));
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
app.Services.GetRequiredService<CoreOptions>().EnsureDataDirWritable();
app.Services.GetRequiredService<UrlSigner>(); // fails fast on a bad signing key
app.Services.GetRequiredService<ArtifactIndex>(); // rebuilds the artifact lookup from the data directory
app.Services.GetRequiredService<ServerTokens>(); // resolves (and, if needed, generates and logs) the tokens at startup

app.UseAuthentication();
app.UseAuthorization();

app.MapGet("/healthz", () => Results.Json(new { status = "ok" }));
// Every /v1 route requires authorization by default.
var v1 = app.MapGroup("/v1").RequireAuthorization();
v1.MapInfoEndpoints();
v1.MapUploadEndpoints();
v1.MapJobEndpoints();
v1.MapDocumentEndpoints();
v1.MapJobReadEndpoints();
v1.MapReadingOrderEndpoints();
app.Run();

public partial class Program;
