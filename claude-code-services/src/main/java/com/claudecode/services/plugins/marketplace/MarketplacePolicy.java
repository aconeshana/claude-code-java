package com.claudecode.services.plugins.marketplace;

import org.apache.commons.lang3.StringUtils;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Enterprise policy checks for marketplace sources: the {@code blockedMarketplaces} blocklist
 * (takes precedence) and the {@code strictKnownMarketplaces} allowlist, including host/path pattern
 * entries and github↔git URL equivalence so a blocked GitHub repo can't be re-added via its clone
 * URL.
 */
public final class MarketplacePolicy {

    private static final Logger LOG = LoggerFactory.getLogger(MarketplacePolicy.class);

    private static final Pattern SSH_HOST = Pattern.compile("^[^@]+@([^:]+):");
    private static final Pattern GITHUB_SSH = Pattern.compile("^git@github\\.com:([^/]+/[^/]+?)(?:\\.git)?$");
    private static final Pattern GITHUB_HTTPS = Pattern.compile("^https?://github\\.com/([^/]+/[^/]+?)(?:\\.git)?$");

    private final PluginSettingsStore settings;

    public MarketplacePolicy(PluginSettingsStore settings) {
        this.settings = settings;
    }

    /**
     * True if allowed (or no policy configured). Blocklist takes precedence
     * over the allowlist, and the check runs BEFORE any network/filesystem
     * operation so blocked sources never touch disk.
     */
    public boolean isSourceAllowedByPolicy(MarketplaceSource source) {
        if (isSourceInBlocklist(source)) {
            return false;
        }
        List<MarketplaceSource> allowlist = settings.strictKnownMarketplaces();
        if (allowlist == null) {
            return true;
        }
        return allowlist.stream().anyMatch(allowed -> switch (allowed) {
            case MarketplaceSource.HostPattern pattern -> matchesHostPattern(source, pattern);
            case MarketplaceSource.PathPattern pattern -> matchesPathPattern(source, pattern);
            default -> sourcesEqual(source, allowed);
        });
    }

    /** True if the source is explicitly blocked (used for error-message differentiation). */
    public boolean isSourceInBlocklist(MarketplaceSource source) {
        List<MarketplaceSource> blocklist = settings.blockedMarketplaces();
        if (blocklist == null) {
            return false;
        }
        return blocklist.stream().anyMatch(blocked -> equivalentForBlocklist(source, blocked));
    }

    /** Allowlist entries visible in error messages. */
    public List<MarketplaceSource> allowedSources() {
        List<MarketplaceSource> allowlist = settings.strictKnownMarketplaces();
        return allowlist == null ? List.of() : allowlist;
    }

    /** hostPattern regexes from the allowlist (for the GitHub-shorthand error tip). */
    public List<String> hostPatternsFromAllowlist() {
        return allowedSources().stream()
            .filter(MarketplaceSource.HostPattern.class::isInstance)
            .map(entry -> ((MarketplaceSource.HostPattern) entry).hostPattern())
            .toList();
    }


    public static String formatSourceForDisplay(MarketplaceSource source) {
        return switch (source) {
            case MarketplaceSource.Github github ->
                "github:" + github.repo() + (github.ref() != null ? "@" + github.ref() : "");
            case MarketplaceSource.Url url -> url.url();
            case MarketplaceSource.Git git ->
                "git:" + git.url() + (git.ref() != null ? "@" + git.ref() : "");
            case MarketplaceSource.Npm npm -> "npm:" + npm.packageName();
            case MarketplaceSource.File file -> "file:" + file.path();
            case MarketplaceSource.Directory dir -> "dir:" + dir.path();
            case MarketplaceSource.HostPattern pattern -> "hostPattern:" + pattern.hostPattern();
            case MarketplaceSource.PathPattern pattern -> "pathPattern:" + pattern.pathPattern();
        };
    }


    public static String extractHostFromSource(MarketplaceSource source) {
        return switch (source) {
            case MarketplaceSource.Github _ -> "github.com";
            case MarketplaceSource.Git git -> {
                Matcher ssh = SSH_HOST.matcher(git.url());
                if (ssh.find()) {
                    yield ssh.group(1);
                }
                yield hostnameOf(git.url());
            }
            case MarketplaceSource.Url url -> hostnameOf(url.url());
            default -> null;
        };
    }

    // ── equality / equivalence ────────────────────────────────────────────────

    /**
     * Exact-source allowlist equality — ref/path must match (null and absent are equivalent).
     */
    static boolean sourcesEqual(MarketplaceSource a, MarketplaceSource b) {
        if (a instanceof MarketplaceSource.Url ua && b instanceof MarketplaceSource.Url ub) {
            return Objects.equals(ua.url(), ub.url());
        }
        if (a instanceof MarketplaceSource.Github ga && b instanceof MarketplaceSource.Github gb) {
            return Objects.equals(ga.repo(), gb.repo())
                && Objects.equals(ga.ref(), gb.ref())
                && Objects.equals(ga.path(), gb.path());
        }
        if (a instanceof MarketplaceSource.Git ga && b instanceof MarketplaceSource.Git gb) {
            return Objects.equals(ga.url(), gb.url())
                && Objects.equals(ga.ref(), gb.ref())
                && Objects.equals(ga.path(), gb.path());
        }
        if (a instanceof MarketplaceSource.Npm(String packageName)
            && b instanceof MarketplaceSource.Npm(
            String name
        )) {
            return Objects.equals(packageName, name);
        }
        if (a instanceof MarketplaceSource.File(String path) && b instanceof MarketplaceSource.File(
            String path1
        )) {
            return Objects.equals(path, path1);
        }
        if (a instanceof MarketplaceSource.Directory(String path)
            && b instanceof MarketplaceSource.Directory(
            String path1
        )) {
            return Objects.equals(path, path1);
        }
        return false;
    }

    /**
     * Blocklist equivalence: same-type match with wildcard ref/path semantics
     * (a blocklist entry without ref/path blocks ALL refs/paths), plus
     * github↔git-URL cross-type matching.
     */
    static boolean equivalentForBlocklist(MarketplaceSource source, MarketplaceSource blocked) {
        if (source instanceof MarketplaceSource.Github s && blocked instanceof MarketplaceSource.Github b) {
            return Objects.equals(s.repo(), b.repo())
                && constraintMatches(b.ref(), s.ref())
                && constraintMatches(b.path(), s.path());
        }
        if (source instanceof MarketplaceSource.Git s && blocked instanceof MarketplaceSource.Git b) {
            return Objects.equals(s.url(), b.url())
                && constraintMatches(b.ref(), s.ref())
                && constraintMatches(b.path(), s.path());
        }
        if (source instanceof MarketplaceSource.Url s
            && blocked instanceof MarketplaceSource.Url b) {
            return Objects.equals(s.url(), b.url());
        }
        if (source instanceof MarketplaceSource.Npm(String packageName)
            && blocked instanceof MarketplaceSource.Npm(
            String name
        )) {
            return Objects.equals(packageName, name);
        }
        if (source instanceof MarketplaceSource.File(String path)
            && blocked instanceof MarketplaceSource.File(
            String path1
        )) {
            return Objects.equals(path, path1);
        }
        if (source instanceof MarketplaceSource.Directory(String path)
            && blocked instanceof MarketplaceSource.Directory(
            String path1
        )) {
            return Objects.equals(path, path1);
        }
        // git source vs github blocklist entry (bypass attempt via clone URL)
        if (source instanceof MarketplaceSource.Git s && blocked instanceof MarketplaceSource.Github b) {
            String repo = extractGitHubRepo(s.url());
            return repo != null && repo.equals(b.repo())
                && constraintMatches(b.ref(), s.ref())
                && constraintMatches(b.path(), s.path());
        }
        // github source vs git blocklist entry (GitHub URL)
        if (source instanceof MarketplaceSource.Github s && blocked instanceof MarketplaceSource.Git b) {
            String repo = extractGitHubRepo(b.url());
            return repo != null && repo.equals(s.repo())
                && constraintMatches(b.ref(), s.ref())
                && constraintMatches(b.path(), s.path());
        }
        return false;
    }

    /** Blocklist wildcard: no constraint blocks everything; a constraint must match exactly. */
    private static boolean constraintMatches(String blockedValue, String sourceValue) {
        if (StringUtils.isEmpty(blockedValue)) {
            return true;
        }
        return blockedValue.equals(sourceValue);
    }

    static String extractGitHubRepo(String url) {
        Matcher ssh = GITHUB_SSH.matcher(url);
        if (ssh.matches()) {
            return ssh.group(1);
        }
        Matcher https = GITHUB_HTTPS.matcher(url);
        return https.matches() ? https.group(1) : null;
    }

    private static boolean matchesHostPattern(MarketplaceSource source, MarketplaceSource.HostPattern pattern) {
        String host = extractHostFromSource(source);
        if (host == null) {
            return false;
        }
        try {
            return Pattern.compile(pattern.hostPattern()).matcher(host).find();
        } catch (PatternSyntaxException _) {
            LOG.warn("Invalid hostPattern regex: {}", pattern.hostPattern());
            return false;
        }
    }

    private static boolean matchesPathPattern(MarketplaceSource source, MarketplaceSource.PathPattern pattern) {
        String path = switch (source) {
            case MarketplaceSource.File file -> file.path();
            case MarketplaceSource.Directory dir -> dir.path();
            default -> null;
        };
        if (path == null) {
            return false;
        }
        try {
            return Pattern.compile(pattern.pathPattern()).matcher(path).find();
        } catch (PatternSyntaxException _) {
            LOG.warn("Invalid pathPattern regex: {}", pattern.pathPattern());
            return false;
        }
    }

    private static String hostnameOf(String url) {
        try {
            return URI.create(url).getHost();
        } catch (IllegalArgumentException _) {
            return null;
        }
    }
}
