package com.claudecode.services.permissions;

import com.claudecode.api.ApiMessage;
import com.claudecode.api.CreateMessageRequest;
import com.claudecode.api.LlmClient;
import com.claudecode.api.StreamEvent;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.state.CwdState;
import com.claudecode.permissions.RuleSource;
import com.claudecode.services.config.SettingsSources;
import com.claudecode.services.model.SideQuery;
import com.claudecode.core.serialization.JsonUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Guards the permission-explainer model resolution: the main model by
 * default, the {@code permissionExplainerModel} settings key when set.
 */
class PermissionExplainerServiceTest {

    @TempDir
    Path tempDir;

    private Path previousCwd;

    @BeforeEach
    void isolateSettingsRoot() {
        previousCwd = CwdState.getOriginalCwd();
    }

    @AfterEach
    void restoreSettingsRoot() {
        if (previousCwd == null) CwdState.clearForTesting();
        else CwdState.setOriginalCwd(previousCwd);
        SettingsSources.configureAllowedSettingSources(
            List.of(RuleSource.USER_SETTINGS, RuleSource.PROJECT_SETTINGS,
                RuleSource.LOCAL_SETTINGS), "/tmp", false);
    }

    @Test
    void explainUsesTheMainModelUnlessTheSettingsKeyOverridesIt() throws Exception {
        CapturingToolClient client = new CapturingToolClient();
        PermissionExplainerService service = new PermissionExplainerService(
            new SideQuery(client), "claude-sonnet-4-6");

        service.explain("Bash", JsonUtils.getMapper().createObjectNode()
            .put("command", "rm -rf /tmp/x"), "Runs a removal");
        assertEquals("claude-sonnet-4-6", client.request.model(),
            "236 pins the explainer to the main model when no override exists");

        Files.createDirectories(tempDir.resolve(".claude"));
        Files.writeString(tempDir.resolve(".claude").resolve("settings.local.json"),
            "{\"permissionExplainerModel\":\"glm-4.6-flash\"}");
        CwdState.setOriginalCwd(tempDir);
        SettingsSources.configureAllowedSettingSources(
            List.of(RuleSource.LOCAL_SETTINGS), tempDir.toString(), false);

        service.explain("Bash", JsonUtils.getMapper().createObjectNode()
            .put("command", "rm -rf /tmp/x"), "Runs a removal");
        assertEquals("glm-4.6-flash", client.request.model());
    }

    @Test
    void explainToleratesAMissingClientResponse() {
        PermissionExplainerService service = new PermissionExplainerService(
            new SideQuery(new FailingClient()), "claude-sonnet-4-6");
        assertNull(service.explain("Bash",
            JsonUtils.getMapper().createObjectNode().put("command", "ls"), null));
    }

    /** Captures the single request and answers with a valid explain_command tool call. */
    private static final class CapturingToolClient implements LlmClient {
        CreateMessageRequest request;

        @Override
        public ApiMessage createMessage(CreateMessageRequest request) {
            this.request = request;
            ToolUseBlock tool = new ToolUseBlock("tool-1", "explain_command",
                JsonUtils.getMapper().createObjectNode()
                    .put("explanation", "Removes a temp directory")
                    .put("reasoning", "I need to clean up")
                    .put("risk", "Deletes files under /tmp")
                    .put("riskLevel", "LOW"));
            return new ApiMessage("resp-1", "message", "assistant",
                List.of(tool), request.model(), "tool_use", null, null, null);
        }

        @Override
        public Iterator<StreamEvent> createMessageStream(CreateMessageRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getModel() {
            return request != null ? request.model() : null;
        }
    }

    private static final class FailingClient implements LlmClient {
        @Override
        public ApiMessage createMessage(CreateMessageRequest request) {
            throw new IllegalStateException("offline");
        }

        @Override
        public Iterator<StreamEvent> createMessageStream(CreateMessageRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getModel() {
            return "offline-model";
        }
    }
}
