package com.claudecode.ui.lanterna.transcript;

import com.claudecode.core.constants.AnsiStyle;
import com.claudecode.core.constants.Figures;
import com.claudecode.core.diff.StructuredPatchHunk;
import com.claudecode.tools.plan.PlanFiles;
import com.claudecode.ui.DiffRenderer;
import com.claudecode.ui.lanterna.features.settings.UiSettings;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.claudecode.ui.syntax.ScopeColorMap;
import com.claudecode.ui.syntax.TmTokenizer;
import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TextColor;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Paints source previews and inline diff hunks into a {@link MessagePanel}: TextMate
 * tokenised code lines, structured-patch hunks with the theme's diff palette, and the
 * dimmed variants used for rejected changes. Also owns the path-display helpers those
 * previews share (cwd-relative paths, diff language by extension, plan-file detection).
 *
 * <p>All members are stateless; colour dimming blends against the theme background once
 * per use site so word backgrounds are never double-dimmed.
 *
 * <ul>
 *   <li>{@code src/components/StructuredDiff.tsx} / {@code src/utils/diff.ts} — hunk
 *       layout, line-number gutter width, added/removed line and word backgrounds.</li>
 *   <li>{@code src/components/FileEditToolUseRejectedMessage.tsx} — {@code dim=true}
 *       selects the dimmed theme backgrounds directly instead of dimming the normal
 *       ones.</li>
 *   <li>{@code src/components/HighlightedCode.tsx} — token scope to colour/style mapping
 *       for code previews.</li>
 *   <li>{@code src/utils/file.ts} — {@code getDisplayPath}: cwd-relative, then
 *       {@code ~}-relative, then absolute.</li>
 * </ul>
 */
final class SourceCodePainter {

    static final String INDENT_CONT = Figures.RESULT_INDENT;

    private SourceCodePainter() {}

    static String displayPath(String filePath) {
        if (StringUtils.isEmpty(filePath)) return "";
        try {
            Path path = Path.of(filePath).toAbsolutePath().normalize();
            Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
            if (path.startsWith(cwd)) {
                String relative = cwd.relativize(path).toString();
                if (!relative.isEmpty()) return relative;
            }
            String home = System.getProperty("user.home");
            if (home != null) {
                Path homePath = Path.of(home).toAbsolutePath().normalize();
                if (path.startsWith(homePath)) {
                    return "~" + File.separator + homePath.relativize(path);
                }
            }
        } catch (RuntimeException _) {
            // Fall through to the original path at the UI boundary.
        }
        return filePath;
    }

    static void appendDiffHunkSeparator(MessagePanel panel) {
        panel.appendMixed(List.of(
            new MessagePanel.Segment(INDENT_CONT + "...", LanternaTheme.welcomeDim())));
    }

    static void appendInlineDiffHunk(MessagePanel panel, StructuredPatchHunk hunk,
                                             String language, boolean dim) {
        List<DiffRenderer.DiffLineView> views = DiffRenderer.renderHunk(hunk, language);
        int maxLine = views.stream()
            .filter(view -> view.lineNo() != null)
            .mapToInt(DiffRenderer.DiffLineView::lineNo)
            .max().orElse(0);
        int digits = Math.max(1, Integer.toString(maxLine).length());
        LanternaTheme.DiffRenderPalette palette = LanternaTheme.diffRenderPalette();

        for (DiffRenderer.DiffLineView view : views) {
            char marker = view.marker();
            TextColor lineBg = switch (marker) {
                // 197 dim=true (FileEditToolUseRejectedMessage) selects the dimmed theme
                // background directly (diffAddedDimmed/diffRemovedDimmed) rather than
                // dimming the normal line background. Keep that two-way lookup here.
                case '+' -> dim ? LanternaTheme.diffAddedDimmed() : palette.addedLineBackground();
                case '-' -> dim ? LanternaTheme.diffRemovedDimmed() : palette.removedLineBackground();
                default -> null;
            };
            TextColor wordBg = switch (marker) {
                case '+' -> palette.addedWordBackground();
                case '-' -> palette.removedWordBackground();
                default -> null;
            };
            TextColor decoration = switch (marker) {
                case '+' -> palette.addedDecoration();
                case '-' -> palette.removedDecoration();
                default -> LanternaTheme.welcomeDim();
            };
            // lineBg is already the theme's dim variant when dim=true; only word/decoration/
            // foreground material gets dimmed here (mirrors Ink dimColor on the diff Text),
            // so lineBg must never be re-dimmed. Dimming happens once at each use site below
            // to avoid stacking blends on wordBg.
            String number = view.lineNo() == null ? "" : Integer.toString(view.lineNo());
            String gutter = marker == '@'
                ? " ".repeat(digits) + "   "
                : " ".repeat(Math.max(0, digits - number.length())) + number + " " + marker + " ";
            TextColor gutterDecoration = dim ? dimColor(decoration) : decoration;
            List<MessagePanel.Segment> segments = new ArrayList<>();
            segments.add(new MessagePanel.Segment(INDENT_CONT + gutter, gutterDecoration, lineBg));
            for (DiffRenderer.Segment segment : view.segments()) {
                TextColor foreground = segment.foreground() != null
                    ? LanternaTheme.toLC(segment.foreground())
                    : segment.kind() == DiffRenderer.SegKind.HUNK
                        ? LanternaTheme.subtle() : LanternaTheme.inputText();
                TextColor background = switch (segment.kind()) {
                    case ADDED, REMOVED -> wordBg;
                    case COMMON -> lineBg;
                    case HUNK -> null;
                };
                if (dim) {
                    foreground = dimColor(foreground);
                    // COMMON segments reuse lineBg, already the theme's dimmed variant, so
                    // only the added/removed word background (wordBg) still needs dimming.
                    if (segment.kind() != DiffRenderer.SegKind.COMMON) {
                        background = dimColor(background);
                    }
                }
                segments.add(new MessagePanel.Segment(segment.text(), foreground, background));
            }
            panel.appendMixed(segments);
        }
    }

    static TmTokenizer.TokenizedCode tokenizeCode(String content, String language) {
        if (UiSettings.readSyntaxHighlightingDisabled()
                || !TmTokenizer.isSupported(language)) {
            return null;
        }
        return TmTokenizer.tokenize(content, language);
    }

    static List<TmTokenizer.TmToken> tokenLine(
            TmTokenizer.TokenizedCode tokenized, int lineIndex) {
        return tokenized != null && lineIndex >= 0 && lineIndex < tokenized.lines().size()
            ? tokenized.lines().get(lineIndex) : List.of();
    }

    static void appendHighlightedCodeLine(MessagePanel panel, String prefix, String line,
                                                   List<TmTokenizer.TmToken> tokens,
                                                   boolean dim) {
        List<MessagePanel.Segment> segments = new ArrayList<>();
        segments.add(new MessagePanel.Segment(prefix,
            dim ? dimColor(LanternaTheme.welcomeDim()) : LanternaTheme.welcomeDim()));
        if (tokens == null || tokens.isEmpty()) {
            segments.add(new MessagePanel.Segment(line,
                dim ? dimColor(TextColor.ANSI.DEFAULT) : TextColor.ANSI.DEFAULT));
            panel.appendMixed(segments);
            return;
        }
        int cursor = 0;
        for (TmTokenizer.TmToken token : tokens) {
            int start = Math.max(cursor, Math.min(token.start(), line.length()));
            int end = Math.max(start, Math.min(token.end(), line.length()));
            if (start > cursor) {
                TextColor plain = dim ? dimColor(TextColor.ANSI.DEFAULT) : TextColor.ANSI.DEFAULT;
                segments.add(new MessagePanel.Segment(line.substring(cursor, start), plain));
            }
            if (end > start) {
                String text = line.substring(start, end);
                TextColor color = LanternaTheme.toLC(ScopeColorMap.scopeColor(
                    token.scopes(), text, LanternaTheme.activeThemeName()));
                if (dim) color = dimColor(color);
                segments.add(new MessagePanel.Segment(text, color, null, null,
                    toLanternaStyles(ScopeColorMap.scopeStyle(token.scopes()))));
                cursor = end;
            }
        }
        if (cursor < line.length()) {
            TextColor plain = dim ? dimColor(TextColor.ANSI.DEFAULT) : TextColor.ANSI.DEFAULT;
            segments.add(new MessagePanel.Segment(line.substring(cursor), plain));
        }
        panel.appendMixed(segments);
    }

    static Set<SGR> toLanternaStyles(Set<AnsiStyle> styles) {
        if (styles == null || styles.isEmpty()) return Set.of();
        Set<SGR> result = new HashSet<>();
        if (styles.contains(AnsiStyle.BOLD)) result.add(SGR.BOLD);
        if (styles.contains(AnsiStyle.ITALIC)) result.add(SGR.ITALIC);
        if (styles.contains(AnsiStyle.UNDERLINE)) result.add(SGR.UNDERLINE);
        return Set.copyOf(result);
    }

    static TextColor dimColor(TextColor color) {
        if (color == null) return null;
        TextColor background = LanternaTheme.clawdBackground();
        return new TextColor.RGB(
            blend(color.getRed(), background.getRed()),
            blend(color.getGreen(), background.getGreen()),
            blend(color.getBlue(), background.getBlue()));
    }

    static int blend(int foreground, int background) {
        return Math.clamp((foreground * 55L + background * 45L) / 100, 0, 255);
    }

    static boolean isPlanFile(String filePath) {
        if (StringUtils.isBlank(filePath)) return false;
        return Strings.CS.startsWith(filePath, PlanFiles.getPlansDirectory().toString());
    }

    static String relativeToCwd(String filePath) {
        if (StringUtils.isBlank(filePath)) return "";
        try {
            Path cwd = Path.of(System.getProperty("user.dir", "."))
                .toAbsolutePath().normalize();
            Path path = Path.of(filePath).toAbsolutePath().normalize();
            return cwd.relativize(path).toString();
        } catch (RuntimeException _) {
            return filePath;
        }
    }

    static String diffLanguageForPath(String path) {
        if (StringUtils.isBlank(path)) return null;
        String name = Path.of(path).getFileName().toString().toLowerCase(Locale.ROOT);
        if (Strings.CS.equals(name, "dockerfile")) return "dockerfile";
        if (Strings.CS.equals(name, "makefile")) return "makefile";
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return null;
        return switch (name.substring(dot + 1)) {
            case "mjs", "cjs" -> "javascript";
            case "mts", "cts" -> "typescript";
            case "yml" -> "yaml";
            case "sh", "bash", "zsh" -> "shell";
            default -> name.substring(dot + 1);
        };
    }

    static int countVisibleLines(String content) {
        if (content == null) return 0;
        if (content.isEmpty()) return 1;
        int lines = 1;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\n') lines++;
        }
        return Strings.CS.endsWith( content, "\n") ? lines - 1 : lines;
    }
}
