package com.claudecode.services.config;

import com.claudecode.core.engine.SandboxConfig;
import com.claudecode.core.serialization.JsonUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Security characterization tests for the sandbox-settings adapter.
 */
class SandboxSettingsTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void clearProcessLocalSandboxState() {
        SettingsSources.clearFlagSettings();
        SettingsSources.clearSessionAdditionalDirectories();
    }

    @Test
    void policyManagedOnlyGatesUseOnlyPolicyAllowlists() throws Exception {
        Path user = write("user.json", """
            {"sandbox":{"network":{"allowedDomains":["user.example"]},
             "filesystem":{"allowRead":["user-read"]}},
             "permissions":{"allow":["WebFetch(domain:user-rule.example)"]}}
            """);
        Path project = write("project.json", "{}");
        Path local = write("local.json", "{}");
        Path policy = write("policy.json", """
            {"sandbox":{"network":{"allowManagedDomainsOnly":true,
             "allowedDomains":["policy.example"]},
             "filesystem":{"allowManagedReadPathsOnly":true,"allowRead":["policy-read"]}},
             "permissions":{"allow":["WebFetch(domain:policy-rule.example)"]}}
            """);

        SandboxConfig config = SandboxSettings.loadSandboxConfig(List.of(user, project, local, policy));

        assertTrue(config.network().allowManagedDomainsOnly());
        assertEquals(List.of("policy.example", "policy-rule.example"),
            config.network().allowedDomains());
        assertTrue(config.filesystem().allowManagedReadPathsOnly());
        assertEquals(List.of(policy.getParent().resolve("policy-read").toString()),
            config.filesystem().allowRead());
    }

    @Test
    void editableSourcesCannotEnableManagedOnlyGates() throws Exception {
        Path user = write("user.json", """
            {"sandbox":{"network":{"allowManagedDomainsOnly":true,
             "allowedDomains":["user.example"]},
             "filesystem":{"allowManagedReadPathsOnly":true,"allowRead":["user-read"]}}}
            """);
        Path project = write("project.json", "{}");
        Path local = write("local.json", "{}");
        Path policy = write("policy.json", "{}");

        SandboxConfig config = SandboxSettings.loadSandboxConfig(List.of(user, project, local, policy));

        assertFalse(config.network().allowManagedDomainsOnly());
        assertEquals(List.of("user.example"), config.network().allowedDomains());
        assertFalse(config.filesystem().allowManagedReadPathsOnly());
        assertEquals(List.of(user.getParent().resolve("user-read").toString()),
            config.filesystem().allowRead());
    }

    @Test
    void mergesEnabledLayersWithArraySemanticsAndSessionDirectories() throws Exception {
        Path userDirectory = Files.createDirectories(tempDir.resolve("persisted-user"));
        Path projectDirectory = Files.createDirectories(tempDir.resolve("persisted-project"));
        Path sessionDirectory = Files.createDirectories(tempDir.resolve("session"));
        Path user = write("user.json", """
            {"sandbox":{"enabled":true,"excludedCommands":["user","shared"],
             "network":{"allowedDomains":["user.example"],"allowUnixSockets":["user.sock"]}},
             "permissions":{"additionalDirectories":[%s]}}
            """.formatted(jsonString(userDirectory)));
        Path project = write("project.json", """
            {"sandbox":{"failIfUnavailable":true,"excludedCommands":["shared","project"],
             "network":{"allowedDomains":["project.example"],"allowAllUnixSockets":true}},
             "permissions":{"additionalDirectories":[%s]}}
            """.formatted(jsonString(projectDirectory)));
        Path local = write("local.json", """
            {"sandbox":{"allowUnsandboxedCommands":false}}
            """);
        Path policy = write("policy.json", """
            {"sandbox":{"autoAllowBashIfSandboxed":false}}
            """);

        SettingsSources.setSessionAdditionalDirectories(List.of(sessionDirectory.toString()));
        SandboxConfig config = SandboxSettings.loadSandboxConfig(List.of(user, project, local, policy));

        assertTrue(config.enabled());
        assertTrue(config.failIfUnavailable());
        assertFalse(config.allowUnsandboxedCommands());
        assertFalse(config.autoAllowBashIfSandboxed());
        assertEquals(List.of("user", "shared", "project"), config.excludedCommands());
        assertEquals(List.of("user.example", "project.example"), config.network().allowedDomains());
        assertEquals(List.of("user.sock"), config.network().allowUnixSockets());
        assertTrue(config.network().allowAllUnixSockets());
        assertEquals(List.of(userDirectory.toString(), projectDirectory.toString(),
            sessionDirectory.toString()), config.filesystem().allowWrite());
    }

    @Test
    void globWarningsUseEffectivePermissionRulesNotResolvedFilesystemPaths() throws Exception {
        Path user = write("user.json", """
            {"sandbox":{"enabled":true,"filesystem":{"allowWrite":["sandbox/*"]}},
             "permissions":{"allow":["Edit(src/*)","Read(notes/**)"],
             "deny":["Read(secret[0-9])"]}}
            """);
        Path project = write("project.json", "{}");
        Path local = write("local.json", "{}");
        Path policy = write("policy.json", "{}");

        SandboxConfig config = SandboxSettings.loadSandboxConfig(List.of(user, project, local, policy));

        assertEquals(List.of("Edit(src/*)", "Read(secret[0-9])"),
            config.permissionGlobWarnings());
    }

    @Test
    void filesystemExtractionUsesAllSourcesWhenOnlyProjectIsEffective() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        Path claudeDirectory = Files.createDirectories(workspace.resolve(".claude"));
        Path user = write("user.json", """
            {"permissions":{"allow":["WebFetch(domain:user.example)","Edit(/user-rule/**)"]},
             "sandbox":{"filesystem":{"allowWrite":["user-filesystem/**"]}}}
            """);
        Path project = claudeDirectory.resolve("settings.json");
        Files.writeString(project, """
            {"permissions":{"allow":["WebFetch(domain:project.example)","Edit(/project-rule/**)"]},
             "sandbox":{"filesystem":{"allowWrite":["project-filesystem/**"]}}}
            """);
        Path local = write("local.json", """
            {"permissions":{"allow":["WebFetch(domain:local.example)"]}}
            """);
        Path flag = write("flag.json", """
            {"permissions":{"allow":["WebFetch(domain:flag.example)","Edit(/flag-rule/**)"]},
             "sandbox":{"filesystem":{"allowWrite":["flag-filesystem/**"]}}}
            """);
        Path policy = write("policy.json", "{}");

        SandboxConfig config = SandboxSettings.loadSandboxConfig(
            List.of(project), List.of(user, project, local, flag, policy));

        assertEquals(List.of("project.example"), config.network().allowedDomains());
        assertTrue(config.filesystem().allowWrite().contains(
            user.getParent().resolve("user-rule").toString()));
        assertTrue(config.filesystem().allowWrite().contains(
            user.getParent().resolve("user-filesystem").toString()));
        assertTrue(config.filesystem().allowWrite().contains(
            workspace.resolve("project-rule").toString()));
        assertTrue(config.filesystem().allowWrite().contains(
            workspace.resolve("project-filesystem").toString()));
        assertTrue(config.filesystem().allowWrite().contains(
            flag.getParent().resolve("flag-rule").toString()));
        assertTrue(config.filesystem().allowWrite().contains(
            flag.getParent().resolve("flag-filesystem").toString()));
    }

    @Test
    void flagSandboxModesLockTheLocalSandboxControls() throws Exception {
        Path flag = tempDir.resolve("flag-settings.json");
        SettingsSources.setFlagSettingsSource(flag, JsonUtils.getMapper().readTree("""
            {"sandbox":{"enabled":true}}
            """));

        assertTrue(SandboxSettings.areSandboxSettingsLockedByPolicy());
    }

    private Path write(String name, String json) throws Exception {
        Path path = tempDir.resolve(name);
        Files.writeString(path, json);
        JsonUtils.getMapper().readTree(json);
        return path;
    }

    private static String jsonString(Path path) throws Exception {
        return JsonUtils.getMapper().writeValueAsString(path.toString());
    }
}
