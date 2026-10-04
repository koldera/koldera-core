using Koldera.Core;

var builder = WebApplication.CreateBuilder(args);

builder.Logging.ClearProviders().AddJsonConsole();
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
