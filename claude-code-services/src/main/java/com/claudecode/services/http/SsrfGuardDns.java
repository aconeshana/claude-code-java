package com.claudecode.services.http;

import okhttp3.Dns;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * DNS resolver for direct HTTP hooks that rejects private, link-local, and
 * other metadata-capable address ranges while intentionally allowing loopback
 * for local development hook servers.
 *
 * <ul>
 *   <li>validates the
 *       exact addresses returned to the socket layer, closing DNS-rebinding
 *       gaps.</li>
 * </ul>
 */
final class SsrfGuardDns implements Dns {

    static final SsrfGuardDns SYSTEM = new SsrfGuardDns(Dns.SYSTEM);

    private final Dns delegate;

    SsrfGuardDns(Dns delegate) {
        this.delegate = delegate;
    }

    @Override
    public List<InetAddress> lookup(String hostname) throws UnknownHostException {
        List<InetAddress> addresses = delegate.lookup(hostname);
        for (InetAddress address : addresses) {
            if (isBlockedAddress(address)) {
                throw new UnknownHostException(
                    "HTTP hook blocked by SSRF guard: " + hostname + " resolved to "
                        + address.getHostAddress());
            }
        }
        return addresses;
    }

    static boolean isBlockedAddress(String address) {
        try {
            return isBlockedAddress(InetAddress.getByName(address));
        } catch (UnknownHostException _) {
            return false;
        }
    }

    private static boolean isBlockedAddress(InetAddress address) {
        if (address.isLoopbackAddress()) return false;
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) return isBlockedV4(bytes);
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
        if ((first & 0xfe) == 0xfc) return true;                 // fc00::/7
        if (first == 0xfe && (second & 0xc0) == 0x80) return true; // fe80::/10

        boolean mapped = true;
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) {
                mapped = false;
                break;
            }
        }
        mapped = mapped && bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff;
        if (mapped) {
            return isBlockedV4(new byte[] {bytes[12], bytes[13], bytes[14], bytes[15]});
        }
        return false;
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
}
