package com.claudecode.ui;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.constants.AnsiColor;
import com.claudecode.core.constants.AnsiStyle;
import com.claudecode.core.constants.Figures;
import com.claudecode.core.text.FormatUtils;
import com.claudecode.ui.lanterna.features.settings.UiSettings;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.claudecode.ui.lanterna.theme.RgbColor;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.ibm.icu.text.BreakIterator;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableBody;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableHead;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.CustomBlock;
import org.commonmark.node.Emphasis;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.ListBlock;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.SourceSpan;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.Text;
import org.commonmark.node.ThematicBreak;
import org.commonmark.parser.Parser;
import org.commonmark.parser.IncludeSourceSpans;

/**
 * Renders Markdown text to ANSI-styled terminal output using commonmark-java.
 */
public class MarkdownRenderer {

    private static final long DEFAULT_CACHE_WEIGHT_BYTES = 16L * 1024 * 1024;
    private static final int MAX_CACHEABLE_RESULT_BYTES = 256 * 1024;
    private static final int CACHE_ENTRY_OVERHEAD_BYTES = 64;

    /**
     * Process-wide renderer matching the compatibility module-level lexer cache. The
     * parser is thread-safe and Caffeine supports concurrent access, so
     * callers that do not need an isolated test cache should reuse this
     * instance instead of creating another independently-budgeted cache.
     */
    public static MarkdownRenderer shared() {
        return SharedHolder.INSTANCE;
    }

    private static final class SharedHolder {
        private static final MarkdownRenderer INSTANCE = new MarkdownRenderer();
    }

    private final Parser parser;

    // Weighted by retained ANSI value size; input text itself is not retained.
    private final Cache<Long, String> renderCache;

    // Task 68.5: Plain text detection pattern (no markdown syntax)
    private static final Pattern MARKDOWN_SYNTAX_PATTERN = Pattern.compile(
        "[#*`|\\[>\\-_~]|\\n\\n|^\\d+\\. |\\n\\d+\\. ");
    private static final Pattern BARE_URL_PATTERN = Pattern.compile(
        "(?:https?://|www\\.)[^\\s\\\"'<>\\\\…\\x00-\\x1f]+");
    private static final Pattern ISSUE_REF_PATTERN = Pattern.compile(
        "(^|[^\\w./-])([A-Za-z0-9][\\w-]*/[A-Za-z0-9][\\w.-]*)#(\\d+)\\b");
    private static final Pattern FOREGROUND_SGR_PATTERN = Pattern.compile(
        "\\u001B\\[(?:3[0-7]|9[0-7]|38;5;\\d{1,3}|38;2;\\d{1,3};\\d{1,3};\\d{1,3})m");


    private static final Pattern INTERNAL_PROMPT_TAG_PATTERN = Pattern.compile(
        "<(commit_analysis|context|function_analysis|pr_analysis)>.*?</\\1>\\n?",
        Pattern.DOTALL);
    private static final Pattern TASK_LIST_MARKER = Pattern.compile("^\\[[ xX]]\\s+");

    public MarkdownRenderer() {
        this.renderCache = weightedCache(DEFAULT_CACHE_WEIGHT_BYTES);
        this.parser = createParser();
    }

    /** Legacy explicit entry-count constructor retained for callers/tests. */
    public MarkdownRenderer(int cacheSize) {
        this.parser = createParser();
        this.renderCache = Caffeine.newBuilder()
            .maximumSize(cacheSize)
            .build();
    }

    private static Parser createParser() {
        List<Extension> extensions = List.of(TablesExtension.create());
        return Parser.builder().extensions(extensions)
            .includeSourceSpans(IncludeSourceSpans.BLOCKS)
            .build();
    }

    @Explanation("bounded ANSI render-cache heap budget")
    private static Cache<Long, String> weightedCache(long maximumWeightBytes) {
        return Caffeine.newBuilder()
            .maximumWeight(maximumWeightBytes)
            .weigher((Long _, String value) -> estimatedUtf16Bytes(value))
            .build();
    }

    /**
     * Render markdown text to an ANSI-styled string for terminal display.
     * Uses caching for repeated content and plain text fast path.
     */
    public String render(String markdown) {
        return render(markdown, 80);
    }

    /** Render using the available terminal columns for responsive tables. */
    public String render(String markdown, int terminalWidth) {
        return render(markdown, terminalWidth, false);
    }


    public String renderDimmed(String markdown, int terminalWidth) {
        return render(markdown, terminalWidth, true);
    }

    private String render(String markdown, int terminalWidth, boolean dimNonTableContent) {
        if (StringUtils.isEmpty(markdown)) {
            return "";
        }

        // Task 68.6: Strip XML tags (prompt injection artifacts)
        String cleaned = stripPromptXmlTags(markdown);

        // Task 68.5: Plain text fast path
        String syntaxSample = cleaned.length() > 500 ? cleaned.substring(0, 500) : cleaned;
        if (!MARKDOWN_SYNTAX_PATTERN.matcher(syntaxSample).find()) {

            return trimMarkdownWhitespace(cleaned);
        }





        int safeWidth = Math.max(1, terminalWidth);
        long hash = computeHash(cleaned + '\u0000' + safeWidth + '\u0000'
            + LanternaTheme.activeThemeName() + '\u0000'
            + UiSettings.readSyntaxHighlightingDisabled() + '\u0000' + dimNonTableContent);
        String cached = renderCache.getIfPresent(hash);
        if (cached != null) {
            return cached;
        }


        String result = trimMarkdownWhitespace(
            renderMarkdown(cleaned, safeWidth, dimNonTableContent));
        if (estimatedUtf16Bytes(result) <= MAX_CACHEABLE_RESULT_BYTES) {
            renderCache.put(hash, result);
        }
        return result;
    }

    private static int estimatedUtf16Bytes(String value) {
        if (value == null) return 0;
        long bytes = CACHE_ENTRY_OVERHEAD_BYTES
            + (long) value.length() * Character.BYTES;
        return (int) Math.min(Integer.MAX_VALUE, bytes);
    }

    /**
     * Returns the start offset of the final top-level Markdown block.
     */
    public int stablePrefixLength(String markdown) {
        if (StringUtils.isEmpty(markdown)) return 0;
        Node document = parser.parse(markdown);
        Node last = document.getLastChild();
        if (last == null || last == document.getFirstChild() || last.getSourceSpans().isEmpty()) {
            return 0;
        }
        // A streaming delta can split a GFM data row immediately after its
        // opening pipe. CommonMark then temporarily parses the completed
        // header+delimiter as a TableBlock and the partial row as a paragraph.
        // Advancing to that paragraph would freeze a header-only table, so
        // keep the preceding table unstable until the tail line is complete.
        if (!Strings.CS.endsWith(markdown, "\n")
                && last.getPrevious() instanceof TableBlock) {
            var tableStart = last.getPrevious().getSourceSpans();
            if (!tableStart.isEmpty()) {
                return sourceOffset(markdown, tableStart.getFirst());
            }
        }
        var start = last.getSourceSpans().getFirst();
        return sourceOffset(markdown, start);
    }

    private static int sourceOffset(String markdown,
                                    SourceSpan start) {
        int line = 0;
        int offset = 0;
        while (line < start.getLineIndex() && offset < markdown.length()) {
            int newline = markdown.indexOf('\n', offset);
            if (newline < 0) return 0;
            offset = newline + 1;
            line++;
        }
        return Math.min(markdown.length(), offset + start.getColumnIndex());
    }

    /** Strip the same four prompt-only wrappers before render or streaming boundary tracking. */
    public String stripPromptXmlTags(String markdown) {
        if (StringUtils.isEmpty(markdown)) return "";
        return INTERNAL_PROMPT_TAG_PATTERN.matcher(markdown).replaceAll("");
    }

    private static String trimMarkdownWhitespace(String value) {
        if (StringUtils.isEmpty(value)) return "";
        int start = 0;
        int end = value.length();
        while (start < end && isMarkdownTrimWhitespace(value.charAt(start))) start++;
        while (end > start && isMarkdownTrimWhitespace(value.charAt(end - 1))) end--;
        return value.substring(start, end);
    }

    private static boolean isMarkdownTrimWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f'
            || c == '\u000B' || c == '\u00A0' || c == '\uFEFF'
            || Character.getType(c) == Character.SPACE_SEPARATOR;
    }

    /**
     * Compute a fast hash for cache key.
     */
    private long computeHash(String text) {
        CRC32 crc = new CRC32();
        crc.update(text.getBytes(StandardCharsets.UTF_8));
        return crc.getValue();
    }

    /**
     * Core markdown rendering logic.
     */
    private String renderMarkdown(String markdown, int terminalWidth, boolean dimNonTableContent) {
        Node document = parser.parse(markdown);
        StringBuilder sb = new StringBuilder();
        TerminalMarkdownVisitor visitor = new TerminalMarkdownVisitor(sb, terminalWidth);
        StringBuilder nonTable = new StringBuilder();
        for (Node node = document.getFirstChild(); node != null; node = node.getNext()) {
            if (node instanceof TableBlock) {
                flushTopLevelGroup(sb, nonTable, dimNonTableContent);
                if (!sb.isEmpty()) appendTopLevelGap(sb, node);
                node.accept(visitor);
                visitor.resetTopLevelBlockState();
                appendTopLevelGap(sb, node.getNext());
            } else {
                int before = sb.length();
                node.accept(visitor);
                nonTable.append(sb, before, sb.length());
                sb.setLength(before);
            }
        }
        flushTopLevelGroup(sb, nonTable, dimNonTableContent);
        return sb.toString();
    }

    private static void flushTopLevelGroup(StringBuilder output, StringBuilder group,
                                            boolean dim) {
        String content = trimMarkdownWhitespace(group.toString());
        group.setLength(0);
        if (content.isEmpty()) return;
        if (!output.isEmpty() && output.charAt(output.length() - 1) != '\n') output.append('\n');
        output.append(dim ? nestedDim(content) : content);
    }

    private static void appendTopLevelGap(StringBuilder output, Node next) {
        if (next == null) return;
        while (!output.isEmpty() && output.charAt(output.length() - 1) == '\n') {
            output.setLength(output.length() - 1);
        }
        output.append("\n\n");
    }

    private static String nestedDim(String content) {
        if (!Ansi.isColorSupported()) return content;
        String opener = AnsiStyle.DIM.on();




        String withoutForegroundSwitches = FOREGROUND_SGR_PATTERN.matcher(content).replaceAll("");
        return opener + withoutForegroundSwitches
            // Reopen DIM before the reset's following content is parsed.
            .replace("\u001B[22m", "\u001B[22m" + opener)
            .replace("\u001B[0m", "\u001B[0m" + opener)
            + "\u001B[22m";
    }

    /** Package-independent regression hook for the ANSI-aware table wrapper. */
    static List<String> wrapAnsiForTest(String ansi, int width, boolean hardWrap) {
        return TerminalMarkdownVisitor.wrapText(ansi, width, hardWrap);
    }

    /**
     * Visitor that converts commonmark AST nodes to ANSI-styled text.
     */
    static class TerminalMarkdownVisitor extends AbstractVisitor {

        private final StringBuilder sb;
        private int listDepth = 0;
        private final int terminalWidth;
        private final Deque<ListState> lists = new ArrayDeque<>();
        private boolean thematicBreakJustRendered;

        private static final class ListState {
            private final boolean ordered;
            private int nextNumber;

            private ListState(boolean ordered, int nextNumber) {
                this.ordered = ordered;
                this.nextNumber = nextNumber;
            }
        }

        TerminalMarkdownVisitor(StringBuilder sb, int terminalWidth) {
            this.sb = sb;
            this.terminalWidth = terminalWidth;
        }

        private void resetTopLevelBlockState() {
            thematicBreakJustRendered = false;
        }

        @Override
        public void visit(Heading heading) {
            String inner = renderChildren(heading);

            if (heading.getLevel() == 1) {
                sb.append(nestedStyled(inner,
                    AnsiStyle.BOLD, AnsiStyle.ITALIC, AnsiStyle.UNDERLINE));
            } else {
                sb.append(nestedStyled(inner, AnsiStyle.BOLD));
            }
            sb.append("\n\n");
        }

        @Override
        public void visit(Paragraph paragraph) {
            if (listDepth > 0) {
                boolean looseListParagraph = paragraph.getParent() instanceof ListItem item
                    && item.getParent() instanceof ListBlock list && !list.isTight();
                if (!looseListParagraph && paragraph.getParent() instanceof ListItem) {
                    sb.append(listMarker());
                }
                visitChildren(paragraph);
                if (paragraph.getNext() != null) {
                    if (paragraph.getNext() instanceof BlockQuote) {
                        sb.append('\n');
                    } else if (paragraph.getNext() instanceof ListBlock) {
                        sb.append('\n');
                    } else {
                        sb.append("\n\n");
                    }
                } else if (looseListParagraph) {
                    sb.append('\n');
                }
            } else {
                boolean followsThematicBreak = thematicBreakJustRendered;
                if (followsThematicBreak) sb.append('\n');
                visitChildren(paragraph);
                sb.append("\n\n");
                thematicBreakJustRendered = false;
            }
        }

        @Override
        public void visit(Text text) {


            // (parent?.type === 'link') to avoid nested OSC 8 hyperlinks; we
            // replicate that with the text node's parent pointer.
            String literal = text.getLiteral();
            if (text.getParent() instanceof Paragraph paragraph
                    && paragraph.getParent() instanceof ListItem
                    && text == paragraph.getFirstChild()) {
                literal = TASK_LIST_MARKER.matcher(literal).replaceFirst("");
            }
            if (text.getParent() instanceof Link) {
                sb.append(literal);
                return;
            }
            sb.append(linkifyPlainText(literal));
        }

        private String linkifyPlainText(String text) {
            if (StringUtils.isEmpty(text)) return text;
            Matcher issue = ISSUE_REF_PATTERN.matcher(text);
            Matcher url = BARE_URL_PATTERN.matcher(text);
            boolean hasIssue = Ansi.supportsHyperlinks() && issue.find();
            boolean hasUrl = url.find();
            if (!hasIssue && !hasUrl) return text;

            StringBuilder result = new StringBuilder(text.length() + 32);
            int offset = 0;
            while (hasIssue || hasUrl) {
                int issueStart = hasIssue ? issue.start(2) : Integer.MAX_VALUE;
                int urlStart = hasUrl ? url.start() : Integer.MAX_VALUE;
                if (issueStart <= urlStart) {
                    result.append(text, offset, issueStart);
                    String repo = issue.group(2);
                    String number = issue.group(3);
                    result.append(hyperlink("https://github.com/" + repo + "/issues/" + number,
                        repo + "#" + number));
                    offset = issue.end();
                    hasIssue = issue.find();
                    while (hasUrl && url.start() < offset) hasUrl = url.find();
                } else {
                    String matched = url.group();
                    int linkedLength = bareUrlLinkedLength(matched);
                    if (linkedLength == 0) {
                        hasUrl = url.find();
                        continue;
                    }
                    String displayUrl = matched.substring(0, linkedLength);
                    String linkedUrl = Strings.CS.startsWith(displayUrl, "www.")
                        ? "http://" + displayUrl : displayUrl;
                    result.append(text, offset, urlStart).append(hyperlink(linkedUrl, displayUrl));
                    offset = urlStart + linkedLength;
                    hasUrl = url.find();
                    while (hasIssue && issue.start(2) < offset) hasIssue = issue.find();
                }
            }
            return result.append(text, offset, text.length()).toString();
        }

        private static int bareUrlLinkedLength(String value) {
            int end = value.length();
            while (end > 0 && ".,!?;:".indexOf(value.charAt(end - 1)) >= 0) end--;
            while (end > 0 && value.charAt(end - 1) == ')'
                    && count(value, '(', end) < count(value, ')', end)) {
                end--;
            }
            while (end > 0 && value.charAt(end - 1) == ']'
                    && count(value, '[', end) < count(value, ']', end)) {
                end--;
            }
            return end;
        }

        private static int count(String value, char needle, int end) {
            int count = 0;
            for (int i = 0; i < end; i++) if (value.charAt(i) == needle) count++;
            return count;
        }

        @Override
        public void visit(Emphasis emphasis) {

            String saved = sb.toString();
            sb.setLength(0);
            visitChildren(emphasis);
            String inner = sb.toString();
            sb.setLength(0);
            sb.append(saved);
            sb.append(nestedStyled(inner, AnsiStyle.ITALIC));
        }

        @Override
        public void visit(StrongEmphasis strongEmphasis) {

            String saved = sb.toString();
            sb.setLength(0);
            visitChildren(strongEmphasis);
            String inner = sb.toString();
            sb.setLength(0);
            sb.append(saved);
            sb.append(nestedStyled(inner, AnsiStyle.BOLD));
        }

        private static String nestedStyled(String inner, AnsiStyle... styles) {
            if (!Ansi.isColorSupported() || styles.length == 0) return inner;
            StringBuilder opener = new StringBuilder();
            for (AnsiStyle style : styles) opener.append(style.on());
            String reset = "\u001B[0m";
            return opener + inner.replace(reset, reset + opener) + reset;
        }

        @Override
        public void visit(Code code) {

            // Use the active theme's `permission` RGB instead of bare ANSI BLUE
            // (\x1b[34m), which would let the terminal pick its own deep navy

            RgbColor c = LanternaTheme.activeTheme().permission();
            sb.append(Ansi.coloredRgb(code.getLiteral(), c.r(), c.g(), c.b()));
        }

        @Override
        public void visit(FencedCodeBlock fencedCodeBlock) {
            String info = fencedCodeBlock.getInfo();
            String lang = (StringUtils.isNotEmpty(info)) ? info.split("\\s+")[0] : null;
            String literal = normalizeCodeLiteral(fencedCodeBlock.getLiteral());
            String language = StringUtils.isBlank(lang) ? "plaintext" : lang;
            sb.append(SyntaxHighlighter.highlight(literal, language));
            sb.append("\n\n");
        }

        /**
         * Recover a language hint from the first line / filename markers of an un-tagged code block.
         */
        static String detectLanguageFromContent(String code) {
            if (StringUtils.isEmpty(code)) return null;
            int eol = code.indexOf('\n');
            String firstLine = (eol < 0 ? code : code.substring(0, eol)).strip();
            if (firstLine.isEmpty()) return null;

            if (firstLine.charAt(0) == '﻿') firstLine = firstLine.substring(1);

            if (Strings.CS.startsWith(firstLine, "#!")) {
                if (Strings.CS.contains(firstLine, "bash") || Strings.CS.contains(firstLine, "/sh") || Strings.CS.endsWith(firstLine, " sh")) return "bash";
                if (Strings.CS.contains(firstLine, "zsh")) return "bash";
                if (Strings.CS.contains(firstLine, "python")) return "python";
                if (Strings.CS.contains(firstLine, "node")) return "javascript";
                if (Strings.CS.contains(firstLine, "ruby")) return "ruby";
                if (Strings.CS.contains(firstLine, "perl")) return "perl";
                if (Strings.CS.contains(firstLine, "pwsh") || Strings.CS.contains(firstLine, "powershell")) return "powershell";
                if (Strings.CS.contains(firstLine, "lua")) return "lua";
                if (Strings.CS.contains(firstLine, "awk")) return "awk";
            }
            if (Strings.CS.startsWith(firstLine, "<?php")) return "php";
            if (Strings.CS.startsWith(firstLine, "<?xml")) return "xml";

            // Filename markers (Dockerfile / Makefile / Rakefile / Gemfile /
            // CMakeLists are commonly seen as the first line of a code block,
            // e.g. when claude pastes a self-contained "edit this Dockerfile"
            // snippet starting with the filename as a marker).
            String token = firstLine.split("[:\\s]", 2)[0];
            return switch (token) {
                case "Dockerfile"            -> "dockerfile";
                case "Makefile", "GNUmakefile" -> "makefile";
                case "Rakefile", "Gemfile"   -> "ruby";
                case "CMakeLists.txt", "CMakeLists" -> "cmake";
                default                       -> null;
            };
        }

        @Override
        public void visit(IndentedCodeBlock indentedCodeBlock) {
            String literal = normalizeCodeLiteral(indentedCodeBlock.getLiteral());
            sb.append(SyntaxHighlighter.highlight(literal, "plaintext"));
            sb.append("\n\n");
        }

        private static String normalizeCodeLiteral(String literal) {
            if (StringUtils.isEmpty(literal)) return "";
            return Strings.CS.endsWith(literal, "\n")
                ? literal.substring(0, literal.length() - 1) : literal;
        }

        @Override
        public void visit(BulletList bulletList) {
            listDepth++;
            lists.push(new ListState(false, 0));
            visitChildren(bulletList);
            lists.pop();
            listDepth--;
            if (listDepth == 0) {
                sb.append("\n");
            }
        }

        @Override
        public void visit(OrderedList orderedList) {
            listDepth++;
            lists.push(new ListState(true, orderedList.getMarkerStartNumber()));
            visitChildren(orderedList);
            lists.pop();
            listDepth--;
            if (listDepth == 0) {
                sb.append("\n");
            }
        }

        @Override
        public void visit(ListItem listItem) {
            visitChildren(listItem);
            if (sb.isEmpty() || sb.charAt(sb.length() - 1) != '\n') sb.append("\n");
        }

        private String listMarker() {
            ListState state = lists.peek();
            String marker;
            if (state != null && state.ordered) {
                marker = getListNumber(listDepth, state.nextNumber) + ". ";
                state.nextNumber++;
            } else {
                marker = "- ";
            }
            // Marked prefixes every nested list_item token with its current
            // depth indentation, so the visible indentation accumulates:
            // depth 1=0, depth 2=2, depth 3=6, depth 4=12 columns.
            int indentPairs = Math.max(0, listDepth * (listDepth - 1) / 2);
            return "  ".repeat(indentPairs) + marker;
        }


        private String getListNumber(int depth, int n) {
            return switch (depth) {
                case 0, 1 -> String.valueOf(n);
                case 2    -> numberToLetter(n);
                case 3    -> numberToRoman(n);
                default   -> String.valueOf(n);
            };
        }


        private String numberToLetter(int n) {
            StringBuilder result = new StringBuilder();
            while (n > 0) {
                n--;
                result.insert(0, (char) ('a' + (n % 26)));
                n /= 26;
            }
            return result.toString();
        }


        private String numberToRoman(int n) {
            int[] values  = {1000,900,500,400,100,90,50,40,10,9,5,4,1};
            String[] syms = {"m","cm","d","cd","c","xc","l","xl","x","ix","v","iv","i"};
            StringBuilder result = new StringBuilder();
            for (int i = 0; i < values.length; i++) {
                while (n >= values[i]) { result.append(syms[i]); n -= values[i]; }
            }
            return result.toString();
        }

        @Override
        public void visit(Link link) {
            String dest = link.getDestination();

            if (dest != null && Strings.CS.startsWith(dest, "mailto:")) {
                sb.append(dest.substring("mailto:".length()));
                return;
            }

            // linkification for text whose immediate parent is a link (replicated
            // via the text node's parent pointer in visit(Text)) to avoid nested
            // OSC 8 hyperlinks.
            String saved = sb.toString();
            sb.setLength(0);
            visitChildren(link);
            String linkText = sb.toString();
            sb.setLength(0);
            sb.append(saved);


            String plain = linkText.replaceAll("\u001B\\[[\\d;]*m", "");
            String display = (plain.isEmpty() || plain.equals(dest)) ? (dest != null ? dest : "") : linkText;
            sb.append(hyperlink(dest, display));
        }


        private String hyperlink(String url, String display) {
            if (!Ansi.supportsHyperlinks()) {
                return url != null ? url : "";
            }
            return "\u001B]8;;" + (url != null ? url : "") + "\u0007"
                + Ansi.colored(display, AnsiColor.BLUE)
                + "\u001B]8;;\u0007";
        }

        @Override
        public void visit(BlockQuote blockQuote) {


            // inner.split(EOL).map(...).join(EOL).
            String saved = sb.toString();
            sb.setLength(0);
            int savedListDepth = listDepth;
            Deque<ListState> savedLists = new ArrayDeque<>(lists);
            listDepth = 0;
            lists.clear();
            visitChildren(blockQuote);
            String inner = sb.toString();
            listDepth = savedListDepth;
            lists.clear();
            lists.addAll(savedLists);
            sb.setLength(0);
            sb.append(saved);
            String[] lines = inner.split("\n", -1);
            List<String> mapped = new ArrayList<>();
            for (String line : lines) {
                String stripped = line.replaceAll("\u001B\\[[\\d;]*m", "").trim();
                if (!stripped.isEmpty()) {
                    mapped.add(Ansi.styled(Figures.BLOCKQUOTE_BAR, AnsiStyle.DIM)
                        + " " + nestedStyled(line, AnsiStyle.ITALIC));
                } else {
                    mapped.add(line);
                }
            }
            sb.append(String.join("\n", mapped));
        }

        @Override
        public void visit(ThematicBreak thematicBreak) {

            sb.append("---");
            thematicBreakJustRendered = true;
        }

        @Override
        public void visit(SoftLineBreak softLineBreak) {

            sb.append("\n");
        }

        @Override
        public void visit(HardLineBreak hardLineBreak) {

            while (!sb.isEmpty() && sb.charAt(sb.length() - 1) == ' ') {
                sb.setLength(sb.length() - 1);
            }
            sb.append("\n");
        }

        @Override
        public void visit(Image image) {

            sb.append(image.getDestination());
        }

        @Override
        public void visit(HtmlInline htmlInline) {

        }

        @Override
        public void visit(HtmlBlock htmlBlock) {

        }

        @Override
        public void visit(CustomBlock customBlock) {
            // Task 68.3: Handle GFM table blocks
            if (customBlock instanceof TableBlock tableBlock) {
                visit(tableBlock);
                return;
            }
            visitChildren(customBlock);
        }

// Responsive table layout matching Claude Code.
        public void visit(TableBlock tableBlock) {
            List<List<Cell>> rows = new ArrayList<>();
            Node tableChild = tableBlock.getFirstChild();
            while (tableChild != null) {
                if (tableChild instanceof TableHead || tableChild instanceof TableBody) {
                    Node row = tableChild.getFirstChild();
                    while (row != null) {
                        if (row instanceof TableRow) {
                            List<Cell> rowData = new ArrayList<>();
                            Node cell = row.getFirstChild();
                            while (cell != null) {
                                if (cell instanceof TableCell tableCell) {
                                    // Collect text from cell (may be wrapped in Paragraph)
                                    String content = trimMarkdownWhitespace(renderChildren(tableCell));
                                    rowData.add(new Cell(content, tableCell.getAlignment()));
                                }
                                cell = cell.getNext();
                            }
                            if (!rowData.isEmpty()) rows.add(rowData);
                        }
                        row = row.getNext();
                    }
                }
                tableChild = tableChild.getNext();
            }

            if (rows.isEmpty()) return;

            int maxCols = rows.stream().mapToInt(List::size).max().orElse(0);
            int[] idealWidths = new int[maxCols];
            int[] minWidths = new int[maxCols];
            Arrays.fill(idealWidths, 3);
            Arrays.fill(minWidths, 3);
            for (List<Cell> r : rows) {
                for (int i = 0; i < Math.min(r.size(), maxCols); i++) {
                    String plain = stripAnsi(r.get(i).content());
                    idealWidths[i] = Math.max(idealWidths[i], visualWidth(plain));
                    minWidths[i] = Math.max(minWidths[i], longestWordWidth(plain));
                }
            }
            int available = Math.max(maxCols * 3, terminalWidth - (1 + maxCols * 3) - 4);
            int[] colWidths = computeColumnWidths(idealWidths, minWidths, available);
            boolean hardWrap = sum(minWidths) > available;
            List<List<List<String>>> wrappedRows = wrapRows(rows, colWidths, hardWrap);
            int maxRowLines = wrappedRows.stream()
                .mapToInt(r -> r.stream().mapToInt(List::size).max().orElse(1)).max().orElse(1);
            int renderedWidth = 1 + maxCols * 3 + sum(colWidths);
            if (maxRowLines > 4 || renderedWidth > terminalWidth - 4) {
                renderVerticalTable(rows);
                return;
            }

            sb.append(border(colWidths, '┌', '┬', '┐')).append('\n');
            for (int r = 0; r < rows.size(); r++) {
                appendWrappedRow(rows.get(r), wrappedRows.get(r), colWidths, r == 0);
                if (r < rows.size() - 1) {
                    sb.append(border(colWidths, '├', '┼', '┤')).append('\n');
                }
            }
            sb.append(border(colWidths, '└', '┴', '┘')).append("\n\n");
        }

        private record Cell(String content, TableCell.Alignment alignment) {}

        private String renderChildren(Node node) {
            int start = sb.length();
            visitChildren(node);
            String rendered = sb.substring(start);
            sb.setLength(start);
            return rendered;
        }

        private static int longestWordWidth(String text) {
            int width = 3;
            for (String word : text.split("\\s+")) width = Math.max(width, visualWidth(word));
            return width;
        }

        private static int sum(int[] values) {
            int sum = 0;
            for (int value : values) sum += value;
            return sum;
        }

        private static int[] computeColumnWidths(int[] ideal, int[] min, int available) {
            if (sum(ideal) <= available) return ideal.clone();
            int[] widths = min.clone();
            if (sum(min) <= available) {
                int extra = available - sum(min);
                int overflow = 0;
                for (int i = 0; i < ideal.length; i++) overflow += ideal[i] - min[i];
                for (int i = 0; i < widths.length; i++) {
                    widths[i] += overflow == 0 ? 0
                        : (int) Math.floor((double) (ideal[i] - min[i]) / overflow * extra);
                }
                return widths;
            }
            double scale = (double) available / Math.max(1, sum(min));
            for (int i = 0; i < widths.length; i++) widths[i] = Math.max(3, (int) Math.floor(min[i] * scale));
            return widths;
        }

        private static List<List<List<String>>> wrapRows(List<List<Cell>> rows, int[] widths,
                                                          boolean hardWrap) {
            List<List<List<String>>> result = new ArrayList<>();
            for (List<Cell> row : rows) {
                List<List<String>> cells = new ArrayList<>();
                for (int c = 0; c < widths.length; c++) {
                    String text = c < row.size() ? row.get(c).content() : "";
                    cells.add(wrapText(text, widths[c], hardWrap));
                }
                result.add(cells);
            }
            return result;
        }

        private static List<String> wrapText(String ansi, int width, boolean hardWrap) {
            if (width <= 0) return List.of(ansi);
            List<AnsiRun> runs = parseAnsiRuns(trimMarkdownWhitespace(ansi));
            String plain = trimMarkdownWhitespace(
                runs.stream().map(AnsiRun::text).reduce("", String::concat));
            if (plain.isEmpty()) return List.of("");
            if (visualWidth(plain) <= width) return List.of(trimMarkdownWhitespace(ansi));

            List<AnsiGlyph> glyphs = new ArrayList<>();
            for (AnsiRun run : runs) {
                for (String grapheme : graphemes(run.text())) {
                    glyphs.add(new AnsiGlyph(grapheme, run.state(), visualWidth(grapheme),
                        grapheme.codePoints().allMatch(Character::isWhitespace)));
                }
            }

            List<String> wrapped = new ArrayList<>();
            int start = 0;
            while (start < glyphs.size()) {
                while (start < glyphs.size() && glyphs.get(start).whitespace()) start++;
                if (start >= glyphs.size()) break;
                int end = start;
                int columns = 0;
                int lastWhitespace = -1;
                while (end < glyphs.size()) {
                    AnsiGlyph glyph = glyphs.get(end);
                    if (columns > 0 && columns + glyph.width() > width) break;
                    columns += glyph.width();
                    if (glyph.whitespace()) lastWhitespace = end;
                    end++;
                }
                int lineEnd = end;
                int nextStart = end;
                if (!hardWrap && end < glyphs.size() && lastWhitespace >= start) {
                    lineEnd = lastWhitespace;
                    nextStart = lastWhitespace + 1;
                }
                while (lineEnd > start && glyphs.get(lineEnd - 1).whitespace()) lineEnd--;
                wrapped.add(encodeAnsiGlyphs(glyphs.subList(start, lineEnd)));
                start = Math.max(nextStart, start + 1);
            }
            return wrapped;
        }

        private record AnsiState(String color, boolean bold, boolean dim, boolean italic,
                                 boolean underline, boolean strike, String hyperlink) {
            private static final AnsiState EMPTY =
                new AnsiState("", false, false, false, false, false, null);

            String sgr() {
                StringBuilder value = new StringBuilder(color);
                if (bold) value.append("\u001B[1m");
                if (dim) value.append("\u001B[2m");
                if (italic) value.append("\u001B[3m");
                if (underline) value.append("\u001B[4m");
                if (strike) value.append("\u001B[9m");
                return value.toString();
            }

            AnsiState withHyperlink(String value) {
                return new AnsiState(color, bold, dim, italic, underline, strike, value);
            }
        }

        private record AnsiRun(String text, AnsiState state) {}

        private record AnsiGlyph(String text, AnsiState state, int width, boolean whitespace) {}

        private static List<AnsiRun> parseAnsiRuns(String ansi) {
            List<AnsiRun> runs = new ArrayList<>();
            StringBuilder text = new StringBuilder();
            AnsiState state = AnsiState.EMPTY;
            for (int i = 0; i < ansi.length();) {
                if (ansi.charAt(i) != '\u001B') {
                    text.append(ansi.charAt(i++));
                    continue;
                }
                flushAnsiRun(runs, text, state);
                if (i + 1 < ansi.length() && ansi.charAt(i + 1) == '[') {
                    int end = ansi.indexOf('m', i + 2);
                    if (end < 0) break;
                    state = applySgr(state, ansi.substring(i + 2, end));
                    i = end + 1;
                } else if (ansi.startsWith("\u001B]8;;", i)) {
                    int end = ansi.indexOf('\u0007', i + 5);
                    if (end < 0) break;
                    String url = ansi.substring(i + 5, end);
                    state = state.withHyperlink(url.isEmpty() ? null : url);
                    i = end + 1;
                } else {
                    i++;
                }
            }
            flushAnsiRun(runs, text, state);
            return runs;
        }

        private static AnsiState applySgr(AnsiState state, String codes) {
            String[] parts = codes.isEmpty() ? new String[] {"0"} : codes.split(";");
            String color = state.color();
            boolean bold = state.bold();
            boolean dim = state.dim();
            boolean italic = state.italic();
            boolean underline = state.underline();
            boolean strike = state.strike();
            for (int i = 0; i < parts.length; i++) {
                int code;
                try { code = Integer.parseInt(parts[i]); } catch (NumberFormatException _) { continue; }
                switch (code) {
                    case 0 -> { color = ""; bold = dim = italic = underline = strike = false; }
                    case 1 -> bold = true;
                    case 2 -> dim = true;
                    case 3 -> italic = true;
                    case 4 -> underline = true;
                    case 9 -> strike = true;
                    case 22 -> { bold = false; dim = false; }
                    case 23 -> italic = false;
                    case 24 -> underline = false;
                    case 29 -> strike = false;
                    case 30, 31, 32, 33, 34, 35, 36, 37,
                         90, 91, 92, 93, 94, 95, 96, 97 -> color = "\u001B[" + code + "m";
                    case 39 -> color = "";
                    case 38 -> {
                        if (i + 2 < parts.length && Strings.CS.equals("5", parts[i + 1])) {
                            color = "\u001B[38;5;" + parts[i + 2] + "m";
                            i += 2;
                        } else if (i + 4 < parts.length
                                && Strings.CS.equals("2", parts[i + 1])) {
                            color = "\u001B[38;2;" + parts[i + 2] + ";" + parts[i + 3]
                                + ";" + parts[i + 4] + "m";
                            i += 4;
                        }
                    }
                    default -> { }
                }
            }
            return new AnsiState(color, bold, dim, italic, underline, strike, state.hyperlink());
        }

        private static void flushAnsiRun(List<AnsiRun> runs, StringBuilder text, AnsiState state) {
            if (text.isEmpty()) return;
            runs.add(new AnsiRun(text.toString(), state));
            text.setLength(0);
        }

        private static String encodeAnsiGlyphs(List<AnsiGlyph> glyphs) {
            StringBuilder out = new StringBuilder();
            AnsiState active = null;
            for (AnsiGlyph glyph : glyphs) {
                AnsiState state = glyph.state();
                if (!Objects.equals(active, state)) {
                    closeAnsiState(out, active);
                    openAnsiState(out, state);
                    active = state;
                }
                out.append(glyph.text());
            }
            closeAnsiState(out, active);
            return out.toString();
        }

        private static void openAnsiState(StringBuilder out, AnsiState state) {
            if (state == null) return;
            out.append(state.sgr());
            if (state.hyperlink() != null) {
                out.append("\u001B]8;;").append(state.hyperlink()).append('\u0007');
            }
        }

        private static void closeAnsiState(StringBuilder out, AnsiState state) {
            if (state == null) return;
            if (state.hyperlink() != null) out.append("\u001B]8;;\u0007");
            if (!state.sgr().isEmpty()) out.append("\u001B[0m");
        }

        private static String stripAnsi(String value) {
            return value.replaceAll("\\u001B\\[[;\\d]*m", "")
                .replaceAll("\\u001B]8;;.*?\\u0007", "");
        }

        private static String border(int[] widths, char left, char middle, char right) {
            StringBuilder line = new StringBuilder().append(left);
            for (int i = 0; i < widths.length; i++) {
                line.repeat("─", widths[i] + 2);
                line.append(i == widths.length - 1 ? right : middle);
            }
            return line.toString();
        }

        private void appendWrappedRow(List<Cell> cells, List<List<String>> wrapped,
                                      int[] widths, boolean header) {
            int height = wrapped.stream().mapToInt(List::size).max().orElse(1);
            for (int line = 0; line < height; line++) {
                sb.append('│');
                for (int c = 0; c < widths.length; c++) {
                    List<String> cellLines = wrapped.get(c);
                    int offset = (height - cellLines.size()) / 2;
                    String text = line >= offset && line < offset + cellLines.size()
                        ? cellLines.get(line - offset) : "";
                    TableCell.Alignment alignment = header ? TableCell.Alignment.CENTER
                        : c < cells.size() ? cells.get(c).alignment() : null;
                    String padded = pad(text, widths[c], alignment);
                    sb.append(' ').append(padded).append(" │");
                }
                sb.append('\n');
            }
        }

        private static String pad(String text, int width, TableCell.Alignment alignment) {
            int missing = Math.max(0, width - visualWidth(stripAnsi(text)));
            if (alignment == TableCell.Alignment.RIGHT) return " ".repeat(missing) + text;
            if (alignment == TableCell.Alignment.CENTER) {
                int left = missing / 2;
                return " ".repeat(left) + text + " ".repeat(missing - left);
            }
            return text + " ".repeat(missing);
        }

        private void renderVerticalTable(List<List<Cell>> rows) {
            List<Cell> headers = rows.getFirst();
            for (int r = 1; r < rows.size(); r++) {
                if (r > 1) {
                    sb.repeat("─", Math.clamp(terminalWidth - 1, 1, 40)).append('\n');
                }
                for (int c = 0; c < headers.size(); c++) {
                    String label = stripAnsi(headers.get(c).content());
                    String value = c < rows.get(r).size() ? rows.get(r).get(c).content() : "";
                    value = normalizeVerticalCellWhitespace(value);
                    int firstWidth = Math.max(10, terminalWidth - visualWidth(label) - 3);
                    List<String> firstPass = wrapText(value, firstWidth, false);
                    String first = firstPass.getFirst();
                    sb.append(Ansi.styled(label + ":", AnsiStyle.BOLD)).append(' ').append(first)
                        .append('\n');
                    if (firstPass.size() > 1) {
                        String remaining = String.join(" ", firstPass.subList(1, firstPass.size()));
                        for (String continuation : wrapText(remaining,
                            Math.max(1, terminalWidth - 3), false)) {
                            if (!StringUtils.isBlank(continuation)) {
                                sb.append("  ").append(continuation).append('\n');
                            }
                        }
                    }
                }
            }
            sb.append('\n');
        }

        private static String normalizeVerticalCellWhitespace(String value) {
            if (StringUtils.isEmpty(value)) return "";
            return trimMarkdownWhitespace(trimMarkdownWhitespace(value)
                .replaceAll("\\n+", " ")
                .replaceAll("\\s+", " "));
        }

        /**
         * Visual width in terminal columns — CJK, Hangul, Kana, fullwidth
         * punctuation and emoji count as 2 columns; ASCII / Latin as 1. Table
         * column alignment collapses without this because Chinese text has
         * {@code String.length == 1} per char but paints 2 columns wide.
         *
         * <p>Ranges follow Unicode East Asian Width property W and F entries:
         * CJK Unified Ideographs, Hiragana / Katakana, Hangul Syllables,
         * CJK Symbols and Punctuation, Halfwidth-Fullwidth Forms. Combining
         * marks (U+0300…) collapse to zero — we treat them as 0 by skipping.
         */
        static int visualWidth(String s) {
            if (StringUtils.isEmpty(s)) return 0;
            return FormatUtils.displayWidth(s.replace("\n", "").replace("\r", ""));
        }

        /** Truncate {@code s} to at most {@code maxCols} visual columns. */
        static String truncateToVisualWidth(String s, int maxCols) {
            if (s == null || maxCols <= 0) return "";
            StringBuilder out = new StringBuilder(s.length());
            int width = 0;
            for (String grapheme : graphemes(s)) {
                if (Strings.CS.equals("\n", grapheme) || Strings.CS.equals("\r", grapheme)) {
                    continue;
                }
                int graphemeWidth = visualWidth(grapheme);
                if (width + graphemeWidth > maxCols) break;
                out.append(grapheme);
                width += graphemeWidth;
            }
            return out.toString();
        }

        private static List<String> graphemes(String value) {
            if (StringUtils.isEmpty(value)) return List.of();
            BreakIterator iterator =
                BreakIterator.getCharacterInstance(Locale.ROOT);
            iterator.setText(value);
            List<String> result = new ArrayList<>();
            int start = iterator.first();
            for (int end = iterator.next(); end != BreakIterator.DONE;
                    start = end, end = iterator.next()) {
                result.add(value.substring(start, end));
            }
            return result;
        }

    }
}
