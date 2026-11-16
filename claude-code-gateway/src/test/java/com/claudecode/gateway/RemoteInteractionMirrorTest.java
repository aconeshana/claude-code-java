package com.claudecode.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

class RemoteInteractionMirrorTest {

    @Test
    void requestedInteractionIsProjectedIntoTheWebPermissionVocabulary() throws Exception {
        JsonNode remote = JsonUtils.getMapper().readTree("""
            {"request_id":"r1","tool_name":"Bash","tool_input":{"command":"ls"},
             "input_raw":{"command":"ls"},"worker_id":"w1","destructive_warning":"x"}
            """);

        JsonNode asked = RemoteInteractionMirror.asked(remote);

        assertThat(asked.path("request_id").asText()).isEqualTo("r1");
        assertThat(asked.path("tool").asText()).isEqualTo("Bash");
        assertThat(asked.path("input").path("command").asText()).isEqualTo("ls");
        assertThat(asked.path("destructive_warning").asText()).isEqualTo("x");
        assertThat(asked.has("worker_id")).isFalse();
    }

    @Test
    void resolvedInteractionMapsBehaviorAndOrigin() throws Exception {
        JsonNode allowed = JsonUtils.getMapper().readTree(
            "{\"request_id\":\"r1\",\"behavior\":\"allow\",\"origin\":\"local\"}");
        JsonNode denied = JsonUtils.getMapper().readTree(
            "{\"request_id\":\"r2\",\"behavior\":\"deny\",\"origin\":\"web\",\"message\":\"no\"}");

        JsonNode a = RemoteInteractionMirror.resolved(allowed);
        JsonNode d = RemoteInteractionMirror.resolved(denied);

        assertThat(a.path("resolution").asText()).isEqualTo("allowed");
        assertThat(a.path("origin").asText()).isEqualTo("tui");
        assertThat(d.path("resolution").asText()).isEqualTo("denied");
        assertThat(d.path("origin").asText()).isEqualTo("web");
        assertThat(d.path("feedback").asText()).isEqualTo("no");
    }
}
