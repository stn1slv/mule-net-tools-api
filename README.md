# Net Tools API

> This repository continues the development of [mulesoft-labs/net-tools-api](https://github.com/mulesoft-labs/net-tools-api), which its owner archived on 4 May 2024 and which is now read-only. Work carries on here: new features, fixes and releases are published in this repository, not the upstream one.

The Net Tools API is a deployable Mule app that you can deploy to CloudHub or any worker cloud. The app will then expose a very simple UI that will allow you to do basic networking commands. The idea is that most networking related issues with your CloudHub VPC and VPN are related to connectivity to your on-prem systems, and most of those issues end up being resolved on the customer end. If you have this tool available to you, you can work with your Networking team to test connectivity to various on-prem systems and verify that firewall and routing rules are working.  It can also be used to generate some traffic that can help with diagnosing networking issues.

This supports HTTP and HTTPS connections with a configurable port for each.

## Requirements

This version requires **Mule runtime 4.9 or later, running on Java 17**. Mule 4.9 does not run on Java 11, so this is a single combination rather than a choice.

If you need to deploy to a worker running Mule 4.4, 4.6 or 4.8, use release [2.5.1](https://github.com/stn1slv/mule-net-tools-api/releases/tag/2.5.1) or earlier, which targets Mule 4.1.3 and above.

## Features

- DNS lookups
- Ping
- TraceRoute
- Opening a TCP socket
- curl request with GET, POST, PUT, PATCH or DELETE, and an optional request body
- Pull SSL certificates
- Check supported ciphers for a given SSL/TLS endpoint

## Latest build

Latest build can be found here: https://github.com/stn1slv/mule-net-tools-api/releases

Builds up to the point the project was archived remain available on the [upstream releases page](https://github.com/mulesoft-labs/net-tools-api/releases), which no longer receives updates.

## Cutting a release

Releases are built by GitHub Actions. Pushing a version tag builds the app and opens a **draft** release with the deployable jar attached, so nothing becomes public until you review the notes and press Publish.

```
# 1. Bump <version> in pom.xml, then commit it
# 2. Tag that commit and push the tag
git tag 2.6.0
git push origin 2.6.0
```

The workflow checks the tag against the version in `pom.xml` and fails immediately if they disagree, so bump the pom first. A leading `v` on the tag is accepted, since older tags used that form.

Building locally needs the same toolchain the workflow uses, Java 17 and any current Maven:

```
mvn clean package
```

# Usage

Every tool is available two ways: through the web UI and through the REST API. Both sit behind the same base URL and the same Basic Authentication.

- CloudHub Shared Load Balancer: `http://{app-name}.{region}.cloudhub.io` where the app-name and region are specific to the deployed app.
- Dedicated Load Balancer: `custom url`.  See *Configuration* section to update settings.

## Web UI

Open the base URL in a browser. Choose a tool from the dropdown, fill in the fields it shows, and press *Run*. Results appear in the console area underneath, and *Clean Console* clears them.

The UI is protected by Basic Authentication, and the default credentials are listed in the *Configuration* section.

# REST API

The UI is a thin layer over a REST API, so anything you can do in the browser you can also script. This is useful for running the same connectivity check from a pipeline, on a schedule, or across several targets at once.

## Base path and authentication

All endpoints live under `/api` on the same host as the UI, and all of them use HTTP Basic Authentication with the `user` and `pass` values from the *Configuration* section.

```
BASE='http://{app-name}.{region}.cloudhub.io/api'
curl -u vpc-tools:SomePass "$BASE/ping?host=10.20.30.40"
```

Every tool runs from the Mule worker, so results describe what the *worker* can reach, which is the whole point when debugging a VPC or VPN path.

## Responses

Successful calls return `200` with `text/plain` containing the raw output of the underlying command, exactly as the UI displays it. There is no JSON envelope and no parsing, so treat the body as human-readable diagnostic text.

A command that runs but fails to connect is still a `200`. For example, an unreachable host returns the curl or ping error text with a `200` status, because the tool did its job: it told you the target is unreachable. Reserve non-`2xx` handling for problems with the request itself.

Errors from the API layer return JSON:

| Status | Body | Cause |
|---|---|---|
| `400` | `{"message": "Bad request"}` | A required parameter is missing, or a value fails validation, such as an unsupported `method` |
| `401` | *(empty)* | Missing or wrong Basic Authentication credentials |
| `404` | `{"message": "Resource not found"}` | Unknown path under `/api` |
| `405` | `{"message": "Method not allowed"}` | Right path, wrong HTTP method, such as `POST /api/ping` |
| `415` | `{"message": "Unsupported media type"}` | A `POST /api/curl` body sent with a content type other than the three supported ones |

## Endpoints

| Method | Path | Parameters | What it does |
|---|---|---|---|
| `GET` | `/api/ping` | `host` | Sends four ICMP echo requests |
| `GET` | `/api/traceroute` | `host` | Traces the network path, up to 18 hops |
| `GET` | `/api/dns` | `host`, `dnsServer` *(optional)* | Resolves a name; with `dnsServer` it queries that resolver via `dig` |
| `GET` | `/api/socket` | `host`, `port` | Opens a TCP connection five times and reports round-trip time |
| `GET` | `/api/certest` | `host`, `port` | Retrieves the certificate chain presented by a TLS endpoint |
| `GET` | `/api/ciphertest` | `host`, `port` | Tests every cipher the local OpenSSL knows against the endpoint |
| `GET` | `/api/curl` | `url`, `method`, `header`, `insecure` | Sends an HTTP request without a body |
| `POST` | `/api/curl` | same, plus a request body | Sends an HTTP request with a body |
| `GET` | `/api/diagnostics` | *(none)* | Reports the worker's own tooling, currently the curl build |

### Examples

Check that the worker can resolve and reach an on-prem host:

```
curl -u vpc-tools:SomePass "$BASE/dns?host=erp.internal.example.com"
curl -u vpc-tools:SomePass "$BASE/ping?host=erp.internal.example.com"
```

Resolve through a specific DNS server, which is how you confirm a private zone is being served:

```
curl -u vpc-tools:SomePass "$BASE/dns?host=erp.internal.example.com&dnsServer=10.0.0.2"
```

Confirm a firewall rule actually allows the port, which is usually more informative than ping because many networks drop ICMP:

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

Note that `ciphertest` tries every cipher in turn, so it takes noticeably longer than the others.

Check what the worker itself is working with, which is the one call that probes nothing remote:

```
curl -u vpc-tools:SomePass "$BASE/diagnostics"
```

It returns `curl --version`: the version, TLS backend and supported protocols. Worth checking when a request behaves unexpectedly, since the curl tool depends on specific flags being available in the worker's build.

Remember to URL-encode parameter values. This matters most for `/api/curl`, where `url` and `header` values routinely contain `&`, `=` and `:`.

## The curl endpoint in detail

The curl tool sends `GET`, `POST`, `PUT`, `PATCH` or `DELETE`. Any other value for the `method` parameter is rejected with a `400 Bad Request`.

There are two endpoints on the same path. Use `GET /api/curl` when the request needs no body:

```
curl -u vpc-tools:SomePass \
  "$BASE/curl?url=https://internal.example.com/health&method=GET"
```

Use `POST /api/curl` when it does. Your payload goes in the request body and is forwarded to the target unchanged when you send it as `text/plain` UTF-8 (see the notes on re-serialisation and encoding below):

```
curl -u vpc-tools:SomePass -X POST \
  -H 'Content-Type: text/plain' \
  --data-raw '{"id": 1}' \
  "$BASE/curl?url=https://internal.example.com/orders&method=POST&header=Content-Type:application/json"
```

Note the two separate content types in that example. The `-H 'Content-Type: text/plain'` tells *this API* how to read your payload; the `header=Content-Type:application/json` is what the *target* will receive.

In the UI the same choice is automatic: pick the method from the dropdown next to the URL field and type the payload in the *request body* box. Leaving the body empty sends a request without a payload.

Query parameters for both endpoints:

- `url`: the target URL. Required. Must be `http` or `https`.
- `method`: the HTTP method sent to the target. Defaults to `GET` when omitted, on both endpoints. Set it explicitly to `POST` when sending a body, otherwise the payload is attached to a GET request, which most servers ignore.
- `header`: a `name:value` header for the target request. Repeat the parameter for multiple headers.
- `insecure`: `true` skips TLS certificate verification (curl's `-k`). Defaults to `false`, and the UI checkbox is unticked to match, so certificates are verified unless you deliberately turn that off.

**On encoding:** the body is re-encoded as UTF-8 before it reaches the target. A payload sent in another charset, such as `text/plain; charset=ISO-8859-1`, therefore arrives as UTF-8 even though a `header=Content-Type:...;charset=ISO-8859-1` you set says otherwise, and binary payloads are not preserved. This tool is for text.

Things worth knowing:

- **The `Content-Type` the target receives comes from the `header` parameter**, not from the content type used to call this API. This is what lets you send a SOAP envelope (`header=Content-Type:text/xml`), a form post (`header=Content-Type:application/x-www-form-urlencoded`) or anything else. If you send a body but no `Content-Type` header, curl labels it `application/x-www-form-urlencoded`, which is rarely what you want, so set the header explicitly.
- **The POST endpoint accepts `application/json`, `application/xml` and `text/plain`.** JSON and XML bodies are parsed and re-serialised on the way through, so exact whitespace is not preserved, and a payload that does not parse fails with a `500` rather than a clean `400`. To send a payload byte for byte, including a deliberately malformed one, call the API with `Content-Type: text/plain`. The UI always uses `text/plain`.
- **Requests time out.** curl runs with `--connect-timeout 10` and `--max-time 30`, so a blackholed host fails within about 30 seconds instead of holding a worker thread open. URL globbing is disabled (`-g`), so a url such as `http://10.0.0.[1-254]/` is treated as a single literal address rather than expanding into 254 requests, each with its own timeout.
- **Responses are capped at 10 MiB** (`--max-filesize`). The whole body is buffered in memory, so a larger response is refused rather than risking the worker.
- **Only `http` and `https` are allowed** (curl's `--proto` and `--proto-redir`). Other schemes such as `file://` are refused, on the original request and on any redirect.
- **Header values may not start with `@`, or contain line breaks.** curl treats a leading `@` as "read this local file and send every line as a header", which would disclose files from the Mule worker. A carriage return or line feed inside a header value would let the caller append headers of its own, or write an entire second request line, sidestepping the method allowlist.
- **Redirects are followed** (curl's `-L`), and because the method is always set explicitly the *same* method is used on every hop. A redirected `POST` therefore arrives at the final host as a `POST`, but **curl does not resend the body**, so the final request carries an empty payload. If a target redirects, treat the response as evidence about routing rather than about how it handles your payload.
- **Credentials for the target go in the `header` parameter**, for example `header=Authorization:Bearer%20eyJ...`. The application logs only the method and path, not the query string, so these values do not reach the log. They are still part of the request URL, so anything else in front of the app that logs full URLs, such as a load balancer or proxy, would still record them.
- **Only the scheme is restricted, not the destination.** `http` and `https` to *any* reachable address are allowed by design, which is the point of the tool. That includes the worker's own listener on `127.0.0.1` and, on CloudHub 1.0, the instance metadata service on `169.254.169.254`. Anyone who can authenticate to this app can therefore reach whatever the worker can reach. Treat access to this tool as equivalent to shell access on the worker's network, and set a strong `pass`.

# Configuration
The properties below can be set on the app to override the default settings.  The proper ports must be set to accommodate load balancer and VPC firewall rule settings.  The default settings are for the CloudHub shared load balancer HTTP endpoint.

- `user`: User name for login.  Defaults to `vpc-tools`
- `pass`: Password for login.  Defaults to `SomePass`
- `httpPort`: Sets the listener port for HTTP.  Defaults to `8081`
- `httpsPort`: Sets the listener port for HTTPS.  Defaults to `8082`
- `httpListener`: The running state of the HTTP endpoint flows.  Defaults to `started`.  Options: `started` or `stopped`.  Stop this to disable HTTP endpoint on CloudHub 1.0 or non-RTF infrastructure.  This doesn't affect RTF or CloudHub 2.0 because only a single HTTP port is used.
- `ignoreFiles`: Comma-delimited list of browser-requested resource files for this app to ignore.  Defaults to `favicon.ico`.

## Network Considerations

- `httpsPort` and `httpPort` **must always** be different numbers, even if `httpListener=stopped`.  This is because both HTTP and HTTPS listener configurations are always created, even if the HTTP endpoint is not enabled.
- CloudHub 2.0 and RTF only use a single port for the HTTP listener.  This means you can only run either HTTP or HTTPS, but not both at the same time.  Make sure the property you want to use is set to the proper port and the other is set to another unused port.
- When using CloudHub 2.0 and RTF, you must enable *Last-Mile Security* in the app's Ingress tab if you want to use HTTPS.
- This does not use `http.port` and `https.port` properties since those are overrriden on Cloudhub 2.0 and RTF to the same port and will prevent the app from starting because of a port conflict.

# References
- [CloudHub 2.0 Infrastructure Considerations](https://docs.mulesoft.com/cloudhub-2/ch2-comparison#infrastructure-considerations)
- [CloudHub 1.0 Load Balancer Architecture](https://docs.mulesoft.com/cloudhub-1/lb-architecture)
- [Enable Last Mile Security in RTF](https://help.mulesoft.com/s/article/How-to-Enable-both-Last-Mile-Security-and-Mutual-TLS-in-Runtime-Fabric)

# Maintenance
This uses the JS libraries below.
- jQuery 1.11.3 [min](https://code.jquery.com/jquery-1.11.3.min.js) and [map](https://code.jquery.com/jquery-1.11.3.min.map).
- [Toastr](https://github.com/CodeSeven/toastr) 2.1.4 [min, map, and css](https://cdnjs.com/libraries/toastr.js).

