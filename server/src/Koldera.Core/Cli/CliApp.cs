using System.Reflection;
using System.Security.Cryptography;
using System.Text.Json;
using System.Text.Json.Nodes;
using Koldera.Core.Documents;
using Koldera.Core.Workers;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging;

namespace Koldera.Core.Cli;

/// <summary>
/// The command-line tool: <c>pdf</c>, <c>html</c>, <c>scan</c> and <c>document</c> run one job in this process
/// (no HTTP server, no authentication) against a temporary data directory that is always removed, and write
/// the artifacts to <c>--out</c>. The input is never modified.
/// </summary>
public static class CliApp
{
    private const string CliOwner = "cli";
    private const int Interrupted = 130;

    /// <summary>No arguments, <c>serve</c> or only host options (<c>--urls</c> and the like) start the server; anything else is a CLI invocation.</summary>
    public static bool StartsServer(string[] args) =>
        args.Length == 0 || args[0] == "serve" || (args[0].StartsWith("--") && args[0] is not ("--help" or "--version"));

    /// <param name="env">Environment lookup for KOLDERA_* settings (default: the process environment).</param>
    /// <param name="tempRoot">Where the temporary data directory is created (default: the system temp directory).</param>
    public static async Task<int> RunAsync(string[] args, TextWriter stdout, TextWriter stderr, IWorkerFactory workers,
        Func<string, string?>? env = null, string? tempRoot = null, CancellationToken ct = default)
    {
        if (args.Length == 0 || args[0] is "--help" or "-h" or "help")
        {
            await stdout.WriteLineAsync(CliOptions.HelpOf(""));
            return Summary.Ok;
        }
        if (args[0] == "--version")
        {
            var version = typeof(CliApp).Assembly.GetCustomAttribute<AssemblyInformationalVersionAttribute>()?.InformationalVersion.Split('+')[0] ?? "unknown";
            await stdout.WriteLineAsync("koldera-core " + version);
            return Summary.Ok;
        }
        if (!CliOptions.Commands.Contains(args[0]))
            return await UsageError(stderr, args[0] == "serve" ? "serve starts the HTTP server and takes no job" : $"unknown command: {args[0]}", "");
        if (args.Skip(1).Any(a => a is "--help" or "-h"))
        {
            await stdout.WriteLineAsync(CliOptions.HelpOf(args[0]));
            return Summary.Ok;
        }
        var command = CliOptions.Parse(args, out var parseError);
        if (command is null) return await UsageError(stderr, parseError!, args[0]);

        env ??= Environment.GetEnvironmentVariable;
        CoreOptions options;
        try
        {
            options = CoreOptions.From(env);
        }
        catch (InvalidOperationException e)
        {
            return await UsageError(stderr, "invalid configuration: " + e.Message, command.Name);
        }

        var tmp = Path.Combine(tempRoot ?? Path.GetTempPath(), "koldera-cli-" + Guid.NewGuid().ToString("N"));
        try
        {
            Directory.CreateDirectory(tmp);
            // One job, one loop; the data directory is the throwaway one so scratch files go there too.
            options = options with { DataDir = tmp, Concurrency = 1, QueueCapacity = 16 };
            return await RunJobAsync(command, options, workers, tmp, stdout, stderr, ct);
        }
        catch (OperationCanceledException)
        {
            await stderr.WriteLineAsync("koldera: interrupted");
            return Interrupted;
        }
        finally
        {
            try { if (Directory.Exists(tmp)) Directory.Delete(tmp, true); }
            catch (Exception e) when (e is IOException or UnauthorizedAccessException)
            {
                stderr.WriteLine("koldera: could not remove the temporary directory " + tmp);
            }
        }
    }

    private static async Task<int> RunJobAsync(CliCommand command, CoreOptions options, IWorkerFactory workers, string tmp, TextWriter stdout, TextWriter stderr, CancellationToken ct)
    {
        var clock = TimeProvider.System;
        var store = new DataStore(tmp, clock);
        var prepared = await PrepareAsync(command, store, options, clock, stderr, ct);
        if (prepared is null) return Summary.Usage;
        var (job, stem) = prepared.Value;

        var services = new ServiceCollection()
            .AddSingleton(clock).AddSingleton(options).AddSingleton(store)
            .AddSingleton(workers.Pdf()).AddSingleton(workers.Html()).AddSingleton(workers.Web()).AddSingleton(workers.Document())
            .AddExecutors();
        await using var provider = services.BuildServiceProvider();
        var queue = new JobQueue(options.QueueCapacity);
        var runner = new JobRunner(store, queue, provider.GetServices<IJobExecutor>(), options, clock, new StderrLogger<JobRunner>(stderr));
        await runner.StartAsync(ct);
        try
        {
            queue.TryEnqueue(job.Id);
            // Only a terminal job counts: false means the run ended without a stored result.
            if (!await runner.WaitForCompletionAsync(job.Id, Timeout.InfiniteTimeSpan, ct))
            {
                await stderr.WriteLineAsync("koldera: the job did not finish");
                return Summary.JobFailed;
            }
        }
        finally
        {
            await runner.StopAsync(CancellationToken.None);
        }

        var finished = store.GetJob(job.Id) ?? throw new InvalidOperationException("the finished job is missing");
        List<WrittenArtifact> written;
        try
        {
            written = await WriteArtifactsAsync(finished, store, command.OutDir ?? Directory.GetCurrentDirectory(), stem, ct);
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            await stderr.WriteLineAsync("koldera: could not write the output: " + e.Message);
            return Summary.JobFailed;
        }
        await stdout.WriteAsync(command.Json ? Summary.Json(finished, written) + "\n" : Summary.Text(finished, written));
        return Summary.ExitCode(finished);
    }

    // ---- input ----

    /// <summary>Validates the input exactly as the endpoints do and stores it as a completed upload; null (after printing why) on a usage error.</summary>
    private static async Task<(CoreJob Job, string Stem)?> PrepareAsync(CliCommand c, DataStore store, CoreOptions options, TimeProvider clock, TextWriter stderr, CancellationToken ct)
    {
        var now = clock.GetUtcNow();
        async Task<(CoreJob, string)?> Refuse(string message)
        {
            await stderr.WriteLineAsync($"koldera: {message}");
            await stderr.WriteLineAsync(CliOptions.UsageOf(c.Name));
            return null;
        }

        if (c.Name == "scan")
        {
            // Syntax only, like the server; the scanner repeats the checks with live DNS. The reason never repeats the URL.
            var reason = ScanTargetPolicy.Reject(c.Target, options.WebAllowHttp, out var target);
            if (reason is not null || target is null) return await Refuse("url rejected: " + reason);
            var job = new CoreJob
            {
                Id = Guid.NewGuid(),
                Kind = CoreJobKind.Web,
                Mode = "scan",
                State = CoreJobState.Queued,
                Profile = WebJobExecutor.Profile,
                TargetUrl = target.AbsoluteUri, // query and fragment already removed: they may carry tokens
                Owner = CliOwner,
                CreatedAt = now,
            };
            await store.SaveJobAsync(job);
            return (job, SafeName(target.Host));
        }

        if (!File.Exists(c.Target)) return await Refuse($"file not found: {c.Target}");
        var fileName = Path.GetFileName(c.Target);
        var stem = SafeName(Path.GetFileNameWithoutExtension(fileName));
        try
        {
            switch (c.Name)
            {
                case "pdf":
                {
                    if (c.Language is not null && !JobRequestRules.IsLanguageTag(c.Language)) return await Refuse(JobRequestRules.LanguageError);
                    var title = TitleInput.Normalize(c.Title, out var titleError);
                    if (titleError is not null) return await Refuse(titleError);
                    if (!await JobRequestRules.HasPdfHeaderAsync(c.Target, ct)) return await Refuse("not a PDF (missing %PDF- header)");
                    var upload = AddUpload(store, c.Target, fileName, "application/pdf", options, now);
                    var job = new CoreJob
                    {
                        Id = Guid.NewGuid(), Kind = CoreJobKind.Pdf, Mode = c.Fix ? "fix" : "analyze", State = CoreJobState.Queued, Profile = "ua1",
                        ExplicitLanguage = c.Language, ExplicitTitle = title, SourceFileName = upload.FileName, InputUploadId = upload.Id, Owner = CliOwner, CreatedAt = now,
                    };
                    await store.SaveJobAsync(job);
                    return (job, stem);
                }
                case "html":
                {
                    var selector = JobRequestRules.NormalizeSelector(c.ContentSelector, out var selectorError);
                    if (selectorError is not null) return await Refuse(selectorError);
                    if (await JobRequestRules.SniffHtmlAsync(c.Target, ct) is { } unsupported) return await Refuse(unsupported);
                    var upload = AddUpload(store, c.Target, fileName, "text/html", options, now);
                    var job = new CoreJob
                    {
                        Id = Guid.NewGuid(), Kind = CoreJobKind.HtmlToPdf, Mode = c.AnalyzeOnly ? "generate_analyze" : "generate_fix", State = CoreJobState.Queued, Profile = "ua1",
                        ContentSelector = selector, SourceFileName = upload.FileName, InputUploadId = upload.Id, Owner = CliOwner, CreatedAt = now,
                    };
                    await store.SaveJobAsync(job);
                    return (job, stem);
                }
                default:
                    return await PrepareDocumentAsync(c, store, options, stem, now, stderr, ct);
            }
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            return await Refuse($"cannot read {c.Target}: {e.Message}");
        }
    }

    private static UploadRecord AddUpload(DataStore store, string path, string fileName, string contentType, CoreOptions options, DateTimeOffset now)
    {
        var id = Guid.NewGuid();
        File.Copy(path, store.UploadPath(id));
        using var fs = File.OpenRead(store.UploadPath(id));
        var record = new UploadRecord(id, fileName, fs.Length, Convert.ToHexString(SHA256.HashData(fs)).ToLowerInvariant(), contentType, CliOwner, now, now.AddHours(options.RetentionHours), true);
        return store.CreateUpload(record);
    }

    private static async Task<(CoreJob, string)?> PrepareDocumentAsync(CliCommand c, DataStore store, CoreOptions options, string stem, DateTimeOffset now, TextWriter stderr, CancellationToken ct)
    {
        async Task<(CoreJob, string)?> Invalid(IEnumerable<ValidationError> errors)
        {
            await stderr.WriteLineAsync($"koldera: invalid document {c.Target}");
            foreach (var e in errors)
                await stderr.WriteLineAsync($"  {(e.Path.Length == 0 ? "(document)" : e.Path)}  {e.Code}: {e.Message}");
            return null;
        }

        JsonNode? root;
        try
        {
            root = JsonNode.Parse(await File.ReadAllTextAsync(c.Target, ct));
        }
        catch (JsonException e)
        {
            await stderr.WriteLineAsync($"koldera: {c.Target} is not valid JSON: {e.Message}");
            return null;
        }
        if (root is not JsonObject document)
            return await Invalid([new ValidationError("", DocumentErrorCodes.Schema, "The document must be a JSON object.")]);

        var element = JsonSerializer.SerializeToElement(document);
        var errors = DocumentValidator.Validate(element);
        if (errors.Count > 0) return await Invalid(errors);

        // The validator accepts both image sources; the CLI takes files (relative to the JSON file), never an uploadId.
        var directory = Path.GetDirectoryName(Path.GetFullPath(c.Target))!;
        var problems = new List<ValidationError>();
        var uploads = new Dictionary<string, Guid>(StringComparer.OrdinalIgnoreCase);
        foreach (var image in DocumentImages.Find(element))
        {
            if (image.UploadId is not null)
            {
                problems.Add(new ValidationError(image.Path, "UPLOAD_ID_NOT_ALLOWED", "uploadId is only available over HTTP; name the image with a file path relative to the JSON file."));
                continue;
            }
            var (full, refusal) = ResolveImage(directory, image.File!);
            string? contentType = full is null ? null : ContentTypeOf(full);
            if (full is null) problems.Add(new ValidationError(image.Path, "FILE_IMAGE_INVALID", refusal!));
            else if (contentType is null) problems.Add(new ValidationError(image.Path, "FILE_IMAGE_INVALID", "an image file must end in .png, .jpg, .jpeg or .svg."));
            else if (await ImageSniffer.MismatchAsync(full, contentType, ct) is { } mismatch) problems.Add(new ValidationError(image.Path, "FILE_IMAGE_INVALID", mismatch));
            else
            {
                if (!uploads.TryGetValue(full, out var id)) uploads[full] = id = AddUpload(store, full, Path.GetFileName(full), contentType!, options, now).Id;
                // The renderer looks an image up by the document's own uploadId string.
                var block = Navigate(document, image.Path)!.AsObject();
                block.Remove("file");
                block["uploadId"] = id.ToString();
            }
        }
        if (problems.Count > 0) return await Invalid(problems);

        var job = new CoreJob
        {
            Id = Guid.NewGuid(), Kind = CoreJobKind.DocumentToPdf, Mode = "generate_analyze", State = CoreJobState.Queued, Profile = "ua1",
            SourceFileName = Path.GetFileName(c.Target), Owner = CliOwner, CreatedAt = now,
        };
        await File.WriteAllTextAsync(DocumentExecutor.InputPath(store, job.Id), document.ToJsonString(), ct);
        await store.SaveJobAsync(job);
        return (job, stem);
    }

    /// <summary>The image file under <paramref name="directory"/>, or a reason why it is refused: absolute paths, <c>..</c> escapes and links leading out are refused.</summary>
    private static (string? Path, string? Reason) ResolveImage(string directory, string file)
    {
        if (Path.IsPathRooted(file) || file.StartsWith('/') || file.StartsWith('\\')) return (null, "an image file path must be relative to the JSON file.");
        var root = Path.GetFullPath(directory).TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar;
        var comparison = OperatingSystem.IsWindows() ? StringComparison.OrdinalIgnoreCase : StringComparison.Ordinal;
        var full = Path.GetFullPath(Path.Combine(directory, file));
        if (!full.StartsWith(root, comparison)) return (null, "the image file is outside the directory of the JSON file.");
        if (!File.Exists(full)) return (null, "the image file does not exist.");

        // A link anywhere on the way (a directory link as well as the file) must not lead out either.
        var current = root.TrimEnd(Path.DirectorySeparatorChar);
        foreach (var segment in full[root.Length..].Split(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar))
        {
            current = Path.Combine(current, segment);
            FileSystemInfo info = Directory.Exists(current) ? new DirectoryInfo(current) : new FileInfo(current);
            if (info.LinkTarget is null) continue;
            var target = info.ResolveLinkTarget(true)?.FullName;
            if (target is null || !target.StartsWith(root, comparison))
                return (null, "the image file is a link that leads outside the directory of the JSON file.");
        }
        return (full, null);
    }

    private static string? ContentTypeOf(string path) => Path.GetExtension(path).ToLowerInvariant() switch
    {
        ".png" => "image/png",
        ".jpg" or ".jpeg" => "image/jpeg",
        ".svg" => "image/svg+xml",
        _ => null,
    };

    /// <summary>The node a JSON Pointer (RFC 6901) names.</summary>
    private static JsonNode? Navigate(JsonNode root, string pointer)
    {
        var node = root;
        foreach (var raw in pointer.Split('/', StringSplitOptions.RemoveEmptyEntries))
        {
            var key = raw.Replace("~1", "/").Replace("~0", "~");
            node = node is JsonArray array ? array[int.Parse(key)] : node![key];
        }
        return node;
    }

    // ---- output ----

    /// <summary>Copies every artifact to <paramref name="outDir"/> as <c>stem.kind.ext</c>; an existing file is never overwritten (-1, -2, ... before the extension).</summary>
    private static async Task<List<WrittenArtifact>> WriteArtifactsAsync(CoreJob job, DataStore store, string outDir, string stem, CancellationToken ct)
    {
        var written = new List<WrittenArtifact>();
        if (job.Artifacts.Count == 0) return written;
        Directory.CreateDirectory(outDir);
        foreach (var artifact in job.Artifacts)
        {
            var source = Path.Combine(store.JobDir(job.Id), "out", artifact.FileName);
            var extension = Path.GetExtension(artifact.FileName);
            var baseName = $"{stem}.{SafeName(artifact.Kind)}";
            for (var n = 0; ; n++)
            {
                var path = Path.GetFullPath(Path.Combine(outDir, n == 0 ? baseName + extension : $"{baseName}-{n}{extension}"));
                try
                {
                    // CreateNew refuses an existing file, also when another process creates it between a check and the write.
                    await using var target = new FileStream(path, FileMode.CreateNew, FileAccess.Write, FileShare.None);
                    await using var from = File.OpenRead(source);
                    await from.CopyToAsync(target, ct);
                }
                catch (IOException) when (File.Exists(path))
                {
                    continue;
                }
                written.Add(new WrittenArtifact(artifact, path));
                break;
            }
        }
        return written;
    }

    private static string SafeName(string name)
    {
        var invalid = Path.GetInvalidFileNameChars();
        var safe = new string(name.Select(ch => invalid.Contains(ch) ? '_' : ch).ToArray()).Trim();
        return safe.Length == 0 ? "output" : safe;
    }

    private static async Task<int> UsageError(TextWriter stderr, string message, string command)
    {
        await stderr.WriteLineAsync($"koldera: {message}");
        await stderr.WriteLineAsync(CliOptions.UsageOf(command));
        return Summary.Usage;
    }

    /// <summary>Runner log lines go to stderr, so stdout stays clean for <c>--json</c>.</summary>
    private sealed class StderrLogger<T>(TextWriter stderr) : ILogger<T>
    {
        public IDisposable? BeginScope<TState>(TState state) where TState : notnull => null;
        public bool IsEnabled(LogLevel logLevel) => logLevel >= LogLevel.Warning;
        public void Log<TState>(LogLevel logLevel, EventId eventId, TState state, Exception? exception, Func<TState, Exception?, string> formatter) =>
            stderr.WriteLine($"koldera: {logLevel.ToString().ToLowerInvariant()}: {formatter(state, exception)}");
    }
}
