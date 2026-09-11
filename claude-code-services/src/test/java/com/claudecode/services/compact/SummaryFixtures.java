package com.claudecode.services.compact;

/**
 * Shapes stub summarizer output the way a real one does.
 *
 * <p>The compact prompt requires the model to answer with a {@code <summary>} block, and
 * {@link CompactService#containsSummarySection} rejects anything that lacks one so a refusal can
 * never replace the conversation. A stub that returns a bare sentence is therefore no longer a
 * stand-in for a model response, and tests exercising the compaction pipeline wrap their fixtures
 * here rather than restating the markup.
 */
final class SummaryFixtures {

    private SummaryFixtures() {
        // test utility
    }

    /** Wraps summary body text in the {@code <summary>} block a model response carries. */
    static String asModelSummary(String body) {
        return "<summary>\n" + body + "\n</summary>";
    }
}
