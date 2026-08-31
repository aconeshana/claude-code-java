package com.claudecode.ui.lanterna.components;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


class ListPaginationTest {

    @Test
    void smallList_needsNoPagination() {
        ListPagination p = new ListPagination(5);
        p.reset(3);
        assertFalse(p.needsPagination());
        assertEquals(0, p.startIndex());
        assertEquals(3, p.endIndex());
        assertFalse(p.canScrollUp());
        assertFalse(p.canScrollDown());
    }

    @Test
    void selectingPastWindow_scrollsDownTrailing() {
        ListPagination p = new ListPagination(5);
        p.reset(10);
        for (int i = 0; i < 7; i++) {
            p.moveBy(1);
        }
        assertEquals(7, p.selectedIndex());

        assertEquals(3, p.startIndex());
        assertEquals(8, p.endIndex());
        assertTrue(p.canScrollUp());
        assertTrue(p.canScrollDown());
    }

    @Test
    void selectingAboveWindow_snapsOffsetToSelection() {
        ListPagination p = new ListPagination(5);
        p.reset(10);
        p.select(9);
        assertEquals(5, p.startIndex());
        p.select(2);
        assertEquals(2, p.startIndex());
        assertTrue(p.canScrollDown());
    }

    @Test
    void selection_isClampedToBounds() {
        ListPagination p = new ListPagination(5);
        p.reset(4);
        p.select(99);
        assertEquals(3, p.selectedIndex());
        p.moveBy(-99);
        assertEquals(0, p.selectedIndex());
    }

    @Test
    void scrollPosition_reportsOneBasedCurrent() {
        ListPagination p = new ListPagination(5);
        p.reset(10);
        p.select(4);
        assertEquals(5, p.scrollCurrent());
        assertEquals(10, p.totalItems());
    }
}
