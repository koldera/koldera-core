# Running on AWS Lambda

koldera-core runs on AWS Lambda (and similar function platforms that start a container image) in two ways:

- **as the HTTP server** behind the [AWS Lambda Web Adapter](https://github.com/awslabs/aws-lambda-web-adapter),
  called through a function URL or API Gateway, using `Prefer: wait=N` so one request returns the finished job;
- **as a command-line tool** inside your own handler (for example on an S3 upload), see "S3 trigger" below.

Both use the same container image as everywhere else (`docker/Dockerfile`). Lambda is not a long-lived host: the
server keeps jobs, uploads and idempotency keys in its data directory and in memory, so nothing survives a cold
start or a change of execution environment. Plan for one request (or one invocation) to be the whole life of a job.

## Image and function settings

| Setting | Value |
| --- | --- |
| Package type | container image (the image from `docker/Dockerfile`, pushed to Amazon ECR) |
| Memory | at least 3008 MB (Chromium, the Java engine and the .NET host run in one environment) |
| Timeout | up to 900 s; set it above the longest `wait=` you send |
| Ephemeral storage | configure up to 10 GB (`EphemeralStorage` in the function configuration); uploads, scratch files and artifacts live there |
| Architecture | the one you built the image for (`x86_64` or `arm64`) |

Environment variables:

| Variable | Value |
| --- | --- |
| `AWS_LWA_PORT` | `8080` (the port the server listens on) |
| `AWS_LWA_READINESS_CHECK_PATH` | `/healthz` |
| `KOLDERA_DATA_DIR` | `/tmp/koldera` (the only writable place besides `/tmp`; the image's `/data` is not writable on Lambda) |
| `KOLDERA_TOKEN` | the bearer token for `/v1`; read it from AWS Secrets Manager or an encrypted environment variable, never bake it into the image |
| `KOLDERA_PUBLIC_URL` | the externally visible base URL (the function URL or your custom domain), so signed upload and download URLs point at it |
| `KOLDERA_CHROMIUM_SANDBOX` | leave unset (false). Lambda does not allow the user namespaces Chromium's own sandbox needs |
| `KOLDERA_SIGNING_KEY` | set one (base64, 32 bytes or more), see below |

Lambda starts several execution environments under load, and each has its own `/tmp` and its own memory. A job, its
upload and its artifacts exist only in the environment that handled them. Use `Prefer: wait=N` so the answer to
one request already carries the finished job, and fetch artifacts from the same function URL right away, or use the
command-line path below, which needs no state across invocations. Signed upload and download URLs are checked with
`KOLDERA_SIGNING_KEY`; set the same key on every environment so a URL minted by one is accepted by another.

## Lambda Web Adapter

Add the adapter as an extension in the image (a `COPY --from=public.ecr.aws/awsguru/aws-lambda-adapter:<version>
/lambda-adapter /opt/extensions/lambda-adapter` line in a Dockerfile that starts `FROM` the koldera-core image), pin
the adapter version, and keep the image's own entrypoint (`serve`). The adapter waits for `GET /healthz` to answer,
then forwards each invocation as an HTTP request to port 8080.

```dockerfile
FROM koldera-core:0.1.0
COPY --from=public.ecr.aws/awsguru/aws-lambda-adapter:0.9.1 /lambda-adapter /opt/extensions/lambda-adapter
ENV AWS_LWA_PORT=8080 \
    AWS_LWA_READINESS_CHECK_PATH=/healthz \
    KOLDERA_DATA_DIR=/tmp/koldera
```

Check the adapter's own documentation for the version and options you use; the lines above show the shape only.

## Prefer: wait

A job-creating POST (`/v1/pdf/jobs`, `/v1/html-to-pdf/jobs`, `/v1/web/jobs`, `/v1/documents/jobs` and
`/v1/jobs/{id}/reading-order/remediations`) accepts `Prefer: wait=N`, `N` from 1 to 900 seconds.

- The job finished within N seconds: the answer is `200` with the finished job (state `completed` or `failed`).
- It did not: the answer is `202` as without the header; poll `Location`.
- `Preference-Applied: wait=N` is set whenever the server waited. A missing, malformed or out-of-range value is
  ignored and the answer is `202` without that header.
- The wait ends when the caller disconnects; the job keeps running.
- With an `Idempotency-Key`, a retry replays the stored answer, including a `200` and its `Preference-Applied`.

```bash
BASE=https://abc123.lambda-url.eu-west-1.on.aws
curl -sS -X POST "$BASE/v1/pdf/jobs" \
  -H "Authorization: Bearer $KOLDERA_TOKEN" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $(uuidgen)" \
  -H "Prefer: wait=60" \
  -d '{"uploadId":"<upload id>","mode":"analyze","profile":"ua1"}' \
  -D - | head -20
```

Function URLs and API Gateway have their own response time limits (API Gateway REST APIs stop at 29 s by default);
use a `wait` below the limit of the front door you use, or a function URL (up to the function timeout).

## Payload and time limits

- Synchronous invocations (function URLs, API Gateway, ALB) limit the request and the response body to 6 MB each.
  Uploads and artifact downloads through the HTTP server must fit; larger files belong to the S3 recipe below
  (the CLI reads and writes files, with no payload limit). Response streaming is not needed for either path.
- API Gateway stops an integration at 29 s by default; use a `wait` below that, or a function URL, whose limit is the
  function timeout (up to 900 s).
- A poll, download or later request that lands on a different execution environment than the one that created the
  job returns 404: the job, its upload and its artifacts exist only in the environment that handled them. Prefer
  `wait=N` and read what you need from that one answer, or use the CLI recipe.

## S3 trigger

For file-in, file-out processing the command-line tool is simpler than the HTTP server: no upload tickets, no
polling, no state across invocations, and no payload limit. The handler below is a recipe, it is not part of the
image. It downloads the object, runs `koldera pdf <file> --fix --json --out /tmp/out`, and uploads the results
under `OUT_PREFIX`.

Configure the S3 event notification with a prefix and suffix filter (for example prefix `incoming/`, suffix `.pdf`)
and keep `OUT_PREFIX` outside that prefix, or write to a separate bucket. The handler also skips keys under
`OUT_PREFIX`, so a misconfigured trigger cannot make the function run on its own output.

```js
// handler.mjs (Node.js 22 runtime in a container image based on the koldera-core image, or a layer that provides `koldera`)
import { execFile } from "node:child_process";
import { mkdir, readFile, rm, writeFile } from "node:fs/promises";
import path from "node:path";
import { promisify } from "node:util";
import { GetObjectCommand, PutObjectCommand, S3Client } from "@aws-sdk/client-s3";

const run = promisify(execFile);
const s3 = new S3Client({});
const OUT_PREFIX = process.env.OUT_PREFIX ?? "koldera-out/";
const OUT_BUCKET = process.env.OUT_BUCKET; // optional: a separate bucket for the results

export const handler = async (event) => {
  for (const record of event.Records) {
    const bucket = record.s3.bucket.name;
    const key = decodeURIComponent(record.s3.object.key.replace(/\+/g, " "));
    if (key.startsWith(OUT_PREFIX)) continue; // never process our own output
    const work = "/tmp/job";
    const out = "/tmp/out";
    try {
      await rm(work, { recursive: true, force: true });
      await rm(out, { recursive: true, force: true });
      await mkdir(work, { recursive: true });
      await mkdir(out, { recursive: true });

      const input = path.join(work, "input.pdf");
      const object = await s3.send(new GetObjectCommand({ Bucket: bucket, Key: key }));
      await writeFile(input, Buffer.from(await object.Body.transformToByteArray()));

      // Exit code 0: machine check passed; 2: completed with failed machine rules (still a result); anything else is an error.
      let stdout;
      try {
        ({ stdout } = await run("koldera", ["pdf", input, "--fix", "--json", "--out", out], {
          env: { ...process.env, KOLDERA_DATA_DIR: "/tmp/koldera" },
          maxBuffer: 64 * 1024 * 1024,
        }));
      } catch (e) {
        if (e.code !== 2 || !e.stdout) throw e;
        stdout = e.stdout;
      }
      const result = JSON.parse(stdout);

      const target = OUT_BUCKET ?? bucket;
      const prefix = OUT_PREFIX + key.replace(/\.pdf$/i, "");
      await s3.send(new PutObjectCommand({ Bucket: target, Key: `${prefix}.koldera.json`, Body: stdout, ContentType: "application/json" }));
      for (const artifact of result.artifacts ?? []) {
        await s3.send(new PutObjectCommand({ Bucket: target, Key: `${prefix}.${path.basename(artifact.path)}`, Body: await readFile(artifact.path) }));
      }
    } finally {
      await rm(work, { recursive: true, force: true });
      await rm(out, { recursive: true, force: true });
      await rm("/tmp/koldera", { recursive: true, force: true });
    }
  }
};
```

Notes for this recipe:

- `--json` prints `{job, findings, actions, artifacts}`; each artifact has `id`, `kind`, `sha256`, `sizeBytes` and the `path` it was written to.
- `/tmp` is ephemeral storage: size it (up to 10 GB) for the largest input plus its artifacts; the handler cleans it after each record. Jobs do not survive across invocations; a timeout loses the work of that invocation and S3 retries the event.
- The result lists machine rules only. A person still has to review what the checks cannot decide.
