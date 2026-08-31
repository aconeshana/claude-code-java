package com.claudecode.api;

import com.claudecode.core.config.ClaudePaths;
import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.model.CustomModelCatalog;
import com.claudecode.core.model.CustomModelConfig;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Atomic, owner-readable persistence for user-defined model endpoints.
 */
@Explanation("Separate model.json persistence for user-defined endpoints")
public final class CustomModelJsonStore implements CustomModelCatalog {
    private static final int FORMAT_VERSION = 1;
    private static final Set<PosixFilePermission> OWNER_ONLY = Set.of(
        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private final Path file;

    public CustomModelJsonStore() {
        this(ClaudePaths.MODEL_JSON);
    }

    public CustomModelJsonStore(Path file) {
        this.file = file.toAbsolutePath().normalize();
    }

    @Override
    public synchronized List<CustomModelConfig> list() {
        if (!Files.exists(file)) return List.of();
        try {
            CatalogDocument document = JsonUtils.getMapper().readValue(file.toFile(), CatalogDocument.class);
            if (document.version() != FORMAT_VERSION) {
                throw new CustomModelConfigException(
                    "Unsupported model.json version: " + document.version(), null);
            }
            List<CustomModelConfig> models = document.models() == null ? List.of() : document.models();
            return models.stream()
                .sorted(Comparator.comparing(CustomModelConfig::modelName, String.CASE_INSENSITIVE_ORDER))
                .toList();
        } catch (CustomModelConfigException e) {
            throw e;
        } catch (Exception e) {
            throw new CustomModelConfigException("Unable to read custom model configuration", e);
        }
    }

    @Override
    public synchronized Optional<CustomModelConfig> find(String modelName) {
        if (StringUtils.isBlank(modelName)) return Optional.empty();
        return list().stream().filter(model -> model.modelName().equals(modelName)).findFirst();
    }

    @Override
    public synchronized void save(CustomModelConfig model) {
        Map<String, CustomModelConfig> byName = new LinkedHashMap<>();
        for (CustomModelConfig existing : list()) byName.put(existing.modelName(), existing);
        byName.put(model.modelName(), model);
        write(new CatalogDocument(FORMAT_VERSION, new ArrayList<>(byName.values())));
    }

    @Override
    public synchronized boolean remove(String modelName) {
        if (StringUtils.isBlank(modelName)) return false;
        List<CustomModelConfig> existing = list();
        List<CustomModelConfig> remaining = existing.stream()
            .filter(model -> !model.modelName().equals(modelName))
            .toList();
        if (remaining.size() == existing.size()) return false;
        write(new CatalogDocument(FORMAT_VERSION, remaining));
        return true;
    }

    private void write(CatalogDocument document) {
        Path parent = file.getParent();
        if (parent == null) {
            throw new CustomModelConfigException(
                "Custom model configuration path must have a parent directory", null);
        }
        Path temp = null;
        try {
            Files.createDirectories(parent);
            temp = Files.createTempFile(parent, ".model-", ".json.tmp");
            JsonUtils.getMapper().writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), document);
            applyOwnerOnlyPermissions(temp);
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException _) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            applyOwnerOnlyPermissions(file);
        } catch (Exception e) {
            throw new CustomModelConfigException("Unable to save custom model configuration", e);
        } finally {
            if (temp != null) {
                try { Files.deleteIfExists(temp); } catch (IOException _) { }
            }
        }
    }

    private static void applyOwnerOnlyPermissions(Path target) throws IOException {
        try {
            Files.setPosixFilePermissions(target, OWNER_ONLY);
        } catch (UnsupportedOperationException _) {
            // Windows and non-POSIX filesystems have no POSIX permission view.
        }
    }

    private record CatalogDocument(int version, List<CustomModelConfig> models) {
        @JsonCreator
        private CatalogDocument(
                @JsonProperty("version") int version,
                @JsonProperty("models") List<CustomModelConfig> models) {
            this.version = version;
            this.models = models == null ? List.of() : List.copyOf(models);
        }
    }
}
