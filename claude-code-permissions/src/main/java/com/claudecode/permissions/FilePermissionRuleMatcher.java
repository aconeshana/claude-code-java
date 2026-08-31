package com.claudecode.permissions;

import com.claudecode.core.engine.FileReadIgnorePattern;
import org.apache.commons.lang3.StringUtils;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.apache.commons.lang3.Strings;

/**
 * Gitignore-style matcher for file permission rule content.
 */
final class FilePermissionRuleMatcher {

    private FilePermissionRuleMatcher() {}

    /** Root is kept as text so Windows/UNC fixtures can be tested on Unix hosts. */
    record RootedPattern(String relativePattern, String root) {}

    static RootedPattern patternWithRoot(String pattern, RuleSource source,
                                         PermissionPathContext context) {
        String value = pattern == null ? "" : pattern.replace('\\', '/');
        PermissionPathContext effectiveContext = context != null
            ? context : PermissionPathContext.defaults(Path.of(System.getProperty("user.dir")));

        if (value.matches("^[A-Za-z]:/.*")) {
            char drive = Character.toUpperCase(value.charAt(0));
            return new RootedPattern(value.substring(2), drive + ":/");
        }

        // when running on Windows. Accepting it here keeps the matcher
        // deterministic for cross-platform tests without changing Unix rules.
        if (value.matches("^//[A-Za-z]/.*")) {
            char drive = Character.toUpperCase(value.charAt(2));
            return new RootedPattern(value.substring(3), drive + ":/");
        }
        if (Strings.CS.startsWith(value, "//")) {

            // at /. Thus //server/share becomes /server/share relative to /.
            return new RootedPattern(value.substring(1), "/");
        }
        if (Strings.CS.startsWith(value, "~/")) {
            return new RootedPattern(
                "/" + value.substring(2),
                normalizePathText(System.getProperty("user.home")));
        }
        if (Strings.CS.startsWith(value, "/")) {
            Path root = effectiveContext.rootFor(source);
            return new RootedPattern(value, normalizePathText(root.toString()));
        }
        String relative =Strings.CS.startsWith( value, "./") ? value.substring(2) : value;
        return new RootedPattern(relative, null);
    }

    static boolean matches(String pattern, RuleSource source, String input,
                           PermissionPathContext context, PermissionBehavior behavior) {
        if (StringUtils.isBlank(input)) return false;
        PermissionPathContext effectiveContext = context != null
            ? context : PermissionPathContext.defaults(Path.of(System.getProperty("user.dir")));
        RootedPattern rooted = patternWithRoot(pattern, source, effectiveContext);
        String root = rooted.root() == null
            ? normalizePathText(effectiveContext.originalWorkingDirectory().toString())
            : normalizePathText(rooted.root());
        String candidate = absoluteCandidate(input, root);
        if (!isWithin(candidate, root)) return false;

        boolean caseFold = behavior != PermissionBehavior.ALLOW
            && isWindowsPath(candidate, root);
        String comparisonCandidate = caseFold
            ? candidate.toLowerCase(Locale.ROOT) : candidate;
        String comparisonRoot = caseFold
            ? root.toLowerCase(Locale.ROOT) : root;
        String relative = comparisonCandidate.equals(comparisonRoot)
            ? ""
            : comparisonCandidate.substring(comparisonRoot.length()).replaceFirst("^/", "");
        return gitignoreMatches(rooted.relativePattern(), relative, caseFold);
    }

    static FileReadIgnorePattern toIgnorePattern(String pattern, RuleSource source,
                                                  PermissionPathContext context) {
        RootedPattern rooted = patternWithRoot(pattern, source, context);
        return rooted.root() == null
            ? FileReadIgnorePattern.anywhere(rooted.relativePattern())
            : FileReadIgnorePattern.atRoot(rooted.relativePattern(), rooted.root());
    }

    /**
     * {@code ignore.test(path)} treats an ignored directory as hiding its
     * descendants. Check every candidate prefix so {@code foo/*} has the same
     * behavior as the npm {@code ignore} package, while its {@code *} token
     * still never consumes a slash.
     */
    private static boolean gitignoreMatches(String pattern, String path, boolean caseFold) {
        String p = pattern == null ? "" : pattern.replace('\\', '/');
        String candidate = path == null ? "" : path.replace('\\', '/');
        if (caseFold) {
            p = p.toLowerCase(Locale.ROOT);
            candidate = candidate.toLowerCase(Locale.ROOT);
        }
        if (p.isEmpty() ||Strings.CS.startsWith( p, "!")) return false;
        if (Strings.CS.endsWith(p, "/**")) {
            String prefix = p.substring(0, p.length() - 3);
            if (Strings.CS.startsWith(prefix, "/")) prefix = prefix.substring(1);
// matches ignore: a trailing /** matches the directory itself and
            // everything beneath it. Both checks are glob-aware (so "foo-*/**"
            // matches "foo-bar/baz"), and the bare-directory check is anchored so
            // a non-slash prefix glob cannot match a mid-path segment.
            if (matchesSinglePattern("/" + prefix, candidate)) return true;
            return matchesSinglePattern(prefix + "/**", candidate);
        }
        while (p.length() > 1 &&Strings.CS.endsWith( p, "/")) {
            p = p.substring(0, p.length() - 1);
        }
        if (candidate.isEmpty()) return false;

        List<String> prefixes = new ArrayList<>();
        String[] segments = candidate.split("/");
        for (int i = segments.length; i >= 1; i--) {
            prefixes.add(String.join("/", Arrays.copyOf(segments, i)));
        }
        String effectivePattern = p;
        return prefixes.stream().anyMatch(prefix -> matchesSinglePattern(effectivePattern, prefix));
    }

    private static boolean matchesSinglePattern(String pattern, String path) {
        boolean anchored =Strings.CS.startsWith( pattern, "/");
        String effective = anchored ? pattern.substring(1) : pattern;
        String regex = globRegex(effective);
        if (anchored || effective.indexOf('/') >= 0) {
            return Pattern.compile("^" + regex + "$").matcher(path).matches();
        }
        return Pattern.compile("(^|/)" + regex + "($|/)").matcher(path).find();
    }

    private static String globRegex(String pattern) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '*') {
                if (i + 1 < pattern.length() && pattern.charAt(i + 1) == '*') {
                    i++;
                    if (i + 1 < pattern.length() && pattern.charAt(i + 1) == '/') {
                        i++;
                        regex.append("(?:.*/)?");
                    } else {
                        regex.append(".*");
                    }
                } else {
                    regex.append("[^/]*");
                }
            } else if (c == '?') {
                regex.append("[^/]");
            } else if (c == '[') {
                int end = pattern.indexOf(']', i + 1);
                if (end > i + 1) {
                    regex.append(pattern, i, end + 1);
                    i = end;
                } else {
                    regex.append("\\[");
                }
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return regex.toString();
    }

    private static String absoluteCandidate(String raw, String root) {
        String value = raw.replace('\\', '/');
        if (Strings.CS.equals(value, "~") ||Strings.CS.startsWith( value, "~/")) {
            value = normalizePathText(System.getProperty("user.home")
                + (value.length() == 1 ? "" : value.substring(1)));
        }
        if (Strings.CS.startsWith(value, "//")) {
            // Keep network paths in the same POSIX-rooted representation used
            // by patternWithRoot for // patterns. Read-side safety has already
            // decided whether an untrusted UNC path is acceptable.
            value = "/" + value.substring(2);
        }
        boolean absolute =Strings.CS.startsWith( value, "/") || value.matches("^[A-Za-z]:/.*");
        return normalizePathText(absolute ? value : root + "/" + value);
    }

    private static String normalizePathText(String value) {
        String path = value == null ? "" : value.replace('\\', '/');
        if (Strings.CS.startsWith(path, "//")) path = "/" + path.substring(2);
        boolean drive = path.matches("^[A-Za-z]:/.*");
        String prefix;
        String body;
        if (drive) {
            prefix = Character.toUpperCase(path.charAt(0)) + ":/";
            body = path.substring(3);
        } else if (Strings.CS.startsWith(path, "/")) {
            prefix = "/";
            body = path.substring(1);
        } else {
            prefix = "";
            body = path;
        }
        List<String> segments = new ArrayList<>();
        for (String segment : body.split("/")) {
            if (segment.isEmpty() ||Strings.CS.equals( segment, ".")) continue;
            if (Strings.CS.equals(segment, "..")) {
                if (!segments.isEmpty() && !Strings.CS.equals(segments.getLast(), "..")) {
                    segments.removeLast();
                } else if (prefix.isEmpty()) {
                    segments.add(segment);
                }
            } else {
                segments.add(segment);
            }
        }
        if (segments.isEmpty()) return prefix.isEmpty() ? "." : prefix;
        return prefix + String.join("/", segments);
    }

    private static boolean isWithin(String candidate, String root) {
        if (Strings.CS.equals(candidate, root)) return true;
        if (Strings.CS.equals(root, "/")) return Strings.CS.startsWith( candidate, "/");
        String normalizedRoot =Strings.CS.endsWith( root, "/") && root.length() > 1
            ? root.substring(0, root.length() - 1) : root;
        return Strings.CS.startsWith( candidate, normalizedRoot + "/");
    }

    private static boolean isWindowsPath(String candidate, String root) {
        return candidate.matches("^[A-Za-z]:/.*") || root.matches("^[A-Za-z]:/.*")
            ||Strings.CS.startsWith( candidate, "//") ||Strings.CS.startsWith( root, "//");
    }
}
