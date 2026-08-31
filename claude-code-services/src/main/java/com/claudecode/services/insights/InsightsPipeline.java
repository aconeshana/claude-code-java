package com.claudecode.services.insights;

import org.apache.commons.lang3.Strings;

import com.claudecode.api.LlmClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Predicate;
import java.util.function.Supplier;


public final class InsightsPipeline {

    private static final Logger LOG = LoggerFactory.getLogger(InsightsPipeline.class);

    private static final int META_BATCH_SIZE = 50;
    private static final int MAX_SESSIONS_TO_LOAD = 200;
    private static final int LOAD_BATCH_SIZE = 10;
    private static final int MAX_FACET_EXTRACTIONS = 50;
    private static final int FACET_CONCURRENCY = 50;

    private final InsightsStore store;
    private final FacetExtractor facetExtractor;
    private final InsightsGenerator insightsGenerator;
    private final Predicate<String> builtInCommandPredicate;

    public InsightsPipeline(
            LlmClient client,
            Supplier<String> modelSupplier,
            Predicate<String> builtInCommandPredicate) {
        this(new InsightsStore(),
            new FacetExtractor(client, modelSupplier),
            new InsightsGenerator(client, modelSupplier),
            builtInCommandPredicate);
    }

    /** Full injection including the active command inventory. */
    public InsightsPipeline(
            InsightsStore store,
            FacetExtractor facetExtractor,
            InsightsGenerator insightsGenerator,
            Predicate<String> builtInCommandPredicate) {
        this.store = store;
        this.facetExtractor = facetExtractor;
        this.insightsGenerator = insightsGenerator;
        this.builtInCommandPredicate = builtInCommandPredicate != null
            ? builtInCommandPredicate : _ -> false;
    }

    /** The pipeline's product: insights JSON per section + report path + aggregate. */
    public record Report(Map<String, JsonNode> insights, Path htmlPath, AggregatedData data) {}


    public Report generate() throws IOException {
        // Phase 1: lite scan — filesystem metadata only.
        List<InsightsStore.LiteSessionInfo> allScanned = store.scanAllSessions();
        long totalSessionsScanned = allScanned.size();

        // Phase 2: SessionMeta — cache first, parse only uncached (bounded).
        List<SessionMeta> allMetas = new ArrayList<>();
        List<InsightsStore.LiteSessionInfo> uncached = new ArrayList<>();
        for (int i = 0; i < allScanned.size(); i += META_BATCH_SIZE) {
            for (InsightsStore.LiteSessionInfo info
                    : allScanned.subList(i, Math.min(i + META_BATCH_SIZE, allScanned.size()))) {
                SessionMeta cached = store.loadCachedSessionMeta(info.sessionId());
                if (cached != null) {
                    allMetas.add(cached);
                } else if (uncached.size() < MAX_SESSIONS_TO_LOAD) {
                    uncached.add(info);
                }
            }
        }

        Map<String, SessionLog> logsForFacets = new HashMap<>();
        for (int i = 0; i < uncached.size(); i += LOAD_BATCH_SIZE) {
            for (InsightsStore.LiteSessionInfo info
                    : uncached.subList(i, Math.min(i + LOAD_BATCH_SIZE, uncached.size()))) {
                List<SessionLog> logs;
                try {
                    logs = TranscriptLogLoader.loadAllLogsFromSessionFile(
                        info.path(), null, builtInCommandPredicate);
                } catch (Exception _) {
                    continue;
                }
                for (SessionLog log : logs) {
                    if (isMetaSession(log) || !SessionMetaExtractor.hasValidDates(log)) continue;
                    SessionMeta meta = SessionMetaExtractor.toSessionMeta(log);
                    allMetas.add(meta);
                    store.saveSessionMeta(meta);
                    logsForFacets.put(meta.sessionId(), log);
                }
            }
        }

        // Branch dedupe (best branch per session), then most-recent-first.
        // (dedupe returns an immutable list — copy before sorting.)
        allMetas = new ArrayList<>(SessionMetaExtractor.deduplicateBranches(allMetas));
        Set<String> kept = new HashSet<>();
        for (SessionMeta meta : allMetas) kept.add(meta.sessionId());
        logsForFacets.keySet().retainAll(kept);
        allMetas.sort(Comparator.comparing(SessionMeta::startTime).reversed());

        // Substantive pre-filter (≥2 user messages, ≥1 minute).
        List<SessionMeta> substantiveMetas = allMetas.stream()
            .filter(m -> m.userMessageCount() >= 2 && m.durationMinutes() >= 1)
            .toList();

        // Phase 3: facets — cache first, LLM-extract the rest (bounded, concurrent).
        Map<String, SessionFacets> facets = new LinkedHashMap<>();
        List<Map.Entry<String, SessionLog>> toExtract = new ArrayList<>();
        for (SessionMeta meta : substantiveMetas) {
            SessionFacets cached = store.loadCachedFacets(meta.sessionId());
            if (cached != null) {
                facets.put(meta.sessionId(), cached);
            } else {
                SessionLog log = logsForFacets.get(meta.sessionId());
                if (log != null && toExtract.size() < MAX_FACET_EXTRACTIONS) {
                    toExtract.add(Map.entry(meta.sessionId(), log));
                }
            }
        }
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < toExtract.size(); i += FACET_CONCURRENCY) {
                List<Future<SessionFacets>> futures = new ArrayList<>();
                List<Map.Entry<String, SessionLog>> batch =
                    toExtract.subList(i, Math.min(i + FACET_CONCURRENCY, toExtract.size()));
                for (Map.Entry<String, SessionLog> entry : batch) {
                    futures.add(pool.submit(() ->
                        facetExtractor.extractFacets(entry.getValue(), entry.getKey())));
                }
                for (Future<SessionFacets> future : futures) {
                    SessionFacets extracted;
                    try {
                        extracted = future.get();
                    } catch (Exception _) {
                        continue;
                    }
                    if (extracted != null) {
                        facets.put(extracted.sessionId(), extracted);
                        store.saveFacets(extracted);
                    }
                }
            }
        }


        List<SessionMeta> substantiveSessions = substantiveMetas.stream()
            .filter(m -> !isMinimalSession(facets.get(m.sessionId())))
            .toList();
        Map<String, SessionFacets> substantiveFacets = new LinkedHashMap<>();
        facets.forEach((id, f) -> {
            if (!isMinimalSession(f)) substantiveFacets.put(id, f);
        });

        AggregatedData aggregated = InsightsAggregator
            .aggregate(substantiveSessions, substantiveFacets)
            .withTotalSessionsScanned(totalSessionsScanned);

        Map<String, JsonNode> insights = insightsGenerator.generate(aggregated, facets);

        String html = HtmlReportGenerator.generate(aggregated, insights);
        Path dataDir = store.dataDir();
        Files.createDirectories(dataDir);
        Path htmlPath = dataDir.resolve("report.html");
        Files.writeString(htmlPath, html);
        try {
            Files.setPosixFilePermissions(htmlPath,
                PosixFilePermissions.fromString("rw-------"));
        } catch (Exception _) {

        }

        LOG.debug("Insights report written to {}", htmlPath);
        return new Report(insights, htmlPath, aggregated);
    }


    static boolean isMetaSession(SessionLog log) {
        List<JsonNode> messages = log.messages();
        for (int i = 0; i < Math.min(5, messages.size()); i++) {
            JsonNode msg = messages.get(i);
            if (!Strings.CS.equals("user", msg.path("type").asText(null))) continue;
            JsonNode content = msg.path("message").path("content");
            if (content.isTextual()) {
                String text = content.asText();
                if (Strings.CS.contains(text, "RESPOND WITH ONLY A VALID JSON OBJECT")
                    || Strings.CS.contains(text, "record_facets")) {
                    return true;
                }
            }
        }
        return false;
    }


    static boolean isMinimalSession(SessionFacets facets) {
        if (facets == null || facets.goalCategories() == null) return false;
        List<String> nonZero = facets.goalCategories().entrySet().stream()
            .filter(e -> e.getValue() != null && e.getValue() > 0)
            .map(Map.Entry::getKey)
            .toList();
        return nonZero.size() == 1 && Strings.CS.equals("warmup_minimal", nonZero.getFirst());
    }
}
