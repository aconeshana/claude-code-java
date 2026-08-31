package com.claudecode.tools.bash;

import java.util.ArrayList;
import java.util.List;

/**
 * Implements npm {@code shell-quote} parsing semantics.
 * The referenced algorithm is MIT licensed: https://github.com/substack/node-shell-quote.
 */
public final class ShellQuoteParse {

    /** A parsed token: a word, a glob, a control operator, or a comment. */
    public sealed interface Token permits Word, Glob, Op, Comment {
        default String asString() {
            return null;
        }
    }

    /** A literal word (after quote processing). May contain glob chars if not a {@link Glob}. */
    public record Word(String value) implements Token {
        @Override
        public String asString() {
            return value;
        }
    }

    /** A word containing an unquoted glob char ({@code *} or {@code ?}). */
    public record Glob(String pattern) implements Token {
        @Override
        public String asString() {
            return pattern;
        }
    }

    /** A shell control operator. */
    public record Op(String op) implements Token {}

    /** A {@code #} comment (consumes rest of line); never precedes a word. */
    public record Comment(String text) implements Token {}

    // Ordered longest-first so multi-char operators win over their single-char prefix.
    private static final String[] CONTROL_LONG = {
        "||", "&&", ";;", "|&", "<(", "<<<", ">>", ">&", "<&"
    };
    // META chars that always terminate a word / act as a control operator.
    private static final String META = "|&;()<> \t\r\n";
    private static final char SQ = '\'';
    private static final char DQ = '"';
    private static final char BS = '\\';
    private static final char DS = '$';
    private static final String SPECIAL_VARS = "*@#?$!_-";

    private ShellQuoteParse() {}

    public static List<Token> parse(String command) {
        List<Token> result = new ArrayList<>();
        if (command == null) {
            return result;
        }
        boolean commented = false;
        int n = command.length();
        int i = 0;
        while (i < n && !commented) {
            char c = command.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                i++;
                continue;
            }
            String op = matchControl(command, i);
            if (op != null) {
                result.add(new Op(op));
                i += op.length();
                continue;
            }
            WordResult wr = scanWord(command, i);
            if (wr.comment != null) {
                commented = true;
                if (!wr.word.isEmpty()) {
                    result.add(new Word(wr.word));
                }
                result.add(new Comment(wr.comment));
                i = wr.next;
                continue;
            }
            if (wr.isGlob) {
                result.add(new Glob(wr.word));
            } else {
                result.add(new Word(wr.word));
            }
            i = wr.next;
        }
        return result;
    }

    private static String matchControl(String s, int i) {
        for (String alt : CONTROL_LONG) {
            if (s.startsWith(alt, i)) {
                return alt;
            }
        }
        char c = s.charAt(i);
        if (META.indexOf(c) >= 0) {
            return String.valueOf(c);
        }
        return null;
    }

    private static final class WordResult {
        final String word;
        final boolean isGlob;
        final String comment; // non-null ⇒ the rest of the line is a comment
        final int next;

        WordResult(String word, boolean isGlob, String comment, int next) {
            this.word = word;
            this.isGlob = isGlob;
            this.comment = comment;
            this.next = next;
        }
    }

    private static WordResult scanWord(String s, int start) {
        StringBuilder out = new StringBuilder();
        boolean isGlob = false;
        int n = s.length();
        int i = start;
        boolean inQuote = false;
        char quoteChar = 0;
        boolean esc = false;
        while (i < n) {
            char c = s.charAt(i);
            if (esc) {
                out.append(c);
                esc = false;
                i++;
                continue;
            }
            if (inQuote) {
                if (c == quoteChar) {
                    inQuote = false;
                    quoteChar = 0;
                } else if (quoteChar == SQ) {
                    out.append(c);
                } else { // double quote
                    if (c == BS) {
                        i++;
                        if (i < n) {
                            char d = s.charAt(i);
                            if (d == DQ || d == BS || d == DS) {
                                out.append(d);
                            } else {
                                out.append(BS);
                                out.append(d);
                            }
                        }
                    } else if (c == DS) {
                        i = envVar(s, out, i);
                        continue;
                    } else {
                        out.append(c);
                    }
                }
                i++;
                continue;
            }
            // Not in a quote, not escaping.
            if (c == SQ || c == DQ) {
                inQuote = true;
                quoteChar = c;
                i++;
                continue;
            }
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                break;
            }
            if (META.indexOf(c) >= 0) {
                break; // control operator ends the word (emitted as a separate Op)
            }
            if (c == '#') {
                return new WordResult(out.toString(), isGlob, s.substring(i + 1), i + 1);
            }
            if (c == BS) {
                esc = true;
                i++;
                continue;
            }
            if (c == DS) {
                i = envVar(s, out, i);
                continue;
            }
            if (c == '*' || c == '?') {
                isGlob = true;
            }
            out.append(c);
            i++;
        }
        return new WordResult(out.toString(), isGlob, null, i);
    }

    /**
     * Appends {@code $$VAR} (no expansion, matching the compatibility wrapper) for a {@code $}
     * at {@code dollarPos} and returns the index just past the variable name.
     */
    private static int envVar(String s, StringBuilder out, int dollarPos) {

        // wrapper env => '$'+key this yields a SINGLE '$' followed by the name
        // (the variable is intentionally NOT expanded). Append exactly one '$'.
        out.append('$');
        int i = dollarPos + 1;
        int n = s.length();
        if (i >= n) {
            return n;
        }
        char c = s.charAt(i);
        if (c == '{') {
            int end = s.indexOf('}', i + 1);
            if (end < 0) {
                return n;
            }
            out.append(s, i + 1, end);
            return end + 1;
        } else if (SPECIAL_VARS.indexOf(c) >= 0) {
            out.append(c);
            return i + 1;
        } else {
            int j = i;
            while (j < n && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_')) {
                j++;
            }
            out.append(s, i, j);
            return j;
        }
    }
}
