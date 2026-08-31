package com.claudecode.core.util;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class UuidUtilsTest {
    @Test
    void validatesExactShapeWithoutVersionRestriction() {
        assertEquals("00000000-0000-0000-0000-000000000000",
            UuidUtils.validate("00000000-0000-0000-0000-000000000000"));
        assertTrue(UuidUtils.isValid("ABCDEF12-3456-7890-ABCD-EF1234567890"));
        assertFalse(UuidUtils.isValid("1-1-1-1-1"));
        assertNull(UuidUtils.validate(123));
    }
}
