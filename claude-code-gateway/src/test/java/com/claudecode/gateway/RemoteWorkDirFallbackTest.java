package com.claudecode.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.claudecode.core.message.Message;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RemoteWorkDirFallbackTest {

    @Test
    void localHitNeverConsultsTheRemoteWorkDir() {
        List<String> resolved = new ArrayList<>();
        GatewaySessionMessagesPort port = GatewayServer.withRemoteWorkDirFallback(
            new LocalOnly(Optional.of(List.of()), Optional.empty()),
            id -> {
                resolved.add(id);
                return Optional.of("/remote");
            });

        assertThat(port.messages("s1", null)).isPresent();
        assertThat(resolved).isEmpty();
    }

    @Test
    void localMissRetriesAtTheWorkDirTheRemoteWorkerReported() {
        List<String> readAt = new ArrayList<>();
        GatewaySessionMessagesPort port = GatewayServer.withRemoteWorkDirFallback(
            new LocalOnly(Optional.empty(), Optional.of(List.of())) {
                @Override
                public Optional<List<Message>> messagesInWorkDir(
                        String sessionId, String workDir) {
                    readAt.add(workDir);
                    return Optional.of(List.of());
                }
            },
            _ -> Optional.of("/projects/remote"));

        assertThat(port.messages("s1", null)).isPresent();
        assertThat(readAt).containsExactly("/projects/remote");
    }

    @Test
    void unknownEverywhereStaysEmpty() {
        GatewaySessionMessagesPort port = GatewayServer.withRemoteWorkDirFallback(
            new LocalOnly(Optional.empty(), Optional.empty()), _ -> Optional.empty());

        assertThat(port.messages("s1", null)).isEmpty();
    }

    private static class LocalOnly implements GatewaySessionMessagesPort {
        private final Optional<List<Message>> messages;
        private final Optional<List<Message>> atWorkDir;

        LocalOnly(Optional<List<Message>> messages,
                Optional<List<Message>> atWorkDir) {
            this.messages = messages;
            this.atWorkDir = atWorkDir;
        }

        @Override
        public Optional<List<Message>> messages(
                String sessionId, String headlessSessionId) {
            return messages;
        }

        @Override
        public Optional<List<Message>> messagesInWorkDir(
                String sessionId, String workDir) {
            return atWorkDir;
        }
    }
}
