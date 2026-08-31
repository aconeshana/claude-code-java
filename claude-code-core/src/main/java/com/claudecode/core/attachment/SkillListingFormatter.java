package com.claudecode.core.attachment;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.core.message.SkillListingEntry;
import com.claudecode.core.message.TokenEstimator;
import com.claudecode.core.model.ModelContextWindows;
import com.claudecode.core.process.SubprocessEnvironment;
import com.claudecode.core.text.FormatUtils;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;




public final class SkillListingFormatter {

    public static final int DEFAULT_CHAR_BUDGET = 8_000;
    public static final int MAX_LISTING_DESC_CHARS = 1_536;
    private static final double DEFAULT_BUDGET_FRACTION = 0.01;
    private static final Set<String> FOUR_BYTES_PER_TOKEN_FAMILIES = Set.of(
        "claude-3-opus",
        "claude-3-sonnet",
        "claude-3-haiku",
        "claude-3-5-sonnet",
        "claude-3-5-haiku",
        "claude-3-7-sonnet",
        "claude-opus-4-0",
        "claude-opus-4-1",
        "claude-opus-4-5",
        "claude-opus-4-6",
        "claude-sonnet-4-0",
        "claude-sonnet-4-5",
        "claude-sonnet-4-6",
        "claude-haiku-4-5"
    );

    private SkillListingFormatter() {}

    public static String formatWithinBudget(List<SkillListingEntry> skills) {
        return formatWithinBudget(skills, DEFAULT_CHAR_BUDGET);
    }


    public static String formatWithinBudget(List<SkillListingEntry> skills, String model) {
        return formatWithinBudget(skills, charBudgetForModel(model));
    }

    public static int charBudgetForModel(String model) {
        String override = SubprocessEnvironment.get(
            "SLASH_COMMAND_TOOL_CHAR_BUDGET");
        if (StringUtils.isNotBlank(override)) {
            try {
                int value = Integer.parseInt(override.trim());
                if (value != 0) return value;
            } catch (NumberFormatException _) { }
        }
        return Math.max(1, (int) Math.floor(
            ModelContextWindows.defaultContextWindow(model)
                * bytesPerTokenForModel(model) * DEFAULT_BUDGET_FRACTION));
    }

    static int bytesPerTokenForModel(String model) {
        if (StringUtils.isBlank(model)) return 4;
        if (TokenEstimator.isGptModel(model)) return 4;
        String normalized = model.toLowerCase(Locale.ROOT).replace('_', '-').replace('.', '-');
        for (String family : FOUR_BYTES_PER_TOKEN_FAMILIES) {
            if (normalized.equals(family) || Strings.CS.startsWith(normalized, family + "-")) return 4;
        }
        return 3;
    }

    public static String formatWithinBudget(List<SkillListingEntry> skills, int budget) {
        if (skills == null || skills.isEmpty()) return "";

        List<String> fullEntries = skills.stream().map(SkillListingFormatter::fullEntry).toList();
        int fullTotal = fullEntries.stream().mapToInt(FormatUtils::displayWidth).sum()
            + fullEntries.size() - 1;
        if (fullTotal <= budget) return String.join("\n", fullEntries);

        Set<Integer> protectedEntries = new HashSet<>();
        for (int i = 0; i < skills.size(); i++) {
            SkillListingEntry skill = skills.get(i);
            if (skill.bundled() || skill.nameOnly()) protectedEntries.add(i);
        }

        int baseWidth = Math.max(0, skills.size() - 1);
        for (int i = 0; i < skills.size(); i++) {
            baseWidth += protectedEntries.contains(i)
                ? FormatUtils.displayWidth(fullEntries.get(i))
                : FormatUtils.displayWidth(nameOnlyEntry(skills.get(i)));
        }
        int remaining = budget - baseWidth;

        List<Integer> candidates = new ArrayList<>();
        for (int i = 0; i < skills.size(); i++) {
            if (!protectedEntries.contains(i)) candidates.add(i);
        }
        candidates.sort(Comparator
            .comparingDouble((Integer i) -> skills.get(i).priority()).reversed()
            .thenComparingInt(Integer::intValue));

        Set<Integer> selected = new HashSet<>();
        for (int index : candidates) {
            int incrementalWidth = FormatUtils.displayWidth(fullEntries.get(index))
                - FormatUtils.displayWidth(nameOnlyEntry(skills.get(index)));
            if (incrementalWidth <= remaining) {
                selected.add(index);
                remaining -= incrementalWidth;
            }
        }

        StringBuilder out = new StringBuilder();
        for (int i = 0; i < skills.size(); i++) {
            if (!out.isEmpty()) out.append('\n');
            if (protectedEntries.contains(i) || selected.contains(i)) {
                out.append(fullEntries.get(i));
            } else {
                out.append(nameOnlyEntry(skills.get(i)));
            }
        }
        return out.toString();
    }

    private static String fullEntry(SkillListingEntry skill) {
        if (skill.nameOnly() || skill.description() == null || StringUtils.isBlank(skill.description())) {
            return nameOnlyEntry(skill);
        }
        return "- " + skill.name() + ": " + description(skill);
    }

    private static String nameOnlyEntry(SkillListingEntry skill) {
        return "- " + skill.name();
    }

    private static String description(SkillListingEntry skill) {
        String description = skill.description() == null ? "" : skill.description();
        if (description.length() <= MAX_LISTING_DESC_CHARS) return description;
        return description.substring(0, MAX_LISTING_DESC_CHARS - 1) + '…';
    }
}
