package com.claudecode.services.hooks;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import com.claudecode.core.tool.LegacyToolNames;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * A hook matcher associates a pattern with a list of hook commands.
 */
public record HookMatcher(
    Optional<String> matcher,
    List<HookCommand> hooks
) {

    private static final Pattern SIMPLE_MATCHER = Pattern.compile("^[a-zA-Z0-9_|]+$");
    /**
     * Tests whether the given query matches this matcher's pattern.
     * An empty/absent matcher matches everything.
     *
     * @param query the value to match (e.g., tool name)
     * @return true if matched
     */
    public boolean matches(String query) {
        if (matcher.isEmpty() || StringUtils.isBlank(matcher.get())) {
            return true;
        }
        String pattern = matcher.get();
        if (query == null) {
            return false;
        }
        if (Strings.CS.equals(pattern, "*")) {
            return true;
        }
        if (SIMPLE_MATCHER.matcher(pattern).matches()) {
            if (Strings.CS.contains(pattern, "|")) {
                for (String candidate : pattern.split("\\|", -1)) {
                    if (Strings.CS.equals(LegacyToolNames.normalize(candidate.strip()), query)) {
                        return true;
                    }
                }
                return false;
            }
            return Strings.CS.equals(LegacyToolNames.normalize(pattern), query);
        }
        try {
            Pattern regex = Pattern.compile(pattern);
            if (regex.matcher(query).find()) {
                return true;
            }
            return LegacyToolNames.legacyNames(query).stream().anyMatch(legacyName ->
                regex.matcher(legacyName).find());
        } catch (PatternSyntaxException _) {
            return false;
        }
    }

}
