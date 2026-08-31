package com.claudecode.core.text;

import org.apache.commons.lang3.StringUtils;
/**
 * Escapes untrusted text for interpolation into XML-style prompt and report markup.
 *
 * <ul>
 *   <li>text-node escaping for
 *       {@code &amp;}, {@code <}, and {@code >}.</li>
 *   <li>attribute-value escaping,
 *       including both quote characters.</li>
 * </ul>
 */
public final class XmlEscaper {

    private XmlEscaper() {
    }

    /** Escapes text interpolated between XML tags. A null value becomes empty text. */
    public static String escapeText(String value) {
        if (StringUtils.isEmpty(value)) return "";
        return value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;");
    }

    /** Escapes text interpolated into either a single- or double-quoted XML attribute. */
    public static String escapeAttribute(String value) {
        return escapeText(value)
            .replace("\"", "&quot;")
            .replace("'", "&apos;");
    }
}
