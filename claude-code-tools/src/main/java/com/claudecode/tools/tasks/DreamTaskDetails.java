package com.claudecode.tools.tasks;

import org.apache.commons.lang3.StringUtils;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Dream-specific task state kept alongside the common {@link TaskState} fields.
 *
 * <ul>
 *   <li>{@code MAX_TURNS},
 *       {@code DreamTurn}, {@code DreamPhase}, and the dream-only fields of
 *       {@code DreamTaskState}; {@link #addTurn} ports {@code addDreamTurn}.</li>
 * </ul>
 */
public record DreamTaskDetails(
    DreamPhase phase,
    int sessionsReviewing,
    List<String> filesTouched,
    List<DreamTurn> turns
) {
    public static final int MAX_TURNS = 30;

    public enum DreamPhase { STARTING, UPDATING }

    public record DreamTurn(String text, int toolUseCount) {
        public DreamTurn {
            text = text == null ? "" : text;
            toolUseCount = Math.max(0, toolUseCount);
        }
    }

    public DreamTaskDetails {
        phase = phase == null ? DreamPhase.STARTING : phase;
        sessionsReviewing = Math.max(0, sessionsReviewing);
        filesTouched = filesTouched == null ? List.of() : List.copyOf(filesTouched);
        turns = turns == null ? List.of() : List.copyOf(turns);
    }

    public static DreamTaskDetails starting(int sessionsReviewing) {
        return new DreamTaskDetails(DreamPhase.STARTING, sessionsReviewing, List.of(), List.of());
    }

    public DreamTaskDetails addTurn(DreamTurn turn, List<String> touchedPaths) {
        DreamTurn normalizedTurn = turn == null ? new DreamTurn("", 0) : turn;
        Set<String> seen = new LinkedHashSet<>(filesTouched);
        List<String> newlyTouched = new ArrayList<>();
        if (touchedPaths != null) {
            for (String path : touchedPaths) {
                if (StringUtils.isNotBlank(path) && seen.add(path)) {
                    newlyTouched.add(path);
                }
            }
        }
        if (normalizedTurn.text().isEmpty()
                && normalizedTurn.toolUseCount() == 0
                && newlyTouched.isEmpty()) {
            return this;
        }

        List<String> nextFiles = filesTouched;
        DreamPhase nextPhase = phase;
        if (!newlyTouched.isEmpty()) {
            nextFiles = new ArrayList<>(filesTouched);
            nextFiles.addAll(newlyTouched);
            nextPhase = DreamPhase.UPDATING;
        }

        int keepFrom = Math.max(0, turns.size() - (MAX_TURNS - 1));
        List<DreamTurn> nextTurns = new ArrayList<>(turns.subList(keepFrom, turns.size()));
        nextTurns.add(normalizedTurn);
        return new DreamTaskDetails(nextPhase, sessionsReviewing, nextFiles, nextTurns);
    }
}
