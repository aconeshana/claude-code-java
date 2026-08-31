package com.claudecode.ui.vim;

/**
 * Result of processing a key in the Vim state machine.
 */
public record VimAction(Type type, VimMode newMode, VimOperator operator, int cursorPos, int pendingCount) {

    public enum Type {
        NONE,
        MODE_CHANGE,
        BUFFER_CHANGED,
        CURSOR_MOVED,
        OPERATOR_PENDING,
        WAITING_FOR_CHAR,
        COUNT_PENDING
    }

    public static VimAction none() {
        return new VimAction(Type.NONE, null, null, -1, 0);
    }

    public static VimAction modeChange(VimMode mode) {
        return new VimAction(Type.MODE_CHANGE, mode, null, -1, 0);
    }

    public static VimAction bufferChanged() {
        return new VimAction(Type.BUFFER_CHANGED, null, null, -1, 0);
    }

    public static VimAction cursorMoved(int pos) {
        return new VimAction(Type.CURSOR_MOVED, null, null, pos, 0);
    }

    public static VimAction operatorPending(VimOperator op) {
        return new VimAction(Type.OPERATOR_PENDING, null, op, -1, 0);
    }

    public static VimAction waitingForChar() {
        return new VimAction(Type.WAITING_FOR_CHAR, null, null, -1, 0);
    }

    public static VimAction countPending(int count) {
        return new VimAction(Type.COUNT_PENDING, null, null, -1, count);
    }
}
