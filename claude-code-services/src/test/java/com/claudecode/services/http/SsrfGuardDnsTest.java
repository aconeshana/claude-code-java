package com.claudecode.services.http;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SsrfGuardDnsTest {

    @Test
    void blocksPrivateLinkLocalCgnatAndMappedMetadataAddresses() {
        assertTrue(SsrfGuardDns.isBlockedAddress("0.1.2.3"));
        assertTrue(SsrfGuardDns.isBlockedAddress("10.1.2.3"));
        assertTrue(SsrfGuardDns.isBlockedAddress("100.100.100.200"));
        assertTrue(SsrfGuardDns.isBlockedAddress("169.254.169.254"));
        assertTrue(SsrfGuardDns.isBlockedAddress("172.16.1.1"));
        assertTrue(SsrfGuardDns.isBlockedAddress("192.168.1.1"));
        assertTrue(SsrfGuardDns.isBlockedAddress("fc00::1"));
        assertTrue(SsrfGuardDns.isBlockedAddress("fe80::1"));
        assertTrue(SsrfGuardDns.isBlockedAddress("::ffff:169.254.169.254"));
    }

    @Test
    void allowsLoopbackAndPublicAddresses() {
        assertFalse(SsrfGuardDns.isBlockedAddress("127.0.0.1"));
        assertFalse(SsrfGuardDns.isBlockedAddress("::1"));
        assertFalse(SsrfGuardDns.isBlockedAddress("8.8.8.8"));
        assertFalse(SsrfGuardDns.isBlockedAddress("2606:4700:4700::1111"));
    }
}
