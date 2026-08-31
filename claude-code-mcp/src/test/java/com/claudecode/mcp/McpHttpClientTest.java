package com.claudecode.mcp;

import com.claudecode.http.SharedHttpClient;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import okio.Okio;
import okio.Source;
import okio.Timeout;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpHttpClientTest {

    @Test
    void requestResponseProfileSharesCommonResources() {
        OkHttpClient base = SharedHttpClient.shared();
        OkHttpClient mcp = McpHttpClient.requestResponse();

        assertSame(base.connectionPool(), mcp.connectionPool());
        assertSame(base.dispatcher(), mcp.dispatcher());
        assertTrue(mcp.fastFallback());
    }

    @Test
    void requestResponseProfileLeavesLongBodiesUnboundedAndUsesExplicitIoTimeouts() {
        OkHttpClient mcp = McpHttpClient.requestResponse();

        assertEquals(Duration.ofSeconds(60).toMillis(), mcp.connectTimeoutMillis());
        assertEquals(Duration.ZERO.toMillis(), mcp.readTimeoutMillis());
        assertEquals(Duration.ofSeconds(60).toMillis(), mcp.writeTimeoutMillis());
        assertEquals(Duration.ZERO.toMillis(), mcp.callTimeoutMillis());
        assertTrue(mcp.interceptors().isEmpty());
    }

    @Test
    void eventStreamProfileHasNoTotalOrIdleReadDeadline() {
        OkHttpClient base = SharedHttpClient.shared();
        OkHttpClient stream = McpHttpClient.eventStream();

        assertSame(base.connectionPool(), stream.connectionPool());
        assertSame(base.dispatcher(), stream.dispatcher());
        assertEquals(0, stream.callTimeoutMillis());
        assertEquals(0, stream.readTimeoutMillis());
    }

    @Test
    void executeAndCloseClosesDiscardedResponseBody() throws IOException {
        AtomicBoolean closed = new AtomicBoolean();
        BufferedSource source = Okio.buffer(new Source() {
            @Override
            public long read(Buffer sink, long byteCount) {
                return -1;
            }

            @Override
            public Timeout timeout() {
                return Timeout.NONE;
            }

            @Override
            public void close() {
                closed.set(true);
            }
        });
        ResponseBody body = new ResponseBody() {
            @Override
            public MediaType contentType() {
                return null;
            }

            @Override
            public long contentLength() {
                return 0;
            }

            @Override
            public BufferedSource source() {
                return source;
            }
        };
        OkHttpClient client = new OkHttpClient.Builder()
            .addInterceptor(chain -> new Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(202)
                .message("Accepted")
                .body(body)
                .build())
            .build();
        Request request = new Request.Builder()
            .url("http://localhost/discarded-response")
            .build();

        McpHttpClient.executeAndClose(client, request);

        assertTrue(closed.get());
    }
}
