package com.claudecode.core.text;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DisplayTagUtilsTest {

    @Test
    void stripsPairedLowercaseBlocksButNotUppercaseMarkup() {
        assertEquals("my task", DisplayTagUtils.stripDisplayTags(
            "<command-name>/clear</command-name>\nmy task"));
        assertEquals("fix <Button> layout", DisplayTagUtils.stripDisplayTags("fix <Button> layout"));
    }

    @Test
    void displayVariantFallsBackAndAllowEmptyDoesNot() {
        String systemOnly = "<ide_opened_file>x.java</ide_opened_file>";
        assertEquals(systemOnly, DisplayTagUtils.stripDisplayTags(systemOnly));
        assertEquals("", DisplayTagUtils.stripDisplayTagsAllowEmpty(systemOnly));
    }

    @Test
    void ideOnlyVariantPreservesOtherLowercaseMarkup() {
        assertEquals("<code>keep</code>", DisplayTagUtils.stripIdeContextTags(
            "<ide_selection>noise</ide_selection>\n<code>keep</code>"));
    }
}
