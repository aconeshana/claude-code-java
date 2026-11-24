package com.claudecode.runtime.sessionlink;

import com.claudecode.core.annotation.Explanation;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Objects;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Local IPC endpoint for a {@link SessionLinkServer} / {@link SessionLinkClient} pair.
 *
 * <p>Prefers a kernel Unix domain socket ({@link UnixDomain}) on macOS, Linux, and modern
 * Windows ({@code afunix.sys}), and transparently supports an authenticated loopback-only TCP
 * fallback ({@link TcpLoopback}, {@code 127.0.0.1:<port>}) when {@code AF_UNIX} is unavailable
 * on stripped Windows images or when a Windows user profile path exceeds the 108-byte
 * {@code sun_path} limit.
 */
@Explanation("Cross-platform local Session Link endpoint supporting UDS and loopback TCP fallback")
public sealed interface SessionLinkEndpoint
        permits SessionLinkEndpoint.UnixDomain, SessionLinkEndpoint.TcpLoopback {

    /**
     * Conservative UTF-8 byte ceiling below macOS (104 bytes) and Linux/Windows (108 bytes)
     * {@code sockaddr_un.sun_path} limits, leaving room for the trailing NUL terminator.
     */
    int MAX_UNIX_SOCKET_PATH_BYTES = 100;

    /** Value written to {@code WebWorkerRegistration.socket()}. */
    String toRegistrationString();

    /** URI form passed to sidecars via {@code CC_SESSION_LINK_ENDPOINT}. */
    String toSidecarUri();

    /** Local filesystem Unix domain socket endpoint. */
    record UnixDomain(Path path) implements SessionLinkEndpoint {
        public UnixDomain {
            Objects.requireNonNull(path, "path");
            if (!path.isAbsolute()) {
                throw new IllegalArgumentException("Session Link socket path must be absolute: " + path);
            }
        }

        public boolean withinPathLimit() {
            return withinUnixPathLimit(path);
        }

        @Override
        public String toRegistrationString() {
            return path.toString();
        }

        @Override
        public String toSidecarUri() {
            return "unix://" + path;
        }
    }

    /** Loopback-only TCP endpoint ({@code 127.0.0.1} or {@code ::1}); non-loopback hosts are rejected. */
    record TcpLoopback(InetSocketAddress address) implements SessionLinkEndpoint {
        public TcpLoopback {
            Objects.requireNonNull(address, "address");
            if (address.isUnresolved() || address.getAddress() == null) {
                throw new IllegalArgumentException("TCP Session Link address must be resolved: " + address);
            }
            if (!address.getAddress().isLoopbackAddress()) {
                throw new IllegalArgumentException(
                    "TCP Session Link endpoint must bind loopback only, got: " + address.getAddress());
            }
        }

        public static TcpLoopback ephemeral() {
            return ofPort(0);
        }

        public static TcpLoopback ofPort(int port) {
            try {
                return new TcpLoopback(new InetSocketAddress(
                    InetAddress.getByAddress("127.0.0.1", new byte[] {127, 0, 0, 1}), port));
            } catch (UnknownHostException impossible) {
                throw new IllegalStateException(impossible);
            }
        }

        @Override
        public String toRegistrationString() {
            InetAddress addr = address.getAddress();
            String host = addr instanceof Inet6Address
                ? "[" + addr.getHostAddress() + "]"
                : addr.getHostAddress();
            return "tcp://" + host + ":" + address.getPort();
        }

        @Override
        public String toSidecarUri() {
            return toRegistrationString();
        }
    }

    static boolean withinUnixPathLimit(Path path) {
        return path != null
            && path.toString().getBytes(StandardCharsets.UTF_8).length <= MAX_UNIX_SOCKET_PATH_BYTES;
    }

    /**
     * Parses a worker registration endpoint string:
     * <ul>
     *   <li>{@code tcp://127.0.0.1:<port>} or {@code tcp://[::1]:<port>} (strictly loopback, port 1..65535)</li>
     *   <li>{@code unix://<path>}</li>
     *   <li>raw absolute filesystem socket path (legacy / default format)</li>
     * </ul>
     */
    static SessionLinkEndpoint parse(String raw) {
        if (StringUtils.isBlank(raw)) {
            throw new IllegalArgumentException("Session Link endpoint must not be blank");
        }
        String trimmed = raw.trim();
        if (Strings.CI.startsWith(trimmed, "tcp://")) {
            return parseTcpLoopback(trimmed.substring("tcp://".length()));
        }
        if (Strings.CI.startsWith(trimmed, "unix://")) {
            String pathPart = trimmed.substring("unix://".length());
            // Normalize unix:///C:/Users/... URI form on Windows if present
            if (pathPart.length() >= 4
                    && pathPart.charAt(0) == '/'
                    && Character.isLetter(pathPart.charAt(1))
                    && pathPart.charAt(2) == ':') {
                pathPart = pathPart.substring(1);
            }
            return parseUnixPath(pathPart);
        }
        return parseUnixPath(trimmed);
    }

    private static UnixDomain parseUnixPath(String rawPath) {
        if (StringUtils.isBlank(rawPath)) {
            throw new IllegalArgumentException("Session Link Unix socket path is empty");
        }
        try {
            return new UnixDomain(Path.of(rawPath));
        } catch (InvalidPathException failure) {
            throw new IllegalArgumentException("Invalid Session Link socket path: " + rawPath, failure);
        }
    }

    private static TcpLoopback parseTcpLoopback(String authority) {
        if (StringUtils.isBlank(authority) || Strings.CS.contains(authority, "/")) {
            throw new IllegalArgumentException("Invalid TCP Session Link authority: " + authority);
        }
        String host;
        String portPart;
        if (Strings.CS.startsWith(authority, "[")) {
            int close = authority.indexOf(']');
            if (close <= 1 || close + 2 > authority.length() || authority.charAt(close + 1) != ':') {
                throw new IllegalArgumentException("Invalid IPv6 TCP Session Link authority: " + authority);
            }
            host = authority.substring(1, close);
            portPart = authority.substring(close + 2);
        } else {
            int colon = authority.lastIndexOf(':');
            if (colon <= 0 || colon + 1 >= authority.length()
                    || authority.indexOf(':') != colon) {
                throw new IllegalArgumentException("Invalid TCP Session Link authority: " + authority);
            }
            host = authority.substring(0, colon);
            portPart = authority.substring(colon + 1);
        }
        int port;
        try {
            port = Integer.parseInt(portPart);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("Invalid TCP Session Link port: " + portPart, failure);
        }
        if (port <= 0 || port > 65_535) {
            throw new IllegalArgumentException("Connected TCP Session Link port out of range: " + port);
        }
        InetAddress addr = resolveLiteralLoopback(host);
        return new TcpLoopback(new InetSocketAddress(addr, port));
    }

    private static InetAddress resolveLiteralLoopback(String host) {
        try {
            if (Strings.CS.equals("127.0.0.1", host) || Strings.CI.equals("localhost", host)) {
                return InetAddress.getByAddress("127.0.0.1", new byte[] {127, 0, 0, 1});
            }
            if (Strings.CS.equals("::1", host) || Strings.CS.equals("0:0:0:0:0:0:0:1", host)) {
                byte[] v6Loop = new byte[16];
                v6Loop[15] = 1;
                return InetAddress.getByAddress("::1", v6Loop);
            }
        } catch (UnknownHostException impossible) {
            throw new IllegalStateException(impossible);
        }
        throw new IllegalArgumentException(
            "Refusing non-loopback TCP Session Link host '" + host + "'");
    }
}
