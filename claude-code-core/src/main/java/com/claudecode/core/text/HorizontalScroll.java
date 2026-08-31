package com.claudecode.core.text;

import java.util.List;

/**
 * Width-based edge scrolling for compact horizontal item lists.
 *
 * <ul>
 *   <li> —
 *       {@code calculateHorizontalScrollWindow}.</li>
 * </ul>
 */
public final class HorizontalScroll {
    private HorizontalScroll() {}

    public record Window(int startIndex, int endIndex,
                         boolean showLeftArrow, boolean showRightArrow) {}

    public static Window calculate(List<Integer> itemWidths, int availableWidth,
                                   int arrowWidth, int selectedIndex,
                                   boolean firstItemHasSeparator) {
        int total = itemWidths.size();
        if (total == 0) return new Window(0, 0, false, false);
        int selected = Math.max(0, Math.min(selectedIndex, total - 1));
        int allWidth = itemWidths.stream().mapToInt(Integer::intValue).sum();
        if (allWidth <= availableWidth) return new Window(0, total, false, false);
        int[] cumulative = new int[total + 1];
        for (int i = 0; i < total; i++) cumulative[i + 1] = cumulative[i] + itemWidths.get(i);

        int start = 0, end = 1;
        while (end < total && rangeWidth(cumulative, start, end + 1, firstItemHasSeparator)
                <= effectiveWidth(availableWidth, arrowWidth, start, end + 1, total)) end++;
        if (selected >= start && selected < end) return window(start, end, total);
        if (selected >= end) {
            end = selected + 1;
            start = selected;
            while (start > 0 && rangeWidth(cumulative, start - 1, end, firstItemHasSeparator)
                    <= effectiveWidth(availableWidth, arrowWidth, start - 1, end, total)) start--;
        } else {
            start = selected;
            end = selected + 1;
            while (end < total && rangeWidth(cumulative, start, end + 1, firstItemHasSeparator)
                    <= effectiveWidth(availableWidth, arrowWidth, start, end + 1, total)) end++;
        }
        return window(start, end, total);
    }

    private static int rangeWidth(int[] cumulative, int start, int end, boolean separator) {
        return cumulative[end] - cumulative[start] - (separator && start > 0 ? 1 : 0);
    }

    private static int effectiveWidth(int width, int arrowWidth, int start, int end, int total) {
        return width - (start > 0 ? arrowWidth : 0) - (end < total ? arrowWidth : 0);
    }

    private static Window window(int start, int end, int total) {
        return new Window(start, end, start > 0, end < total);
    }
}
