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

var app = builder.Build();
app.Services.GetRequiredService<CoreOptions>().EnsureDataDirWritable();

app.MapInfoEndpoints();
app.Run();

public partial class Program;
