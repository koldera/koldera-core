namespace Koldera.Core;

public static class Problems
{
    public static IResult Result(int status, string code, string title, string? detail = null) =>
        Results.Json(
            new { type = "about:blank", title, status, code, detail },
            contentType: "application/problem+json",
            statusCode: status);
}
