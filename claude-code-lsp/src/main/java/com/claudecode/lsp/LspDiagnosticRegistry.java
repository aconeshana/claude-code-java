package com.claudecode.lsp;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Cross-turn / within-batch deduplication of LSP diagnostics and the live diagnostic cache,
 * matching.
 */
public class LspDiagnosticRegistry {

    /** Bound on how many delivered-fingerprint sets we keep before evicting the oldest file's entry. */
    private static final int MAX_TRACKED_FILES = 500;


    private static final int MAX_DIAGNOSTICS_PER_FILE = 10;


    private static final int MAX_TOTAL_DIAGNOSTICS = 30;

    private final Map<String, CopyOnWriteArrayList<Diagnostic>> diagnostics;
    private final List<DiagnosticListener> listeners;

    /** Fingerprints already delivered to listeners per file, used to suppress repeat notifications
     * when {@code didChange} churn re-publishes the same set of diagnostics. */
    private final Map<String, Set<String>> deliveredFingerprints;

    public LspDiagnosticRegistry() {
        this.diagnostics = new ConcurrentHashMap<>();
        this.listeners = new CopyOnWriteArrayList<>();
        this.deliveredFingerprints = new LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Set<String>> eldest) {
                return size() > MAX_TRACKED_FILES;
            }
        };
    }

    public void addListener(DiagnosticListener listener) {
        listeners.add(listener);
    }

    public void registerDiagnostics(String fileUri, List<Diagnostic> diags) {

        // (MAX_TOTAL_DIAGNOSTICS): sort by severity (most severe first) and keep the top N.
        List<Diagnostic> capped = diags.stream()
            .sorted(Comparator.comparingInt(d -> d.severity().value()))
            .limit(MAX_DIAGNOSTICS_PER_FILE)
            .collect(Collectors.toList());
        diagnostics.put(fileUri, new CopyOnWriteArrayList<>(capped));

        enforceTotalCap();

        // Deliver (and fingerprint) whatever survived both caps.
        CopyOnWriteArrayList<Diagnostic> stored = diagnostics.get(fileUri);
        List<Diagnostic> finalList = stored != null ? stored : List.of();
        Set<String> fingerprints = finalList.stream().map(LspDiagnosticRegistry::fingerprint)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> previous;
        synchronized (deliveredFingerprints) {
            previous = deliveredFingerprints.put(fileUri, fingerprints);
        }
        if (previous != null && previous.equals(fingerprints)) {
            return; // identical to what we already delivered — suppress the repeat notification
        }

        notifyListeners(fileUri, finalList);
    }

    /**
     * Keep the workspace-wide diagnostic count within {@link #MAX_TOTAL_DIAGNOSTICS}, retaining the
     * most severe diagnostics and dropping the least severe (which may empty out some files entirely).
     */
    private void enforceTotalCap() {
        int total = diagnostics.values().stream().mapToInt(List::size).sum();
        if (total <= MAX_TOTAL_DIAGNOSTICS) {
            return;
        }
        List<Diagnostic> all = diagnostics.values().stream()
            .flatMap(List::stream)
            .sorted(Comparator.comparingInt(d -> d.severity().value()))
            .limit(MAX_TOTAL_DIAGNOSTICS)
            .toList();
        Set<Diagnostic> keep = new LinkedHashSet<>(all);
        for (Map.Entry<String, CopyOnWriteArrayList<Diagnostic>> entry : diagnostics.entrySet()) {
            List<Diagnostic> filtered = entry.getValue().stream()
                .filter(keep::contains)
                .collect(Collectors.toList());
            if (filtered.isEmpty()) {
                diagnostics.remove(entry.getKey());
            } else {
                entry.setValue(new CopyOnWriteArrayList<>(filtered));
            }
        }
    }

    public void clearDiagnostics(String fileUri) {
        diagnostics.remove(fileUri);
        synchronized (deliveredFingerprints) {
            deliveredFingerprints.remove(fileUri);
        }
        notifyListeners(fileUri, List.of());
    }

    /**
     * Clears only the cross-turn dedup state for a file, leaving the currently held diagnostics in
     * place.
     */
    public void clearDeliveredFingerprints(String fileUri) {
        synchronized (deliveredFingerprints) {
            deliveredFingerprints.remove(fileUri);
        }
    }

    public List<Diagnostic> getDiagnostics(String fileUri) {
        return diagnostics.getOrDefault(fileUri, new CopyOnWriteArrayList<>());
    }

    public Map<String, List<Diagnostic>> getAllDiagnostics() {
        return Map.copyOf(diagnostics);
    }

    public void clearAll() {
        diagnostics.clear();
        synchronized (deliveredFingerprints) {
            deliveredFingerprints.clear();
        }
    }

    private void notifyListeners(String fileUri, List<Diagnostic> diags) {
        for (DiagnosticListener listener : listeners) {
            try {
                listener.onDiagnosticsChanged(fileUri, diags);
            } catch (Exception _) {
                // Log but don't fail
            }
        }
    }

    private static String fingerprint(Diagnostic d) {



        return d.startLine() + ":" + d.startCharacter() + ":" + d.endLine() + ":" + d.endCharacter()
            + ":" + d.severity() + ":" + d.source() + ":" + d.code() + ":" + d.message();
    }

    public interface DiagnosticListener {
        void onDiagnosticsChanged(String fileUri, List<Diagnostic> diagnostics);
    }
}
