package com.claudecode.recipes;

import com.google.errorprone.refaster.annotation.AfterTemplate;
import com.google.errorprone.refaster.annotation.BeforeTemplate;
import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import org.openrewrite.java.template.RecipeDescriptor;

import java.awt.Color;

/**
 * Refaster templates that replace deprecated Lanterna constants/methods with their
 * behavior-identical non-deprecated equivalents.
 *
 * <p>{@code TerminalPosition.TOP_LEFT_CORNER} and {@code TerminalSize.ZERO} are deprecated
 * bare fields with no replacement documented in their Javadoc; the fork's own field
 * initializers (e.g. {@code TOP_LEFT_CORNER = TerminalPosition.of(0, 0)}) prove the
 * {@code of(...)} factory returns the exact same cached singleton, so rewriting the call
 * site to call {@code of(...)} directly is a zero-behavior-difference identity rewrite.
 *
 * <p>{@code TextColor#toColor} is deprecated because it pulls in the {@code java.desktop}
 * module; all three concrete {@code TextColor} implementations in the fork implement it as
 * {@code new Color(getRed, getGreen, getBlue)}, so inlining that expression at the call
 * site is likewise behavior-identical.
 */
public final class UseLanternaOfFactories {

    private UseLanternaOfFactories() {
    }

    @RecipeDescriptor(
        name = "Use TerminalPosition.of(0, 0) instead of the deprecated TOP_LEFT_CORNER constant",
        description = "TOP_LEFT_CORNER is deprecated; TerminalPosition.of(0, 0) returns the same cached instance.")
    public static final class TerminalPositionTopLeftCorner {
        @BeforeTemplate
        TerminalPosition before() {
            return TerminalPosition.TOP_LEFT_CORNER;
        }

        @AfterTemplate
        TerminalPosition after() {
            return TerminalPosition.of(0, 0);
        }
    }

    @RecipeDescriptor(
        name = "Use TerminalSize.of(0, 0) instead of the deprecated ZERO constant",
        description = "ZERO is deprecated; TerminalSize.of(0, 0) returns the same cached instance.")
    public static final class TerminalSizeZero {
        @BeforeTemplate
        TerminalSize before() {
            return TerminalSize.ZERO;
        }

        @AfterTemplate
        TerminalSize after() {
            return TerminalSize.of(0, 0);
        }
    }

    @RecipeDescriptor(
        name = "Inline TextColor#toColor() instead of calling the deprecated method",
        description = "toColor() is deprecated to avoid a hard java.desktop dependency in lanterna itself; "
            + "all TextColor implementations define it as new Color(getRed(), getGreen(), getBlue()).")
    public static final class TextColorToColor {
        @BeforeTemplate
        Color before(TextColor c) {
            return c.toColor();
        }

        @AfterTemplate
        Color after(TextColor c) {
            return new Color(c.getRed(), c.getGreen(), c.getBlue());
        }
    }
}
