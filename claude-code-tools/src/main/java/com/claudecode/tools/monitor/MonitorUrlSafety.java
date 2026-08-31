package com.claudecode.tools.monitor;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.core.engine.SandboxConfig;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Validates Monitor WebSocket destinations before opening a network socket.
 */
final class MonitorUrlSafety {

    @FunctionalInterface
    interface AddressResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    record Result(boolean valid, String message, List<InetAddress> addresses) {
        Result {
            addresses = addresses == null ? List.of() : List.copyOf(addresses);
        }

        static Result validResult() { return new Result(true, null, List.of()); }
        static Result resolved(InetAddress[] addresses) {
            return new Result(true, null, Arrays.asList(addresses));
        }
        static Result invalid(String message) { return new Result(false, message, List.of()); }
    }

    private MonitorUrlSafety() {}

    static Result validate(URI uri) {
        return validate(uri, InetAddress::getAllByName);
    }

    static Result validate(URI uri, AddressResolver resolver) {
        if (uri == null || uri.getScheme() == null) {
            return Result.invalid("WebSocket URL must be absolute");
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!Strings.CS.equals(scheme, "ws") && !Strings.CS.equals(scheme, "wss")) {
            return Result.invalid("WebSocket URL must use ws:// or wss://");
        }
        if (uri.getRawUserInfo() != null) {
            return Result.invalid("WebSocket URL must not contain credentials");
        }
        String host = uri.getHost();
        if (StringUtils.isBlank(host)) {
            return Result.invalid("WebSocket URL must contain a host");
        }
        try {
            InetAddress[] addresses = resolver.resolve(host);
            if (addresses == null || addresses.length == 0) {
                return Result.invalid("WebSocket host did not resolve");
            }
            for (InetAddress address : addresses) {
                if (isBlocked(address)) {
                    return Result.invalid(host + " resolves to " + address.getHostAddress()
                        + ", which is in a private, link-local, or cloud-metadata range");
                }
            }
            return Result.resolved(addresses);
        } catch (UnknownHostException _) {
            return Result.invalid("WebSocket host could not be resolved");
        }
    }

    static Result validateDomainPolicy(URI uri,
                                       SandboxConfig.SandboxNetworkConfig network) {
        if (network == null) return Result.validResult();
        String host = uri.getHost();
        if (host == null) return Result.invalid("WebSocket URL must contain a host");
        for (String denied : network.deniedDomains()) {
            if (matchesDomain(host, denied)) {
                return Result.invalid("WebSocket host is denied by the network policy");
            }
        }
        if (!network.allowedDomains().isEmpty()
                && network.allowedDomains().stream().noneMatch(domain -> matchesDomain(host, domain))) {
            return Result.invalid("WebSocket host is not in the network allowlist");
        }
        return Result.validResult();
    }

    static boolean isBlockedLiteral(String host) {
        if (StringUtils.isBlank(host)) return false;
        boolean possibleV4 = host.chars().allMatch(ch -> Character.isDigit(ch) || ch == '.');
        boolean possibleV6 = host.indexOf(':') >= 0;
        if (!possibleV4 && !possibleV6) return false;
        try {
            return isBlocked(InetAddress.getByName(host));
        } catch (UnknownHostException _) {
            return false;
        }
    }

/** established guard intentionally allows loopback for local development. */
    static boolean isBlocked(InetAddress address) {
        if (address.isLoopbackAddress()) return false;
        byte[] bytes = address.getAddress();
        if (address instanceof Inet6Address) {
            if (bytes.length != 16) return false;
            boolean unspecified = true;
            for (byte value : bytes) {
                if (value != 0) {
                    unspecified = false;
                    break;
                }
            }
            if (unspecified) return true;
            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);
            if ((first & 0xfe) == 0xfc) return true;
            if (first == 0xfe && (second & 0xc0) == 0x80) return true;
            if (isIpv4Mapped(bytes)) {
                return isBlockedV4(new byte[] {bytes[12], bytes[13], bytes[14], bytes[15]});
            }
            return false;
        }
        return address instanceof Inet4Address && bytes.length == 4 && isBlockedV4(bytes);
    }

    private static boolean isBlockedV4(byte[] bytes) {
        int a = Byte.toUnsignedInt(bytes[0]);
        int b = Byte.toUnsignedInt(bytes[1]);
        if (a == 127) return false;
        if (a == 0 || a == 10) return true;
        if (a == 100 && b >= 64 && b <= 127) return true;
        if (a == 169 && b == 254) return true;
        if (a == 172 && b >= 16 && b <= 31) return true;
        return a == 192 && b == 168;
    }

    private static boolean isIpv4Mapped(byte[] bytes) {
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) return false;
        }
        return bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff;
    }

    private static boolean matchesDomain(String host, String rule) {
        if (StringUtils.isBlank(rule)) return false;
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        String normalizedRule = rule.trim().toLowerCase(Locale.ROOT);
        return normalizedHost.equals(normalizedRule)
            || Strings.CS.endsWith(normalizedHost, "." + normalizedRule);
    }
}
