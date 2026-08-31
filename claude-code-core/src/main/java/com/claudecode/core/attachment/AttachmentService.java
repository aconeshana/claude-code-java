package com.claudecode.core.attachment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.claudecode.core.message.AttachmentPayload;


public final class AttachmentService {

    private final Map<String, AttachmentProvider> providers;
    private final FeatureFlagRegistry flags;

    public AttachmentService(List<AttachmentProvider> providers, FeatureFlagRegistry flags) {
        this.providers = new LinkedHashMap<>();
        for (AttachmentProvider p : providers) {
            this.providers.put(p.name(), p);
        }
        this.flags = flags;
    }

    /** Collect this turn's attachments across all enabled providers, in order. */
    public List<AttachmentPayload> collect(AttachmentContext ctx) {
        List<AttachmentPayload> out = new ArrayList<>();
        for (AttachmentProvider p : providers.values()) {
            if (p.isEnabled(flags)) {
                out.addAll(p.collect(ctx));
            }
        }
        return out;
    }

    /**
     * Whether this service owns a named.
     */
    public boolean hasProvider(String name) {
        return providers.containsKey(name);
    }

    /** Empty service (no providers) — the safe default when none are wired. */
    public static AttachmentService empty() {
        return new AttachmentService(List.of(), FeatureFlagRegistry.allOff());
    }
}
