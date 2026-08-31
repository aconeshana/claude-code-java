package com.claudecode.services.config;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.engine.SandboxConfig;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.permissions.RuleSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsSandboxTest {

    @TempDir
    Path tempDir;

    @Test
    void managedOnlySwitchesComeFromPolicyOnly() throws Exception {
        Path user = write("user.json", """
            {"sandbox":{"network":{"allowManagedDomainsOnly":true,"allowedDomains":["user.example"]},
             "filesystem":{"allowManagedReadPathsOnly":true,"allowRead":["user-read"]}}}
            """);
        Path project = write("project.json", "{}");
        Path local = write("local.json", "{}");
        Path policy = write("policy.json", "{}");

        SandboxConfig config = SandboxSettings.loadSandboxConfig(List.of(user, project, local, policy));

        assertFalse(config.network().allowManagedDomainsOnly());
        assertTrue(config.network().allowedDomains().contains("user.example"));
        assertFalse(config.filesystem().allowManagedReadPathsOnly());
        assertTrue(config.filesystem().allowRead().stream()
            .anyMatch(p -> Strings.CS.endsWith(p, "user-read")));
    }

    @Test
    void policyManagedOnlyFiltersNonPolicyAllowlists() throws Exception {
        Path user = write("user.json", """
            {"sandbox":{"network":{"allowedDomains":["user.example"]},
             "filesystem":{"allowRead":["user-read"]}}}
            """);
        Path project = write("project.json", "{}");
        Path local = write("local.json", "{}");
        Path policy = write("policy.json", """
            {"sandbox":{"network":{"allowManagedDomainsOnly":true,"allowedDomains":["policy.example"]},
             "filesystem":{"allowManagedReadPathsOnly":true,"allowRead":["policy-read"]}}}
            """);

        SandboxConfig config = SandboxSettings.loadSandboxConfig(List.of(user, project, local, policy));

        assertTrue(config.network().allowManagedDomainsOnly());
        assertEquals(List.of("policy.example"), config.network().allowedDomains());
        assertTrue(config.filesystem().allowManagedReadPathsOnly());
        assertEquals(1, config.filesystem().allowRead().size());
        assertTrue(Strings.CS.endsWith(config.filesystem().allowRead().getFirst(), "policy-read"));
    }

    @Test
    void projectRelativePathsUseWorkspaceRootNotClaudeDirectory() throws Exception {
        Path claudeDir = Files.createDirectories(tempDir.resolve(".claude"));
        Path user = write("user.json", "{}");
        Path project = claudeDir.resolve("settings.json");
        Files.writeString(project, """
            {"permissions":{"allow":["Edit(/src/**)"]},
             "sandbox":{"filesystem":{"allowRead":["read/**"]}}}
            """);
        Path local = write("local.json", "{}");
        Path policy = write("policy.json", "{}");

        SandboxConfig config = SandboxSettings.loadSandboxConfig(List.of(user, project, local, policy));

        assertTrue(config.filesystem().allowWrite().contains(tempDir.resolve("src").toString()));
        assertTrue(config.filesystem().allowRead().contains(tempDir.resolve("read").toString()));
    }

    @Test
    void additionalDirectoriesStayAbsoluteLikeMergedSettings() throws Exception {
        Path user = write("user.json", """
            {"permissions":{"additionalDirectories":["/tmp/from-settings"]}}
            """);
        Path project = write("project.json", "{}");
        Path local = write("local.json", "{}");
        Path policy = write("policy.json", "{}");

        SandboxConfig config = SandboxSettings.loadSandboxConfig(List.of(user, project, local, policy));

        assertTrue(config.filesystem().allowWrite().contains("/tmp/from-settings"));
    }

    @Test
    void sessionAdditionalDirectoriesAreWritableInsideSandbox() throws Exception {
        Path user = write("user.json", "{}");
        Path project = write("project.json", "{}");
        Path local = write("local.json", "{}");
        Path policy = write("policy.json", "{}");
        Path cliDirectory = Files.createDirectories(tempDir.resolve("cli-add-dir"));
        try {
            SettingsSources.setSessionAdditionalDirectories(List.of(cliDirectory.toString()));

            SandboxConfig config = SandboxSettings.loadSandboxConfig(List.of(user, project, local, policy));

            assertTrue(config.filesystem().allowWrite().contains(cliDirectory.toString()));
        } finally {
            SettingsSources.clearSessionAdditionalDirectories();
        }
    }

    @Test
    void networkRulesHonorEnabledSourcesButFilesystemRulesRemainPerSource() throws Exception {
        String originalHome = System.getProperty("user.home");
        String originalDir = System.getProperty("user.dir");
        Path cwd = tempDir.resolve("workspace");
        Files.createDirectories(cwd.resolve(".claude"));
        Path userSettings = tempDir.resolve(".claude/settings.json");
        Files.createDirectories(userSettings.getParent());
        Files.writeString(userSettings, """
            {"permissions":{"allow":["WebFetch(domain:user.example)","Edit(/user/**)"]},
             "sandbox":{"filesystem":{"allowWrite":["/user-fs"]}}}
            """);
        Files.writeString(cwd.resolve(".claude/settings.json"), """
            {"permissions":{"allow":["WebFetch(domain:project.example)","Edit(/project/**)"]}}
            """);
        try {
            System.setProperty("user.home", tempDir.toString());
            System.setProperty("user.dir", cwd.toString());
            SettingsSources.configureAllowedSettingSources(
                List.of(RuleSource.PROJECT_SETTINGS), cwd.toString());

            SandboxConfig config = SandboxSettings.loadSandboxConfig();

            assertTrue(config.network().allowedDomains().contains("project.example"));
            assertFalse(config.network().allowedDomains().contains("user.example"),
                "disabled sources must not contribute merged WebFetch rules");
            assertTrue(config.filesystem().allowWrite().stream()
                .anyMatch(p ->Strings.CS.endsWith( p, "/user")),
                "filesystem permission extraction follows TS's fixed per-source loop");
            assertTrue(config.filesystem().allowWrite().contains("/user-fs"),
                "sandbox filesystem paths also follow the fixed per-source loop");
        } finally {
            SettingsSources.configureAllowedSettingSources(true, true, true,
                originalDir == null ? tempDir.toString() : originalDir);
            if (originalHome != null) System.setProperty("user.home", originalHome);
            if (originalDir != null) System.setProperty("user.dir", originalDir);
            SettingsSources.clearFlagSettings();
        }
    }

    private Path write(String name, String json) throws Exception {
        Path path = tempDir.resolve(name);
        Files.writeString(path, json);
        // Keep the test's JSON fixture construction tied to the same parser
        // used by production code; this also catches accidental malformed text.
        JsonUtils.getMapper().readTree(json);
        return path;
    }
}
