package com.claudecode.core.paste;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Resolves clipboard images through an ordered backend chain.
 */
final class ClipboardImageService {

    private final List<ClipboardImageBackend> backends;
    private final AtomicReference<Set<String>> permanentlyUnavailable =
        new AtomicReference<>(Set.of());

    ClipboardImageService(List<ClipboardImageBackend> backends) {
        this.backends = List.copyOf(backends);
    }

    ClipboardReadResult read() {
        ClipboardReadResult lastUnavailable = ClipboardReadResult.Unavailable.transientFailure(null);
        for (ClipboardImageBackend backend : backends) {
            if (permanentlyUnavailable.get().contains(backend.name())) continue;
            ClipboardReadResult result = readSafely(backend);
            if (result instanceof ClipboardReadResult.Unavailable unavailable) {
                if (unavailable.permanent()) markPermanentlyUnavailable(backend.name());
                lastUnavailable = unavailable;
                continue;
            }
            return result;
        }
        return lastUnavailable;
    }

    private void markPermanentlyUnavailable(String backendName) {
        permanentlyUnavailable.updateAndGet(existing -> {
            if (existing.contains(backendName)) return existing;
            Set<String> updated = new HashSet<>(existing);
            updated.add(backendName);
            return Set.copyOf(updated);
        });
    }

    private static ClipboardReadResult readSafely(ClipboardImageBackend backend) {
        try {
            return backend.read();
        } catch (RuntimeException | LinkageError failure) {
            return ClipboardReadResult.Unavailable.transientFailure(failure);
        }
    }
}
