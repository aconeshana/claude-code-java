package com.claudecode.runtime.query;


/**
 * Background memory-consolidation trigger.
 */
public interface AutoDreamEngine {

    void maybeRunAutoDream(QuerySession engine);
}
