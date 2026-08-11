# Net Tools API

> This repository continues the development of [mulesoft-labs/net-tools-api](https://github.com/mulesoft-labs/net-tools-api), which its owner archived on 4 May 2024 and which is now read-only. Work carries on here: new features, fixes and releases are published in this repository, not the upstream one.

Net Tools API is a deployable Mule app that runs network diagnostics *from your worker*. Deploy it to CloudHub or any worker cloud, and you can ping, resolve, traceroute, open TCP sockets, inspect TLS certificates and relay arbitrary HTTP requests, all from inside the VPC.

Most connectivity problems between a CloudHub VPC or VPN and an on-premises system come down to firewall and routing rules, and they are resolved on the customer's side. This tool lets you and the networking team see what the worker itself can reach, rather than guessing. It is also useful for generating traffic while diagnosing a flaky path.

**Version 3.0 removed the web UI.** The app is now API-only, and the `/curl` endpoint was redesigned. See [Upgrading from 2.x](#upgrading-from-2x) if you have existing callers.

## Quick start

```
BASE='http://{app-name}.{region}.cloudhub.io/api'
AUTH='vpc-tools:SomePass'

# 1. Can the worker resolve the host?
curl -u "$AUTH" "$BASE/dns?host=erp.internal.example.com"

# 2. Can it open the port? (more reliable than ping, which many networks drop)
curl -u "$AUTH" "$BASE/socket?host=erp.internal.example.com&port=1433"

# 3. Can it actually talk to the service?
curl -u "$AUTH" -H 'x-target-url: https://erp.internal.example.com/health' "$BASE/curl"
```

Change `pass` from its default before you deploy this anywhere real. See [Security](#security) for why that matters more than it might look.

## Requirements

This version requires **Mule runtime 4.9 or later, running on Java 17**. Mule 4.9 does not run on Java 11, so this is a single combination rather than a choice.

If you need to deploy to a worker running Mule 4.4, 4.6 or 4.8, use release [2.5.1](https://github.com/stn1slv/mule-net-tools-api/releases/tag/2.5.1) or earlier, which targets Mule 4.1.3 and above.

## Configuration

Set these properties on the app to override the defaults. The ports must match your load balancer and VPC firewall rules; the defaults suit the CloudHub shared load balancer HTTP endpoint.

| Property | Default | Purpose |
|---|---|---|
| `user` | `vpc-tools` | Username for Basic Authentication |
| `pass` | `SomePass` | Password for Basic Authentication. **Change this.** |
| `httpPort` | `8081` | Listener port for HTTP |
| `httpsPort` | `8082` | Listener port for HTTPS |
| `httpListener` | `started` | Running state of the HTTP flows. Set to `stopped` to disable the HTTP endpoint on CloudHub 1.0 or non-RTF infrastructure. No effect on RTF or CloudHub 2.0, which use a single port. |

# Usage

## Base URL and authentication

Every endpoint lives under `/api` and uses HTTP Basic Authentication with the `user` and `pass` values above.

- CloudHub Shared Load Balancer: `http://{app-name}.{region}.cloudhub.io`
- Dedicated Load Balancer: your custom URL.

```
BASE='http://{app-name}.{region}.cloudhub.io/api'
curl -u vpc-tools:SomePass "$BASE/ping?host=10.20.30.40"
```

Every tool runs on the Mule worker, so the results describe what the *worker* can reach. That is the whole point when debugging a VPC or VPN path.

## Endpoints

| Method | Path | Parameters | What it does |
|---|---|---|---|
| `GET` | `/api/ping` | `host` | Sends four ICMP echo requests |
| `GET` | `/api/traceroute` | `host` | Traces the network path, up to 18 hops |
| `GET` | `/api/dns` | `host`, `dnsServer` *(optional)* | Resolves a name; with `dnsServer` it queries that resolver via `dig` |
| `GET` | `/api/socket` | `host`, `port` | Opens a TCP connection five times and reports round-trip time |
| `GET` | `/api/certest` | `host`, `port` | Retrieves the certificate chain presented by a TLS endpoint |
| `GET` | `/api/ciphertest` | `host`, `port` | Tests every cipher the local OpenSSL knows against the endpoint |
| `GET` `POST` `PUT` `PATCH` `DELETE` | `/api/curl` | `x-target-*` headers, see below | Relays an HTTP request to a target |
| `GET` | `/api/diagnostics` | *(none)* | Reports the worker's own tooling, currently the curl build |

Remember to URL-encode query parameter values.

## Common checks

Confirm the worker resolves and reaches an on-prem host:

```
curl -u vpc-tools:SomePass "$BASE/dns?host=erp.internal.example.com"
curl -u vpc-tools:SomePass "$BASE/ping?host=erp.internal.example.com"
```

Resolve through a specific DNS server, which is how you confirm a private zone is being served:

```
curl -u vpc-tools:SomePass "$BASE/dns?host=erp.internal.example.com&dnsServer=10.0.0.2"
```

Confirm a firewall rule actually allows the port. This is usually more informative than ping, because many networks drop ICMP:

```
curl -u vpc-tools:SomePass "$BASE/socket?host=erp.internal.example.com&port=1433"
```

Find where a path breaks:

```
curl -u vpc-tools:SomePass "$BASE/traceroute?host=erp.internal.example.com"
```

Inspect the certificate an endpoint presents, and which ciphers it accepts:

```
curl -u vpc-tools:SomePass "$BASE/certest?host=erp.internal.example.com&port=443"
curl -u vpc-tools:SomePass "$BASE/ciphertest?host=erp.internal.example.com&port=443"
```

`ciphertest` tries every cipher in turn, so it takes noticeably longer than the others.

Check what the worker itself is working with. This is the one call that probes nothing remote:

```
curl -u vpc-tools:SomePass "$BASE/diagnostics"
```

It returns `curl --version`: the version, TLS backend and supported protocols. Worth checking when a request behaves unexpectedly, since the curl endpoint depends on specific flags being available in the worker's build.

## The curl endpoint

`/api/curl` relays an HTTP request from the worker to a target. Two rules make it predictable:

1. **The method you call it with is the method the target receives.** A `PUT` here sends a `PUT` there.
2. **Your request body and its content type are passed straight through.** The body is relayed byte for byte, and the target is told the same `Content-Type` you sent. If you send a body without a `Content-Type`, there is nothing to forward and curl falls back to labelling it `application/x-www-form-urlencoded`, so set one.

In other words, you send the request you want relayed, and add headers saying where to send it.

| Header | Required | Purpose |
|---|---|---|
| `x-target-url` | yes | The target URL. Must be `http` or `https`. |
| `x-target-h-<name>` | no | Sends `<name>` to the target with this value, e.g. `x-target-h-client-secret: zwx` sends `client-secret: zwx`. One per header; see below. |
| `x-target-insecure` | no | `true` skips TLS certificate verification. Defaults to `false`. |
| `x-target-credentials` | no | Credentials for the target. `user:password` for basic; the token alone for bearer. |
| `x-target-auth-type` | no | `basic` or `bearer`. Defaults to `basic`, and is ignored without `x-target-credentials`. |

Header names are case-insensitive, so `X-Target-Url` works just as well.

There is no request body parameter and no method parameter: the body you send is the body the target gets, and the method you use is the method it receives.

### Examples

A plain reachability check:

```
curl -u vpc-tools:SomePass \
  -H 'x-target-url: https://internal.example.com/health' \
  "$BASE/curl"
```

Send a JSON body. Note that you declare `application/json` once, and that is what the target receives:

```
curl -u vpc-tools:SomePass -X POST \
  -H 'Content-Type: application/json' \
  -H 'x-target-url: https://internal.example.com/orders' \
  --data-raw '{"id": 1}' \
  "$BASE/curl"
```

The same for XML, or anything else. There is no list of accepted content types:

```
curl -u vpc-tools:SomePass -X PUT \
  -H 'Content-Type: text/xml' \
  -H 'x-target-url: https://internal.example.com/soap' \
  --data-binary @envelope.xml \
  "$BASE/curl"
```

`PATCH` and `DELETE` work the same way, and `DELETE` may carry a body if the target expects one:

```
curl -u vpc-tools:SomePass -X DELETE \
  -H 'x-target-url: https://internal.example.com/orders/1' \
  "$BASE/curl"
```

### Sending headers to the target

Prefix the header name with `x-target-h-`. Whatever follows the prefix is the name the target receives:

```
x-target-h-client-secret: zwx      ->  client-secret: zwx
x-target-h-accept: application/json ->  accept: application/json
```

Send as many as you need. Each is a separate header, so there is no limit and nothing is lost:

```
curl -u vpc-tools:SomePass \
  -H 'x-target-url: https://internal.example.com/orders' \
  -H 'x-target-h-client-id: zsd' \
  -H 'x-target-h-client-secret: zwx' \
  -H 'x-target-h-accept: application/json' \
  "$BASE/curl"
```

Two things to know:

- **Target header names arrive in lowercase**, because header names are normalised on the way in. HTTP treats them case-insensitively and HTTP/2 requires lowercase, so this makes no difference to the target.
- **The name must be a valid HTTP header name**, so no spaces. Values are unrestricted apart from the rules in [Limits and safeguards](#limits-and-safeguards).

This replaces the repeated `x-target-header` used in earlier 3.0 pre-releases. Repeating one header name proved unreliable: infrastructure in front of the app folded the duplicates and kept only the last, so all but one header was silently dropped. One header name per target header cannot be folded.

### Authenticating to the target

```
# basic, without hand-rolling base64
-H 'x-target-credentials: alice:secret' -H 'x-target-auth-type: basic'

# bearer token
-H 'x-target-credentials: eyJhbGciOiJIUzI1NiJ9...' -H 'x-target-auth-type: bearer'
```

Both are equivalent to setting `x-target-h-authorization: ...` yourself, so use whichever is clearer. `x-target-credentials` exists mainly so basic does not require you to base64-encode by hand. Anything else, such as API keys, signed requests or custom schemes, goes through `x-target-h-<name>`.

Only these two schemes are supported, on purpose. curl can also do `digest`, `ntlm` and `negotiate`, but those target legacy on-prem stacks rather than APIs and were deliberately left out.

One practical note: an `x-target-credentials` value with no colon would make curl wait for a password on a terminal that does not exist here, so a trailing colon is added for you and the password is treated as empty.

# How responses work

Successful calls return `200` with `text/plain` containing the raw output of the underlying command. There is no JSON envelope and no parsing, so treat the body as human-readable diagnostic text.

**A command that runs but fails to connect is still a `200`.** An unreachable host returns the curl or ping error text with a `200` status, because the tool did its job: it told you the target is unreachable. Reserve non-`2xx` handling for problems with your request to *this* API.

Errors from the API layer return JSON:

| Status | Body | Cause |
|---|---|---|
| `400` | `{"message": "Bad request"}` | A required parameter is missing, such as `x-target-url`, or a value fails validation, such as an `x-target-auth-type` outside `basic` and `bearer` |
| `401` | *(empty)* | Missing or wrong Basic Authentication credentials |
| `404` | `{"message": "Resource not found"}` | Unknown path under `/api` |
| `405` | `{"message": "Method not allowed"}` | Right path, wrong method, such as `POST /api/ping` or `HEAD /api/curl` |
| `406` | `{"message": "Not acceptable"}` | An `Accept` header that excludes `text/plain`. Every endpoint returns `text/plain`, so a client defaulting to `Accept: application/json` trips this |
| `413` | *(plain text)* | A `/api/curl` request body larger than 10 MiB |

Paths outside `/api`, including `/`, return a bare `404` from the HTTP connector with no body. Nothing is served there.

# Limits and safeguards

The curl endpoint runs a real `curl` on the worker with input you supply, so it is deliberately fenced in.

| Limit | Value | Why |
|---|---|---|
| Connect timeout | 10 seconds | A blackholed host fails quickly instead of holding a worker thread open |
| Total transfer time | 30 seconds | Same |
| Response size | 10 MiB | The whole body is buffered in memory, so a larger response is refused rather than risking the worker. curl can only enforce this when the target declares a `Content-Length`; a chunked response is not capped, and is bounded only by the 30 second transfer timeout |
| Request body size | 10 MiB | Same reason in the other direction: the body you send is read into memory before it is handed to curl. Enforced from `Content-Length`, so a chunked request with no declared length is not covered |
| Protocols | `http` and `https` only | On the original request and on every redirect, so `file://` cannot read worker files |
| URL globbing | disabled | `http://10.0.0.[1-254]/` is one literal address, not 254 requests each with its own timeout |
| Header values | may not start with `@` or contain line breaks | A leading `@` makes curl read a local file and send every line as a header, disclosing worker files. A line break lets a caller append headers or write a whole second request line |

Three behaviours worth knowing:

- **The target's `Content-Type` comes from your request.** If you want the target to receive something different from what you sent, override it with `x-target-h-content-type: ...`, which always wins. With no body, no `Content-Type` is sent at all.
- **`x-target-*` values are treated as UTF-8.** Send `x-target-h-x-name: café` and the target receives `café` as UTF-8, and a non-ASCII password in `x-target-credentials` is encoded as UTF-8. HTTP header values carry no charset and are decoded as ISO-8859-1 by convention, so this app reinterprets them as UTF-8, which is what callers almost always send. A value that is not valid UTF-8 keeps its characters rather than being mangled, and is still sent out as UTF-8. Note the asymmetry with the request body, which is byte-transparent: headers are re-encoded, bodies are not.
- **Redirects are followed**, and because the method is set explicitly the *same* method is used on every hop. A redirected `POST` therefore arrives at the final host as a `POST`, but **curl does not resend the body**, so the final request carries an empty payload. If a target redirects, treat the response as evidence about routing rather than about how it handles your payload.

# Security

**Only the scheme is restricted, not the destination.** `http` and `https` to *any* reachable address are allowed by design, because that is the point of the tool. That includes the worker's own listener on `127.0.0.1` and, on CloudHub 1.0, the instance metadata service on `169.254.169.254`.

Anyone who can authenticate to this app can therefore reach whatever the worker can reach. **Treat access to this tool as equivalent to shell access on the worker's network, and set a strong `pass`.**

Credentials you send for the target, whether in `x-target-credentials` or an `x-target-h-` header, stay out of the application log: it records only the scheme, method and path. Anything in front of the app that logs request headers would still record them.

# Network considerations

- `httpsPort` and `httpPort` **must always** be different numbers, even when `httpListener=stopped`, because both listener configurations are always created.
- CloudHub 2.0 and RTF use a single port for the HTTP listener, so you can run either HTTP or HTTPS but not both. Set the property you want to the proper port and the other to an unused one.
- On CloudHub 2.0 and RTF, enable *Last-Mile Security* in the app's Ingress tab to use HTTPS.
- This app does not use the `http.port` and `https.port` properties, because CloudHub 2.0 and RTF override those to the same port, which causes a port conflict at startup.

# Upgrading from 2.x

Every 2.x call to `/api/curl` needs changing. The other endpoints are unchanged.

| 2.x | 3.0 |
|---|---|
| `?url=...` | `-H 'x-target-url: ...'` |
| `?method=PUT` | call the API with `-X PUT` |
| `?header=Name:value` | `-H 'x-target-h-name: value'` |
| `?header=A:1&header=B:2` *(repeated)* | `-H 'x-target-h-a: 1' -H 'x-target-h-b: 2'` — one prefixed header each, never a repeated header name |
| `?insecure=true` | `-H 'x-target-insecure: true'` |
| `?user=alice:secret` | `-H 'x-target-credentials: alice:secret'` |
| `?authType=bearer` | `-H 'x-target-auth-type: bearer'` |
| `?header=Content-Type:application/json` | nothing: your own `Content-Type` is used |

Before and after:

```
# 2.x
curl -u vpc-tools:SomePass -X POST -H 'Content-Type: text/plain' --data-raw '{"id":1}' \
  "$BASE/curl?url=https://internal.example.com/orders&method=POST&header=Content-Type:application/json"

# 3.0
curl -u vpc-tools:SomePass -X POST -H 'Content-Type: application/json' \
  -H 'x-target-url: https://internal.example.com/orders' \
  --data-raw '{"id":1}' "$BASE/curl"
```

Three behaviours changed as well as the syntax:

- **Bodies are now relayed byte for byte.** In 2.x, JSON and XML payloads were parsed and re-serialised, so whitespace, key order and XML comments were not preserved, non-UTF-8 payloads were mangled, and a malformed payload failed with a `500` before reaching the target. All of that is fixed. If you worked around it by sending everything as `text/plain`, you no longer need to.
- **Any content type is accepted.** 2.x allowed only `application/json`, `application/xml` and `text/plain`, and answered `415` otherwise.
- **An unsupported method is now a `405`, not a `400`**, because the method is the HTTP verb rather than a parameter.

# Releasing

Releases are built by GitHub Actions. Pushing a version tag builds the app and **publishes** a release with the deployable jar attached. The notes are generated automatically, so the tag is all it takes; edit them afterwards if you want to expand on them. Nothing is held back as a draft, so a pushed tag is public straight away.

```
# 1. Bump <version> in pom.xml, then commit it
# 2. Tag that commit and push the tag
git tag 3.0.0
git push origin 3.0.0
```

The workflow checks the tag against the version in `pom.xml` and fails immediately if they disagree, so bump the pom first. A leading `v` on the tag is accepted, since older tags used that form.

Building locally needs the same toolchain the workflow uses, Java 17 and any current Maven:

```
mvn clean package
```

Latest builds are on the [releases page](https://github.com/stn1slv/mule-net-tools-api/releases). Builds from before the project was archived remain on the [upstream releases page](https://github.com/mulesoft-labs/net-tools-api/releases), which no longer receives updates.

# References

- [CloudHub 2.0 Infrastructure Considerations](https://docs.mulesoft.com/cloudhub-2/ch2-comparison#infrastructure-considerations)
- [CloudHub 1.0 Load Balancer Architecture](https://docs.mulesoft.com/cloudhub-1/lb-architecture)
- [Enable Last Mile Security in RTF](https://help.mulesoft.com/s/article/How-to-Enable-both-Last-Mile-Security-and-Mutual-TLS-in-Runtime-Fabric)
