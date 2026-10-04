using Koldera.Core;
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

var app = builder.Build();
app.Services.GetRequiredService<CoreOptions>().EnsureDataDirWritable();
app.Services.GetRequiredService<UrlSigner>(); // fails fast on a bad signing key
app.Services.GetRequiredService<ServerTokens>(); // resolves (and, if needed, generates and logs) the tokens at startup

app.UseAuthentication();
app.UseAuthorization();

app.MapGet("/healthz", () => Results.Json(new { status = "ok" }));
// Every /v1 route requires authorization by default.
var v1 = app.MapGroup("/v1").RequireAuthorization();
v1.MapInfoEndpoints();
v1.MapUploadEndpoints();
app.Run();

public partial class Program;
