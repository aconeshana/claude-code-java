package com.claudecode.core.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Locks down {@link PermissionAskCallback.Result} — the answer-delivery channel for interactive
 * tools (AskUserQuestion folds collected answers into the tool input via {@code updatedInput}).
 */
class PermissionAskCallbackTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void allowWithInput_carriesRewrittenInputAndNoFeedback() {
        ObjectNode input = mapper.createObjectNode().put("a", 1);
        PermissionAskCallback.Result r = PermissionAskCallback.Result.allowWithInput(input);

        assertTrue(r.allowed());
        assertSame(input, r.updatedInput());
        assertNull(r.feedback());
    }

    @Test
    void allowWithInputAndFeedback_carriesBoth() {
        ObjectNode input = mapper.createObjectNode().put("a", 1);
        PermissionAskCallback.Result r =
            PermissionAskCallback.Result.allowWithInputAndFeedback(input, "extra instruction");

        assertTrue(r.allowed());
        assertSame(input, r.updatedInput());
        assertEquals("extra instruction", r.feedback());
    }

    @Test
    void legacyFactories_leaveUpdatedInputNull() {
        assertNull(PermissionAskCallback.Result.allow().updatedInput());
        assertNull(PermissionAskCallback.Result.allowWithFeedback("x").updatedInput());
        assertNull(PermissionAskCallback.Result.deny().updatedInput());
        assertNull(PermissionAskCallback.Result.denyWithFeedback("x").updatedInput());
    }

    @Test
    void legacyFactories_flagAllowedCorrectly() {
        assertTrue(PermissionAskCallback.Result.allow().allowed());
        assertTrue(PermissionAskCallback.Result.allowWithFeedback("x").allowed());
        assertFalse(PermissionAskCallback.Result.deny().allowed());
        assertFalse(PermissionAskCallback.Result.denyWithFeedback("x").allowed());
    }

    @Test
    void sdkDirectDenial_isMarkedWithoutChangingLegacyFeedbackSemantics() {
        PermissionAskCallback.Result r =
            PermissionAskCallback.Result.denyWithDirectMessage("host denied");

        assertFalse(r.allowed());
        assertEquals("host denied", r.feedback());
        assertTrue(r.directDenial());
        assertFalse(PermissionAskCallback.Result.denyWithFeedback("x").directDenial());
    }

    @Test
    void blankFeedback_isNormalizedToNull() {
        PermissionAskCallback.Result r = new PermissionAskCallback.Result(true, "   ", null);
        assertNull(r.feedback());

        PermissionAskCallback.Result r2 = new PermissionAskCallback.Result(false, "", null);
        assertNull(r2.feedback());

        PermissionAskCallback.Result r3 = new PermissionAskCallback.Result(true, "  x  ", null);
        assertEquals("  x  ", r3.feedback());
    }
}
