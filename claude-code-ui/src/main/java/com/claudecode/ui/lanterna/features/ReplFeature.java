package com.claudecode.ui.lanterna.features;

import com.claudecode.ui.lanterna.overlay.InlineOverlay;
import java.util.List;

/**
 * A self-contained slice of REPL UI that owns its dialogs and contributes them to the scene.
 *
 * <p>The composition root registers {@link #overlays()} for input routing in one explicit
 * feature list; the render z-order of each feature's views is still declared component by
 * component in the scene layout, because views from one feature interleave with others
 * (for example the effort slider sits above the startup gates while the theme picker sits below
 * the btw panel). Only one inline overlay is active at a time, so overlay registration order is
 * not behaviourally significant — grouping it per feature is safe.
 */
public interface ReplFeature {

    /** Inline overlays this feature owns, or an empty list when it renders nothing modal. */
    List<InlineOverlay> overlays();
}
