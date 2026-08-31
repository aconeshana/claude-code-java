package com.claudecode.ui.lanterna.repl;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.runtime.tasks.TaskBoardPort;

import java.util.Objects;

@Explanation("Adds a user-expandable task board because released 2.1.197 only toggles the compact five-row view.")
final class TaskBoardToggleState {

    enum Toggle {
        SHOW_COMPACT,
        SHOW_EXPANDED,
        HIDE
    }

    private String listId = "";
    private boolean expanded;

    void updateSnapshot(TaskBoardPort.Snapshot snapshot) {
        TaskBoardPort.Snapshot current = snapshot == null
            ? TaskBoardPort.Snapshot.EMPTY : snapshot;
        if (!Objects.equals(listId, current.listId()) || current.hidden()) {
            expanded = false;
        }
        listId = current.listId();
    }

    Toggle toggle(boolean visible, boolean expandable) {
        if (!visible) {
            expanded = false;
            return Toggle.SHOW_COMPACT;
        }
        if (expandable && !expanded) {
            expanded = true;
            return Toggle.SHOW_EXPANDED;
        }
        expanded = false;
        return Toggle.HIDE;
    }

    void showCompact() {
        expanded = false;
    }

    boolean expanded() {
        return expanded;
    }
}
