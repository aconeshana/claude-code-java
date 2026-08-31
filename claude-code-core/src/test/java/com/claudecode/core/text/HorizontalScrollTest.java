package com.claudecode.core.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.List;
import org.junit.jupiter.api.Test;

class HorizontalScrollTest {
    @Test void showsAllItemsWhenTheyFit() {
        assertEquals(new HorizontalScroll.Window(0, 3, false, false),
            HorizontalScroll.calculate(List.of(3, 3, 3), 9, 2, 1, true));
    }

    @Test void scrollsSelectedItemToRightEdge() {
        assertEquals(new HorizontalScroll.Window(2, 4, true, false),
            HorizontalScroll.calculate(List.of(4, 4, 4, 4), 9, 2, 3, true));
    }

    @Test void clampsSelectionAndHandlesEmptyLists() {
        assertEquals(new HorizontalScroll.Window(0, 0, false, false),
            HorizontalScroll.calculate(List.of(), 10, 2, 9, true));
        assertEquals(0, HorizontalScroll.calculate(List.of(6, 6), 7, 2, -5, true).startIndex());
    }
}
