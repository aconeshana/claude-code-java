package com.claudecode.ui.lanterna.repl;

import com.claudecode.core.annotation.Explanation;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.LockSupport;
import org.apache.commons.lang3.StringUtils;

/**
 * Non-blocking terminal escape-sequence framer placed below Lanterna's decoder.
 */
final class EscapeSequenceInputStream extends InputStream {

    static final Duration DEFAULT_ESCAPE_TIMEOUT = Duration.ofMillis(50);
    @Explanation("Shortens only the still-unclassified lone-Escape ambiguity window on local "
        + "terminals; 197 waits 50 ms for every incomplete sequence, while Java first drains "
        + "already-queued PTY bytes, retains a conservative window for remote/multiplexed "
        + "terminals, and restores the full 50 ms after any sequence continuation arrives")
    static final Duration DEFAULT_LONE_ESCAPE_TIMEOUT = Duration.ofMillis(12);
    static final Duration DEFAULT_REMOTE_LONE_ESCAPE_TIMEOUT = Duration.ofMillis(45);
    static final int INPUT_READER_PRIORITY = Math.min(Thread.MAX_PRIORITY,
        Thread.NORM_PRIORITY + 1);

    private static final int READ_BUFFER_SIZE = 8192;
    private static final int INITIAL_READY_CAPACITY = 1024;
    private static final int MAX_ESCAPE_SEQUENCE_BYTES = 4096;
    /** Keep the 50 ms ambiguity window, but publish its expiry with sub-ms precision. */
    private static final long DEADLINE_POLL_NANOS = 250_000L;
    private static final int ESC = 0x1b;
    private static final int BEL = 0x07;
    private static final String[] REMOTE_TERMINAL_MARKERS = {
        "SSH_CONNECTION", "SSH_CLIENT", "SSH_TTY", "MOSH_IP", "TMUX", "STY"
    };

    private final InputStream source;
    private final long escapeTimeoutNanos;
    private final long loneEscapeTimeoutNanos;
    private final ByteQueue ready = new ByteQueue(INITIAL_READY_CAPACITY);
    private final byte[] pending = new byte[MAX_ESCAPE_SEQUENCE_BYTES];
    private final Thread readerThread;

    private SequenceState state = SequenceState.GROUND;
    private int pendingLength;
    private int x10PayloadRemaining;
    private boolean stringTerminatorEsc;
    private long deadlineNanos;
    private boolean endOfInput;
    private boolean closed;
    private IOException failure;

    EscapeSequenceInputStream(InputStream source) {
        this(source, DEFAULT_ESCAPE_TIMEOUT, productionLoneEscapeTimeout(System.getenv()));
    }

    EscapeSequenceInputStream(InputStream source, Duration escapeTimeout) {
        this(source, escapeTimeout, escapeTimeout.equals(DEFAULT_ESCAPE_TIMEOUT)
            ? DEFAULT_LONE_ESCAPE_TIMEOUT : escapeTimeout);
    }

    EscapeSequenceInputStream(InputStream source, Duration escapeTimeout,
                              Duration loneEscapeTimeout) {
        this.source = Objects.requireNonNull(source, "source");
        Objects.requireNonNull(escapeTimeout, "escapeTimeout");
        Objects.requireNonNull(loneEscapeTimeout, "loneEscapeTimeout");
        if (escapeTimeout.isNegative() || escapeTimeout.isZero()) {
            throw new IllegalArgumentException("escapeTimeout must be positive");
        }
        if (loneEscapeTimeout.isNegative() || loneEscapeTimeout.isZero()) {
            throw new IllegalArgumentException("loneEscapeTimeout must be positive");
        }
        escapeTimeoutNanos = escapeTimeout.toNanos();
        loneEscapeTimeoutNanos = loneEscapeTimeout.toNanos();
        readerThread = Thread.ofPlatform()
            .daemon(true)
            .name("claude-terminal-input-reader")
            .priority(INPUT_READER_PRIORITY)
            .start(this::readSource);
    }

    static Duration productionLoneEscapeTimeout(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        for (String marker : REMOTE_TERMINAL_MARKERS) {
            if (StringUtils.isNotBlank(environment.get(marker))) {
                return DEFAULT_REMOTE_LONE_ESCAPE_TIMEOUT;
            }
        }
        return DEFAULT_LONE_ESCAPE_TIMEOUT;
    }

    @Override
    public synchronized int available() throws IOException {
        throwFailureIfNecessary();
        return ready.size();
    }

    @Override
    public synchronized int read() throws IOException {
        awaitReadyByte();
        if (!ready.isEmpty()) return ready.remove() & 0xff;
        throwFailureIfNecessary();
        return -1;
    }

    @Override
    public synchronized int read(byte[] target, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, target.length);
        if (length == 0) return 0;
        awaitReadyByte();
        if (!ready.isEmpty()) return ready.remove(target, offset, length);
        throwFailureIfNecessary();
        return -1;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        notifyAll();
        readerThread.interrupt();
    }

    int readerThreadPriority() {
        return readerThread.getPriority();
    }

    private void awaitReadyByte() throws IOException {
        while (ready.isEmpty() && failure == null && !endOfInput && !closed) {
            try {
                wait();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                InterruptedIOException interrupted = new InterruptedIOException(
                    "interrupted while waiting for terminal input");
                interrupted.initCause(exception);
                throw interrupted;
            }
        }
    }

    private void readSource() {
        byte[] buffer = new byte[READ_BUFFER_SIZE];
        try {
            while (!isClosed()) {
                int count = source.read(buffer);
                if (count < 0) {
                    synchronized (this) {
                        publishPending();
                        endOfInput = true;
                        notifyAll();
                    }
                    return;
                }
                if (count == 0) continue;
                synchronized (this) {
                    for (int index = 0; index < count; index++) {
                        accept(buffer[index] & 0xff);
                    }
                }
                // PTYs may split one write("model") across adjacent reads.
                // Drain bytes that are already available before waking the GUI
                // so ordinary input remains one terminal-poll generation. This
                // is non-blocking packet coalescing on the reader thread, not a
                // debounce or UI-thread wait.
                drainAvailableBytes(buffer);
                synchronized (this) {
                    notifyAll();
                }
                drainIncompleteSequence(buffer);
            }
        } catch (IOException exception) {
            synchronized (this) {
                if (!closed) failure = exception;
                notifyAll();
            }
        }
    }

    private synchronized boolean isClosed() {
        return closed;
    }

    private void drainAvailableBytes(byte[] buffer) throws IOException {
        while (!isClosed()) {
            int available = source.available();
            if (available <= 0) return;
            int count = source.read(buffer, 0, Math.min(available, buffer.length));
            if (count < 0) return;
            if (count == 0) return;
            synchronized (this) {
                for (int index = 0; index < count; index++) {
                    accept(buffer[index] & 0xff);
                }
            }
        }
    }

    private void drainIncompleteSequence(byte[] buffer) throws IOException {
        while (hasPendingSequence() && !isClosed()) {
            int available = source.available();
            if (available > 0) {
                int count = source.read(buffer, 0, Math.min(available, buffer.length));
                if (count < 0) return;
                synchronized (this) {
                    for (int index = 0; index < count; index++) {
                        accept(buffer[index] & 0xff);
                    }
                    notifyAll();
                }
                continue;
            }

            long remaining = remainingNanos();
            if (remaining <= 0) {
                if (source.available() > 0) continue;
                synchronized (this) {
                    if (pendingLength > 0 && deadlineNanos - System.nanoTime() <= 0) {
                        publishPending();
                        notifyAll();
                    }
                }
                return;
            }
            LockSupport.parkNanos(Math.min(remaining, DEADLINE_POLL_NANOS));
            if (Thread.currentThread().isInterrupted() && isClosed()) return;
        }
    }

    private synchronized boolean hasPendingSequence() {
        return pendingLength > 0;
    }

    private synchronized long remainingNanos() {
        return deadlineNanos - System.nanoTime();
    }

    private void accept(int value) {
        if (state == SequenceState.GROUND) {
            if (value == ESC) {
                startEscape();
            } else {
                ready.add((byte) value);
            }
            return;
        }

        if (pendingLength == pending.length) {
            publishPending();
            accept(value);
            return;
        }
        pending[pendingLength++] = (byte) value;
        deadlineNanos = System.nanoTime() + escapeTimeoutNanos;

        switch (state) {
            case ESCAPE -> acceptAfterEscape(value);
            case ESCAPE_INTERMEDIATE -> acceptEscapeIntermediate(value);
            case CSI -> acceptCsi(value);
            case X10_MOUSE -> acceptX10Payload();
            case SS3 -> acceptSs3(value);
            case OSC -> acceptStringSequence(value, true);
            case DCS, APC -> acceptStringSequence(value, false);
            case GROUND -> throw new IllegalStateException("ground state cannot own pending bytes");
        }
    }

    private void startEscape() {
        pending[0] = (byte) ESC;
        pendingLength = 1;
        state = SequenceState.ESCAPE;
        deadlineNanos = System.nanoTime() + loneEscapeTimeoutNanos;
    }

    private void acceptAfterEscape(int value) {
        switch (value) {
            case '[' -> state = SequenceState.CSI;
            case 'O' -> state = SequenceState.SS3;
            case ']' -> state = SequenceState.OSC;
            case 'P' -> state = SequenceState.DCS;
            case '_' -> state = SequenceState.APC;
            case ESC -> publishFirstEscapeAndRestart();
            default -> {
                if (isIntermediate(value)) {
                    state = SequenceState.ESCAPE_INTERMEDIATE;
                } else {
                    publishPending();
                }
            }
        }
    }

    private void publishFirstEscapeAndRestart() {
        ready.add((byte) ESC);
        pending[0] = (byte) ESC;
        pendingLength = 1;
        state = SequenceState.ESCAPE;
        deadlineNanos = System.nanoTime() + loneEscapeTimeoutNanos;
    }

    private void acceptEscapeIntermediate(int value) {
        if (isIntermediate(value)) return;
        publishPending();
    }

    private void acceptCsi(int value) {
        if (pendingLength == 3 && value == 'M') {
            state = SequenceState.X10_MOUSE;
            x10PayloadRemaining = 3;
            return;
        }
        if (isCsiFinal(value)) {
            publishPending();
            return;
        }
        if (!isCsiParameter(value) && !isIntermediate(value)) publishPending();
    }

    private void acceptX10Payload() {
        x10PayloadRemaining--;
        if (x10PayloadRemaining == 0) publishPending();
    }

    private void acceptSs3(int value) {
        if (isCsiFinal(value) || !isIntermediate(value)) publishPending();
    }

    private void acceptStringSequence(int value, boolean bellTerminates) {
        if (bellTerminates && value == BEL) {
            publishPending();
            return;
        }
        if (stringTerminatorEsc && value == '\\') {
            publishPending();
            return;
        }
        stringTerminatorEsc = value == ESC;
    }

    private void publishPending() {
        if (pendingLength > 0) ready.add(pending, 0, pendingLength);
        pendingLength = 0;
        state = SequenceState.GROUND;
        x10PayloadRemaining = 0;
        stringTerminatorEsc = false;
        deadlineNanos = 0;
    }

    private void throwFailureIfNecessary() throws IOException {
        if (failure != null) throw failure;
    }

    private static boolean isIntermediate(int value) {
        return value >= 0x20 && value <= 0x2f;
    }

    private static boolean isCsiParameter(int value) {
        return value >= 0x30 && value <= 0x3f;
    }

    private static boolean isCsiFinal(int value) {
        return value >= 0x40 && value <= 0x7e;
    }

    private enum SequenceState {
        GROUND,
        ESCAPE,
        ESCAPE_INTERMEDIATE,
        CSI,
        X10_MOUSE,
        SS3,
        OSC,
        DCS,
        APC
    }

    private static final class ByteQueue {
        private byte[] elements;
        private int head;
        private int size;

        private ByteQueue(int initialCapacity) {
            elements = new byte[initialCapacity];
        }

        private boolean isEmpty() {
            return size == 0;
        }

        private int size() {
            return size;
        }

        private void add(byte value) {
            ensureCapacity(size + 1);
            elements[(head + size) % elements.length] = value;
            size++;
        }

        private void add(byte[] source, int offset, int length) {
            ensureCapacity(size + length);
            int tail = (head + size) % elements.length;
            int first = Math.min(length, elements.length - tail);
            System.arraycopy(source, offset, elements, tail, first);
            System.arraycopy(source, offset + first, elements, 0, length - first);
            size += length;
        }

        private byte remove() {
            byte value = elements[head];
            head = (head + 1) % elements.length;
            size--;
            return value;
        }

        private int remove(byte[] target, int offset, int maximum) {
            int count = Math.min(maximum, size);
            int first = Math.min(count, elements.length - head);
            System.arraycopy(elements, head, target, offset, first);
            System.arraycopy(elements, 0, target, offset + first, count - first);
            head = (head + count) % elements.length;
            size -= count;
            return count;
        }

        private void ensureCapacity(int required) {
            if (required <= elements.length) return;
            int capacity = Math.max(required, elements.length * 2);
            byte[] expanded = new byte[capacity];
            int existing = size;
            int first = Math.min(existing, elements.length - head);
            System.arraycopy(elements, head, expanded, 0, first);
            System.arraycopy(elements, 0, expanded, first, existing - first);
            elements = expanded;
            head = 0;
        }
    }
}
