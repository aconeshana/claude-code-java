package com.claudecode.core.attachment;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.claudecode.core.message.AttachmentPayload;
import org.apache.commons.lang3.StringUtils;
import com.claudecode.core.message.McpResourceAttachment;

/**
 * Reads MCP resources referenced via {@code @server:uri} mentions in the user's input and attaches
 * their content.
 */
public final class McpResourceAttachmentProvider implements AttachmentProvider {


    // extractMcpResourceMentions (@([^\s]+:[^\s]+)).
    private static final Pattern MENTION = Pattern.compile("@([^\\s]+:[^\\s]+)");

    @Override
    public String name() {
        return "mcp_resource";
    }

    @Override
    public List<AttachmentPayload> collect(AttachmentContext ctx) {
        String input = ctx.input();
        if (StringUtils.isBlank(input) || ctx.mcpResourceReader() == null) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        List<AttachmentPayload> out = new ArrayList<>();
        Matcher m = MENTION.matcher(input);
        while (m.find()) {
            String mention = m.group(1);
            int colon = mention.indexOf(':');
            String server = mention.substring(0, colon);
            String uri = mention.substring(colon + 1);
            String key = server + "\u0000" + uri;
            if (!seen.add(key)) continue;
            String content = ctx.mcpResourceReader().apply(server, uri);
            if (content == null) continue;
            out.add(new McpResourceAttachment(server, uri, server, null, content));
        }
        return out;
    }
}
