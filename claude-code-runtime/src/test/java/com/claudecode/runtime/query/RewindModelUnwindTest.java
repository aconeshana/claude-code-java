package com.claudecode.runtime.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.RefusalFallbackAnnouncement;
import com.claudecode.core.message.SystemMessage;
import com.claudecode.core.message.TextBlock;
import java.util.List;
import org.junit.jupiter.api.Test;

class RewindModelUnwindTest {

    private static AssistantMessage assistant(String uuid, String model) {
        return new AssistantMessage(uuid, AssistantContent.apiResponse(
            "msg-" + uuid, List.of(new TextBlock("answer")), null,
            model, "end_turn", null));
    }

    private static SystemMessage fallback(String uuid, String original, String target) {
        return RefusalFallbackAnnouncement.row(
            uuid, original, target, null, null, List.of(), "user-refused");
    }

    @Test
    void noSlicedFallbackBannerNeedsNoModelAction() {
        assertNull(RewindModelUnwind.evaluate(
            List.of(), List.of(), "claude-opus-4-6", true,
            "claude-sonnet-4-6", _ -> true));
    }

    @Test
    void thirdPartyProviderKeepsTheCurrentModel() {
        RewindModelUnwind.Result result = RewindModelUnwind.evaluate(
            List.of(assistant("a1", "claude-sonnet-4-6")),
            List.of(fallback("f1", "claude-sonnet-4-6", "claude-opus-4-6")),
            "claude-opus-4-6", false, "claude-sonnet-4-6", _ -> true);

        RewindModelUnwind.Keep keep = assertInstanceOf(
            RewindModelUnwind.Keep.class, result.model());
        assertEquals(RewindModelUnwind.KeepReason.NOT_FIRST_PARTY, keep.reason());
    }

    @Test
    void aDifferentCurrentWriterKeepsTheCurrentModel() {
        RewindModelUnwind.Result result = RewindModelUnwind.evaluate(
            List.of(assistant("a1", "claude-sonnet-4-6")),
            List.of(fallback("f1", "claude-sonnet-4-6", "claude-opus-4-6")),
            "claude-haiku-4-5", true, "claude-sonnet-4-6", _ -> true);

        RewindModelUnwind.Keep keep = assertInstanceOf(
            RewindModelUnwind.Keep.class, result.model());
        assertEquals(RewindModelUnwind.KeepReason.WRITER_MISMATCH, keep.reason());
    }

    @Test
    void aDifferentReleaseInTheSameFamilyKeepsTheCurrentWriter() {
        RewindModelUnwind.Result result = RewindModelUnwind.evaluate(
            List.of(assistant("a1", "claude-sonnet-4-6")),
            List.of(fallback("f1", "claude-sonnet-4-6", "claude-opus-4-6")),
            "claude-opus-5", true, "claude-sonnet-4-6", _ -> true);

        RewindModelUnwind.Keep keep = assertInstanceOf(
            RewindModelUnwind.Keep.class, result.model());
        assertEquals(RewindModelUnwind.KeepReason.WRITER_MISMATCH, keep.reason());
    }

    @Test
    void absentCurrentOverrideDoesNotMatchAnAbsentFallbackWriter() {
        RewindModelUnwind.Result result = RewindModelUnwind.evaluate(
            List.of(assistant("a1", "claude-sonnet-4-6")),
            List.of(fallback("f1", "claude-sonnet-4-6", null)),
            null, true, "claude-sonnet-4-6", _ -> true);

        RewindModelUnwind.Keep keep = assertInstanceOf(
            RewindModelUnwind.Keep.class, result.model());
        assertEquals(RewindModelUnwind.KeepReason.WRITER_MISMATCH, keep.reason());
    }

    @Test
    void matchingFallbackRestoresTheLastKeptAssistantModel() {
        RewindModelUnwind.Result result = RewindModelUnwind.evaluate(
            List.<Message>of(
                assistant("a1", "claude-haiku-4-5-20251001"),
                assistant("a2", "claude-sonnet-4-6")),
            List.of(fallback("f1", "claude-sonnet-4-6", "claude-opus-4-6")),
            "claude-opus-4-6", true, "claude-haiku-4-5-20251001", _ -> true);

        RewindModelUnwind.Restore restore = assertInstanceOf(
            RewindModelUnwind.Restore.class, result.model());
        assertEquals("claude-sonnet-4-6", restore.value());
        assertEquals(RewindModelUnwind.RestoreSource.TRANSCRIPT, restore.source());
    }

    @Test
    void unavailableTranscriptModelFallsBackToTheInitialModel() {
        RewindModelUnwind.Result result = RewindModelUnwind.evaluate(
            List.of(assistant("a1", "retired-model")),
            List.of(fallback("f1", "claude-sonnet-4-6", "claude-opus-4-6")),
            "claude-opus-4-6", true, "claude-sonnet-4-6",
            model -> !model.equals("retired-model"));

        RewindModelUnwind.Restore restore = assertInstanceOf(
            RewindModelUnwind.Restore.class, result.model());
        assertEquals("claude-sonnet-4-6", restore.value());
        assertEquals(RewindModelUnwind.RestoreSource.INITIAL_MODEL, restore.source());
    }

    @Test
    void retiredFirstPartyWriterFallsBackToTheInitialModel() {
        RewindModelUnwind.Result result = RewindModelUnwind.evaluate(
            List.of(assistant("a1", "claude-opus-4-1-20250805")),
            List.of(fallback("f1", "claude-opus-4-1-20250805", "claude-opus-5")),
            "claude-opus-5", true, "claude-sonnet-4-6", _ -> true);

        RewindModelUnwind.Restore restore = assertInstanceOf(
            RewindModelUnwind.Restore.class, result.model());
        assertEquals("claude-sonnet-4-6", restore.value());
        assertEquals(RewindModelUnwind.RestoreSource.INITIAL_MODEL, restore.source());
    }

    @Test
    void aDeclinedLatestWriterDoesNotFallBackToAnOlderTranscriptWriter() {
        RewindModelUnwind.Result result = RewindModelUnwind.evaluate(
            List.<Message>of(
                assistant("a1", "claude-sonnet-4-6"),
                assistant("a2", "retired-model")),
            List.of(fallback("f1", "claude-sonnet-4-6", "claude-opus-4-6")),
            "claude-opus-4-6", true, "claude-haiku-4-5-20251001",
            model -> !model.equals("retired-model"));

        RewindModelUnwind.Restore restore = assertInstanceOf(
            RewindModelUnwind.Restore.class, result.model());
        assertEquals("claude-haiku-4-5-20251001", restore.value());
        assertEquals(RewindModelUnwind.RestoreSource.INITIAL_MODEL, restore.source());
    }

    @Test
    void liveModeDependentSettingDeclinesTheTranscriptWriter() {
        RewindModelUnwind.Result result = RewindModelUnwind.evaluate(
            List.of(assistant("a1", "claude-haiku-4-5-20251001")),
            List.of(fallback("f1", "claude-haiku-4-5-20251001", "haiku")),
            "haiku", true, "claude-sonnet-4-6", _ -> true);

        RewindModelUnwind.Restore restore = assertInstanceOf(
            RewindModelUnwind.Restore.class, result.model());
        assertEquals("claude-sonnet-4-6", restore.value());
        assertEquals(RewindModelUnwind.RestoreSource.INITIAL_MODEL, restore.source());
    }

    @Test
    void modeDependentGuardUsesTheCurrentFallbackWriterInsteadOfThePickerPreference() {
        RewindModelUnwind.Result result = RewindModelUnwind.evaluate(
            List.of(assistant("a1", "claude-sonnet-4-6")),
            List.of(fallback("f1", "claude-opus-4-6", "haiku")),
            "haiku", true, "claude-opus-4-6", _ -> true);

        RewindModelUnwind.Restore restore = assertInstanceOf(
            RewindModelUnwind.Restore.class, result.model());
        assertEquals("claude-opus-4-6", restore.value());
        assertEquals(RewindModelUnwind.RestoreSource.INITIAL_MODEL, restore.source());
    }

    @Test
    void taggedInitialModelDoesNotPromoteAReleaseWithoutOneMillionSupport() {
        RewindModelUnwind.Result result = RewindModelUnwind.evaluate(
            List.of(assistant("a1", "claude-haiku-4-5-20251001")),
            List.of(fallback("f1", "claude-haiku-4-5-20251001", "claude-opus-5")),
            "claude-opus-5", true, "haiku[1m]", _ -> true);

        RewindModelUnwind.Restore restore = assertInstanceOf(
            RewindModelUnwind.Restore.class, result.model());
        assertEquals("claude-haiku-4-5-20251001", restore.value());
        assertEquals(RewindModelUnwind.RestoreSource.TRANSCRIPT, restore.source());
    }

    @Test
    void taggedInitialModelDoesNotPromoteAnotherReleaseInTheSameFamily() {
        RewindModelUnwind.Result result = RewindModelUnwind.evaluate(
            List.of(assistant("a1", "claude-sonnet-4-6")),
            List.of(fallback("f1", "claude-sonnet-4-6", "claude-opus-5")),
            "claude-opus-5", true, "claude-sonnet-4-5[1m]", _ -> true);

        RewindModelUnwind.Restore restore = assertInstanceOf(
            RewindModelUnwind.Restore.class, result.model());
        assertEquals("claude-sonnet-4-6", restore.value());
        assertEquals(RewindModelUnwind.RestoreSource.TRANSCRIPT, restore.source());
    }

    @Test
    void absentInitialModelRestoresSettingsFallthrough() {
        RewindModelUnwind.Result result = RewindModelUnwind.evaluate(
            List.of(),
            List.of(fallback("f1", "claude-sonnet-4-6", "claude-opus-4-6")),
            "claude-opus-4-6", true, null, _ -> true);

        RewindModelUnwind.Restore restore = assertInstanceOf(
            RewindModelUnwind.Restore.class, result.model());
        assertNull(restore.value());
        assertEquals(RewindModelUnwind.RestoreSource.SETTINGS_FALLTHROUGH, restore.source());
    }

    @Test
    void theLastSlicedFallbackControlsTheWriterCheckAndAllBannersAreCounted() {
        RewindModelUnwind.Result result = RewindModelUnwind.evaluate(
            List.of(assistant("a1", "claude-sonnet-4-6")),
            List.of(
                fallback("f1", "claude-haiku-4-5-20251001", "claude-sonnet-4-6"),
                fallback("f2", "claude-sonnet-4-6", "claude-opus-4-6")),
            "claude-opus-4-6", true, "claude-haiku-4-5-20251001", _ -> true);

        assertEquals(2, result.bannersSliced());
        assertEquals("claude-opus-4-6", result.lastSlicedFallbackModel());
        RewindModelUnwind.Restore restore = assertInstanceOf(
            RewindModelUnwind.Restore.class, result.model());
        assertEquals("claude-sonnet-4-6", restore.value());
    }
}
