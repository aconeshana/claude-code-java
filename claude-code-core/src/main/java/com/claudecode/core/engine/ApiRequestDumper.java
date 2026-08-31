package com.claudecode.core.engine;

import org.apache.commons.lang3.Strings;

import static com.claudecode.core.config.EnvUtils.isEnvTruthy;

import com.claudecode.core.config.ClaudePaths;
import com.claudecode.core.io.FileUtils;
import com.claudecode.core.process.SubprocessEnvironment;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public final class ApiRequestDumper {

    private static final Logger log = LoggerFactory.getLogger(ApiRequestDumper.class);

    private static final ApiRequestDumper INSTANCE = new ApiRequestDumper(
        isEnvTruthy(SubprocessEnvironment.get("DUMP_PROMPTS")),
        ClaudePaths.PROMPT_DUMPS_DIR);

    public static ApiRequestDumper instance() {
        return INSTANCE;
    }


    private static final class State {
        boolean initialized;
        int messageCountSeen;
        String lastInitDataHash = "";
        String lastInitFingerprint = "";
        int requestSequence;
    }

    private final boolean enabled;
    private final Path dumpDir;
    private final Map<String, State> states = new ConcurrentHashMap<>();

    /** Package-private for tests — inject a temp dir and force-enable. */
    ApiRequestDumper(boolean enabled, Path dumpDir) {
        this.enabled = enabled;
        this.dumpDir = dumpDir;
    }

    /**
     * Single boolean gate — callers check this before paying for wire-body
     * serialization on the request path.
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Queue the serialized wire body for dumping under {@code sessionId}'s file.
     * No-op when disabled or the caller has no session identity.
     */
    public void dump(String sessionId, String wireBodyJson) {
        if (!enabled || sessionId == null || wireBodyJson == null) return;
        String timestamp = Instant.now().toString();
        Thread.ofVirtual().name("dump-prompts").start(() -> {
            try {
                dumpNow(sessionId, wireBodyJson, timestamp);
            } catch (Exception e) {
                // Debug tooling must never disturb the query loop.
                log.debug("dump-prompts write failed: {}", e.toString());
            }
        });
    }

    /** Synchronous core — package-private so tests can call it deterministically. */
    void dumpNow(String sessionId, String wireBodyJson, String timestamp) throws Exception {
        JsonNode body = JsonUtils.getMapper().readTree(wireBodyJson);
        if (body == null || !body.isObject()) return;
        State state = states.computeIfAbsent(sessionId, _ -> new State());
        synchronized (state) {
            StringBuilder out = new StringBuilder();

// Init data (everything except messages), deduped by cheap fingerprint first, then
// content hash.
            String fingerprint = initFingerprint(body);
            if (!state.initialized || !fingerprint.equals(state.lastInitFingerprint)) {
                ObjectNode initData = ((ObjectNode) body).deepCopy();
                initData.remove("messages");
                String initDataStr = JsonUtils.getMapper().writeValueAsString(initData);
                String initDataHash = sha256(initDataStr);
                state.lastInitFingerprint = fingerprint;
                if (!state.initialized) {
                    state.initialized = true;
                    state.lastInitDataHash = initDataHash;
                    out.append(entry("init", timestamp, initDataStr));
                } else if (!initDataHash.equals(state.lastInitDataHash)) {
                    state.lastInitDataHash = initDataHash;
                    out.append(entry("system_update", timestamp, initDataStr));
                }
            }


            JsonNode messages = body.get("messages");
            if (messages != null && messages.isArray()) {
                for (int i = state.messageCountSeen; i < messages.size(); i++) {
                    JsonNode msg = messages.get(i);
                    if (!Strings.CS.equals("user", msg.path("role").asText())) continue;
                    out.append(entry("message", timestamp,
                        JsonUtils.getMapper().writeValueAsString(msg)));
                }
                state.messageCountSeen = messages.size();
            }

            // Lossless snapshot: always append, even when the compatibility
            // init/message stream had no delta. Hash the normalized JSON tree
            // so replays are stable across insignificant input whitespace.
            String requestData = JsonUtils.getMapper().writeValueAsString(body);
            int sequence = ++state.requestSequence;
            out.append(requestEntry(timestamp, sequence, requestData, sha256(requestData)));
            FileUtils.writeString(dumpDir.resolve(sessionId + ".jsonl"), out.toString(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
    }

    /** One JSONL line: {@code {"type":…,"timestamp":…,"data":…}}. */
    private static String entry(String type, String timestamp, String dataJson) {
        return "{\"type\":\"" + type + "\",\"timestamp\":\"" + timestamp
            + "\",\"data\":" + dataJson + "}\n";
    }

    private static String requestEntry(String timestamp, int sequence,
                                       String dataJson, String sha256) {
        return "{\"type\":\"request\",\"timestamp\":\"" + timestamp
            + "\",\"sequence\":" + sequence + ",\"sha256\":\"" + sha256
            + "\",\"data\":" + dataJson + "}\n";
    }


    private static String initFingerprint(JsonNode body) {
        StringBuilder toolNames = new StringBuilder();
        JsonNode tools = body.get("tools");
        if (tools != null && tools.isArray()) {
            for (JsonNode t : tools) {
                if (!toolNames.isEmpty()) toolNames.append(',');
                toolNames.append(t.path("name").asText(""));
            }
        }
        int sysLen = 0;
        JsonNode system = body.get("system");
        if (system != null) {
            if (system.isTextual()) {
                sysLen = system.asText().length();
            } else if (system.isArray()) {
                for (JsonNode b : system) {
                    sysLen += b.path("text").asText("").length();
                }
            }
        }
        return body.path("model").asText("") + "|" + toolNames + "|" + sysLen;
    }

    private static String sha256(String s) throws Exception {
        return HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    }

}
