# Working in this repository

Net Tools API is a deployable MuleSoft Mule 4 application. It exposes network
diagnostic tools (ping, traceroute, dns, tcp socket, curl, TLS certificate and
cipher tests) as a RAML/APIkit REST API plus a small jQuery web UI, and is
deployed into a customer's CloudHub VPC to debug connectivity from a Mule worker
to on-premises systems.

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
   server that echoes the method, headers and body, and assert on what the
   server received. This is how the curl security behaviour was verified.
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

The verb allowlist is enforced twice, in the RAML `enum` and in Java. Keep both
in step. Note that the allowlist is only as strong as the header validation: a
header value carrying CRLF can write its own request line, which is why the
line-break check above matters as much as the verb list itself.

**What is deliberately not restricted:** `http` and `https` to any reachable
address. That is the point of the tool, but it means an authenticated caller can
reach the worker's own listener and, on CloudHub 1.0, the instance metadata
service. Treat access to this app as equivalent to access to the worker's
network. Do not describe local disclosure as "closed" in documentation.

**Do not log the query string.** Target headers, including `Authorization`, are
passed as query parameters. The endpoint logger deliberately records
`attributes.requestPath`, not `attributes.requestUri`, to keep credentials out
of the application log.

## APIkit flows

Flow names are generated from the RAML and must match exactly:

```
get:\curl:net-tools-config
post:\curl:application\json:net-tools-config
```

The media-type separator is a **backslash**, not a forward slash, because Mule
rejects `/` in a flow name outright. The build catches a mismatch, so trust it.

The three `POST /curl` flows differ only in how they stringify the payload, then
delegate to the `curl-request` sub-flow, which holds the only DataWeave-to-Java
call. Add logic there, not in the per-media-type flows.

Note that `application/json` and `application/xml` bodies are parsed and
re-serialised by DataWeave, so they are not byte-exact and a malformed payload
fails with a 500 rather than a 400. `text/plain` passes through unchanged. Two
independent reviews suggested dropping the json and xml types for this reason;
the repository owner chose to keep all three.

## Web UI

`src/main/resources/web/index.html` is plain jQuery with no build step. The
response body comes from an arbitrary target host, so **never concatenate it
into an HTML string**. Build the element and use `.text()`. That was a real XSS.

## Releasing

Pushing a version tag runs `.github/workflows/release.yml`, which builds and
opens a **draft** GitHub Release with the jar attached.

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
gh pr create --repo stn1slv/mule-net-tools-api --base master --head <branch>
```

Then confirm it landed where you meant, because the failure is silent:

```
gh pr view <n> --repo stn1slv/mule-net-tools-api --json baseRefName,url
```

The same applies to `gh issue`, `gh release` and any other `gh` command that
resolves a default repository. `origin` is `stn1slv/mule-net-tools-api` (renamed
from `mulesoft-net-tools-api`, so old URLs still redirect); `upstream` is the
archived parent.
