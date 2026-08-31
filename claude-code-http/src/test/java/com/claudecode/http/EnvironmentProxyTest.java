package com.claudecode.http;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EnvironmentProxyTest {

    @Test
    void selectsSchemeSpecificProxyAndHonorsNoProxy() {
        EnvironmentProxy proxy = EnvironmentProxy.from(Map.of(
            "HTTP_PROXY", "http://proxy.example:8080",
            "HTTPS_PROXY", "http://secure-proxy.example:8443",
            "NO_PROXY", "localhost .internal.example,api.example:9443"));

        assertProxy(proxy.select(URI.create("http://public.example/resource")),
            "proxy.example", 8080);
        assertProxy(proxy.select(URI.create("https://public.example/resource")),
            "secure-proxy.example", 8443);
        assertEquals(List.of(Proxy.NO_PROXY),
            proxy.select(URI.create("https://service.internal.example/resource")));
        assertEquals(List.of(Proxy.NO_PROXY),
            proxy.select(URI.create("https://api.example:9443/resource")));
    }

    @Test
    void wildcardNoProxyDisablesAllEnvironmentProxies() {
        EnvironmentProxy proxy = EnvironmentProxy.from(Map.of(
            "ALL_PROXY", "http://proxy.example:8080",
            "NO_PROXY", "*"));

        assertEquals(List.of(Proxy.NO_PROXY),
            proxy.select(URI.create("https://public.example/resource")));
    }

    @Test
    void ignoresUnsupportedOrMalformedProxyUris() {
        EnvironmentProxy proxy = EnvironmentProxy.from(Map.of(
            "HTTPS_PROXY", "file:///tmp/not-a-proxy"));

        assertEquals(List.of(Proxy.NO_PROXY),
            proxy.select(URI.create("https://public.example/resource")));
    }

    @Test
    void refreshesExistingSelectorForSettingsEnvironmentChanges() {
        EnvironmentProxy proxy = EnvironmentProxy.from(Map.of(
            "HTTPS_PROXY", "http://old-proxy.example:8080"));

        proxy.refreshFrom(Map.of(
            "HTTPS_PROXY", "http://new-proxy.example:8443",
            "NO_PROXY", "public.example"));

        assertProxy(proxy.select(URI.create("https://private.example/resource")),
            "new-proxy.example", 8443);
        assertEquals(List.of(Proxy.NO_PROXY),
            proxy.select(URI.create("https://public.example/resource")));
    }

    private static void assertProxy(List<Proxy> proxies, String host, int port) {
        assertEquals(1, proxies.size());
        InetSocketAddress address = (InetSocketAddress) proxies.getFirst().address();
        assertEquals(host, address.getHostString());
        assertEquals(port, address.getPort());
    }
}
