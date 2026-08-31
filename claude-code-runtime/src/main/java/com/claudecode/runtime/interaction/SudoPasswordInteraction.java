package com.claudecode.runtime.interaction;

import com.claudecode.core.annotation.Explanation;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/** Strongly typed secret-input port for local sudo prompts. */
@FunctionalInterface
@Explanation("Adds local-only sudo password input for interactive shell authorization")
public interface SudoPasswordInteraction {
    SudoPasswordInteraction UNAVAILABLE = _ -> Result.unavailable();

    Result request(Request request);

    record Request(String executable, String command) {
        @Override public String toString() { return "Request[redacted]"; }
    }

    sealed interface Result permits Result.Provided, Result.Cancelled, Result.Unavailable {
        static Provided provided(char[] password) { return new Provided(password); }
        static Result cancelled() { return Cancelled.INSTANCE; }
        static Result unavailable() { return Unavailable.INSTANCE; }

        final class Provided implements Result, AutoCloseable {
            private final AtomicBoolean consumed = new AtomicBoolean();
            private char[] password;

            private Provided(char[] password) {
                if (password == null || password.length == 0) {
                    throw new IllegalArgumentException("password must not be empty");
                }
                this.password = password.clone();
            }

            public synchronized void writeTo(OutputStream destination) throws IOException {
                if (!consumed.compareAndSet(false, true)) {
                    throw new IllegalStateException("sudo password has already been consumed");
                }
                byte[] encoded = null;
                try {
                    ByteBuffer buffer = StandardCharsets.UTF_8.newEncoder()
                        .encode(CharBuffer.wrap(password));
                    encoded = new byte[buffer.remaining() + 1];
                    buffer.get(encoded, 0, encoded.length - 1);
                    encoded[encoded.length - 1] = (byte) '\n';
                    destination.write(encoded);
                    destination.flush();
                } finally {
                    if (encoded != null) Arrays.fill(encoded, (byte) 0);
                    wipe();
                }
            }

            @Override public synchronized void close() {
                consumed.set(true);
                wipe();
            }

            private void wipe() {
                if (password == null) return;
                Arrays.fill(password, '\0');
                password = null;
            }

            @Override public String toString() { return "Provided[redacted]"; }
        }

        enum Cancelled implements Result { INSTANCE }
        enum Unavailable implements Result { INSTANCE }
    }
}
