package com.claudecode.ui.lanterna.input;

import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.input.MouseAction;

/**
 * Press/release/hover latch shared by every clickable footer target.
 *
 * <p>A left-button press inside a target arms the latch; a release on the same
 * target activates it, a release anywhere else cancels it, and drags are
 * consumed while armed so the transcript never scrolls under a half-finished
 * click. Targets are small integers so a single latch serves both fixed
 * rectangles ({@code 0} = inside) and row lists (the hit row).
 *
 * <ul>
 *   <li>Java-side extension: the released product has no mouse footer
 *       controls, so this protocol has no TypeScript counterpart.</li>
 * </ul>
 */
final class FooterMouseLatch {

    /** Sentinel for "the pointer is not over any target". */
    static final int OUTSIDE = -1;

    /** Result of tracking one mouse event. */
    record Outcome(boolean consumed, int activatedTarget, boolean hoverChanged) {
        static final Outcome NONE = new Outcome(false, OUTSIDE, false);

        boolean activated() { return activatedTarget != OUTSIDE; }
    }

    private int pressedTarget = OUTSIDE;
    private int hoveredTarget = OUTSIDE;

    boolean hovered() { return hoveredTarget != OUTSIDE; }

    int hoveredTarget() { return hoveredTarget; }

    void reset() {
        pressedTarget = OUTSIDE;
        hoveredTarget = OUTSIDE;
    }

    Outcome track(MouseAction mouse, int target) {
        boolean inside = target != OUTSIDE;
        return switch (mouse.getActionType()) {
            case MOVE -> {
                boolean changed = hoveredTarget != target;
                hoveredTarget = target;
                yield new Outcome(inside, OUTSIDE, changed);
            }
            case CLICK_DOWN -> {
                if (mouse.getButton() != 1) yield Outcome.NONE;
                pressedTarget = target;
                yield new Outcome(inside, OUTSIDE, false);
            }
            case DRAG -> new Outcome(pressedTarget != OUTSIDE, OUTSIDE, false);
            case CLICK_RELEASE -> {
                if (mouse.getButton() != 1) yield Outcome.NONE;
                boolean activate = pressedTarget != OUTSIDE && pressedTarget == target;
                boolean consume = pressedTarget != OUTSIDE;
                pressedTarget = OUTSIDE;
                yield new Outcome(consume, activate ? target : OUTSIDE, false);
            }
            default -> Outcome.NONE;
        };
    }

    /** Rectangle hit test in absolute terminal cells; {@code 0} inside, {@link #OUTSIDE} otherwise. */
    static int rectTarget(TerminalPosition point, TerminalPosition origin, TerminalSize size) {
        boolean inside = point.getColumn() >= origin.getColumn()
            && point.getColumn() < origin.getColumn() + size.getColumns()
            && point.getRow() >= origin.getRow()
            && point.getRow() < origin.getRow() + size.getRows();
        return inside ? 0 : OUTSIDE;
    }
}
