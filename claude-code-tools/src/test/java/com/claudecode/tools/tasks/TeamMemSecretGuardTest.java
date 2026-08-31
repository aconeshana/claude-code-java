package com.claudecode.tools.tasks;

import org.apache.commons.lang3.Strings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TeamMemSecretGuardTest {

    private static final String GITHUB_PAT = "ghp_0123456789abcdefABCDEF0123456789abcd";
    // 故意构造的测试占位符（sk_test_ 前缀 + 低熵尾巴），非真实密钥；仅用于验证 guard 能识别
    // Stripe token 格式。用 sk_test_ 而非 sk_live_ 是为避免触发 GitHub 的 push 保护扫描。
    private static final String STRIPE = "sk_test_abcdefghijklmnopqrstuvwxyz1234567890ABCD";

    @Test
    void scanDetectsKnownProviders() {
        List<TeamMemSecretGuard.SecretMatch> matches =
            TeamMemSecretGuard.scanForSecrets("token=" + GITHUB_PAT + " and " + STRIPE);
        List<String> labels = matches.stream().map(TeamMemSecretGuard.SecretMatch::label).toList();
        assertTrue(labels.contains("GitHub PAT"), labels.toString());
        assertTrue(labels.contains("Stripe Access Token"), labels.toString());
    }

    @Test
    void scanDetectsPrivateKeyBlock() {
        // gitleaks requires >=64 chars of body between the markers.
        String body = "MIIBOgIBAAKCAQEAxRsnU9Q2Z0vZ1mK3pQ4rS5tU6vW7xY8z"
            + "A1bC2dE3fG4hI5jK6lM7nO8pQ9rS0tU1vW2xY3zA4bC5dE6fG7hI8jK9l"
            + "M0nO1pQ2rS3tU4vW5xY6zA7bC8dE9fG0hI1jK2lM3nO4pQ5rS6tU7vW8x";
        String pem = "-----BEGIN RSA PRIVATE KEY-----\n" + body + "\n-----END RSA PRIVATE KEY-----";
        List<TeamMemSecretGuard.SecretMatch> matches = TeamMemSecretGuard.scanForSecrets(pem);
        boolean found = matches.stream().anyMatch(m -> Strings.CS.equals(m.ruleId(), "private-key"));
        assertTrue(found, matches.toString());
    }

    @Test
    void scanReturnsEmptyForCleanContent() {
        assertTrue(TeamMemSecretGuard.scanForSecrets("just some normal text").isEmpty());
        assertTrue(TeamMemSecretGuard.scanForSecrets("").isEmpty());
    }

    @Test
    void ruleIdToLabelTitleCasesKnownWords() {
        assertEquals("GitHub PAT", TeamMemSecretGuard.ruleIdToLabel("github-pat"));
        assertEquals("AWS Access Token", TeamMemSecretGuard.ruleIdToLabel("aws-access-token"));
    }

    @Test
    void checkTeamMemSecretsBlocksInsideTeamMemDir(@TempDir Path tmp) {
        String teamMemDir = TeamMemPaths.getTeamMemPath(tmp.toString());
        String path = teamMemDir + "MEMORY.md";
        String err = TeamMemSecretGuard.checkTeamMemSecrets(path, "x=" + GITHUB_PAT, tmp.toString());
        assertNotNull(err);
        assertTrue(Strings.CS.contains(err, "team memory"));
        // The matched secret text must never appear in the returned message.
        assertFalse(Strings.CS.contains(err, GITHUB_PAT), "secret leaked into error message");
    }

    @Test
    void checkTeamMemSecretsAllowsCleanContent(@TempDir Path tmp) {
        String teamMemDir = TeamMemPaths.getTeamMemPath(tmp.toString());
        assertNull(TeamMemSecretGuard.checkTeamMemSecrets(
            teamMemDir + "MEMORY.md", "all good", tmp.toString()));
    }

    @Test
    void checkTeamMemSecretsIgnoresNonTeamMemPath(@TempDir Path tmp) {
        // A secret written OUTSIDE the team-memory directory is not the guard's concern.
        assertNull(TeamMemSecretGuard.checkTeamMemSecrets(
            "/etc/passwd", "x=" + GITHUB_PAT, tmp.toString()));
    }
}
