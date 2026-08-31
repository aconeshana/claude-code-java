package com.claudecode.ui.lanterna.repl;

import com.googlecode.lanterna.gui2.BasicWindow;
import com.googlecode.lanterna.gui2.Component;
import com.googlecode.lanterna.gui2.ComponentRenderer;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.Window;
import com.googlecode.lanterna.gui2.WindowListener;
import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.TextGUIGraphics;
import java.util.*;

import com.claudecode.ui.lanterna.components.SmartLayout;
import com.claudecode.ui.lanterna.overlay.InlineOverlay;
import com.claudecode.ui.lanterna.overlay.OverlayHost;
import com.claudecode.ui.lanterna.transcript.SelectionAwareTextGUI;

/**
 * Owns the Lanterna REPL scene graph, overlay registry, and fullscreen window shell.
 */
final class ReplScene {

    private final OverlayHost overlays = new OverlayHost();
    private final Panel root = createRoot();
    private boolean sealed;

    /**
     * The fullscreen message panel plus pinned footer cover every cell. The
     * stock Panel renderer clears all 4,800 cells first and then immediately
     * overwrites them through the children on every keystroke, so this root
     * skips the redundant pre-fill.
     */
    static Panel createRoot() {
        return new Panel(new SmartLayout()) {
            @Override protected ComponentRenderer<Panel> createDefaultRenderer() {
                return new IncrementalRootRenderer(this);
            }
        };
    }

    /**
     * Lanterna's default panel renderer redraws every visible child whenever
     * one descendant is invalid. The REPL root has dozens of mounted overlays,
     * so a cursor move in one picker needlessly redraws the transcript and
     * input tree. Retain valid child pixels and redraw only invalid components,
     * plus later siblings that overlap a changed lower layer. A layout/geometry
     * change still repaints the complete visible tree.
     */
    private static final class IncrementalRootRenderer implements ComponentRenderer<Panel> {
        private final Panel root;
        private TerminalSize lastSize;
        private final Map<Component, TerminalSize> preferredSizes = new IdentityHashMap<>();
        /**
         * Visibility is part of the scene geometry.  In particular, the spinner
         * collapses from one or more rows to zero rows when a turn completes.
         * Keep the previous value so an invisible child can still trigger a
         * relayout and repaint of the content that takes its place.
         */
        private final Map<Component, Boolean> lastVisibility = new IdentityHashMap<>();

        private IncrementalRootRenderer(Panel root) {
            this.root = root;
        }

        @Override
        public TerminalSize getPreferredSize(Panel component) {
            return root.getLayoutManager().getPreferredSize(root.getChildrenList());
        }

        @Override
        public void drawComponent(TextGUIGraphics graphics, Panel component) {
            List<Component> children = List.copyOf(root.getChildrenList());
            TerminalSize size = graphics.getSize();
            boolean visibilityChanged = visibilityChanged(children);
            boolean layoutChanged = lastSize == null
                || !lastSize.equals(size)
                || root.getLayoutManager().hasChanged()
                || visibilityChanged;
            Map<Component, Geometry> before = captureGeometry(children);

            boolean preferredSizeChanged = layoutChanged
                || invalidPreferredSizeChanged(children);
            if (preferredSizeChanged) {
                root.getLayoutManager().doLayout(size, children);
                layoutChanged |= geometryChanged(before, children);
                rememberPreferredSizes(children);
            }
            lastSize = size;
            rememberVisibility(children);

            List<Component> redraw = layoutChanged
                ? children.stream().filter(Component::isVisible).toList()
                : invalidWithCoveringSiblings(children);
            for (Component child : redraw) {
                child.draw(graphics.newTextGraphics(child.getPosition(), child.getSize()));
            }
        }

        private boolean invalidPreferredSizeChanged(List<Component> children) {
            // Child 0 is the transcript viewport: SmartLayout always assigns it
            // the remaining rows, independent of its content preferred size.
            for (int i = 1; i < children.size(); i++) {
                Component child = children.get(i);
                if (!child.isVisible() || !child.isInvalid()) continue;
                TerminalSize preferred = child.getPreferredSize();
                TerminalSize previous = preferredSizes.put(child, preferred);
                // Only the ROW dimension feeds SmartLayout geometry (pinnedH → msgH →
                // setSize/setPosition in doLayout); the width assigned to every child is
                // clamped to the full terminal width. A pure column change — e.g. the
                // spinner's token-count / elapsed-metric text churning each animation
                // frame — must therefore not be treated as a layout change, or doLayout
                // runs on every tick and re-reads as a flicker on the pinned footer while
                // streamed tool output scrolls beneath it.
                if (previous == null || previous.getRows() != preferred.getRows()) return true;
            }
            return false;
        }

        private void rememberPreferredSizes(List<Component> children) {
            preferredSizes.keySet().retainAll(children);
            for (int i = 1; i < children.size(); i++) {
                Component child = children.get(i);
                if (child.isVisible()) {
                    preferredSizes.put(child, child.getPreferredSize());
                }
            }
        }

        private boolean visibilityChanged(List<Component> children) {
            if (lastVisibility.size() != children.size()) return true;
            for (Component child : children) {
                if (!Boolean.valueOf(child.isVisible()).equals(lastVisibility.get(child))) {
                    return true;
                }
            }
            return false;
        }

        private void rememberVisibility(List<Component> children) {
            lastVisibility.keySet().retainAll(children);
            for (Component child : children) {
                lastVisibility.put(child, child.isVisible());
            }
        }

        private static List<Component> invalidWithCoveringSiblings(List<Component> children) {
            Set<Component> redraw = Collections.newSetFromMap(new IdentityHashMap<>());
            for (int i = 0; i < children.size(); i++) {
                Component changed = children.get(i);
                if (!changed.isVisible() || !changed.isInvalid()) continue;
                redraw.add(changed);
                Geometry changedGeometry = Geometry.of(changed);
                for (int j = i + 1; j < children.size(); j++) {
                    Component covering = children.get(j);
                    if (covering.isVisible() && changedGeometry.intersects(Geometry.of(covering))) {
                        redraw.add(covering);
                    }
                }
            }
            if (redraw.isEmpty()) {
                // Parent-only invalidation (for example a theme/focus change)
                // has no child marker; preserve the stock full-redraw behavior.
                return children.stream().filter(Component::isVisible).toList();
            }
            List<Component> ordered = new ArrayList<>();
            for (Component child : children) if (redraw.contains(child)) ordered.add(child);
            return ordered;
        }

        private static Map<Component, Geometry> captureGeometry(List<Component> children) {
            Map<Component, Geometry> geometry = new IdentityHashMap<>();
            for (Component child : children) geometry.put(child, Geometry.of(child));
            return geometry;
        }

        private static boolean geometryChanged(Map<Component, Geometry> before,
                                               List<Component> children) {
            for (Component child : children) {
                if (!Geometry.of(child).equals(before.get(child))) return true;
            }
            return false;
        }

        private record Geometry(TerminalPosition position, TerminalSize size, boolean visible) {
            static Geometry of(Component component) {
                return new Geometry(component.getPosition(), component.getSize(), component.isVisible());
            }

            boolean intersects(Geometry other) {
                if (!visible || !other.visible) return false;
                int left = position.getColumn();
                int top = position.getRow();
                int right = left + size.getColumns();
                int bottom = top + size.getRows();
                int otherLeft = other.position.getColumn();
                int otherTop = other.position.getRow();
                int otherRight = otherLeft + other.size.getColumns();
                int otherBottom = otherTop + other.size.getRows();
                return left < otherRight && otherLeft < right
                    && top < otherBottom && otherTop < bottom;
            }
        }
    }

    synchronized void mount(Component... components) {
        ensureMutable();
        for (Component component : components) root.addComponent(component);
    }

    synchronized void register(InlineOverlay overlay) {
        ensureMutable();
        overlays.register(overlay);
    }

    synchronized void registerAll(Collection<? extends InlineOverlay> featureOverlays) {
        ensureMutable();
        overlays.registerAll(featureOverlays.stream().toList());
    }

    synchronized void seal() {
        if (sealed) return;
        overlays.seal();
        sealed = true;
    }

    synchronized boolean isSealed() {
        return sealed;
    }

    OverlayHost overlays() {
        return overlays;
    }

    BasicWindow attach(SelectionAwareTextGUI gui, WindowListener inputRouter) {
        BasicWindow window = new BasicWindow();
        window.setHints(Set.of(
            Window.Hint.FULL_SCREEN,
            Window.Hint.NO_DECORATIONS,
            Window.Hint.NO_POST_RENDERING,
            Window.Hint.FIT_TERMINAL_WINDOW));
        window.setComponent(root);
        window.addWindowListener(inputRouter);
        gui.addWindow(window);
        gui.setActiveWindow(window);
        return window;
    }

    private void ensureMutable() {
        if (sealed) throw new IllegalStateException("REPL scene is sealed");
    }
}
