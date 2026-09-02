package com.claudecode.tools.plan;

import com.claudecode.core.plan.PlanCatalogContext;
import com.claudecode.core.plan.PlanHistoryEntry;
import com.claudecode.core.serialization.JsonUtils;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/** Versioned sidecar storage for one main-session or agent plan scope. */
final class PlanCatalogStore {

    private static final int VERSION = 1;
    private static final int HISTORY_LIMIT = 5;
    private static final ConcurrentHashMap<Path, ReentrantLock> PROCESS_LOCKS =
        new ConcurrentHashMap<>();

    private final Path directory;
    private final String baseName;
    private final Path manifest;
    private final Path lockFile;
    private final Pattern numberedPlanPattern;

    PlanCatalogStore(Path directory, String baseName) {
        this.directory = directory.toAbsolutePath().normalize();
        this.baseName = baseName;
        this.manifest = this.directory.resolve(baseName + ".plans.json");
        this.lockFile = this.directory.resolve("." + baseName + ".plans.lock");
        this.numberedPlanPattern = Pattern.compile(
            Pattern.quote(baseName) + "-p(\\d{3,})\\.md");
    }

    Path manifestPath() {
        return manifest;
    }

    Path legacyPath() {
        return directory.resolve(baseName + ".md");
    }

    Optional<Path> activePathIfPresent() {
        if (!Files.isRegularFile(manifest)) return Optional.empty();
        try {
            Catalog catalog = readManifest();
            return find(catalog, catalog.activePlanId()).map(this::resolvePlanPath);
        } catch (IOException | RuntimeException _) {
            return Optional.empty();
        }
    }

    boolean copyCatalogTo(PlanCatalogStore target) throws IOException {
        Catalog source = readManifest();
        return target.withMutationLock(() -> {
            List<PlanRecord> rewritten = new ArrayList<>(source.plans().size());
            for (PlanRecord record : source.plans()) {
                Path sourcePath = resolvePlanPath(record);
                Path targetPath = target.planPath(record.ordinal());
                if (Files.isRegularFile(sourcePath)) {
                    Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
                }
                rewritten.add(new PlanRecord(
                    record.planId(), record.ordinal(), targetPath.getFileName().toString(),
                    record.title(), record.summary(), record.status(), record.createdAt(),
                    record.approvedAt(), record.revisesPlanId()));
            }
            target.writeManifest(new Catalog(
                VERSION, source.activePlanId(), source.nextOrdinal(), rewritten));
            return true;
        });
    }

    PlanCatalogContext activate() throws IOException {
        return withMutationLock(() -> {
            Catalog catalog = loadForMutation();
            PlanRecord active = find(catalog, catalog.activePlanId()).orElse(null);
            if (active == null) {
                catalog = allocate(catalog, 1);
            } else if (!Status.DRAFT.name().equals(active.status())) {
                catalog = allocate(catalog, catalog.nextOrdinal());
            }
            writeManifest(catalog);
            return context(catalog, true);
        });
    }

    PlanCatalogContext current(boolean exposeCatalog) throws IOException {
        if (!Files.isRegularFile(manifest)) {
            Path path = legacyPath();
            return new PlanCatalogContext(
                null, null, path.toString(), Files.isRegularFile(path), false, List.of());
        }
        Catalog catalog = readManifest();
        return exposeCatalog ? context(catalog, true) : dormantContext(catalog);
    }

    PlanFiles.PlanCompletion complete(String content, String revisesPlanId,
                                      boolean exposeCatalog) throws IOException {
        return withMutationLock(() -> {
            Catalog catalog = Files.isRegularFile(manifest)
                ? loadForMutation() : null;
            if (catalog == null) return new PlanFiles.PlanCompletion(null, null, null, null);
            PlanRecord active = find(catalog, catalog.activePlanId()).orElse(null);
            if (active == null) return new PlanFiles.PlanCompletion(null, null, null, null);

            String revisionError = validateRevisionTarget(catalog, revisesPlanId);
            if (revisionError != null) throw new IllegalArgumentException(revisionError);
            boolean blank = StringUtils.isBlank(content);
            PlanMetadataExtractor.Metadata metadata =
                PlanMetadataExtractor.extract(content, active.planId());
            Instant now = Instant.now();
            List<PlanRecord> updated = new ArrayList<>(catalog.plans().size());
            for (PlanRecord record : catalog.plans()) {
                if (record.planId().equals(active.planId())) {
                    updated.add(new PlanRecord(
                        record.planId(), record.ordinal(), record.fileName(),
                        metadata.title(), metadata.summary(),
                        blank ? Status.ABANDONED.name() : Status.APPROVED.name(),
                        record.createdAt(), blank ? null : now, revisesPlanId));
                } else if (!blank && revisesPlanId != null
                        && Strings.CS.equals(record.planId(), revisesPlanId)) {
                    updated.add(new PlanRecord(
                        record.planId(), record.ordinal(), record.fileName(),
                        record.title(), record.summary(), Status.SUPERSEDED.name(),
                        record.createdAt(), record.approvedAt(), record.revisesPlanId()));
                } else {
                    updated.add(record);
                }
            }
            Catalog completed = new Catalog(
                VERSION, catalog.activePlanId(), catalog.nextOrdinal(), updated);
            writeManifest(completed);
            return new PlanFiles.PlanCompletion(
                exposeCatalog ? active.planId() : null,
                exposeCatalog ? metadata.title() : null,
                exposeCatalog ? (blank ? Status.ABANDONED.name() : Status.APPROVED.name()) : null,
                exposeCatalog ? revisesPlanId : null);
        });
    }

    String validateRevisionTarget(String revisesPlanId) {
        if (StringUtils.isBlank(revisesPlanId)) return null;
        if (!Files.isRegularFile(manifest)) {
            return "Unknown prior plan ID " + revisesPlanId + ".";
        }
        try {
            return validateRevisionTarget(readManifest(), revisesPlanId);
        } catch (IOException | RuntimeException _) {
            return "Plan catalog is unavailable; omit revisesPlanId and retry.";
        }
    }

    private String validateRevisionTarget(Catalog catalog, String revisesPlanId) {
        if (StringUtils.isBlank(revisesPlanId)) return null;
        PlanRecord active = find(catalog, catalog.activePlanId()).orElse(null);
        PlanRecord target = find(catalog, revisesPlanId).orElse(null);
        if (target == null) return "Unknown prior plan ID " + revisesPlanId + ".";
        if (active != null && target.planId().equals(active.planId())) {
            return "revisesPlanId cannot reference the current plan " + active.planId() + ".";
        }
        if (active != null && target.ordinal() >= active.ordinal()) {
            return "revisesPlanId must reference an older plan.";
        }
        if (Status.DRAFT.name().equals(target.status())
                || Status.ABANDONED.name().equals(target.status())) {
            return "revisesPlanId must reference a completed prior plan.";
        }
        return null;
    }

    private Catalog loadForMutation() throws IOException {
        if (Files.isRegularFile(manifest)) {
            try {
                return readManifest();
            } catch (IOException | RuntimeException _) {
                quarantineCorruptManifest();
            }
        }
        return reconstruct();
    }

    private Catalog reconstruct() throws IOException {
        Files.createDirectories(directory);
        List<PlanRecord> records = new ArrayList<>();
        Path legacy = legacyPath();
        if (Files.isRegularFile(legacy)) records.add(imported(1, legacy));
        try (var files = Files.list(directory)) {
            files.filter(Files::isRegularFile).forEach(path -> {
                Matcher matcher = numberedPlanPattern.matcher(path.getFileName().toString());
                if (!matcher.matches()) return;
                try {
                    int ordinal = Integer.parseInt(matcher.group(1));
                    if (ordinal >= 2) records.add(imported(ordinal, path));
                } catch (NumberFormatException | IOException _) {
                    // Leave unrecognized or unreadable files untouched.
                }
            });
        }
        records.sort(Comparator.comparingInt(PlanRecord::ordinal));
        int next = records.isEmpty() ? 1 : records.getLast().ordinal() + 1;
        String active = records.isEmpty() ? null : records.getLast().planId();
        return new Catalog(VERSION, active, next, records);
    }

    private PlanRecord imported(int ordinal, Path path) throws IOException {
        String planId = planId(ordinal);
        PlanMetadataExtractor.Metadata metadata =
            PlanMetadataExtractor.extract(Files.readString(path), planId);
        return new PlanRecord(
            planId, ordinal, path.getFileName().toString(), metadata.title(), metadata.summary(),
            Status.IMPORTED.name(), Files.getLastModifiedTime(path).toInstant(), null, null);
    }

    private Catalog allocate(Catalog catalog, int requestedOrdinal) {
        int ordinal = Math.max(1, requestedOrdinal);
        while (containsOrdinal(catalog, ordinal) || Files.exists(planPath(ordinal))) ordinal++;
        PlanRecord record = new PlanRecord(
            planId(ordinal), ordinal, planPath(ordinal).getFileName().toString(),
            null, null, Status.DRAFT.name(), Instant.now(), null, null);
        List<PlanRecord> plans = new ArrayList<>(catalog.plans());
        plans.add(record);
        return new Catalog(VERSION, record.planId(), ordinal + 1, plans);
    }

    private boolean containsOrdinal(Catalog catalog, int ordinal) {
        return catalog.plans().stream().anyMatch(record -> record.ordinal() == ordinal);
    }

    private PlanCatalogContext context(Catalog catalog, boolean exposeCatalog) {
        PlanRecord active = find(catalog, catalog.activePlanId()).orElse(null);
        if (active == null) {
            Path path = legacyPath();
            return new PlanCatalogContext(
                null, null, path.toString(), Files.isRegularFile(path), false, List.of());
        }
        Path activePath = resolvePlanPath(active);
        List<PlanHistoryEntry> history = catalog.plans().stream()
            .filter(record -> !record.planId().equals(active.planId()))
            .filter(record -> !Status.DRAFT.name().equals(record.status()))
            .filter(record -> !Status.ABANDONED.name().equals(record.status()))
            .sorted(Comparator.comparingInt(PlanRecord::ordinal).reversed())
            .limit(HISTORY_LIMIT)
            .map(record -> new PlanHistoryEntry(
                record.planId(), record.status(), value(record.title(), "Plan " + record.planId()),
                value(record.summary(), ""), resolvePlanPath(record).toString()))
            .toList();
        return new PlanCatalogContext(
            exposeCatalog ? active.planId() : null,
            exposeCatalog ? active.status() : null,
            activePath.toString(), Files.isRegularFile(activePath),
            Status.DRAFT.name().equals(active.status()) && Files.isRegularFile(activePath),
            exposeCatalog ? history : List.of());
    }

    private PlanCatalogContext dormantContext(Catalog catalog) {
        return context(catalog, false);
    }

    private Catalog readManifest() throws IOException {
        Catalog catalog = JsonUtils.getMapper().readValue(
            Files.readString(manifest, StandardCharsets.UTF_8), Catalog.class);
        validateCatalog(catalog);
        return catalog;
    }

    private void validateCatalog(Catalog catalog) throws IOException {
        if (catalog.version() != VERSION || catalog.nextOrdinal() < 1) {
            throw new IOException("Unsupported or invalid plan catalog version");
        }
        Set<String> planIds = new HashSet<>();
        Set<Integer> ordinals = new HashSet<>();
        Set<String> fileNames = new HashSet<>();
        int maxOrdinal = 0;
        for (PlanRecord record : catalog.plans()) {
            validateFileName(record.fileName());
            if (record.ordinal() < 1
                    || !Strings.CS.equals(planId(record.ordinal()), record.planId())
                    || !Strings.CS.equals(
                        planPath(record.ordinal()).getFileName().toString(), record.fileName())
                    || record.createdAt() == null
                    || !planIds.add(record.planId())
                    || !ordinals.add(record.ordinal())
                    || !fileNames.add(record.fileName())) {
                throw new IOException("Invalid plan catalog record");
            }
            try {
                Status.valueOf(record.status());
            } catch (IllegalArgumentException | NullPointerException e) {
                throw new IOException("Invalid plan catalog status", e);
            }
            maxOrdinal = Math.max(maxOrdinal, record.ordinal());
        }
        if (catalog.nextOrdinal() <= maxOrdinal
                || (catalog.plans().isEmpty() && catalog.activePlanId() != null)
                || (!catalog.plans().isEmpty()
                    && find(catalog, catalog.activePlanId()).isEmpty())) {
            throw new IOException("Invalid plan catalog active plan or ordinal");
        }
    }

    private void writeManifest(Catalog catalog) throws IOException {
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, "." + baseName + "-", ".plans.tmp");
        try {
            Files.writeString(temporary, JsonUtils.toPrettyJson(catalog), StandardCharsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(temporary, manifest,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException _) {
                Files.move(temporary, manifest, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void quarantineCorruptManifest() throws IOException {
        String stamp = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
            .withZone(ZoneOffset.UTC).format(Instant.now());
        Path quarantine = Files.createTempFile(directory,
            manifest.getFileName() + ".corrupt-" + stamp + "-", ".json");
        Files.move(manifest, quarantine, StandardCopyOption.REPLACE_EXISTING);
    }

    private <T> T withMutationLock(IOSupplier<T> action) throws IOException {
        Files.createDirectories(directory);
        Path normalizedLock = lockFile.toAbsolutePath().normalize();
        ReentrantLock processLock = PROCESS_LOCKS.computeIfAbsent(
            normalizedLock, _ -> new ReentrantLock());
        processLock.lock();
        try (FileChannel channel = FileChannel.open(lockFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock _ = channel.lock()) {
            return action.get();
        } finally {
            processLock.unlock();
        }
    }

    private Path planPath(int ordinal) {
        return ordinal == 1
            ? legacyPath()
            : directory.resolve(baseName + "-p" + String.format("%03d", ordinal) + ".md");
    }

    private Path resolvePlanPath(PlanRecord record) {
        validateFileName(record.fileName());
        return directory.resolve(record.fileName()).toAbsolutePath().normalize();
    }

    private static void validateFileName(String fileName) {
        if (StringUtils.isBlank(fileName)
                || !Path.of(fileName).getFileName().toString().equals(fileName)
                || Strings.CS.contains(fileName, "/")
                || Strings.CS.contains(fileName, "\\")) {
            throw new IllegalArgumentException("Invalid plan catalog file name");
        }
    }

    private static Optional<PlanRecord> find(Catalog catalog, String planId) {
        if (catalog == null || planId == null) return Optional.empty();
        return catalog.plans().stream()
            .filter(record -> record.planId().equals(planId)).findFirst();
    }

    private static String planId(int ordinal) {
        return "P" + String.format("%03d", ordinal);
    }

    private static String value(String value, String fallback) {
        return StringUtils.isBlank(value) ? fallback : value;
    }

    private enum Status { DRAFT, APPROVED, IMPORTED, SUPERSEDED, ABANDONED }

    private record Catalog(
        int version,
        String activePlanId,
        int nextOrdinal,
        List<PlanRecord> plans
    ) {
        private Catalog {
            plans = plans == null ? List.of() : List.copyOf(plans);
        }
    }

    private record PlanRecord(
        String planId,
        int ordinal,
        String fileName,
        String title,
        String summary,
        String status,
        Instant createdAt,
        Instant approvedAt,
        String revisesPlanId
    ) {}

    @FunctionalInterface
    private interface IOSupplier<T> {
        T get() throws IOException;
    }
}
