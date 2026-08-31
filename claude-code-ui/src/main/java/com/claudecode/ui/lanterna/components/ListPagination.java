package com.claudecode.ui.lanterna.components;

/**
 * Continuous-scroll windowing for the plugin panel's lists: keeps the selected index visible inside
 * a {@code maxVisible}-row window, scrolling the window only when the selection walks off either
 * edge.
 */
public final class ListPagination {


    static final int DEFAULT_MAX_VISIBLE = 5;

    private final int maxVisible;
    private int totalItems;
    private int selectedIndex;
    private int scrollOffset;

    public ListPagination(int maxVisible) {
        this.maxVisible = maxVisible;
    }

    public ListPagination() {
        this(DEFAULT_MAX_VISIBLE);
    }

    /** Resets for a new list; selection and offset return to the top. */
    public void reset(int totalItems) {
        this.totalItems = Math.max(0, totalItems);
        this.selectedIndex = 0;
        this.scrollOffset = 0;
    }


    public void select(int newIndex) {
        selectedIndex = Math.max(0, Math.min(newIndex, Math.max(0, totalItems - 1)));
        if (!needsPagination()) {
            scrollOffset = 0;
            return;
        }
        if (selectedIndex < scrollOffset) {
            scrollOffset = selectedIndex;
        } else if (selectedIndex >= scrollOffset + maxVisible) {
            scrollOffset = selectedIndex - maxVisible + 1;
        } else {
            scrollOffset = Math.min(scrollOffset, Math.max(0, totalItems - maxVisible));
        }
    }

    public void moveBy(int delta) {
        select(selectedIndex + delta);
    }

    public boolean needsPagination() {
        return totalItems > maxVisible;
    }

    public int selectedIndex() {
        return selectedIndex;
    }

    public int totalItems() {
        return totalItems;
    }

    public int startIndex() {
        return scrollOffset;
    }

    public int endIndex() {
        return Math.min(scrollOffset + maxVisible, totalItems);
    }

    public boolean canScrollUp() {
        return scrollOffset > 0;
    }

    public boolean canScrollDown() {
        return scrollOffset + maxVisible < totalItems;
    }

    /** 1-based position of the selection, for the "(current/total)" header. */
    public int scrollCurrent() {
        return selectedIndex + 1;
    }
}
