package com.claudecode.cli;

import java.io.InputStream;
import java.io.PrintWriter;
import java.io.PrintStream;
import java.util.Objects;

/**
 * Non-owning stdout/stderr-like sink used by CLI session orchestration.
 *
 * <p>This is the one place the process streams are named. Every other production
 * type writes through a {@code CliOutput} so the CLI can run against a captured
 * or redirected sink — and so that a value bound at the process boundary stays
 * swappable in tests. {@code NoDirectProcessStreamAccess} in
 * {@code config/pmd/ruleset.xml} enforces that by failing the build on a direct
 * {@code System.out}/{@code System.err} read anywhere else; this file is
 * suppressed because wrapping them is exactly its job.
 */
@SuppressWarnings("PMD.NoDirectProcessStreamAccess")
interface CliOutput {

    void print(String value);

    void println(String value);

    void println();

    void flush();

    static CliOutput systemOut() {
        return new PrintStreamOutput(System.out);
    }

    static CliOutput systemErr() {
        return new PrintStreamOutput(System.err);
    }

    static InputStream systemInStream() {
        return System.in;
    }

    static PrintStream systemOutStream() {
        return System.out;
    }

    static PrintStream systemErrStream() {
        return System.err;
    }

    static CliOutput borrowed(PrintWriter writer) {
        return new PrintWriterOutput(writer);
    }

    final class PrintWriterOutput implements CliOutput {
        private final PrintWriter writer;

        private PrintWriterOutput(PrintWriter writer) {
            this.writer = Objects.requireNonNull(writer, "writer");
        }

        @Override
        public void print(String value) {
            writer.print(value);
        }

        @Override
        public void println(String value) {
            writer.println(value);
        }

        @Override
        public void println() {
            writer.println();
        }

        @Override
        public void flush() {
            writer.flush();
        }
    }

    final class PrintStreamOutput implements CliOutput {
        private final PrintStream stream;

        private PrintStreamOutput(PrintStream stream) {
            this.stream = Objects.requireNonNull(stream, "stream");
        }

        @Override
        public void print(String value) {
            stream.print(value);
        }

        @Override
        public void println(String value) {
            stream.println(value);
        }

        @Override
        public void println() {
            stream.println();
        }

        @Override
        public void flush() {
            stream.flush();
        }
    }
}
