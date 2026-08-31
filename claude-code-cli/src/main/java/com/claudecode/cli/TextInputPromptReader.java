package com.claudecode.cli;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Resolves the one-shot text prompt supplied to print mode.
 *
 * <ul>
 *   <li>reads non-TTY text stdin and
 *       appends it to the positional prompt with one separator newline.</li>
 *   <li>waits up to three
 *       seconds for the first byte, then waits unconditionally for EOF once a
 *       producer has started writing.</li>
 * </ul>
 */
final class TextInputPromptReader {

    record Result(String prompt, boolean timedOut) {}

    private TextInputPromptReader() {}

    static Result resolve(String argvPrompt, InputStream stdin, boolean stdinIsTty,
                          Duration firstByteTimeout) throws IOException {
        String prompt = argvPrompt == null ? "" : argvPrompt;
        if (stdinIsTty) return new Result(prompt, false);

        FutureTask<Integer> firstByteRead = new FutureTask<>(stdin::read);
        Thread.ofVirtual().name("claude-stdin-text-peek").start(firstByteRead);

        final int firstByte;
        try {
            firstByte = firstByteRead.get(
                Math.max(0L, firstByteTimeout.toMillis()), TimeUnit.MILLISECONDS);
        } catch (TimeoutException _) {
            firstByteRead.cancel(true);
            return new Result(prompt, true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while reading stdin", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) throw io;
            throw new IOException("Failed to read stdin", cause);
        }

        if (firstByte < 0) return new Result(prompt, false);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(firstByte);
        stdin.transferTo(bytes);
        String stdinText = bytes.toString(StandardCharsets.UTF_8);
        if (prompt.isEmpty()) return new Result(stdinText, false);
        if (stdinText.isEmpty()) return new Result(prompt, false);
        return new Result(prompt + "\n" + stdinText, false);
    }
}
