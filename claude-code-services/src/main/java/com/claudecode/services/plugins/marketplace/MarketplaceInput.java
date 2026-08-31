package com.claudecode.services.plugins.marketplace;

import org.apache.commons.lang3.Strings;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;






public final class MarketplaceInput {

    /** Outcome of parsing: a source, a user-facing error, or unrecognized input. */
    public sealed interface Result permits Parsed, Invalid, Unrecognized {}

    /** Successfully parsed into a marketplace source. */
    public record Parsed(MarketplaceSource source) implements Result {}

    /** Recognized shape but invalid (e.g. path does not exist). */
    public record Invalid(String error) implements Result {}


    public record Unrecognized() implements Result {}

    private static final Pattern SSH_URL = Pattern.compile(
        "^([a-zA-Z0-9._-]+@[^:]+:.+?(?:\\.git)?)(#(.+))?$");
    private static final Pattern URL_FRAGMENT = Pattern.compile("^([^#]+)(#(.+))?$");
    private static final Pattern GITHUB_PATHNAME = Pattern.compile("^/([^/]+/[^/]+?)(/|\\.git|$)");
    private static final Pattern SHORTHAND = Pattern.compile("^([^#@]+)(?:[#@](.+))?$");
    private static final Pattern WINDOWS_DRIVE = Pattern.compile("^[a-zA-Z]:[/\\\\].*");

    private MarketplaceInput() {}

    public static Result parse(String input) {
        return parse(input, Path.of(System.getProperty("user.home")));
    }

    /** Overload with injectable home directory so tests never touch the real one. */
    public static Result parse(String input, Path homeDir) {
        String trimmed = input.trim();

        // Git SSH URLs with any valid username (user@host:path[.git][#ref])
        Matcher ssh = SSH_URL.matcher(trimmed);
        if (ssh.matches() && ssh.group(1) != null) {
            String url = ssh.group(1);
            String ref = ssh.group(3);
            return new Parsed(new MarketplaceSource.Git(url, ref));
        }

        if (Strings.CS.startsWith(trimmed, "http://") || Strings.CS.startsWith(trimmed, "https://")) {
            return parseHttpUrl(trimmed);
        }

        if (isLocalPathInput(trimmed)) {
            return parseLocalPath(trimmed, homeDir);
        }

        // GitHub shorthand (owner/repo, owner/repo#ref, or owner/repo@ref).
        // Both # and @ accepted as ref separators — the display formatter uses @.
        if (Strings.CS.contains(trimmed, "/") && !Strings.CS.startsWith(trimmed, "@")) {
            if (Strings.CS.contains(trimmed, ":")) {
                return new Unrecognized();
            }
            Matcher shorthand = SHORTHAND.matcher(trimmed);
            String repo = shorthand.matches() && shorthand.group(1) != null ? shorthand.group(1) : trimmed;
            String ref = shorthand.matches() ? shorthand.group(2) : null;
            return new Parsed(new MarketplaceSource.Github(repo, ref));
        }


        return new Unrecognized();
    }

    private static Result parseHttpUrl(String trimmed) {
        Matcher fragment = URL_FRAGMENT.matcher(trimmed);
        String urlWithoutFragment = fragment.matches() && fragment.group(1) != null
            ? fragment.group(1) : trimmed;
        String ref = fragment.matches() ? fragment.group(3) : null;

        // Explicit git-repo URLs clone rather than fetch-as-JSON. The .git suffix
        // is a GitHub/GitLab/Bitbucket convention; Azure DevOps uses /_git/ with
        // no suffix (gh-31256 / CC-299).
        if (Strings.CS.endsWith(urlWithoutFragment, ".git") || Strings.CS.contains(urlWithoutFragment, "/_git/")) {
            return new Parsed(new MarketplaceSource.Git(urlWithoutFragment, ref));
        }

        String host = hostnameOf(urlWithoutFragment);
        if (host == null) {

            return new Parsed(new MarketplaceSource.Url(urlWithoutFragment));
        }
        if (Strings.CS.equals(host, "github.com") || Strings.CS.equals(host, "www.github.com")) {
            String pathname = pathnameOf(urlWithoutFragment);
            if (pathname != null && GITHUB_PATHNAME.matcher(pathname).find()) {
                // User explicitly provided HTTPS — keep HTTPS via 'git' type,
                // appending .git for a proper clone URL.
                String gitUrl = Strings.CS.endsWith(urlWithoutFragment, ".git")
                    ? urlWithoutFragment : urlWithoutFragment + ".git";
                return new Parsed(new MarketplaceSource.Git(gitUrl, ref));
            }
        }
        return new Parsed(new MarketplaceSource.Url(urlWithoutFragment));
    }

    private static Result parseLocalPath(String trimmed, Path homeDir) {
        String expanded = Strings.CS.startsWith(trimmed, "~")
            ? homeDir + trimmed.substring(1)
            : trimmed;
        Path resolvedPath = Path.of(expanded).toAbsolutePath().normalize();

        if (!Files.exists(resolvedPath)) {
            return new Invalid("Path does not exist: " + resolvedPath);
        }
        if (Files.isRegularFile(resolvedPath)) {
            if (Strings.CS.endsWith(resolvedPath.toString(), ".json")) {
                return new Parsed(new MarketplaceSource.File(resolvedPath.toString()));
            }
            return new Invalid("File path must point to a .json file (marketplace.json), but got: "
                + resolvedPath);
        }
        if (Files.isDirectory(resolvedPath)) {
            return new Parsed(new MarketplaceSource.Directory(resolvedPath.toString()));
        }
        return new Invalid("Path is neither a file nor a directory: " + resolvedPath);
    }

    private static boolean isLocalPathInput(String trimmed) {
        boolean isWindows = Strings.CI.contains(System.getProperty("os.name", ""), "win");
        boolean isWindowsPath = isWindows
            && (Strings.CS.startsWith(trimmed, ".\\") || Strings.CS.startsWith(trimmed, "..\\")
                || WINDOWS_DRIVE.matcher(trimmed).matches());
        return Strings.CS.startsWith(trimmed, "./") || Strings.CS.startsWith(trimmed, "../")
            || Strings.CS.startsWith(trimmed, "/") || Strings.CS.startsWith(trimmed, "~") || isWindowsPath;
    }

    private static String hostnameOf(String url) {
        try {
            return URI.create(url).getHost();
        } catch (IllegalArgumentException _) {
            return null;
        }
    }

    private static String pathnameOf(String url) {
        try {
            return URI.create(url).getPath();
        } catch (IllegalArgumentException _) {
            return null;
        }
    }
}
