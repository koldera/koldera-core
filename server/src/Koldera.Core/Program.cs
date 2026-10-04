using Koldera.Core;

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

var app = builder.Build();
app.Services.GetRequiredService<CoreOptions>().EnsureDataDirWritable();
app.Services.GetRequiredService<ServerTokens>(); // resolves (and, if needed, generates and logs) the tokens at startup

app.UseAuthentication();
app.UseAuthorization();

app.MapGet("/healthz", () => Results.Json(new { status = "ok" }));
// Every /v1 route requires authorization by default.
var v1 = app.MapGroup("/v1").RequireAuthorization();
v1.MapInfoEndpoints();
app.Run();

public partial class Program;
