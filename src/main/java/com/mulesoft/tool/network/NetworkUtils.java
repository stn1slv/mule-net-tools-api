package com.mulesoft.tool.network;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.BufferedWriter;

public class NetworkUtils {

	private static final List<String> ALLOWED_METHODS =
			Arrays.asList("GET", "POST", "PUT", "PATCH", "DELETE");

	// BASIC, DIGEST, NTLM and NEGOTIATE need curl to answer a 401 challenge, so they
	// cannot be expressed as a fixed header. BEARER can, and remains available through the
	// header parameter, but is offered here too so callers have one consistent place to
	// put credentials. For BEARER the credential is the token, not user:password.
	private static final List<String> ALLOWED_AUTH_TYPES =
			Arrays.asList("BASIC", "DIGEST", "NTLM", "NEGOTIATE", "BEARER");

	public static String ping(String host) throws Exception {
		return execute(new ProcessBuilder("ping", "-c", "4", host));
	}

	public static String resolveIPs(String host, String dnsServer) throws UnknownHostException {
		if (dnsServer.equals("default") || dnsServer == null || dnsServer.isEmpty())
 		{
			InetAddress[] addresses = InetAddress.getAllByName(host);
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < addresses.length; i++) {
				if (i != 0) {
					sb.append("\n");
				}
				sb.append(addresses[i].getHostAddress());
			}
			return sb.toString();
		}	
		else {
 			dnsServer = "@" + dnsServer;
			try {
				return execute(new ProcessBuilder("dig", "+short", dnsServer, host));
			} catch (IOException e) {
				return e.getMessage();
			} 
		}
	}

	public static String curl(String url, String method, String body, String[] headers, Boolean insecure,
			String user, String authType) throws IOException {
		//-i include protocol headers
		//-L follow redirects
		//-k insecure
		//-X the HTTP method sent to the target
		//--data-binary @- the request body, piped through stdin and sent verbatim
		//--proto/--proto-redir restrict curl to http and https, so 'file://' cannot read worker files
		String verb = (method == null || method.trim().isEmpty())
				? "GET" : method.trim().toUpperCase(Locale.ROOT);
		if (!ALLOWED_METHODS.contains(verb)) {
			return "Unsupported HTTP method: " + method
					+ ". Allowed methods: " + String.join(", ", ALLOWED_METHODS);
		}

		List<String> command = new ArrayList<String>();
		command.add("curl");
		if(insecure != null && insecure) command.add("-k");
		command.add("-i");
		command.add("-L");
		// -sS silences the progress meter, which curl writes to stderr whenever stdout is
		// not a terminal. It keeps that noise out of the returned text.
		command.add("-sS");
		// -g disables curl's URL globbing. Without it a url such as http://10.0.0.[1-254]/
		// expands into one request per address, and --max-time bounds each transfer rather
		// than the whole invocation, so a single call could pin this thread for hours.
		command.add("-g");
		// Bound the response so a large or hostile target cannot exhaust worker memory:
		// the whole body is buffered into a String and copied again on the way out.
		command.add("--max-filesize");
		command.add("10485760"); // 10 MiB
		command.add("--connect-timeout");
		command.add("10");
		command.add("--max-time");
		command.add("30");
		command.add("--proto");
		command.add("=http,https");
		command.add("--proto-redir");
		command.add("=http,https");
		command.add("-X");
		command.add(verb);
		if (user != null && !user.trim().isEmpty()) {
			if (user.indexOf('\r') >= 0 || user.indexOf('\n') >= 0) {
				return "Credentials may not contain carriage returns or line feeds.";
			}
			String scheme = (authType == null || authType.trim().isEmpty())
					? "BASIC" : authType.trim().toUpperCase(Locale.ROOT);
			if (!ALLOWED_AUTH_TYPES.contains(scheme)) {
				return "Unsupported authentication type: " + authType + ". Allowed types: "
						+ String.join(", ", ALLOWED_AUTH_TYPES).toLowerCase(Locale.ROOT);
			}
			if ("BEARER".equals(scheme)) {
				// The credential is the token itself; curl turns it into an Authorization
				// header rather than answering a challenge.
				command.add("--oauth2-bearer");
				command.add(user.trim());
			} else {
				command.add("--" + scheme.toLowerCase(Locale.ROOT));
				command.add("-u");
				// curl prompts for a password when the value carries no colon, and stdin here
				// is a pipe rather than a terminal, so an omitted password would hang or eat
				// the request body. A trailing colon means "empty password" and keeps it going.
				command.add(user.indexOf(':') >= 0 ? user : user + ":");
			}
		}
		for (String header : headers ) {
			if (header != null && !header.trim().isEmpty()) {
				if (header.indexOf('\r') >= 0 || header.indexOf('\n') >= 0) {
					// curl writes -H values into the request verbatim, so a line break lets
					// the caller add headers of its own, or write a whole second request
					// line, which would sidestep the method allowlist entirely. The value is
					// deliberately not echoed back.
					return "Header values may not contain carriage returns or line feeds.";
				}
				if (header.trim().startsWith("@")) {
					// curl reads a local file when a header starts with '@' and sends every
					// line of it as a header to the target, which would leak worker files.
					return "Header values starting with '@' are not allowed: " + header;
				}
				command.add("-H");
				command.add(header);
			}
		}
		boolean hasBody = body != null && !body.isEmpty();
		if (hasBody) {
			// '@-' reads the body from stdin. That keeps it clear of the operating system
			// argument limit, which on Linux caps a single argument at 128KB, and keeps the
			// payload out of the process list where any local process could read it.
			command.add("--data-binary");
			command.add("@-");
		}
		command.add("--"); // end of options, so a url starting with '-' is never read as a curl flag
		command.add(url);
		ProcessBuilder pb = new ProcessBuilder(command);
		return hasBody ? execute(pb, body) : execute(pb);
	}

	public static String curlVersion() throws IOException {
		// Reports the worker's curl build: version, TLS backend and supported protocols.
		// Useful when a request behaves unexpectedly, since this tool depends on specific
		// curl flags being available.
		return execute(new ProcessBuilder("curl", "--version"));
	}

	public static String testConnect(String host, String port) {
		long startTime = System.nanoTime();
		long totalTime = System.nanoTime();
		String result = "";
		for (int x = 1; x <= 5; x++) {
			try {
				Socket socket = new Socket();
				startTime = System.nanoTime();
				socket.connect(new InetSocketAddress(host, Integer.parseInt(port)), 10000);
				socket.setSoTimeout(10000);
				if (socket.isConnected()) {
					totalTime = System.nanoTime() - startTime;
					socket.getInputStream();
				}
				socket.close();
			} 
			catch (java.net.UnknownHostException e) {
				return "Could not resolve host " + host;
			}
			catch (java.net.SocketTimeoutException e) {
				return "Timeout while trying to connect to " + host;
			}
			catch (java.lang.IllegalArgumentException e) {
				return e.getMessage();
			}
			catch (Exception e) {
				ByteArrayOutputStream b = new ByteArrayOutputStream();
				e.printStackTrace(new PrintStream(b));
				return b.toString();
			}
			result = result + "Probe " + x + ": Connection successful, RTT=" + Long.toString(totalTime/1000000) + "ms\n";
		}
		return result + "socket test completed";
	}

	public static String traceRoute(String host) throws Exception {
		return execute(new ProcessBuilder("traceroute", "-w", "3", "-q", "1", "-m", "18", "-n", host));
	}

	public static String certest(String host, String port) throws Exception {
		return execute(new ProcessBuilder("openssl", "s_client", "-showcerts", "-servername", host, "-connect", host+":"+port));
	}

	public static String cipherTest(String host, String port) throws Exception {
		String remoteEndpointSupportedCiphers = "List of supported ciphers:\n\n";
		String[] openSslAvailableCiphers = execute(new ProcessBuilder("openssl","ciphers","ALL:!eNULL")).split(":");

		for (String cipher : openSslAvailableCiphers) {
			if (execute(new ProcessBuilder("openssl", "s_client", "-cipher", cipher, "-servername", host, "-connect", host+":"+port)).contains("BEGIN CERTIFICATE")) {
				remoteEndpointSupportedCiphers = remoteEndpointSupportedCiphers + cipher + ": YES\n";
			} else {
				remoteEndpointSupportedCiphers = remoteEndpointSupportedCiphers + cipher + ": NO\n";
			}
		}
		return remoteEndpointSupportedCiphers;
	}

	private static String execute(ProcessBuilder pb) throws IOException {
		return execute(pb, "\n");
	}

	private static String execute(ProcessBuilder pb, String stdinData) throws IOException {
		// Merge stderr into stdout at the OS level. Reading them as two separate streams
		// deadlocks whenever a command fills the stderr pipe while we are still draining
		// stdout, and no amount of quietening individual commands removes that class.
		pb.redirectErrorStream(true);
		Process p = pb.start();
		try {
			try (BufferedWriter writer = new BufferedWriter(
					new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8))) {
				writer.write(stdinData);
				writer.flush();
			} catch (IOException e) {
				// A short-lived command can exit before we finish writing, which closes the
				// pipe. Its output is what we are after, so report that rather than this.
			}
			// Explicit UTF-8: Scanner would otherwise use the platform default, which on a
			// worker with no LANG set can be US-ASCII and would mangle non-ASCII responses.
			try (java.util.Scanner s = new java.util.Scanner(p.getInputStream(), StandardCharsets.UTF_8)
					.useDelimiter("\\A")) {
				return s.hasNext() ? s.next() : "";
			}
		} finally {
			// Reap the child. Without this the worker accumulates defunct processes and
			// file descriptors, which matters most for cipherTest: it spawns one openssl
			// per cipher, so hundreds per call.
			try {
				p.waitFor();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} finally {
				p.destroy();
			}
		}
	}
}
