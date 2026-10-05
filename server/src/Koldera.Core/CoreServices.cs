using Koldera.Core.Workers;
using Microsoft.Extensions.DependencyInjection;

namespace Koldera.Core;

/// <summary>Registrations shared by the server and the command-line tool.</summary>
public static class CoreServices
{
    /// <summary>
    /// The executors of the four job kinds a user can start. Needs <see cref="DataStore"/>, <see cref="CoreOptions"/>,
    /// <see cref="TimeProvider"/> and the four worker records in the container.
    /// </summary>
    public static IServiceCollection AddExecutors(this IServiceCollection services)
    {
        services.AddSingleton<IJobExecutor>(sp => new DocumentExecutor(sp.GetRequiredService<DataStore>(), sp.GetRequiredService<DocumentWorkers>(),
            sp.GetRequiredService<PdfWorkers>(), sp.GetRequiredService<CoreOptions>(), sp.GetRequiredService<TimeProvider>()));
        services.AddSingleton<IJobExecutor>(sp => new WebJobExecutor(sp.GetRequiredService<DataStore>(), sp.GetRequiredService<WebWorkers>(),
            sp.GetRequiredService<CoreOptions>(), sp.GetRequiredService<TimeProvider>()));
        services.AddSingleton<IJobExecutor>(sp => new HtmlToPdfExecutor(sp.GetRequiredService<DataStore>(), sp.GetRequiredService<HtmlWorkers>(),
            sp.GetRequiredService<PdfWorkers>(), sp.GetRequiredService<CoreOptions>(), sp.GetRequiredService<TimeProvider>()));
        services.AddSingleton<IJobExecutor>(sp => new PdfJobExecutor(sp.GetRequiredService<DataStore>(), sp.GetRequiredService<PdfWorkers>(),
            sp.GetRequiredService<CoreOptions>(), sp.GetRequiredService<TimeProvider>()));
        return services;
    }
}
