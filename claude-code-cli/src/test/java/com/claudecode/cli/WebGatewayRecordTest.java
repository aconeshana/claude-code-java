package com.claudecode.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WebGatewayRecordTest {

    private static final String URL = "http://127.0.0.1:8087/?token=t";

    @TempDir
    Path home;

    @Test
    void liveOwnerKeepsTheRecordReadable() throws Exception {
        Path file = home.resolve("web-gateway.json");
        WebGatewayRecord record = new WebGatewayRecord(ProcessHandle.current().pid(), URL, "0.2.14", 1_700_000_000_000L);
        WebGatewayRecord.write(file, record);

        assertThat(WebGatewayRecord.readLive(file)).contains(record);
    }

    @Test
    void recordOfExitedProcessIsDeletedOnRead() throws Exception {
        Path file = home.resolve("web-gateway.json");
        long deadPid = Long.MAX_VALUE - 1;
        WebGatewayRecord.write(file, new WebGatewayRecord(deadPid, URL, "0.2.14", 1_700_000_000_000L));

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

    /**
     * A gateway started by an older build left a record with only the two
     * original fields. Reading it still has to identify that gateway: a user
     * stuck behind such a process is exactly who needs {@code stop} and
     * {@code status}, so failing here would strand them.
     */
    @Test
    void recordFromAnOlderBuildReportsDefaultsInsteadOfFailing() throws Exception {
        Path file = home.resolve("web-gateway.json");
        Files.writeString(file,
            "{\"pid\":" + ProcessHandle.current().pid() + ",\"url\":\"" + URL + "\"}");

        Optional<WebGatewayRecord> live = WebGatewayRecord.readLive(file);

        assertThat(live).isPresent();
        assertThat(live.get().pid()).isEqualTo(ProcessHandle.current().pid());
        assertThat(live.get().version()).isEqualTo(WebGatewayRecord.UNKNOWN_VERSION);
        assertThat(live.get().startedAtMillis()).isZero();
    }

    /** A blank version is the same display problem as a missing one. */
    @Test
    void blankVersionIsReportedAsUnknown() throws Exception {
        Path file = home.resolve("web-gateway.json");
        Files.writeString(file, "{\"pid\":" + ProcessHandle.current().pid()
            + ",\"url\":\"" + URL + "\",\"version\":\"  \",\"startedAtMillis\":-5}");

        Optional<WebGatewayRecord> live = WebGatewayRecord.readLive(file);

        assertThat(live).isPresent();
        assertThat(live.get().version()).isEqualTo(WebGatewayRecord.UNKNOWN_VERSION);
        assertThat(live.get().startedAtMillis()).isZero();
    }

    @Test
    void removeIfOwnedByLeavesAnotherProcessesRecordAlone() throws Exception {
        Path file = home.resolve("web-gateway.json");
        WebGatewayRecord record = new WebGatewayRecord(ProcessHandle.current().pid(), URL, "0.2.14", 1_700_000_000_000L);
        WebGatewayRecord.write(file, record);

        WebGatewayRecord.removeIfOwnedBy(file, ProcessHandle.current().pid() + 1);
        assertThat(file).exists();

        WebGatewayRecord.removeIfOwnedBy(file, ProcessHandle.current().pid());
        assertThat(file).doesNotExist();
    }
}
