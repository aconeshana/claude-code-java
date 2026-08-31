package com.claudecode.tools.monitor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

class MonitorUrlSafetyTest {

    @Test
    void acceptsPublicWebSocketTargets() throws Exception {
        var result = MonitorUrlSafety.validate(URI.create("wss://events.example.com/feed"),
            _ -> new InetAddress[] { InetAddress.getByName("93.184.216.34") });

        assertTrue(result.valid());
    }

    @Test
    void allowsLoopbackForLocalDevelopmentButRejectsPrivateAndCredentialedTargets() throws Exception {
        assertTrue(MonitorUrlSafety.validate(URI.create("ws://127.0.0.1/events"),
            _ -> new InetAddress[] { InetAddress.getByName("127.0.0.1") }).valid());
        assertFalse(MonitorUrlSafety.validate(URI.create("ws://10.0.0.7/events"),
            _ -> new InetAddress[] { InetAddress.getByName("10.0.0.7") }).valid());
        assertFalse(MonitorUrlSafety.validate(URI.create("wss://user:pass@example.com/events"),
            _ -> new InetAddress[] { InetAddress.getByName("93.184.216.34") }).valid());
        assertFalse(MonitorUrlSafety.validate(URI.create("https://example.com/events"),
            _ -> new InetAddress[] { InetAddress.getByName("93.184.216.34") }).valid());
    }

    @Test
    void matchesReleasedSsrfGuardAddressRanges() throws Exception {
        for (String allowed : List.of(
                "127.0.0.1", "127.255.255.255", "::1",
                "8.8.8.8", "192.0.2.1", "2001:db8::1")) {
            assertFalse(MonitorUrlSafety.isBlocked(InetAddress.getByName(allowed)), allowed);
        }
        for (String blocked : List.of(
                "0.1.2.3", "10.0.0.1", "100.64.0.1", "100.127.255.255",
                "169.254.169.254", "172.16.0.1", "172.31.255.255", "192.168.1.1",
                "::", "fc00::1", "fdff::1", "fe80::1", "febf::1",
                "::ffff:10.0.0.1", "::ffff:a9fe:a9fe")) {
            assertTrue(MonitorUrlSafety.isBlocked(InetAddress.getByName(blocked)), blocked);
        }
    }

    @Test
    void rejectsTheWholeDnsAnswerWhenAnyAddressIsPrivate() throws Exception {
        var result = MonitorUrlSafety.validate(URI.create("wss://events.example.com/feed"),
            _ -> new InetAddress[] {
                InetAddress.getByName("93.184.216.34"),
                InetAddress.getByName("169.254.169.254")
            });

        assertFalse(result.valid());
    }
}
