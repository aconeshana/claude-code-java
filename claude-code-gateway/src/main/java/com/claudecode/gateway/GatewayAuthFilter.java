package com.claudecode.gateway;

import com.sun.net.httpserver.HttpExchange;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;
import org.apache.commons.lang3.Strings;
import org.apache.commons.lang3.StringUtils;

/**
 * Constant-time token authentication for every gateway route.
 *
 * <p>The token is required unconditionally — including loopback — because the
 * Messages protocol face can drive Bash tool execution: any local process
 * that can reach the port must present the per-launch token. Clients send it
 * as {@code Authorization: Bearer <token>} or a {@code ?token=} query
 * parameter (the form a browsable landing URL uses).
 */
public final class GatewayAuthFilter {

    private final byte[] expectedToken;

    public GatewayAuthFilter(String token) {
        Objects.requireNonNull(token, "token");
        if (token.length() < 32 || token.length() > 4096) {
            throw new IllegalArgumentException(
                "Gateway auth token must contain 32-4096 characters");
        }
        this.expectedToken = token.getBytes(StandardCharsets.UTF_8);
    }

    /** True when the exchange carries the launch token in either form. */
    public boolean authenticated(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            if (matches(header.substring(7).trim())) return true;
        }
        String query = exchange.getRequestURI().getRawQuery();
        if (query != null) {
            for (String pair : query.split("&")) {
                int split = pair.indexOf('=');
                if (split > 0 && Strings.CS.equals("token", pair.substring(0, split))
                        && matches(percentDecoded(pair.substring(split + 1)))) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean matches(String candidate) {
        if (StringUtils.isEmpty(candidate)) return false;
        return MessageDigest.isEqual(
            candidate.getBytes(StandardCharsets.UTF_8), expectedToken);
    }

    private static String percentDecoded(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
