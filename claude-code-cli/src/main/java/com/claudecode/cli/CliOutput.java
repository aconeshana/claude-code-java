package com.claudecode.cli;

import java.io.InputStream;
import java.io.PrintWriter;
import java.io.PrintStream;
import java.util.Objects;

/**
 * Non-owning stdout/stderr-like sink used by CLI session orchestration.
 */
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
