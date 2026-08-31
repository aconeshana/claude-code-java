package com.claudecode.ui.lanterna.components;

import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The bare spinner-plus-message indicator used by dialogs during async loads. */
class LoadingStateLabelTest {

    @Test
    void theRowIsGlyphThenTwoSpacesThenMessage() {
        LoadingStateLabel label = new LoadingStateLabel().setMessage("Loading session…");

        String row = label.rowText();
        assertTrue(Strings.CS.endsWith(row, "  Loading session…"),
            "TS renders a two-column glyph box followed by a leading-space Text: " + row);
        assertEquals(row.length(), "Loading session…".length() + 3,
            "exactly one glyph column plus two spaces precede the message: " + row);
    }

    @Test
    void aBlankSubtitleKeepsTheSecondRowOut() {
        LoadingStateLabel label = new LoadingStateLabel().setMessage("Loading");
        assertEquals(2, label.getChildCount(), "the subtitle row exists but stays hidden");
        assertFalse(label.getChildrenList().get(1).isVisible());

        label.setSubtitle("Fetching your Claude Code sessions…");
        assertTrue(label.getChildrenList().get(1).isVisible());

        label.setSubtitle("");
        assertFalse(label.getChildrenList().get(1).isVisible(),
            "clearing the subtitle must take the row back out");
    }

    @Test
    void startAdvancesTheGlyphThroughTheGuiInvokerAndStopIsIdempotent() throws Exception {
        // Reduced motion pins the glyph to a static ●, which is exactly the
        // behaviour the last assertion here forbids.
        Assumptions.assumeFalse(SpinnerFrames.REDUCED_MOTION);
        LoadingStateLabel label = new LoadingStateLabel().setMessage("Loading");
        List<String> glyphs = new ArrayList<>();
        AtomicInteger hops = new AtomicInteger();

        assertFalse(label.isRunning());
        label.start(r -> {
            hops.incrementAndGet();
            r.run();
            glyphs.add(label.rowText().substring(0, 1));
        });
        assertTrue(label.isRunning());

        for (int i = 0; i < 100 && glyphs.size() < 3; i++) Thread.sleep(20);
        label.stop();
        label.stop();  // idempotent — the failure path calls it blindly

        assertFalse(label.isRunning());
        assertTrue(hops.get() >= 3, "each frame must hop onto the GUI thread, saw " + hops.get());
        assertTrue(glyphs.stream().distinct().count() > 1,
            "the glyph must actually change between frames: " + glyphs);
    }

    @Test
    void stopWithoutStartIsANoOp() {
        LoadingStateLabel label = new LoadingStateLabel();
        label.stop();
        assertFalse(label.isRunning());
    }
}
