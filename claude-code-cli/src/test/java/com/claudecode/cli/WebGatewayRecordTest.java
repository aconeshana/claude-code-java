package com.claudecode.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WebGatewayRecordTest {

    @TempDir
    Path home;

    @Test
    void liveOwnerKeepsTheRecordReadable() throws Exception {
        Path file = home.resolve("web-gateway.json");
        WebGatewayRecord record = new WebGatewayRecord(ProcessHandle.current().pid(), "http://127.0.0.1:8087/?token=t");
        WebGatewayRecord.write(file, record);

        assertThat(WebGatewayRecord.readLive(file)).contains(record);
    }

    @Test
    void recordOfExitedProcessIsDeletedOnRead() throws Exception {
        Path file = home.resolve("web-gateway.json");
        long deadPid = Long.MAX_VALUE - 1;
        WebGatewayRecord.write(file, new WebGatewayRecord(deadPid, "http://127.0.0.1:8087/?token=t"));

        assertThat(WebGatewayRecord.readLive(file)).isEmpty();
        assertThat(file).doesNotExist();
    }

    @Test
    void unreadableRecordIsTreatedAsAbsent() throws Exception {
        Path file = home.resolve("web-gateway.json");
        Files.writeString(file, "{not json");

        Optional<WebGatewayRecord> live = WebGatewayRecord.readLive(file);

        assertThat(live).isEmpty();
        assertThat(file).doesNotExist();
    }

    @Test
    void removeIfOwnedByLeavesAnotherProcessesRecordAlone() throws Exception {
        Path file = home.resolve("web-gateway.json");
        WebGatewayRecord record = new WebGatewayRecord(ProcessHandle.current().pid(), "http://127.0.0.1:8087/?token=t");
        WebGatewayRecord.write(file, record);

        WebGatewayRecord.removeIfOwnedBy(file, ProcessHandle.current().pid() + 1);
        assertThat(file).exists();

        WebGatewayRecord.removeIfOwnedBy(file, ProcessHandle.current().pid());
        assertThat(file).doesNotExist();
    }
}
