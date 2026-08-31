package com.claudecode.ui.lanterna.repl;

import com.claudecode.ui.lanterna.features.settings.UiSettings;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Prepares settings and transcript metadata needed by the first interactive
 * frame. Every supplier runs on a virtual thread; the prestart caller only
 * applies the resulting immutable snapshot after the scene has been sealed.
 */
final class ReplStartupPreparation {

    private static final Logger log = LoggerFactory.getLogger(ReplStartupPreparation.class);

    record Prepared(
            boolean copyOnSelect,
            boolean spinnerTipsEnabled,
            boolean vimModeEnabled,
            int btwUseCount,
            SessionController.RestoredSessionBadge sessionBadge,
            String sessionCustomTitle) {
    }

    record SessionMetadata(
            SessionController.RestoredSessionBadge badge,
            String customTitle) {
    }

    private ReplStartupPreparation() {
    }

    static CompletableFuture<Prepared> start(
            String sessionId, InteractiveSessionPort sessions) {
        return startWithMetadata(
            UiSettings::readCopyOnSelect,
            UiSettings::readSpinnerTipsEnabled,
            UiSettings::isVimModeEnabled,
            () -> UiSettings.readGlobalInt("btwUseCount", 0),
            () -> loadSessionMetadata(sessionId, sessions));
    }

    static CompletableFuture<Prepared> startForTest(
            BooleanSupplier copyOnSelect,
            BooleanSupplier spinnerTipsEnabled,
            BooleanSupplier vimModeEnabled,
            IntSupplier btwUseCount,
            Supplier<SessionController.RestoredSessionBadge> sessionBadge) {
        return startWithMetadata(copyOnSelect, spinnerTipsEnabled, vimModeEnabled, btwUseCount,
            () -> new SessionMetadata(sessionBadge.get(), null));
    }

    private static CompletableFuture<Prepared> startWithMetadata(
            BooleanSupplier copyOnSelect,
            BooleanSupplier spinnerTipsEnabled,
            BooleanSupplier vimModeEnabled,
            IntSupplier btwUseCount,
            Supplier<SessionMetadata> sessionMetadata) {
        CompletableFuture<Boolean> copy = supply(
            "repl-copy-on-select-prepare", copyOnSelect::getAsBoolean, true);
        CompletableFuture<Boolean> tips = supply(
            "repl-spinner-tips-prepare", spinnerTipsEnabled::getAsBoolean, true);
        CompletableFuture<Boolean> vim = supply(
            "repl-vim-mode-prepare", vimModeEnabled::getAsBoolean, false);
        CompletableFuture<Integer> btw = supply(
            "repl-btw-count-prepare", btwUseCount::getAsInt, 0);
        CompletableFuture<SessionMetadata> metadata = supply(
            "repl-session-metadata-prepare", sessionMetadata,
            emptySessionMetadata());

        return CompletableFuture.allOf(copy, tips, vim, btw, metadata)
            .thenApply(_ -> new Prepared(
                copy.getNow(true), tips.getNow(true), vim.getNow(false), btw.getNow(0),
                metadata.getNow(emptySessionMetadata()).badge(),
                metadata.getNow(emptySessionMetadata()).customTitle()));
    }

    static SessionMetadata loadSessionMetadata(
            String sessionId, InteractiveSessionPort sessions) {
        if (StringUtils.isBlank(sessionId) || sessions == null) {
            return emptySessionMetadata();
        }
        String cwd = System.getProperty("user.dir");
        Path transcript = sessions.sessionFile(cwd, sessionId);
        InteractiveSessionPort.MetadataSnapshot snapshot = transcript == null
            ? InteractiveSessionPort.MetadataSnapshot.empty()
            : sessions.scanMetadata(transcript);
        return new SessionMetadata(
            SessionController.restoredSessionBadge(snapshot), snapshot.customTitle());
    }

    private static SessionMetadata emptySessionMetadata() {
        return new SessionMetadata(
            new SessionController.RestoredSessionBadge(null, null), null);
    }

    private static <T> CompletableFuture<T> supply(
            String threadName, Supplier<T> supplier, T fallback) {
        CompletableFuture<T> result = new CompletableFuture<>();
        Thread.ofVirtual().name(threadName).start(() -> {
            try {
                result.complete(supplier.get());
            } catch (RuntimeException failure) {
                log.debug("Optional startup preparation {} degraded: {}",
                    threadName, failure.toString());
                result.complete(fallback);
            }
        });
        return result;
    }
}
