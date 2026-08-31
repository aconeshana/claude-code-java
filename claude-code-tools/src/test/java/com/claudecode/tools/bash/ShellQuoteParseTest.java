package com.claudecode.tools.bash;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.tools.bash.ShellQuoteParse.Comment;
import com.claudecode.tools.bash.ShellQuoteParse.Glob;
import com.claudecode.tools.bash.ShellQuoteParse.Op;
import com.claudecode.tools.bash.ShellQuoteParse.Token;
import com.claudecode.tools.bash.ShellQuoteParse.Word;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Verifies tokenization used by redirect routing and command-segment splitting. */
class ShellQuoteParseTest {

    private static Word word(Token t) {
        assertInstanceOf(Word.class, t);
        return (Word) t;
    }

    private static Op op(Token t) {
        assertInstanceOf(Op.class, t);
        return (Op) t;
    }

    @Test
    void plainWord() {
        List<Token> tokens = ShellQuoteParse.parse("echo hello");
        assertEquals(2, tokens.size());
        assertEquals("echo", word(tokens.getFirst()).value());
        assertEquals("hello", word(tokens.get(1)).value());
    }

    @Test
    void singleQuotesLiteral() {
        List<Token> tokens = ShellQuoteParse.parse("echo 'a $HOME b'");
        assertEquals(2, tokens.size());
        assertEquals("a $HOME b", word(tokens.get(1)).value());
    }

    @Test
    void doubleQuotesLiteralExceptDollar() {
        // $VAR is preserved literally (not expanded) by the env wrapper.
        List<Token> tokens = ShellQuoteParse.parse("echo \"a $HOME b\"");
        assertEquals(2, tokens.size());
        assertEquals("a $HOME b", word(tokens.get(1)).value());
    }

    @Test
    void envVarEmitsSingleDollar() {

        List<Token> tokens = ShellQuoteParse.parse("cp $HOME/x /y");
        assertEquals(3, tokens.size());
        assertEquals("$HOME/x", word(tokens.get(1)).value());
    }

    @Test
    void bracedEnvVar() {
        // shell-quote strips the braces: ${FOO} -> $FOO (still contains '$').
        List<Token> tokens = ShellQuoteParse.parse("cat ${FOO}/bar");
        assertEquals(2, tokens.size());
        assertEquals("$FOO/bar", word(tokens.get(1)).value());
    }

    @Test
    void backslashEscape() {
        List<Token> tokens = ShellQuoteParse.parse("echo a\\ b");
        assertEquals(2, tokens.size());
        assertEquals("a b", word(tokens.get(1)).value());
    }

    @Test
    void globDetection() {
        List<Token> tokens = ShellQuoteParse.parse("rm *.log");
        assertEquals(2, tokens.size());
        assertInstanceOf(Glob.class, tokens.get(1));
        assertEquals("*.log", ((Glob) tokens.get(1)).pattern());
    }

    @Test
    void questionGlobDetection() {
        List<Token> tokens = ShellQuoteParse.parse("ls ?");
        assertEquals(2, tokens.size());
        assertInstanceOf(Glob.class, tokens.get(1));
    }

    @Test
    void commentConsumesRestOfLine() {
        List<Token> tokens = ShellQuoteParse.parse("echo hi # rm /");
        // shell-quote emits a Comment token for the remainder of the line.
        assertEquals(3, tokens.size());
        assertEquals("echo", word(tokens.getFirst()).value());
        assertEquals("hi", word(tokens.get(1)).value());
        assertInstanceOf(Comment.class, tokens.get(2));
    }

    @Test
    void redirectOperators() {
        List<Token> tokens = ShellQuoteParse.parse("echo x > file");
        assertEquals(4, tokens.size());
        assertEquals(">", op(tokens.get(2)).op());
        assertEquals("file", word(tokens.get(3)).value());
    }

    @Test
    void appendRedirectOperator() {
        List<Token> tokens = ShellQuoteParse.parse("echo x >> file");
        assertEquals(4, tokens.size());
        assertEquals(">>", op(tokens.get(2)).op());
    }

    @Test
    void bothRedirectOperator() {
        // shell-quote does NOT combine '&>'; it is '&' then '>', and BashPermissions
        // routes the '>' to its following target.
        List<Token> tokens = ShellQuoteParse.parse("cmd &> file");
        assertEquals(4, tokens.size());
        assertEquals("&", op(tokens.get(1)).op());
        assertEquals(">", op(tokens.get(2)).op());
        assertEquals("file", word(tokens.get(3)).value());
    }

    @Test
    void fdDupOperator() {
        // '>&' is a single operator.
        List<Token> tokens = ShellQuoteParse.parse("cmd >&1");
        assertEquals(3, tokens.size());
        assertEquals(">&", op(tokens.get(1)).op());
        assertEquals("1", word(tokens.get(2)).value());
    }

    @Test
    void forceClobberSplitsAsGtThenPipe() {
        // '>|' is NOT a combined operator in shell-quote; it is '>' then '|'.
        // BashPermissions must skip the '|' to find the target after it.
        List<Token> tokens = ShellQuoteParse.parse("echo x >| file");
        assertEquals(5, tokens.size());
        assertEquals(">", op(tokens.get(2)).op());
        assertEquals("|", op(tokens.get(3)).op());
        assertEquals("file", word(tokens.get(4)).value());
    }

    @Test
    void processSubstitutionOperator() {
        List<Token> tokens = ShellQuoteParse.parse("echo <(cmd)");
        assertEquals(4, tokens.size());
        assertEquals("<(", op(tokens.get(1)).op());
    }

    @Test
    void nullAndEmpty() {
        assertTrue(ShellQuoteParse.parse(null).isEmpty());
        assertTrue(ShellQuoteParse.parse("   ").isEmpty());
    }
}
