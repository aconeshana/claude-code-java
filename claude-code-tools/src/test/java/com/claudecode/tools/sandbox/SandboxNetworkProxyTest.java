package com.claudecode.tools.sandbox;

import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for {@link SandboxNetworkProxy}: an upstream echo server
 * stands in for a real host, and we assert that the proxy tunnels CONNECTs to
 * allowlisted domains but returns 403 for everything else.
 */
class SandboxNetworkProxyTest {

    @Test
    void connect_allowedDomain_tunnels() throws IOException {
        ServerSocket echo = new ServerSocket(0);
        int echoPort = echo.getLocalPort();
        Thread echoThread = new Thread(() -> {
            try (Socket s = echo.accept()) {
                InputStream in = s.getInputStream();
                OutputStream out = s.getOutputStream();
                byte[] buf = new byte[1024];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    out.flush();
                }
            } catch (IOException _) {
                // echo client closed
            }
        });
        echoThread.setDaemon(true);
        echoThread.start();

        // Use a resolvable allowlisted host so the proxy can actually connect.
        SandboxNetworkProxy proxy = new SandboxNetworkProxy(List.of("127.0.0.1"), List.of());
        try {
            try (Socket client = new Socket("127.0.0.1", proxy.getPort())) {
                OutputStream out = client.getOutputStream();
                InputStream in = client.getInputStream();
                out.write(("CONNECT 127.0.0.1:" + echoPort + " HTTP/1.1\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
                out.flush();
                String status = readLine(in);
                assertTrue(Strings.CS.contains(status, "200"), status);
                // Consume the trailing CRLF of the "200 Connection established"
                // response header block before the tunneled payload begins.
                in.read();
                in.read();
                out.write("hello".getBytes(StandardCharsets.UTF_8));
                out.flush();
                byte[] buf = new byte[64];
                int n = in.read(buf);
                assertEquals("hello", new String(buf, 0, n, StandardCharsets.UTF_8));
            }
        } finally {
            proxy.stop();
            echo.close();
        }
    }

    @Test
    void connect_disallowedDomain_isForbidden() throws IOException {
        SandboxNetworkProxy proxy = new SandboxNetworkProxy(List.of("127.0.0.1"), List.of());
        try {
            try (Socket client = new Socket("127.0.0.1", proxy.getPort())) {
                OutputStream out = client.getOutputStream();
                InputStream in = client.getInputStream();
                // A host not in the allowlist is refused without any upstream connect.
                out.write("CONNECT 10.255.255.1:443 HTTP/1.1\r\n\r\n"
                    .getBytes(StandardCharsets.UTF_8));
                out.flush();
                String status = readLine(in);
                assertTrue(Strings.CS.contains(status, "403"), status);
            }
        } finally {
            proxy.stop();
        }
    }

    @Test
    void connect_deniedDomain_isForbidden() throws IOException {
        // Denylist alone (empty allowlist) still routes through the proxy; the
        // denied host is refused even though the allowlist would otherwise allow all.
        SandboxNetworkProxy proxy = new SandboxNetworkProxy(List.of(), List.of("10.255.255.1"));
        try {
            try (Socket client = new Socket("127.0.0.1", proxy.getPort())) {
                OutputStream out = client.getOutputStream();
                InputStream in = client.getInputStream();
                out.write("CONNECT 10.255.255.1:443 HTTP/1.1\r\n\r\n"
                    .getBytes(StandardCharsets.UTF_8));
                out.flush();
                String status = readLine(in);
                assertTrue(Strings.CS.contains(status, "403"), status);
            }
        } finally {
            proxy.stop();
        }
    }

    @Test
    void connect_deniedBeatsAllow_isForbidden() throws IOException {
// A host present in BOTH lists must be refused: the denylist wins.
        SandboxNetworkProxy proxy = new SandboxNetworkProxy(
            List.of("10.255.255.1"), List.of("10.255.255.1"));
        try {
            try (Socket client = new Socket("127.0.0.1", proxy.getPort())) {
                OutputStream out = client.getOutputStream();
                InputStream in = client.getInputStream();
                out.write("CONNECT 10.255.255.1:443 HTTP/1.1\r\n\r\n"
                    .getBytes(StandardCharsets.UTF_8));
                out.flush();
                String status = readLine(in);
                assertTrue(Strings.CS.contains(status, "403"), status);
            }
        } finally {
            proxy.stop();
        }
    }

    @Test
    void connect_denylistOnly_allowsOtherDomains_tunnels() throws IOException {
        // With an empty allowlist + a denylist, a non-denied host tunnels through.
        ServerSocket echo = new ServerSocket(0);
        int echoPort = echo.getLocalPort();
        Thread echoThread = new Thread(() -> {
            try (Socket s = echo.accept()) {
                InputStream in = s.getInputStream();
                OutputStream out = s.getOutputStream();
                byte[] buf = new byte[1024];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    out.flush();
                }
            } catch (IOException _) {
                // echo client closed
            }
        });
        echoThread.setDaemon(true);
        echoThread.start();

        SandboxNetworkProxy proxy = new SandboxNetworkProxy(List.of(), List.of("10.255.255.1"));
        try {
            try (Socket client = new Socket("127.0.0.1", proxy.getPort())) {
                OutputStream out = client.getOutputStream();
                InputStream in = client.getInputStream();
                out.write(("CONNECT 127.0.0.1:" + echoPort + " HTTP/1.1\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
                out.flush();
                String status = readLine(in);
                assertTrue(Strings.CS.contains(status, "200"), status);
                in.read();
                in.read();
                out.write("world".getBytes(StandardCharsets.UTF_8));
                out.flush();
                byte[] buf = new byte[64];
                int n = in.read(buf);
                assertEquals("world", new String(buf, 0, n, StandardCharsets.UTF_8));
            }
        } finally {
            proxy.stop();
            echo.close();
        }
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\r') continue;
            if (b == '\n') break;
            sb.append((char) b);
        }
        return sb.toString();
    }
}
