using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.Extensions.Configuration;

namespace Koldera.Core.Tests.Support;

public sealed class CoreFactory : WebApplicationFactory<Program>
{
    private readonly Dictionary<string, string?> _env;
    private readonly string? _tempDir;

    private CoreFactory(Dictionary<string, string?> env, string? tempDir)
    {
        _env = env;
        _tempDir = tempDir;
    }

    public static CoreFactory Create(Dictionary<string, string?> env)
    {
        var values = new Dictionary<string, string?>(env);
        string? temp = null;
        if (!values.ContainsKey("KOLDERA_DATA_DIR"))
        {
            temp = Path.Combine(Path.GetTempPath(), "koldera-test-" + Guid.NewGuid().ToString("N"));
            values["KOLDERA_DATA_DIR"] = temp;
        }
        return new CoreFactory(values, temp);
    }

    protected override void ConfigureWebHost(IWebHostBuilder builder) =>
        builder.ConfigureAppConfiguration((_, config) => config.AddInMemoryCollection(_env));

    protected override void Dispose(bool disposing)
    {
        base.Dispose(disposing);
        if (disposing && _tempDir is not null && Directory.Exists(_tempDir))
            try { Directory.Delete(_tempDir, true); } catch (IOException) { }
    }
}
