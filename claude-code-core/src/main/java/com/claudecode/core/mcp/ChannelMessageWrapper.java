package com.claudecode.core.mcp;

import com.claudecode.core.text.XmlEscaper;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Utility for wrapping MCP channel notification content in the {@code <channel>} XML tag
 * before injecting it into the conversation queue.
 *
 * <ul>
 *   <li>{@code wrapChannelMessage}</li>
 *   <li>{@code CHANNEL_TAG = "channel"}</li>
 * </ul>
 *
 * <p>Meta keys become XML attribute names. Only plain-identifier keys are accepted —
 * rejects anything that could break out of the attribute structure (e.g., {@code x="" injected="y"}).
 */
public final class ChannelMessageWrapper {


    public static final String CHANNEL_TAG = "channel";

    /**
     * Only accept keys that look like plain identifiers.
     */
    private static final Pattern SAFE_META_KEY = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");

    private ChannelMessageWrapper() {}

    /**
     * Wraps a channel notification payload in a {@code <channel>} XML tag.
     *
     * @param serverName the MCP server name (used as the {@code source} attribute)
     * @param content    the raw notification content
     * @param meta       optional opaque metadata passed by the server; safe keys become attributes
     * @return the wrapped XML string to enqueue as a user message
     */
    public static String wrapChannelMessage(String serverName, String content,
                                            Map<String, String> meta) {
        StringBuilder attrs = new StringBuilder();
        attrs.append(" source=\"").append(XmlEscaper.escapeAttribute(serverName)).append('"');
        if (meta != null) {
            meta.entrySet().stream()
                .filter(e -> SAFE_META_KEY.matcher(e.getKey()).matches())
                .forEach(e -> attrs.append(' ')
                                   .append(e.getKey())
                                   .append("=\"")
                                   .append(XmlEscaper.escapeAttribute(e.getValue()))
                                   .append('"'));
        }
        return "<" + CHANNEL_TAG + attrs + ">\n" + content + "\n</" + CHANNEL_TAG + ">";
    }

    /** Convenience overload with no meta. */
    public static String wrapChannelMessage(String serverName, String content) {
        return wrapChannelMessage(serverName, content, null);
    }

}
