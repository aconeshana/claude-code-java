package com.claudecode.core.util;

import org.apache.commons.lang3.StringUtils;
import java.security.SecureRandom;

/**
 * Generates sub-agent invocation ids for sidechain transcript isolation.
 */
public final class AgentId {

    private AgentId() {}

    private static final SecureRandom RANDOM = new SecureRandom();


    public static String create() {
        return create(null);
    }

/** matches the optional label form: {@code "a<label>-" + 16 hex chars}. */
    public static String create(String label) {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(17 + (label == null ? 0 : label.length() + 1))
            .append('a');
        if (StringUtils.isNotEmpty(label)) sb.append(label).append('-');
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
