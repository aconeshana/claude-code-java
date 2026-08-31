package com.claudecode.core.prompt;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;





class ArgumentSubstitutorTest {

    // =========================================================================
    // parseArguments
    // =========================================================================

    @Test
    void parseArguments_nullReturnsEmpty() {
        assertEquals(Collections.emptyList(), ArgumentSubstitutor.parseArguments(null));
    }

    @Test
    void parseArguments_emptyStringReturnsEmpty() {
        assertEquals(Collections.emptyList(), ArgumentSubstitutor.parseArguments(""));
    }

    @Test
    void parseArguments_blankStringReturnsEmpty() {
        assertEquals(Collections.emptyList(), ArgumentSubstitutor.parseArguments("   "));
    }

    @Test
    void parseArguments_singleWord() {
        assertEquals(List.of("foo"), ArgumentSubstitutor.parseArguments("foo"));
    }

    @Test
    void parseArguments_multipleWords() {
        assertEquals(List.of("foo", "bar", "baz"),
                ArgumentSubstitutor.parseArguments("foo bar baz"));
    }

    @Test
    void parseArguments_doubleQuotedGroup() {
        // 'foo "hello world" baz' => ["foo", "hello world", "baz"]
        assertEquals(List.of("foo", "hello world", "baz"),
                ArgumentSubstitutor.parseArguments("foo \"hello world\" baz"));
    }

    @Test
    void parseArguments_singleQuotedGroup() {
        // "foo 'hello world' baz" => ["foo", "hello world", "baz"]
        assertEquals(List.of("foo", "hello world", "baz"),
                ArgumentSubstitutor.parseArguments("foo 'hello world' baz"));
    }

    @Test
    void parseArguments_backslashEscape() {
        // foo\ bar => single token "foo bar"
        assertEquals(List.of("foo bar"), ArgumentSubstitutor.parseArguments("foo\\ bar"));
    }

    @Test
    void parseArguments_dollarVariablePreserved() {
        // $KEY should remain as $KEY (not expanded)
        List<String> result = ArgumentSubstitutor.parseArguments("$FOO $BAR");
        assertEquals(List.of("$FOO", "$BAR"), result);
    }

    @Test
    void parseArguments_mixedQuotesAndWords() {
        // alice "bob jones" carol => ["alice", "bob jones", "carol"]
        assertEquals(List.of("alice", "bob jones", "carol"),
                ArgumentSubstitutor.parseArguments("alice \"bob jones\" carol"));
    }

    // =========================================================================
    // parseArgumentNames
    // =========================================================================

    @Test
    void parseArgumentNames_nullReturnsEmpty() {
        assertEquals(Collections.emptyList(), ArgumentSubstitutor.parseArgumentNames(null));
    }

    @Test
    void parseArgumentNames_stringForm_spaceSeparated() {
        assertEquals(List.of("foo", "bar", "baz"),
                ArgumentSubstitutor.parseArgumentNames("foo bar baz"));
    }

    @Test
    void parseArgumentNames_stringForm_filtersNumericNames() {
        // "foo 0 bar 1 baz" => ["foo", "bar", "baz"] (pure digits filtered)
        assertEquals(List.of("foo", "bar", "baz"),
                ArgumentSubstitutor.parseArgumentNames("foo 0 bar 1 baz"));
    }

    @Test
    void parseArgumentNames_listForm() {
        assertEquals(List.of("foo", "bar"),
                ArgumentSubstitutor.parseArgumentNames(List.of("foo", "bar")));
    }

    @Test
    void parseArgumentNames_listForm_filtersNumericNames() {
        assertEquals(List.of("foo", "bar"),
                ArgumentSubstitutor.parseArgumentNames(List.of("foo", "123", "bar", "0")));
    }

    @Test
    void parseArgumentNames_listForm_filtersBlankNames() {
        assertEquals(List.of("foo", "bar"),
                ArgumentSubstitutor.parseArgumentNames(List.of("foo", "", "  ", "bar")));
    }

    @Test
    void parseArgumentNames_unknownTypeReturnsEmpty() {
        // Non-string, non-list object should return empty
        assertEquals(Collections.emptyList(), ArgumentSubstitutor.parseArgumentNames(42));
    }

    // =========================================================================
    // generateProgressiveArgumentHint
    // =========================================================================

    @Test
    void generateProgressiveArgumentHint_noArgNames_returnsEmpty() {
        assertEquals(Optional.empty(),
                ArgumentSubstitutor.generateProgressiveArgumentHint(
                        Collections.emptyList(), Collections.emptyList()));
    }

    @Test
    void generateProgressiveArgumentHint_noneTyped_showsAll() {
        Optional<String> hint = ArgumentSubstitutor.generateProgressiveArgumentHint(
                List.of("a", "b", "c"), Collections.emptyList());
        assertEquals(Optional.of("[a] [b] [c]"), hint);
    }

    @Test
    void generateProgressiveArgumentHint_partiallyFilled() {
        Optional<String> hint = ArgumentSubstitutor.generateProgressiveArgumentHint(
                List.of("a", "b", "c"), List.of("x"));
        assertEquals(Optional.of("[b] [c]"), hint);
    }

    @Test
    void generateProgressiveArgumentHint_allFilled_returnsEmpty() {
        Optional<String> hint = ArgumentSubstitutor.generateProgressiveArgumentHint(
                List.of("a", "b"), List.of("x", "y"));
        assertEquals(Optional.empty(), hint);
    }

    @Test
    void generateProgressiveArgumentHint_moreThanFilled_returnsEmpty() {
        Optional<String> hint = ArgumentSubstitutor.generateProgressiveArgumentHint(
                List.of("a"), List.of("x", "y", "z"));
        assertEquals(Optional.empty(), hint);
    }

    // =========================================================================
    // substitute — null/undefined rawArgs
    // =========================================================================

    @Test
    void substitute_nullRawArgs_returnsContentUnchanged() {
        String content = "hello $ARGUMENTS world";
        assertEquals(content,
                ArgumentSubstitutor.substitute(content, null,
                        Collections.emptyList(), true));
    }

    // =========================================================================
    // substitute — bare $ARGUMENTS
    // =========================================================================

    @Test
    void substitute_bareArguments_replacedWithRawArgs() {
        String result = ArgumentSubstitutor.substitute(
                "run with $ARGUMENTS now", "alpha beta",
                Collections.emptyList(), false);
        assertEquals("run with alpha beta now", result);
    }

    @Test
    void substitute_emptyRawArgs_bareArgumentsReplacedWithEmpty() {
        String result = ArgumentSubstitutor.substitute(
                "run $ARGUMENTS end", "",
                Collections.emptyList(), false);
        assertEquals("run  end", result);
    }

    // =========================================================================
    // substitute — indexed $ARGUMENTS[N]
    // =========================================================================

    @Test
    void substitute_indexedArguments_replacedByIndex() {
        String result = ArgumentSubstitutor.substitute(
                "first=$ARGUMENTS[0] second=$ARGUMENTS[1]", "alpha beta",
                Collections.emptyList(), false);
        assertEquals("first=alpha second=beta", result);
    }

    @Test
    void substitute_indexedArguments_outOfRange_replacedWithEmpty() {
        String result = ArgumentSubstitutor.substitute(
                "$ARGUMENTS[5]", "alpha",
                Collections.emptyList(), false);
        assertEquals("", result);
    }

    // =========================================================================

    // =========================================================================

    @Test
    void substitute_positionalShorthand_zeroIndexed() {

        String result = ArgumentSubstitutor.substitute(
                "$0 and $1", "foo bar",
                Collections.emptyList(), false);
        assertEquals("foo and bar", result);
    }

    @Test
    void substitute_positionalShorthand_outOfRange_empty() {
        String result = ArgumentSubstitutor.substitute(
                "$3", "foo bar",
                Collections.emptyList(), false);
        assertEquals("", result);
    }

    // =========================================================================
    // substitute — named $foo
    // =========================================================================

    @Test
    void substitute_namedArguments_replacedByPosition() {
        List<String> names = List.of("lang", "version");
        String result = ArgumentSubstitutor.substitute(
                "use $lang at $version", "java 21",
                names, false);
        assertEquals("use java at 21", result);
    }

    @Test
    void substitute_namedArguments_outOfRange_replacedWithEmpty() {
        List<String> names = List.of("a", "b", "c");
        String result = ArgumentSubstitutor.substitute(
                "$a $b $c", "only_one",
                names, false);
        assertEquals("only_one  ", result);
    }

    @Test
    void substitute_namedArgument_doesNotMatchLongerName() {
        // $lang should NOT match inside $language
        List<String> names = List.of("lang");
        String result = ArgumentSubstitutor.substitute(
                "$language and $lang", "java",
                names, false);
        // $language has word-char after, so stays; $lang is replaced
        assertEquals("$language and java", result);
    }

    // =========================================================================
    // substitute — appendIfNoPlaceholder
    // =========================================================================

    @Test
    void substitute_appendIfNoPlaceholder_true_noPlaceholderInContent() {
        String result = ArgumentSubstitutor.substitute(
                "just content", "extra args",
                Collections.emptyList(), true);
        assertEquals("just content\n\nARGUMENTS: extra args", result);
    }

    @Test
    void substitute_appendIfNoPlaceholder_false_noPlaceholderInContent() {
        String result = ArgumentSubstitutor.substitute(
                "just content", "extra args",
                Collections.emptyList(), false);
        assertEquals("just content", result);
    }

    @Test
    void substitute_appendIfNoPlaceholder_true_emptyRawArgs_noAppend() {
        // Empty rawArgs should NOT trigger append even when flag is true
        String result = ArgumentSubstitutor.substitute(
                "just content", "",
                Collections.emptyList(), true);
        assertEquals("just content", result);
    }

    @Test
    void substitute_appendIfNoPlaceholder_placeholderExists_noAppend() {
        // When a placeholder exists and is replaced, content changes -> no append
        String result = ArgumentSubstitutor.substitute(
                "Hello $ARGUMENTS", "world",
                Collections.emptyList(), true);
        assertEquals("Hello world", result);
    }

    // =========================================================================
    // substitute — mixed placeholders
    // =========================================================================

    @Test
    void substitute_mixedPlaceholders_allExpanded() {
        List<String> names = List.of("cmd");
        // $cmd -> parsedArgs[0]="git", $ARGUMENTS[1] -> "status", $ARGUMENTS -> full args
        String result = ArgumentSubstitutor.substitute(
                "run $cmd then $ARGUMENTS[1] from $ARGUMENTS", "git status",
                names, false);
        assertEquals("run git then status from git status", result);
    }

    @Test
    void substitute_emptyRawArgs_expandsToEmpty_noAppend() {
        // Empty string triggers expansion (not skipped like null)
        String result = ArgumentSubstitutor.substitute(
                "prefix $ARGUMENTS suffix", "",
                Collections.emptyList(), true);
        // $ARGUMENTS replaced with "" -> content changed -> no append
        assertEquals("prefix  suffix", result);
    }

    // =========================================================================
    // Internal shell tokeniser edge cases
    // =========================================================================

    @Test
    void tryParseShellTokens_singleDoubleQuotedToken() {
        List<String> result = ArgumentSubstitutor.tryParseShellTokens("\"hello world\"");
        assertEquals(List.of("hello world"), result);
    }

    @Test
    void tryParseShellTokens_singleSingleQuotedToken() {
        List<String> result = ArgumentSubstitutor.tryParseShellTokens("'hello world'");
        assertEquals(List.of("hello world"), result);
    }

    @Test
    void tryParseShellTokens_escapedDoubleQuoteInsideQuotes() {
        // "she said \"hi\"" -> she said "hi"
        List<String> result = ArgumentSubstitutor.tryParseShellTokens("\"she said \\\"hi\\\"\"");
        assertEquals(List.of("she said \"hi\""), result);
    }

    // =========================================================================
    // args_001: Shell metacharacter operators discarded (gap fix)

    // =========================================================================

    @Test
    void tryParseShellTokens_pipe_discarded() {
        // 'git log | head' -> ['git', 'log', 'head']  (| is discarded)
        assertEquals(List.of("git", "log", "head"),
                ArgumentSubstitutor.tryParseShellTokens("git log | head"));
    }

    @Test
    void tryParseShellTokens_semicolon_discarded() {
        // 'a;b' -> ['a', 'b']
        assertEquals(List.of("a", "b"),
                ArgumentSubstitutor.tryParseShellTokens("a;b"));
    }

    @Test
    void tryParseShellTokens_ampersandAmpersand_discarded() {
        // 'a && b' -> ['a', 'b']
        assertEquals(List.of("a", "b"),
                ArgumentSubstitutor.tryParseShellTokens("a && b"));
    }

    @Test
    void tryParseShellTokens_redirection_discarded() {
        // 'echo > file' -> ['echo', 'file']
        assertEquals(List.of("echo", "file"),
                ArgumentSubstitutor.tryParseShellTokens("echo > file"));
    }

    @Test
    void tryParseShellTokens_metachar_insideDoubleQuotes_kept() {
        // Inside double quotes, | is a literal character
        assertEquals(List.of("a|b"),
                ArgumentSubstitutor.tryParseShellTokens("\"a|b\""));
    }

    @Test
    void tryParseShellTokens_metachar_insideSingleQuotes_kept() {
        // Inside single quotes, ; is a literal character
        assertEquals(List.of("a;b"),
                ArgumentSubstitutor.tryParseShellTokens("'a;b'"));
    }

    @Test
    void parseArguments_pipeShiftsIndicesCorrectly() {
        // 'git log | head' -> ['git', 'log', 'head'], $0=git $1=log $2=head
        List<String> result = ArgumentSubstitutor.parseArguments("git log | head");
        assertEquals(List.of("git", "log", "head"), result);
    }

    // =========================================================================
    // args_002: Glob tokens (* and ?) — intentional divergence (URL-preserving)

    // This is intentional: URL fragments like ?q=test must be preserved.
    // =========================================================================

    @Test
    void tryParseShellTokens_glob_star_kept_intentionalDivergence() {

        // Documented intentional divergence: URL query strings need * and ? intact.
        assertEquals(List.of("*.sh", "arg"),
                ArgumentSubstitutor.tryParseShellTokens("*.sh arg"));
    }

    @Test
    void tryParseShellTokens_urlWithQueryString_preserved() {

        assertEquals(List.of("https://example.com/?q=test"),
                ArgumentSubstitutor.tryParseShellTokens("https://example.com/?q=test"));
    }

    // =========================================================================
    // args_003: Backslash-dollar escape inside double-quoted strings (gap fix)

    // Java (before fix): appended backslash literally, yielding \$
    // =========================================================================

    @Test
    void tryParseShellTokens_backslashDollar_insideDoubleQuotes_yieldsBareDollar() {
        // "cost \$5" -> ["cost $5"]  (backslash consumed, bare $ kept)
        assertEquals(List.of("cost $5"),
                ArgumentSubstitutor.tryParseShellTokens("\"cost \\$5\""));
    }

    @Test
    void tryParseShellTokens_backslashBackslash_insideDoubleQuotes_yieldsOneBackslash() {
        // "\\" -> ["\"]
        assertEquals(List.of("\\"),
                ArgumentSubstitutor.tryParseShellTokens("\"\\\\\""));
    }

    @Test
    void tryParseShellTokens_nonEscapeChar_insideDoubleQuotes_backslashKept() {
        // "\n" (non-escape sequence) -> ["\\n"] (backslash preserved, then n appended)
        assertEquals(List.of("\\n"),
                ArgumentSubstitutor.tryParseShellTokens("\"\\n\""));
    }

    // =========================================================================
    // args_004: Hash (#) — intentional divergence (URL fragment preservation)

    // Java treats # as a plain word character to preserve URL fragments.
    // =========================================================================

    @Test
    void tryParseShellTokens_hash_keptAsWordChar_intentionalDivergence() {

        // Intentional: URL fragments like #section must not be stripped.
        assertEquals(List.of("https://example.com/path#section"),
                ArgumentSubstitutor.tryParseShellTokens("https://example.com/path#section"));
    }

    @Test
    void tryParseShellTokens_hashInWord_preserved() {

        assertEquals(List.of("query", "foo#bar"),
                ArgumentSubstitutor.tryParseShellTokens("query foo#bar"));
    }

    // =========================================================================


    // =========================================================================

    @Test
    void substitute_namedArg_withRegexMetacharName_doesNotOverMatch() {

        // '$fooXbar' too. Java's Pattern.quote prevents this.
        List<String> names = List.of("foo.bar");
        String result = ArgumentSubstitutor.substitute(
                "$foo.bar and $fooXbar", "value",
                names, false);
        // $foo.bar (literal dot) is replaced; $fooXbar must NOT be replaced
        assertEquals("value and $fooXbar", result);
    }

    // =========================================================================
    // args_006: Trailing backslash at EOF discarded (gap fix)

    // Java (before fix): fell through to regular-char branch, appending '\'.
    // =========================================================================

    @Test
    void tryParseShellTokens_trailingBackslash_discarded() {
        // 'foo\' -> ['foo']  (trailing backslash consumed and discarded)
        assertEquals(List.of("foo"),
                ArgumentSubstitutor.tryParseShellTokens("foo\\"));
    }

    @Test
    void tryParseShellTokens_trailingBackslashOnly_emptyResult() {
        // '\' alone -> []
        assertTrue(ArgumentSubstitutor.tryParseShellTokens("\\").isEmpty());
    }

    @Test
    void parseArguments_trailingBackslash_discarded() {
        assertEquals(List.of("foo"),
                ArgumentSubstitutor.parseArguments("foo\\"));
    }

    // =========================================================================
    // args_007: Unterminated quotes fail open to a lossless whitespace fallback

// tryParseShellTokens returns null on an unterminated quote.

    @Test
    void tryParseShellTokens_unterminatedDoubleQuote_returnsNull() {
        // 'foo "bar baz' (no closing quote) -> tokenisation cannot complete
        assertNull(ArgumentSubstitutor.tryParseShellTokens("foo \"bar baz"));
    }

    @Test
    void tryParseShellTokens_unterminatedSingleQuote_returnsNull() {
        // "foo 'bar baz" (no closing quote) -> tokenisation cannot complete
        assertNull(ArgumentSubstitutor.tryParseShellTokens("foo 'bar baz"));
    }

    @Test
    void tryParseShellTokens_terminatedQuote_stillParses() {
        // Regression guard: a properly closed quote must NOT trigger the fallback.
        assertEquals(List.of("foo", "bar baz"),
                ArgumentSubstitutor.tryParseShellTokens("foo \"bar baz\""));
    }

    @Test
    void parseArguments_unterminatedDoubleQuote_fallsBackToWhitespaceSplit() {
// 'foo "bar baz' -> ['foo', '"bar', 'baz']: the stray quote is preserved verbatim rather
// than silently dropped/merged.
        assertEquals(List.of("foo", "\"bar", "baz"),
                ArgumentSubstitutor.parseArguments("foo \"bar baz"));
    }

    @Test
    void parseArguments_unterminatedSingleQuote_fallsBackToWhitespaceSplit() {
        // "foo 'bar baz" -> ['foo', "'bar", 'baz']
        assertEquals(List.of("foo", "'bar", "baz"),
                ArgumentSubstitutor.parseArguments("foo 'bar baz"));
    }

    @Test
    void parseArguments_wellFormedUrl_keepsHashFragment_viaTolerantParser() {
        // Well-formed input still uses the tolerant parser (URL # preserved),
        // NOT the fallback — proving the hybrid keeps Java's domain improvement.
        assertEquals(List.of("https://example.com/path#section"),
                ArgumentSubstitutor.parseArguments("https://example.com/path#section"));
    }
}
