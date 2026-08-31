package com.claudecode.core.attachment;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import java.util.List;

import com.claudecode.core.message.AttachmentPayload;
import com.claudecode.core.message.OutputStyleAttachment;

/**
 * Surfaces the active output style as a reminder.
 */
public final class OutputStyleAttachmentProvider implements AttachmentProvider {

    @Override
    public String name() {
        return "output_style";
    }

    @Override
    public List<AttachmentPayload> collect(AttachmentContext ctx) {
        String style = ctx.outputStyle();
        if (StringUtils.isBlank(style) || Strings.CI.equals("default", style)) {
            return List.of();
        }
        return List.of(new OutputStyleAttachment(style));
    }
}
