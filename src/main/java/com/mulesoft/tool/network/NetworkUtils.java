package com.mulesoft.tool.network;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.SequenceInputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
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

	public static String curl(String url, String method, String body, String[] headers, Boolean insecure) throws IOException {
		//-i include protocol headers
		//-L follow redirects
		//-k insecure
		//-X the HTTP method sent to the target
		//--data-raw the request body, sent verbatim ('-d' would read a local file for an '@' prefix)
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
		for (String header : headers ) {
			if (header != null && !header.trim().isEmpty()) {
				if (header.trim().startsWith("@")) {
					// curl reads a local file when a header starts with '@' and sends every
					// line of it as a header to the target, which would leak worker files.
					return "Header values starting with '@' are not allowed: " + header;
				}
				command.add("-H");
				command.add(header);
			}
		}
		if (body != null && !body.isEmpty()) {
			command.add("--data-raw");
			command.add(body);
		}
		command.add("--"); // end of options, so a url starting with '-' is never read as a curl flag
		command.add(url);
		return execute(new ProcessBuilder(command));
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
		Process p = pb.start();
		OutputStream stdin = p.getOutputStream();
		BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(stdin));
		writer.write("\n");
        writer.flush();
        writer.close();
		SequenceInputStream sis = new SequenceInputStream(p.getInputStream(), p.getErrorStream());
		java.util.Scanner s = new java.util.Scanner(sis).useDelimiter("\\A");
		return s.hasNext() ? s.next() : "";
	}
}
