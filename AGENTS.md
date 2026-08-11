# Working in this repository

Net Tools API is a deployable MuleSoft Mule 4 application. It exposes network
diagnostic tools (ping, traceroute, dns, tcp socket, curl, TLS certificate and
cipher tests) as a RAML/APIkit REST API, and is deployed into a customer's
CloudHub VPC to debug connectivity from a Mule worker to on-premises systems.

It is API-only. A jQuery web UI was served from `/*` until 3.0; nothing outside
`/api` is claimed by a listener now, so those paths return a bare 404.

Read `README.md` for what the tool does and how it is used. This file covers
what you need to know to change it safely.

## Build

```
mvn clean package
```

**Java 17 is required.** The app targets Mule runtime 4.9, and Mule 4.9 refuses
to build on Java 11 with `Mule Runtime 4.9.0 requires Java 17 or later`. If your
shell defaults to another JDK:

```
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn clean package
```

Any current Maven works. This was not always true: `mule-maven-plugin` 3.8.x
fails under Maven 3.9.x with a missing `BasicRepositoryConnectorFactory`, and
3.8.7 fails the same way as 3.8.0. Do not downgrade the plugin below 4.x.

The build validates the Mule XML, the RAML, and the mapping between them, so a
successful `package` catches a surprising amount. It does not run the app.

## Testing

**There is no test infrastructure.** No JUnit, no MUnit, no test sources. Do not
claim a change is verified because the build passed; the build only proves it
compiles and the XML parses.

There is also no Mule runtime available in most development environments here,
so flows cannot be exercised locally. Two things you *can* do:

1. **Exercise the Java layer directly.** `NetworkUtils` is a plain class with
   static methods. Compile it with `javac`, point it at a small local HTTP
   server built on `com.sun.net.httpserver.HttpServer` that records the method,
   headers and body it received, and assert on that rather than on curl's
   output. This is how the curl security behaviour and the 3.0 byte-exact body
   relay were verified.

   Two traps found doing this. Assert on **bytes**, not strings, or a body that
   is not valid UTF-8 will appear to survive a round trip it did not. And when
   checking that `-g` stops URL globbing, count response status lines in curl's
   own output rather than requests the handler saw: curl sends the literal path
   `/g[1-5]`, which `HttpServer` rejects with a 400 before the handler runs, so
   a handler-side counter reads zero and the check fails for the wrong reason.
2. **Reproduce before fixing.** Every curl hardening flag below traces to an
   exploit that was actually reproduced against a local server first. Keep that
   habit; several plausible-sounding findings turned out to be wrong when tested.

If you add a change that cannot be verified either way, say so plainly rather
than implying it was tested.

## Security invariants in `NetworkUtils.curl`

This method runs `curl` on the worker with caller-supplied input, so it is the
sharp edge of the whole application. Each of these flags is load-bearing. Do not
remove one without understanding what it stops.

| Flag | Why |
|---|---|
| `--data-binary @-` with the body on stdin | `-d <body>` treats a leading `@` as "read this local file". Passing the body as an argument also hits the Linux 128KB per-argument limit and exposes it in `ps`. |
| reject headers starting with `@` | `-H @file` reads a local file and sends every line as a header to the caller's target. This was verified to exfiltrate the app's own Basic Auth password from `properties.yaml`. |
| reject `\r` and `\n` in headers | curl writes `-H` values verbatim, so a line break lets the caller add its own headers, or write a whole second request line, which sidesteps the verb allowlist. Verified: a header value containing CRLF arrived at the target as a separate header. |
| `-g` | Without it curl expands `[1-254]` and `{a,b}` in the URL, so one call becomes many requests. `--max-time` bounds each *transfer*, not the invocation, so `http://10.0.0.[1-254]/` against a blackholed range pins a worker thread for roughly 42 minutes. Verified: `/g[1-5]` produced five requests. |
| `--max-filesize` | The whole response is buffered into a Java `String` and copied again on the way out, so an unbounded body can exhaust a small worker. |
| `--proto =http,https` and `--proto-redir =http,https` | Without them, `url=file:///...` returns worker files in the response body. |
| `--` before the URL | Otherwise a URL such as `-oFILE` is parsed as a curl option. |
| `--connect-timeout` / `--max-time` | There was no timeout at all; an unresponsive target pinned a Mule worker thread indefinitely. |
| `-sS` | Keeps curl's progress meter, which it writes to stderr whenever stdout is not a terminal, out of the text returned to the caller. Errors still surface. `execute()` merges stderr into stdout with `redirectErrorStream(true)`, so this is presentation rather than the deadlock guard it originally was. |
| reject `\r` and `\n` in the forwarded `Content-Type` | Since 3.0 the inbound `Content-Type` becomes a third source of `-H` arguments, so it needs the same line-break check as the caller-supplied headers. The HTTP connector should already make a CRLF-bearing header value unreachable; this is defence in depth. |
| the `Content-Type` block runs **after** the header loop | That ordering is what makes an explicit `x-target-header: Content-Type:` win over the inbound one. Hoisting it above the loop would silently invert the documented precedence. `hasContentTypeHeader` compares the name before the first colon, with whitespace stripped, rather than matching a `content-type:` prefix, so `Content-Type : x` is still recognised as an override. |

The verb allowlist is enforced twice. Since 3.0 the primary enforcement is
structural: the RAML declares exactly `get`, `post`, `put`, `patch` and `delete`
on `/curl`, and APIkit answers 405 for anything else, so the verb reaching Java
is `attributes.method`. `ALLOWED_METHODS` in Java is kept as defence in depth,
because `curl` is a public static method that nothing stops another flow from
calling. Keep the two in step.

The allowlist is only as strong as the header validation: a header value carrying
CRLF can write its own request line, which is why the line-break check above
matters as much as the verb list itself.

**What is deliberately not restricted:** `http` and `https` to any reachable
address. That is the point of the tool, but it means an authenticated caller can
reach the worker's own listener and, on CloudHub 1.0, the instance metadata
service. Treat access to this app as equivalent to access to the worker's
network. Do not describe local disclosure as "closed" in documentation.

**Do not log the target URL.** Since 3.0 the parameters arrive as `x-target-*`
request headers rather than query parameters, so the query string is no longer
the danger it was. The danger moved rather than disappeared: `x-target-credentials`
carries credentials, `x-target-header` carries `Authorization`, and
`x-target-url` can embed credentials as `https://user:pass@host`. Log none of
them, and do not "restore" target logging on the grounds that the query string is
now empty.

The logger records `attributes.rawRequestPath`. Two things are deliberate there:
not the URI, and *raw* rather than `requestPath` because the latter is
URL-decoded, so `/api/%0aINFO%20forged` would decode into a real newline and let a
caller forge log entries. `rawRequestPath` is the path as received and needs HTTP
connector 1.5.0 or later.

### The worker's curl is 7.76.1

```
curl 7.76.1 (x86_64-redhat-linux-gnu) libcurl/7.76.1 OpenSSL/3.2.2 zlib/1.2.11 nghttp2/1.43.0
Release-Date: 2021-04-14
Protocols: file ftp ftps http https
Features: alt-svc AsynchDNS GSS-API HTTP2 HTTPS-proxy IPv6 Kerberos Largefile libz NTLM SPNEGO SSL UnixSockets
```

Development machines run curl 8.x, so a flag that is too new for the worker will
work locally and fail in the customer's VPC. Check any new flag against 7.76.1.

| Not available | Introduced in |
|---|---|
| `--json` | 7.82.0 |
| `--url-query` | 7.87.0 |
| `--variable` | 8.3.0 |
| `%{header_json}` | 7.83.0 |
| `%{certs}` | 7.88.0 |

Available if wanted: `-w '%{json}'` (7.70.0), `%{errormsg}` and `%{exitcode}`
(7.75.0), `--fail-with-body` (7.76.0, which just makes it), `--connect-to`
(7.49.0), `--resolve`, `--doh-url` (7.62.0).

Two things to note about this build specifically. `file` is in its `Protocols:`
line, so `--proto =http,https` is genuinely load-bearing here rather than
defensive decoration. And `-X HEAD` hangs until `--max-time` expires, because
curl waits for a response body that never arrives; use `-I` if HEAD is ever added
to the allowlist. That one is a curl behaviour, not a version limit.

`GET /api/diagnostics` returns the worker's live `curl --version`, so this can be
re-checked against the deployed environment rather than trusted indefinitely.

## APIkit flows

Flow names are generated from the RAML and must match exactly:

```
get:\curl:net-tools-config
post:\curl:net-tools-config
put:\curl:net-tools-config
patch:\curl:net-tools-config
delete:\curl:net-tools-config
```

There is no media-type segment because `/curl` declares no `body:` in the RAML.
That is deliberate and load-bearing: a declared media type both adds a segment to
the flow name and hands the payload to DataWeave to parse. Declaring none is what
lets the body through untouched. If you ever add a `body:` to `/curl`, the flow
names change and the byte-exact relay is at risk.

Where a media type *is* declared elsewhere, the separator is a **backslash**, not
a forward slash, because Mule rejects `/` in a flow name outright. The build
catches any mismatch, so trust it.

All five flows are one-line `flow-ref`s into the `curl-request` sub-flow, which
holds the only DataWeave-to-Java call. Add logic there, never in the per-verb
flows.

**Use `read()`, never `write()`, for the request body.** `write(payload, ...)`
parses the payload and serialises it again, which used to alter JSON and XML
bodies on the way through: whitespace, key order, XML comments, CDATA and number
formatting were all at the serialiser's discretion, and a malformed payload died
with a 500 before it ever left the worker. `read(payload, "application/octet-stream")`
hands over the caller's bytes as received. Two independent reviews had suggested
dropping the json and xml media types over this; declaring no body at all
resolves it instead, and every content type is now accepted.

The body reaches Java as `byte[]`, not `String`. A `String` forces a decode and a
re-encode, which cannot be byte-exact for a payload that is not valid UTF-8 or is
not text at all.

`curl-request` refuses a body over 10 MiB before the transform, by reading
`Content-Length` rather than the payload, so nothing is buffered in order to
decide. Without it an authenticated caller could `PUT` a multi-gigabyte body and
`read()` would pull all of it into the heap of a small worker. The limit matches
the cap that `max-filesize` puts on the response, which exists for the same
reason. **A chunked request declares no length and so is not covered.** Closing
that gap means streaming the body into curl's stdin instead of passing a
`byte[]`, which is a larger change than it looks.

Two APIkit behaviours worth knowing about `x-target-header`, both invisible in
the source and both found by decompiling `mule-apikit-module` 1.12.4:

- It parses as `array=true, repeat=true`, which is what stops APIkit answering
  "Header x-target-header is not repeatable" with a 400. The `items:`-without-`type: array`
  shape in the RAML does infer an array. Do not "tidy" it away.
- Values are routed through `DummyAttributeDeserializer`, which **strips a
  surrounding pair of double quotes** and unescapes `\"` inside. So
  `x-target-header: "Authorization: Bearer abc"` reaches curl without the quotes,
  and a value that legitimately begins and ends with `"` cannot be sent verbatim.
  The comma-splitting deserializer is not active, because no
  `arrayHeaderDelimiter` is configured on `<apikit:config>`.

## Releasing

Pushing a version tag runs `.github/workflows/release.yml`, which builds and
**publishes** a GitHub Release with the jar attached. There is no draft step, so
a pushed tag is immediately public. The workflow does not pass `--latest`; which
release wears that label is left to GitHub.

1. Bump `<version>` in `pom.xml` and commit.
2. Tag that commit and push the tag.

The workflow fails fast if the tag does not match the pom version. Both `2.6.0`
and `v2.6.0` are accepted; the tag history mixes the two conventions.

Deployment is gated by `minMuleVersion` in `mule-artifact.json`, currently
`4.9.0`. Note that `supportedJavaVersions` is enforced by the plugin at build
time but always serialises to `[]` inside the packaged artifact, so it is not
what gates deployment.

## Conventions

- Conventional Commits, with a body explaining *why* for anything non-trivial.
- Git and GitHub write operations need explicit approval from the maintainer.

### Pull requests go to this fork, never upstream

This repository is a **fork** of the archived `mulesoft-labs/net-tools-api`, so
`gh pr create` defaults to opening the pull request **against the parent**. That
is always wrong here: the parent was archived on 4 May 2024 and is read-only.

Always target this repository explicitly:

```
gh pr create --repo stn1slv/mule-net-tools --base main --head <branch>
```

The default branch here is `main`. `master` is the archived parent's default and
is not a branch of this repository.

Then confirm it landed where you meant, because the failure is silent:

```
gh pr view <n> --repo stn1slv/mule-net-tools --json baseRefName,url
```

The same applies to `gh issue`, `gh release` and any other `gh` command that
resolves a default repository. `origin` is `stn1slv/mule-net-tools` (renamed
from `mulesoft-net-tools-api`, so old URLs still redirect); `upstream` is the
archived parent.
