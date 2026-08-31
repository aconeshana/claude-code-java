package com.claudecode.services.plugins.marketplace;

import org.apache.commons.lang3.Strings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


class MarketplaceInputTest {

    @TempDir
    Path tempDir;

    private static MarketplaceSource parsed(MarketplaceInput.Result result) {
        return assertInstanceOf(MarketplaceInput.Parsed.class, result).source();
    }

    // ── GitHub shorthand ──────────────────────────────────────────────────────

    @Test
    void shorthandOwnerRepoParsesAsGithub() {
        MarketplaceSource source = parsed(MarketplaceInput.parse("owner/repo", tempDir));
        MarketplaceSource.Github github = assertInstanceOf(MarketplaceSource.Github.class, source);
        assertEquals("owner/repo", github.repo());
        assertNull(github.ref());
    }

    @Test
    void shorthandWithHashRefExtractsRef() {
        MarketplaceSource.Github github = assertInstanceOf(MarketplaceSource.Github.class,
            parsed(MarketplaceInput.parse("owner/repo#v1.0.0", tempDir)));
        assertEquals("owner/repo", github.repo());
        assertEquals("v1.0.0", github.ref());
    }

    @Test
    void shorthandWithAtRefExtractsRef() {
        MarketplaceSource.Github github = assertInstanceOf(MarketplaceSource.Github.class,
            parsed(MarketplaceInput.parse("owner/repo@main", tempDir)));
        assertEquals("owner/repo", github.repo());
        assertEquals("main", github.ref());
    }

    @Test
    void shorthandContainingColonIsUnrecognized() {
        assertInstanceOf(MarketplaceInput.Unrecognized.class,
            MarketplaceInput.parse("owner/repo:extra", tempDir));
    }

    @Test
    void scopedNpmPackageIsUnrecognized() {
        assertInstanceOf(MarketplaceInput.Unrecognized.class,
            MarketplaceInput.parse("@scope/package", tempDir));
    }

    @Test
    void plainWordIsUnrecognized() {
        assertInstanceOf(MarketplaceInput.Unrecognized.class,
            MarketplaceInput.parse("some-plugin", tempDir));
    }

    // ── SSH URLs ──────────────────────────────────────────────────────────────

    @Test
    void sshUrlParsesAsGit() {
        MarketplaceSource.Git git = assertInstanceOf(MarketplaceSource.Git.class,
            parsed(MarketplaceInput.parse("git@github.com:owner/repo.git", tempDir)));
        assertEquals("git@github.com:owner/repo.git", git.url());
        assertNull(git.ref());
    }

    @Test
    void sshUrlWithFragmentExtractsRef() {
        MarketplaceSource.Git git = assertInstanceOf(MarketplaceSource.Git.class,
            parsed(MarketplaceInput.parse("git@github.com:owner/repo.git#develop", tempDir)));
        assertEquals("git@github.com:owner/repo.git", git.url());
        assertEquals("develop", git.ref());
    }

    @Test
    void sshUrlWithCustomUsernameParsesAsGit() {
        MarketplaceSource.Git git = assertInstanceOf(MarketplaceSource.Git.class,
            parsed(MarketplaceInput.parse("deploy@gitlab.com:group/project.git", tempDir)));
        assertEquals("deploy@gitlab.com:group/project.git", git.url());
    }

    // ── HTTP(S) URLs ──────────────────────────────────────────────────────────

    @Test
    void httpsUrlEndingDotGitParsesAsGit() {
        MarketplaceSource.Git git = assertInstanceOf(MarketplaceSource.Git.class,
            parsed(MarketplaceInput.parse("https://gitlab.com/group/project.git", tempDir)));
        assertEquals("https://gitlab.com/group/project.git", git.url());
    }

    @Test
    void azureDevOpsUnderscoreGitUrlParsesAsGit() {
        // gh-31256 / CC-299: ADO uses /_git/ with no .git suffix.
        MarketplaceSource.Git git = assertInstanceOf(MarketplaceSource.Git.class,
            parsed(MarketplaceInput.parse("https://dev.azure.com/org/proj/_git/repo", tempDir)));
        assertEquals("https://dev.azure.com/org/proj/_git/repo", git.url());
    }

    @Test
    void githubHttpsUrlBecomesGitWithDotGitSuffix() {
        MarketplaceSource.Git git = assertInstanceOf(MarketplaceSource.Git.class,
            parsed(MarketplaceInput.parse("https://github.com/owner/repo", tempDir)));
        assertEquals("https://github.com/owner/repo.git", git.url());
    }

    @Test
    void githubHttpsUrlWithFragmentExtractsRef() {
        MarketplaceSource.Git git = assertInstanceOf(MarketplaceSource.Git.class,
            parsed(MarketplaceInput.parse("https://github.com/owner/repo#dev", tempDir)));
        assertEquals("https://github.com/owner/repo.git", git.url());
        assertEquals("dev", git.ref());
    }

    @Test
    void nonGithubHttpsUrlParsesAsUrl() {
        MarketplaceSource.Url url = assertInstanceOf(MarketplaceSource.Url.class,
            parsed(MarketplaceInput.parse("https://example.com/marketplace.json", tempDir)));
        assertEquals("https://example.com/marketplace.json", url.url());
    }

    // ── local paths ───────────────────────────────────────────────────────────

    @Test
    void existingDirectoryParsesAsDirectory() throws Exception {
        Path dir = Files.createDirectories(tempDir.resolve("my-marketplace"));
        MarketplaceSource.Directory directory = assertInstanceOf(MarketplaceSource.Directory.class,
            parsed(MarketplaceInput.parse(dir.toString(), tempDir)));
        assertEquals(dir.toString(), directory.path());
    }

    @Test
    void existingJsonFileParsesAsFile() throws Exception {
        Path file = tempDir.resolve("marketplace.json");
        Files.writeString(file, "{}");
        MarketplaceSource.File fileSource = assertInstanceOf(MarketplaceSource.File.class,
            parsed(MarketplaceInput.parse(file.toString(), tempDir)));
        assertEquals(file.toString(), fileSource.path());
    }

    @Test
    void nonJsonFileIsInvalidWithTsErrorText() throws Exception {
        Path file = tempDir.resolve("readme.md");
        Files.writeString(file, "hi");
        MarketplaceInput.Invalid invalid = assertInstanceOf(MarketplaceInput.Invalid.class,
            MarketplaceInput.parse(file.toString(), tempDir));
        assertEquals("File path must point to a .json file (marketplace.json), but got: " + file,
            invalid.error());
    }

    @Test
    void missingPathIsInvalidWithTsErrorText() {
        Path missing = tempDir.resolve("nope");
        MarketplaceInput.Invalid invalid = assertInstanceOf(MarketplaceInput.Invalid.class,
            MarketplaceInput.parse(missing.toString(), tempDir));
        assertEquals("Path does not exist: " + missing, invalid.error());
    }

    @Test
    void tildePathResolvesAgainstInjectedHome() throws Exception {
        Path home = Files.createDirectories(tempDir.resolve("home"));
        Files.createDirectories(home.resolve("mkt"));
        MarketplaceSource.Directory directory = assertInstanceOf(MarketplaceSource.Directory.class,
            parsed(MarketplaceInput.parse("~/mkt", home)));
        assertTrue(Strings.CS.endsWith(directory.path(), "mkt"));
        assertTrue(Strings.CS.startsWith(directory.path(), home.toString()));
    }
}
