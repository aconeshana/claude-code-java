package com.claudecode.http;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import okhttp3.Credentials;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.Route;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Environment-variable proxy selection for OkHttp.
 */
public final class EnvironmentProxy extends ProxySelector {

    private volatile ProxyConfiguration configuration;

    private EnvironmentProxy(ProxyEndpoint httpProxy, ProxyEndpoint httpsProxy,
                             ProxyEndpoint allProxy, List<NoProxyRule> noProxyRules) {
        this.configuration = new ProxyConfiguration(httpProxy, httpsProxy, allProxy, noProxyRules);
    }

    public static EnvironmentProxy system() {
        return from(System.getenv());
    }

    public static EnvironmentProxy from(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        ProxyEndpoint http = ProxyEndpoint.parse(first(environment, "HTTP_PROXY", "http_proxy"));
        ProxyEndpoint https = ProxyEndpoint.parse(first(environment, "HTTPS_PROXY", "https_proxy"));
        ProxyEndpoint all = ProxyEndpoint.parse(first(environment, "ALL_PROXY", "all_proxy"));
        String noProxy = first(environment, "NO_PROXY", "no_proxy");
        List<NoProxyRule> rules = new ArrayList<>();
        if (noProxy != null) {

            for (String token : noProxy.split("[,\\s]+")) {
                NoProxyRule rule = NoProxyRule.parse(token.trim());
                if (rule != null) rules.add(rule);
            }
        }
        return new EnvironmentProxy(http, https, all, List.copyOf(rules));
    }

    /**
     * Replaces the parsed proxy settings in-place. Existing OkHttp clients keep
     * the same selector instance, so settings.env proxy changes take effect on
     * subsequent requests without rebuilding every client profile.
     */
    public void refreshFrom(Map<String, String> environment) {
        EnvironmentProxy refreshed = from(environment);
        this.configuration = refreshed.configuration;
    }

    /** Applies selector and proxy authentication to an OkHttp builder. */
    public void applyTo(OkHttpClient.Builder builder) {
        builder.proxySelector(this);
        builder.proxyAuthenticator(this::authenticate);
    }

    /** True when this URL is routed through an environment proxy. */
    public boolean usesProxy(URI uri) {
        return select(uri).stream().anyMatch(proxy -> proxy.type() != Proxy.Type.DIRECT);
    }

    @Override
    public List<Proxy> select(URI uri) {
        ProxyConfiguration current = configuration;
        if (uri == null || shouldBypass(uri, current)) {
            return List.of(Proxy.NO_PROXY);
        }
        ProxyEndpoint endpoint = switch (normalizedScheme(uri)) {
            case "https" -> current.httpsProxy() != null ? current.httpsProxy() : current.allProxy();
            case "http" -> current.httpProxy() != null ? current.httpProxy() : current.allProxy();
            default -> current.allProxy();
        };
        return endpoint != null ? List.of(endpoint.proxy()) : List.of(Proxy.NO_PROXY);
    }

    @Override
    public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
        // OkHttp reports the actual connection error to the request caller.
    }

    private Request authenticate(Route route, Response response) {
        if (response.request().header("Proxy-Authorization") != null) return null;
        ProxyEndpoint endpoint = endpointFor(route.proxy(), configuration);
        if (endpoint == null || endpoint.username() == null) return null;
        String credential = Credentials.basic(endpoint.username(), endpoint.password());
        return response.request().newBuilder()
            .header("Proxy-Authorization", credential)
            .build();
    }

    private static ProxyEndpoint endpointFor(Proxy proxy, ProxyConfiguration configuration) {
        for (ProxyEndpoint endpoint : new ProxyEndpoint[] {
            configuration.httpProxy(), configuration.httpsProxy(), configuration.allProxy()}) {
            if (endpoint != null && endpoint.proxy().equals(proxy)) return endpoint;
        }
        return null;
    }

    private static boolean shouldBypass(URI uri, ProxyConfiguration current) {
        String host = uri.getHost();
        if (StringUtils.isBlank(host)) return false;
        int port = uri.getPort() >= 0 ? uri.getPort() : defaultPort(normalizedScheme(uri));
        for (NoProxyRule rule : current.noProxyRules()) {
            if (rule.matches(host, port)) return true;
        }
        return false;
    }

    private static String normalizedScheme(URI uri) {
        return uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    }

    private static int defaultPort(String scheme) {
        return Strings.CS.equals("https", scheme) ? 443 : 80;
    }

    private static String first(Map<String, String> env, String upper, String lower) {
        String value = env.get(upper);
        if (StringUtils.isBlank(value)) value = env.get(lower);
        return StringUtils.isBlank(value) ? null : value.trim();
    }

    private record ProxyEndpoint(Proxy proxy, String username, String password) {
        static ProxyEndpoint parse(String raw) {
            if (raw == null) return null;
            try {
                String normalized = Strings.CS.contains(raw, "://") ? raw : "http://" + raw;
                URI uri = URI.create(normalized);
                String host = uri.getHost();
                if (StringUtils.isBlank(host)) return null;
                String scheme = uri.getScheme() == null
                    ? "http" : uri.getScheme().toLowerCase(Locale.ROOT);
                if (!Strings.CS.equals(scheme, "http") && !Strings.CS.equals(scheme, "https")
                        && !Strings.CS.equals(scheme, "socks") && !Strings.CS.equals(scheme, "socks5")) {
                    return null;
                }
                Proxy.Type type = Strings.CS.startsWith(scheme, "socks") ? Proxy.Type.SOCKS : Proxy.Type.HTTP;
                int port = uri.getPort() >= 0 ? uri.getPort()
                    : type == Proxy.Type.SOCKS ? 1080 : 80;
                String username = null;
                String password = "";
                if (uri.getRawUserInfo() != null) {
                    String[] parts = uri.getRawUserInfo().split(":", 2);
                    username = decode(parts[0]);
                    if (parts.length == 2) password = decode(parts[1]);
                }
                return new ProxyEndpoint(
                    new Proxy(type, InetSocketAddress.createUnresolved(host, port)),
                    username, password);
            } catch (IllegalArgumentException _) {
                return null;
            }
        }

        private static String decode(String value) {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        }
    }

    private record ProxyConfiguration(ProxyEndpoint httpProxy, ProxyEndpoint httpsProxy,
                                      ProxyEndpoint allProxy, List<NoProxyRule> noProxyRules) {}

    private record NoProxyRule(String host, Integer port, boolean wildcard) {
        static NoProxyRule parse(String raw) {
            if (StringUtils.isBlank(raw)) return null;
            if (Strings.CS.equals("*", raw)) return new NoProxyRule("", null, true);
            String value = raw;
            Integer port = null;
            int colon = value.lastIndexOf(':');
            if (colon > 0 && value.indexOf(':') == colon) {
                try {
                    port = Integer.parseInt(value.substring(colon + 1));
                    value = value.substring(0, colon);
                } catch (NumberFormatException _) {
                    port = null;
                }
            }
            while (Strings.CS.startsWith(value, ".")) value = value.substring(1);
            if (StringUtils.isBlank(value)) return null;
            return new NoProxyRule(value.toLowerCase(Locale.ROOT), port, false);
        }

        boolean matches(String candidateHost, int candidatePort) {
            if (wildcard) return true;
            if (port != null && port != candidatePort) return false;
            String candidate = candidateHost.toLowerCase(Locale.ROOT);
            return candidate.equals(host) || Strings.CS.endsWith(candidate, "." + host);
        }
    }
}
