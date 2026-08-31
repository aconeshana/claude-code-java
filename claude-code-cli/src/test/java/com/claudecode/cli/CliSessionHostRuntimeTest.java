package com.claudecode.cli;

import org.apache.commons.lang3.Strings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.runtime.interaction.InteractionCoordinator;
import com.claudecode.runtime.sessionhost.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.Arrays;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliSessionHostRuntimeTest {

    @TempDir Path tempDir;

    @Test
    void explicitConfigWinsAndMissingDefaultKeepsSidecarDisabled() throws Exception {
        Path explicit = Files.writeString(tempDir.resolve("im.toml"), "[[projects]]\n");
        assertEquals(explicit.toAbsolutePath().normalize(),
            CliSessionHostRuntime.resolveConfig(explicit.toString(), tempDir).orElseThrow());
        assertTrue(CliSessionHostRuntime.resolveConfig("", tempDir).isEmpty());
    }

    @Test
    void missingExplicitConfigIsASetupDestinationNotAStartupFailure() {
        Path explicit = tempDir.resolve("new-im.toml").toAbsolutePath().normalize();
        assertEquals(explicit,
            CliSessionHostRuntime.resolveConfig(explicit.toString(), tempDir).orElseThrow());
    }

    @Test
    void conventionalConfigEnablesBundledSidecar() throws Exception {
        Path expected = Files.writeString(tempDir.resolve("cc-connect.toml"), "[[projects]]\n");
        assertEquals(expected, CliSessionHostRuntime.resolveConfig(null, tempDir).orElseThrow());
    }

    @Test
    void platformResourceUsesNormalizedOsAndArchitecture() {
        assertEquals("/native/darwin-arm64/cc-connect",
            CliSessionHostRuntime.nativeResource("Mac OS X", "aarch64"));
        assertEquals("/native/linux-amd64/cc-connect",
            CliSessionHostRuntime.nativeResource("Linux", "x86_64"));
        assertThrows(IllegalArgumentException.class,
            () -> CliSessionHostRuntime.nativeResource("Windows 11", "amd64"));
        assertThrows(IllegalArgumentException.class,
            () -> CliSessionHostRuntime.nativeResource("Linux", "riscv64"));
    }

    @Test
    void generatedTokenHasEnoughEntropyAndNoShellMetacharacters() {
        String token = CliSessionHostRuntime.newAuthToken();
        assertEquals(64, token.length());
        assertTrue(token.matches("[0-9a-f]{64}"));
    }

    @Test
    void runtimeSocketPathStaysWithinUnixDomainLimitOnMacOs() throws Exception {
        Path runtimeDir = CliSessionHostRuntime.createSocketDirectory();
        try {
            Path socket = runtimeDir.resolve("link.sock");
            assertTrue(socket.toString().getBytes(StandardCharsets.UTF_8).length <= 100,
                () -> "Unix socket path is too long: " + socket);
            Path apiSocket = runtimeDir.resolve("api.sock");
            assertTrue(apiSocket.toString().getBytes(StandardCharsets.UTF_8).length <= 100,
                () -> "cc-connect API socket path is too long: " + apiSocket);
        } finally {
            Files.deleteIfExists(runtimeDir);
        }
    }

    @Test
    void missingOptionalConfigKeepsSharedInteractionCoordinatorAvailable() throws Exception {
        Path deletedConfig = Files.writeString(tempDir.resolve("deleted.toml"), "[[projects]]\n");
        Files.delete(deletedConfig);
        InteractionCoordinator interactions = new InteractionCoordinator(() -> "session-1");
        SessionHostRegistry registry = new SessionHostRegistry(new SessionHostRegistry.Activator() {
            @Override public CompletableFuture<SessionHostSession> activate(
                    SessionOpenRequest request) {
                return CompletableFuture.failedFuture(new UnsupportedOperationException());
            }

            @Override public List<SessionHostInfo> list() {
                return List.of();
            }
        });
        CliSessionHostRuntime runtime = new CliSessionHostRuntime(
            registry, interactions, new SessionCollaborationController(registry),
            deletedConfig, tempDir.toString());

        try {
            runtime.start();
            assertEquals(interactions, runtime.interactions());
        } finally {
            runtime.close();
        }
    }

    @Test
    void sessionHostAcceptsOnlyTheProjectComposedIntoThisJvm() throws Exception {
        Path project = Files.createDirectory(tempDir.resolve("project")).toRealPath();
        Path alias = tempDir.resolve("project-alias");
        Files.createSymbolicLink(alias, project);
        Path another = Files.createDirectory(tempDir.resolve("another")).toRealPath();

        CliSessionHostRuntime.requireCurrentProject(project.toString(), "");
        CliSessionHostRuntime.requireCurrentProject(project.toString(), alias.toString());
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> CliSessionHostRuntime.requireCurrentProject(
                project.toString(), another.toString()));
        assertTrue(Strings.CS.contains(failure.getMessage(), "another Claude Code process"));
    }

    @Test
    void starterConfigDefersCredentialFileUntilOnboardingSucceeds() throws Exception {
        Path config = tempDir.resolve("cc-connect.toml");
        Path credentials = tempDir.resolve("cc-connect.credentials.env");
        CliSessionHostRuntime.writeStarterConfig(config, "demo-project", tempDir.toString());

        String text = Files.readString(config);
        assertTrue(Strings.CS.contains(text, "type = \"sessionhost\""));
        assertFalse(Strings.CS.contains(text, "app_id"));
        assertFalse(Strings.CS.contains(text, "app_secret"));
        assertFalse(Strings.CS.contains(text, "env_file"));
        assertFalse(Files.exists(credentials));
        assertFalse(CliSessionHostRuntime.hasSavedFeishuCredentials(config));
        assertEquals(Set.of(PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(config));
    }

    @Test
    void bindCommandNeverContainsTheAppSecret() {
        List<String> command = CliSessionHostRuntime.setupCommand(
            Path.of("/tmp/cc-connect"), Path.of("/tmp/config.toml"),
            Path.of("/tmp/credentials.env"), "demo", CollaborationSetupPort.Mode.BIND);
        assertTrue(command.contains("--app-stdin"));
        assertTrue(command.contains("--discover-target"));
        assertTrue(command.stream().noneMatch(value -> Strings.CS.contains(value, "secret-value")));
        assertTrue(Arrays.stream(command.toArray(String[]::new))
            .noneMatch(value -> Strings.CS.startsWith(value, "--app-secret")));
    }

    @Test
    void resumeCommandUsesSavedCredentialsWithoutStdin() {
        List<String> command = CliSessionHostRuntime.setupCommand(
            Path.of("/tmp/cc-connect"), Path.of("/tmp/config.toml"),
            Path.of("/tmp/credentials.env"), "demo", CollaborationSetupPort.Mode.RESUME);

        assertTrue(command.contains("resume"));
        assertFalse(command.contains("--app-stdin"));
        assertFalse(command.contains("--discover-target"));
    }

    @Test
    void generatedCredentialPlaceholdersArePendingOnlyAfterCredentialsExist() throws Exception {
        Path config = tempDir.resolve("partial.toml");
        Path credentials = tempDir.resolve("cc-connect.credentials.env");
        Files.writeString(config, """
            env_file = "%s"
            [[projects.platforms]]
            type = "feishu"
            [projects.platforms.options]
            app_id = "${FEISHU_APP_ID}"
            app_secret = "${FEISHU_APP_SECRET}"
            """.formatted(credentials));

        assertFalse(CliSessionHostRuntime.hasSavedFeishuCredentials(config));
        assertFalse(CliSessionHostRuntime.targetConfigured(config));

        Files.writeString(credentials, """
            FEISHU_APP_ID='cli_test'
            FEISHU_APP_SECRET='secret_test'
            """);

        assertTrue(CliSessionHostRuntime.hasSavedFeishuCredentials(config));
    }

    @Test
    void oldGeneratedStarterWithMissingCredentialFileIsRepairable() throws Exception {
        Path credentials = tempDir.resolve("cc-connect.credentials.env");
        Path config = Files.writeString(tempDir.resolve("cc-connect.toml"), """
            language = "en"
            env_file = "%s"
            [[projects]]
            name = "demo"
            [projects.agent]
            type = "sessionhost"
            [[projects.platforms]]
            type = "feishu"
            [projects.platforms.options]
            app_id = "${FEISHU_APP_ID}"
            app_secret = "${FEISHU_APP_SECRET}"
            """.formatted(credentials));

        assertTrue(CliSessionHostRuntime.isLegacyUninitializedStarter(config, credentials));

        CliSessionHostRuntime.writeStarterConfig(config, "demo", tempDir.toString());

        assertFalse(CliSessionHostRuntime.isLegacyUninitializedStarter(config, credentials));
        assertFalse(Strings.CS.contains(Files.readString(config), "env_file"));
    }

    @Test
    void inlineFeishuCredentialsRemainEligibleForResume() throws Exception {
        Path config = Files.writeString(tempDir.resolve("inline.toml"), """
            [[projects.platforms]]
            type = "feishu"
            [projects.platforms.options]
            app_id = "cli_test"
            app_secret = "secret_test"
            """);

        assertTrue(CliSessionHostRuntime.hasSavedFeishuCredentials(config));
    }
}
