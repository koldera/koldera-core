using System.Security.Cryptography;
using Koldera.Core.Workers;

namespace Koldera.Core.Tests;

public sealed class ArtifactFilesTests : IDisposable
{
    private readonly string _root = Path.Combine(Path.GetTempPath(), "koldera-artifacts-" + Guid.NewGuid().ToString("N"));
    private static readonly DateTimeOffset Now = DateTimeOffset.Parse("2026-10-05T00:00:00Z");

    public ArtifactFilesTests() => Directory.CreateDirectory(_root);

    public void Dispose() => Directory.Delete(_root, true);

    private string Write(string name, byte[] bytes)
    {
        var path = Path.Combine(_root, name);
        File.WriteAllBytes(path, bytes);
        return path;
    }

    [Fact]
    public async Task A_file_with_matching_size_and_hash_is_moved_into_the_job_out_directory()
    {
        var bytes = "%PDF-1.7 fixed"u8.ToArray();
        var local = Write("fixed.pdf", bytes);
        var outDir = Path.Combine(_root, "out");
        var sha = Convert.ToHexStringLower(SHA256.HashData(bytes));

        var artifact = await ArtifactFiles.VerifyAndKeepAsync(local, "fixed_pdf", sha, bytes.Length, outDir, Now, Now.AddHours(24), CancellationToken.None);

        Assert.Equal("fixed_pdf", artifact.Kind);
        Assert.Equal("fixed.pdf", artifact.FileName);
        Assert.Equal(sha, artifact.Sha256);
        Assert.Equal(bytes.Length, artifact.Bytes);
        Assert.Equal(Now.AddHours(24), artifact.ExpiresAt);
        Assert.NotEqual(Guid.Empty, artifact.Id);
        Assert.False(File.Exists(local));
        Assert.Equal(bytes, File.ReadAllBytes(Path.Combine(outDir, "fixed.pdf")));
    }

    [Fact]
    public async Task A_hash_mismatch_is_a_contract_violation_and_keeps_nothing()
    {
        var local = Write("fixed.pdf", "%PDF-1.7 fixed"u8.ToArray());
        var outDir = Path.Combine(_root, "out");

        await Assert.ThrowsAsync<WorkerContractException>(() =>
            ArtifactFiles.VerifyAndKeepAsync(local, "fixed_pdf", new string('0', 64), 14, outDir, Now, Now.AddHours(1), CancellationToken.None));

        Assert.True(File.Exists(local));
        Assert.False(Directory.Exists(outDir) && Directory.GetFiles(outDir).Length > 0);
    }

    [Fact]
    public async Task A_pre_existing_target_in_the_output_directory_is_a_contract_violation()
    {
        var bytes = "%PDF-1.7 fixed"u8.ToArray();
        var local = Write("fixed.pdf", bytes);
        var outDir = Path.Combine(_root, "out");
        Directory.CreateDirectory(outDir);
        File.WriteAllText(Path.Combine(outDir, "fixed.pdf"), "already here");
        var sha = Convert.ToHexStringLower(SHA256.HashData(bytes));

        var e = await Assert.ThrowsAsync<WorkerContractException>(() =>
            ArtifactFiles.VerifyAndKeepAsync(local, "fixed_pdf", sha, bytes.Length, outDir, Now, Now.AddHours(1), CancellationToken.None));

        Assert.Equal("artifact already exists", e.Message);
        Assert.Equal("already here", File.ReadAllText(Path.Combine(outDir, "fixed.pdf")));
    }

    [Fact]
    public async Task A_size_mismatch_or_missing_file_is_a_contract_violation()
    {
        var bytes = "abc"u8.ToArray();
        var local = Write("a.json", bytes);
        var sha = Convert.ToHexStringLower(SHA256.HashData(bytes));
        var outDir = Path.Combine(_root, "out");

        await Assert.ThrowsAsync<WorkerContractException>(() =>
            ArtifactFiles.VerifyAndKeepAsync(local, "report", sha, 4, outDir, Now, Now.AddHours(1), CancellationToken.None));
        await Assert.ThrowsAsync<WorkerContractException>(() =>
            ArtifactFiles.VerifyAndKeepAsync(Path.Combine(_root, "absent.pdf"), "report", sha, 3, outDir, Now, Now.AddHours(1), CancellationToken.None));
    }

    [Fact]
    public void A_declared_path_inside_the_output_directory_yields_its_plain_file_name()
    {
        Assert.Equal("fixed.pdf", ArtifactFiles.RequireOutputName("/work/out/fixed.pdf", "/work/out"));
    }

    [Theory]
    [InlineData("/etc/passwd")]
    [InlineData("/work/outside/fixed.pdf")]
    [InlineData("/work/out/")]
    public void A_declared_path_outside_the_output_directory_is_refused(string declared)
    {
        Assert.Throws<WorkerContractException>(() => ArtifactFiles.RequireOutputName(declared, "/work/out"));
    }

    [Theory]
    [InlineData("/work/out/sub/fixed.pdf")]
    [InlineData("/work/out/..")]
    [InlineData("/work/out/a\\b.pdf")]
    public void A_declared_name_that_is_not_a_plain_file_name_is_refused(string declared)
    {
        Assert.Throws<WorkerContractException>(() => ArtifactFiles.RequireOutputName(declared, "/work/out"));
    }
}
