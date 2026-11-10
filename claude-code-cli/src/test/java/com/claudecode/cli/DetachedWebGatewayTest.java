package com.claudecode.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DetachedWebGatewayTest {

    @TempDir
    Path home;

    @Test
    void reusesALiveGatewayInsteadOfLaunchingAnother() throws Exception {
        Path record = home.resolve("web-gateway.json");
        String url = "http://127.0.0.1:8087/?token=t";
        WebGatewayRecord.write(record,
            new WebGatewayRecord(ProcessHandle.current().pid(), url));

        var started = new DetachedWebGateway(record, home.resolve("web-gateway.log")).start();

        assertThat(started.url()).isEqualTo(url);
        assertThat(home.resolve("web-gateway.log")).doesNotExist();
    }
}
