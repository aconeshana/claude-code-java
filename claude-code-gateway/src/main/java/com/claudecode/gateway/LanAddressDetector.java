package com.claudecode.gateway;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.platform.Platform;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Discovers LAN, mDNS ({@code .local}), and Tailscale/CGNAT URLs other machines can use to reach
 * the gateway, adapted from {@code yetone/magpie} ({@code internal/gateway/lan.go}).
 *
 * <p>Filters out loopback ({@code 127.0.0.0/8}), link-local ({@code 169.254.0.0/16}), and proxy
 * Fake-IP TUN ranges ({@code 198.18.0.0/15} used by Clash, Mihomo, Surge, sing-box, and Stash)
 * so the Web UI never advertises unreachable virtual interface addresses.
 */
@Explanation("Smart LAN / Bonjour / Tailscale endpoint discovery for cross-machine model sharing")
public final class LanAddressDetector {

    /**
     * One reachable base URL for the gateway, tagged by network kind ({@code "lan"},
     * {@code "mdns"}, {@code "tailscale"}, or {@code "public"}).
     */
    public record DetectedEndpoint(String kind, String host, String url) {}

    private LanAddressDetector() {}

    /** Discovers reachable HTTP endpoints for {@code port} on the current host. */
    public static List<DetectedEndpoint> detect(int port) {
        List<InetAddress> addresses = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces != null) {
                while (interfaces.hasMoreElements()) {
                    NetworkInterface iface = interfaces.nextElement();
                    if (!iface.isUp() || iface.isLoopback()) continue;
                    Enumeration<InetAddress> addrs = iface.getInetAddresses();
                    while (addrs.hasMoreElements()) {
                        addresses.add(addrs.nextElement());
                    }
                }
            }
        } catch (Exception _) {
            // Fall through with whatever addresses were collected.
        }
        String hostName = null;
        try {
            hostName = InetAddress.getLocalHost().getHostName();
        } catch (Exception _) {
            // Best effort.
        }
        return classifyAddresses(addresses, hostName, Platform.IS_DARWIN, port);
    }

    static List<DetectedEndpoint> classifyAddresses(
            List<InetAddress> addresses, String rawHostName, boolean isMac, int port) {
        Set<String> lanIps = new LinkedHashSet<>();
        Set<String> tailscaleIps = new LinkedHashSet<>();
        Set<String> publicIps = new LinkedHashSet<>();

        for (InetAddress addr : addresses) {
            if (!(addr instanceof Inet4Address ipv4)) continue;
            if (ipv4.isLoopbackAddress() || ipv4.isLinkLocalAddress()
                    || ipv4.isAnyLocalAddress() || ipv4.isMulticastAddress()) {
                continue;
            }
            byte[] octets = ipv4.getAddress();
            int b0 = octets[0] & 0xFF;
            int b1 = octets[1] & 0xFF;
            if (isProxyFakeIp(b0, b1)) continue;
            String text = ipv4.getHostAddress();
            if (ipv4.isSiteLocalAddress()) {
                lanIps.add(text);
            } else if (isCarrierGradeNat(b0, b1)) {
                tailscaleIps.add(text);
            } else {
                publicIps.add(text);
            }
        }

        List<DetectedEndpoint> out = new ArrayList<>();
        for (String ip : lanIps) {
            out.add(new DetectedEndpoint("lan", ip, formatHttpUrl(ip, port)));
        }
        String mdnsHost = normalizeMdnsHost(rawHostName, isMac);
        if (mdnsHost != null) {
            out.add(new DetectedEndpoint("mdns", mdnsHost, formatHttpUrl(mdnsHost, port)));
        }
        for (String ip : tailscaleIps) {
            out.add(new DetectedEndpoint("tailscale", ip, formatHttpUrl(ip, port)));
        }
        for (String ip : publicIps) {
            out.add(new DetectedEndpoint("public", ip, formatHttpUrl(ip, port)));
        }
        return Collections.unmodifiableList(out);
    }

    /** {@code 198.18.0.0/15}: IANA benchmarking block used by Clash/Surge/sing-box TUN interfaces. */
    static boolean isProxyFakeIp(int b0, int b1) {
        return b0 == 198 && (b1 == 18 || b1 == 19);
    }

    /** {@code 100.64.0.0/10}: RFC 6598 CGNAT block used by Tailscale and ZeroTier. */
    static boolean isCarrierGradeNat(int b0, int b1) {
        return b0 == 100 && b1 >= 64 && b1 <= 127;
    }

    private static String normalizeMdnsHost(String rawHostName, boolean isMac) {
        if (!isMac || StringUtils.isBlank(rawHostName)) return null;
        String clean = rawHostName.trim().toLowerCase(Locale.ROOT);
        if (Strings.CS.endsWith(clean, ".")) {
            clean = clean.substring(0, clean.length() - 1);
        }
        if (Strings.CS.equals("localhost", clean) || clean.indexOf(':') >= 0) return null;
        if (Strings.CS.endsWith(clean, ".local")) {
            String stem = clean.substring(0, clean.length() - ".local".length());
            return isValidHostLabel(stem) ? clean : null;
        }
        if (clean.indexOf('.') >= 0) return null;
        return isValidHostLabel(clean) ? clean + ".local" : null;
    }

    private static boolean isValidHostLabel(String label) {
        if (label.isEmpty() || label.length() > 63
                || label.charAt(0) == '-' || label.charAt(label.length() - 1) == '-') {
            return false;
        }
        for (int i = 0; i < label.length(); i++) {
            char c = label.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-';
            if (!ok) return false;
        }
        return true;
    }

    private static String formatHttpUrl(String host, int port) {
        return "http://" + host + ":" + port;
    }
}
