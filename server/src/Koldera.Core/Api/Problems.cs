using System.Text.Json.Serialization;

namespace Koldera.Core;

public static class Problems
{
    private sealed record Body(
        string Type,
        string Title,
        int Status,
        string Code,
        [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] string? Detail);

    public static IResult Result(int status, string code, string title, string? detail = null) =>
        Results.Json(
            new Body("about:blank", title, status, code, detail),
            new System.Text.Json.JsonSerializerOptions(System.Text.Json.JsonSerializerDefaults.Web),
            contentType: "application/problem+json",
            statusCode: status);
}
