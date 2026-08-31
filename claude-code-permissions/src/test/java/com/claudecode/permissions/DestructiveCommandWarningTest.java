package com.claudecode.permissions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;


class DestructiveCommandWarningTest {

    @Test
    void gitResetHardMatches() {
        assertTrue(DestructiveCommandWarning.check("git reset --hard").isPresent(),
            "lowercase git reset --hard should warn");
    }

    @Test
    void gitResetHardUppercaseDoesNotMatch() {

        assertFalse(DestructiveCommandWarning.check("GIT RESET --HARD").isPresent(),
            "uppercase must not match the case-sensitive git pattern");
    }

    @Test
    void dropTableMatchesCaseInsensitively() {
        assertTrue(DestructiveCommandWarning.check("DROP TABLE users").isPresent());
        assertTrue(DestructiveCommandWarning.check("drop table users").isPresent(),
            "DROP/TRUNCATE pattern is case-insensitive per TS /i");
    }

    @Test
    void deleteFromMatchesCaseInsensitively() {
        assertTrue(DestructiveCommandWarning.check("DELETE FROM users").isPresent());
        assertTrue(DestructiveCommandWarning.check("delete from users").isPresent(),
            "DELETE FROM pattern is case-insensitive per TS /i");
    }

    @Test
    void nonDestructiveCommandReturnsEmpty() {
        assertFalse(DestructiveCommandWarning.check("git status").isPresent());
        assertFalse(DestructiveCommandWarning.check("echo hello").isPresent());
    }

    @Test
    void nullOrBlankReturnsEmpty() {
        assertFalse(DestructiveCommandWarning.check(null).isPresent());
        assertFalse(DestructiveCommandWarning.check("   ").isPresent());
    }
}
