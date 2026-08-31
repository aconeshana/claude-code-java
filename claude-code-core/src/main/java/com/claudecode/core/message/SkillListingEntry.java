package com.claudecode.core.message;


/**
 * One skill surfaced by the {@code skill_listing} attachment.
 */
public record SkillListingEntry(
        String name,
        String description,
        boolean bundled,
        boolean nameOnly,
        double priority) {
    public SkillListingEntry(String name, String description) {
        this(name, description, false, false, 0.0);
    }

    public SkillListingEntry(String name, String description, boolean bundled) {
        this(name, description, bundled, false, 0.0);
    }
}
