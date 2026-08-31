package com.claudecode.tools.sandbox;

import java.util.Locale;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Domain-allowlisting HTTP proxy used to enforce {@code sandbox.network.allowedDomains}.
 */
final class SandboxNetworkProxy {

    private final List<String> allowedDomains;
    private final List<String> deniedDomains;
    private final ServerSocket server;
    private final ExecutorService pool;
    private volatile boolean stopped;

    SandboxNetworkProxy(List<String> allowedDomains, List<String> deniedDomains) throws IOException {
        this.allowedDomains = allowedDomains;
        this.deniedDomains = deniedDomains;
        this.server = new ServerSocket(0); // ephemeral port
        this.pool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "sandbox-net-proxy");
            t.setDaemon(true);
            return t;
        });
        this.pool.execute(this::acceptLoop);
    }

    int getPort() {
        return server.getLocalPort();
    }

    void stop() {
        stopped = true;
        try {
            server.close();
        } catch (IOException _) {
            // best-effort shutdown
        }
        pool.shutdownNow();
    }

    /** Proxy env vars (HTTP_PROXY/HTTPS_PROXY/ALL_PROXY + lowercase) for this proxy. */
    Map<String, String> proxyEnvironment() {
        String url = "http://127.0.0.1:" + getPort();
        Map<String, String> env = new HashMap<>();
        env.put("HTTP_PROXY", url);
        env.put("HTTPS_PROXY", url);
        env.put("ALL_PROXY", url);
        env.put("http_proxy", url);
        env.put("https_proxy", url);
        env.put("all_proxy", url);
        return env;
    }

    private void acceptLoop() {
        while (!stopped) {
            try {
                Socket s = server.accept();
                pool.execute(() -> handle(s));
            } catch (IOException _) {
                if (!stopped) return;
            }
        }
    }

    private void handle(Socket client) {
        try (Socket c = client) {
            InputStream in = c.getInputStream();
            OutputStream out = c.getOutputStream();
            // Read the entire request header block (request line + headers) verbatim so
            // we can forward it unchanged to the upstream after the host check.
            byte[] headerBuf = readHeaderBlock(in);
            if (headerBuf == null) return;
            String requestLine = firstLine(headerBuf);
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                sendStatus(out, 400);
                return;
            }
            String method = parts[0];
            String target = parts[1];
            String host = extractHost(method, target, headerBuf);
            if (host == null || !domainAllowed(host)) {
                sendStatus(out, 403); // Forbidden — domain not allowlisted
                return;
            }
            boolean connect = Strings.CI.equals(method, "CONNECT");
            try (Socket upstream = new Socket(host, connect ? defaultPort(target, 443) : defaultPort(target, 80))) {
                if (connect) {
                    sendStatus(out, 200); // 200 Connection established
                    tunnel(in, out, upstream);
                } else {
                    upstream.getOutputStream().write(headerBuf);
                    tunnel(in, out, upstream);
                }
            }
        } catch (Exception _) {
            // any failure just closes the socket
        }
    }

    /** Default port when target has no explicit ":port" (CONNECT/absolute-URI). */
    private static int defaultPort(String target, int dflt) {
        int colon = target.lastIndexOf(':');
        if (colon < 0) return dflt;
        try {
            return Integer.parseInt(target.substring(colon + 1));
        } catch (NumberFormatException _) {
            return dflt;
        }
    }

    /** Resolves the upstream host to check: CONNECT target, or absolute-URI / Host header. */
    private static String extractHost(String method, String target, byte[] headerBuf) {
        if (Strings.CI.equals(method, "CONNECT")) {
            int colon = target.lastIndexOf(':');
            return colon < 0 ? target : target.substring(0, colon);
        }
        if (Strings.CS.startsWith(target, "http://")) {
            String rest = target.substring(7);
            int slash = rest.indexOf('/');
            String authority = slash < 0 ? rest : rest.substring(0, slash);
            int colon = authority.lastIndexOf(':');
            return colon < 0 ? authority : authority.substring(0, colon);
        }
        // Plain origin-form: look for the Host header.
        for (String line : new String(headerBuf, StandardCharsets.UTF_8).split("\r\n")) {
            if (line.regionMatches(true, 0, "Host:", 0, 5)) {
                String h = line.substring(5).trim();
                int colon = h.lastIndexOf(':');
                return colon < 0 ? h : h.substring(0, colon);
            }
        }
        return null;
    }

    private boolean domainAllowed(String host) {
        if (Strings.CS.startsWith(host, "[")) {
            int end = host.indexOf(']');
            if (end > 0) host = host.substring(1, end);
        }
        String h = host.toLowerCase(Locale.ROOT);

        for (String d : deniedDomains) {
            String dom = d.toLowerCase(Locale.ROOT).trim();
            if (StringUtils.isEmpty(dom)) continue;
            if (Strings.CS.equals(h, dom) || Strings.CS.endsWith(h, "." + dom)) return false;
        }
        // No allowlist means "allow everything except the denylist" (so a
        // denylist alone still routes through this proxy).
        if (allowedDomains.isEmpty()) return true;
        for (String d : allowedDomains) {
            String dom = d.toLowerCase(Locale.ROOT).trim();
            if (StringUtils.isEmpty(dom)) continue;
            if (Strings.CS.equals(h, dom) || Strings.CS.endsWith(h, "." + dom)) return true;
        }
        return false;
    }

    /** Reads the request header block (up to and including the blank line) into a buffer. */
    private static byte[] readHeaderBlock(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int state = 0; // count consecutive CR/LF to detect end of headers
        int b;
        while ((b = in.read()) != -1) {
            bos.write(b);
            if (b == '\n') {
                if (state == 1) break; // saw \r\n\r\n (or \n\n)
                state = 1;
            } else if (b != '\r') {
                state = 0;
            }
        }
        if (bos.size() == 0) return null;
        return bos.toByteArray();
    }

    private static String firstLine(byte[] buf) {
        int end = 0;
        while (end < buf.length && buf[end] != '\r' && buf[end] != '\n') end++;
        return new String(buf, 0, end, StandardCharsets.UTF_8);
    }

    private static void sendStatus(OutputStream out, int code) throws IOException {
        String msg = code == 200 ? "Connection established"
            : code == 400 ? "Bad Request" : "Forbidden";
        String resp = "HTTP/1.1 " + code + " " + msg + "\r\n\r\n";
        out.write(resp.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /** Bidirectionally pipes client<->upstream until either side closes. */
    private static void tunnel(InputStream clientIn, OutputStream clientOut, Socket upstream)
            throws IOException {
        InputStream upIn = upstream.getInputStream();
        OutputStream upOut = upstream.getOutputStream();
        Thread t = new Thread(() -> copy(clientIn, upOut), "sandbox-proxy-fwd");
        t.setDaemon(true);
        t.start();
        copy(upIn, clientOut);
        try {
            t.join(5000);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    private static void copy(InputStream in, OutputStream out) {
        byte[] buf = new byte[8192];
        int n;
        try {
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (IOException _) {
            // pipe closed
        }
    }
}
