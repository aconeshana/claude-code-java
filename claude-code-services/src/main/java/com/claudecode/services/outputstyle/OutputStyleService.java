package com.claudecode.services.outputstyle;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.core.prompt.OutputStyleConfig;
import com.claudecode.core.prompt.OutputStylePresets;
import com.claudecode.core.util.FrontmatterParser;
import com.claudecode.runtime.outputstyle.OutputStyleCatalog;
import com.claudecode.core.config.ClaudeConfigDirectories;
import com.claudecode.core.config.ClaudePaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Discovers, merges, and resolves built-in, plugin, user, project, and managed output styles.
 */
public final class OutputStyleService implements OutputStyleCatalog {

    private static final Logger LOG = LoggerFactory.getLogger(OutputStyleService.class);
    private static final String SUBDIR = "output-styles";

    private final Path configHome;
    private final Path managedRoot;
    private final Supplier<List<OutputStyleConfig>> pluginStyles;
    private final FrontmatterParser frontmatter = FrontmatterParser.shared();

    public OutputStyleService(Path configHome, Path managedRoot,
                              Supplier<List<OutputStyleConfig>> pluginStyles) {
        this.configHome = configHome;
        this.managedRoot = managedRoot;
        this.pluginStyles = pluginStyles != null ? pluginStyles : List::of;
    }

    public static OutputStyleService standard(
            Supplier<List<OutputStyleConfig>> pluginStyles) {
        return new OutputStyleService(
            ClaudePaths.currentClaudeHome(), ClaudePaths.managedRoot(), pluginStyles);
    }

    @Override
    public List<Entry> list(Path cwd) {
        List<Entry> entries = new ArrayList<>();
        allStyles(cwd).forEach((value, config) -> entries.add(config == null
            ? new Entry(value, "Default",
                "Claude completes coding tasks efficiently and provides concise responses")
            : new Entry(value, config.name(), config.description())));
        return List.copyOf(entries);
    }

    /** Returns the effective ordered style map; {@code default} intentionally maps to null. */
    public Map<String, OutputStyleConfig> allStyles(Path cwd) {
        LinkedHashMap<String, OutputStyleConfig> styles =
            new LinkedHashMap<>(OutputStylePresets.BUILT_IN);

        safePluginStyles().forEach(style -> styles.put(style.name(), style));

        List<OutputStyleConfig> custom = loadCustomStyles(cwd);
        applySource(styles, custom, OutputStyleConfig.Source.USER_SETTINGS);
        applySource(styles, custom, OutputStyleConfig.Source.PROJECT_SETTINGS);
        applySource(styles, custom, OutputStyleConfig.Source.POLICY_SETTINGS);
        return Collections.unmodifiableMap(styles);
    }

    /** Forced plugin styles win; otherwise resolves the exact configured name. */
    public OutputStyleConfig resolve(Path cwd, String selectedName) {
        Map<String, OutputStyleConfig> styles = allStyles(cwd);
        List<OutputStyleConfig> forced = styles.values().stream()
            .filter(style -> style != null
                && style.source() == OutputStyleConfig.Source.PLUGIN
                && style.forceForPlugin())
            .toList();
        if (!forced.isEmpty()) {
            if (forced.size() > 1) {
                LOG.warn("Multiple plugins have forced output styles: {}. Using: {}",
                    forced.stream().map(OutputStyleConfig::name).toList(), forced.getFirst().name());
            }
            return forced.getFirst();
        }
        String name = StringUtils.isEmpty(selectedName)
            ? OutputStylePresets.DEFAULT_OUTPUT_STYLE_NAME : selectedName;
        return styles.get(name);
    }

    private List<OutputStyleConfig> safePluginStyles() {
        try {
            List<OutputStyleConfig> styles = pluginStyles.get();
            return styles != null ? styles : List.of();
        } catch (RuntimeException e) {
            LOG.debug("Failed to load plugin output styles: {}", e.getMessage());
            return List.of();
        }
    }

    private List<OutputStyleConfig> loadCustomStyles(Path cwd) {
        List<SourceDirectory> dirs = new ArrayList<>();
        dirs.add(new SourceDirectory(
            managedRoot.resolve(".claude").resolve(SUBDIR),
            OutputStyleConfig.Source.POLICY_SETTINGS));
        dirs.add(new SourceDirectory(
            configHome.resolve(SUBDIR), OutputStyleConfig.Source.USER_SETTINGS));
        for (Path projectDir : ClaudeConfigDirectories.projectDirs(cwd, SUBDIR)) {
            dirs.add(new SourceDirectory(projectDir, OutputStyleConfig.Source.PROJECT_SETTINGS));
        }

        List<OutputStyleConfig> styles = new ArrayList<>();
        Set<Path> seen = new HashSet<>();
        for (SourceDirectory dir : dirs) {
            for (Path file : markdownFiles(dir.path())) {
                Path identity = normalize(file);
                if (!seen.add(identity)) continue;
                OutputStyleConfig style = parseCustom(file, dir.source());
                if (style != null) styles.add(style);
            }
        }
        return List.copyOf(styles);
    }

    private OutputStyleConfig parseCustom(Path file, OutputStyleConfig.Source source) {
        try {
            FrontmatterParser.ParseResult parsed = frontmatter.parse(Files.readString(file));
            Map<String, Object> fm = parsed.metadata();
            String fileName = stripMarkdownSuffix(file.getFileName().toString());
            String name = scalar(fm.get("name"));
            if (StringUtils.isBlank(name)) name = fileName;
            String description = scalar(fm.get("description"));
            if (StringUtils.isBlank(description)) {
                description = extractDescription(parsed.body(), "Custom " + fileName + " output style");
            }
            boolean keepCoding = parseOptionalBoolean(fm.get("keep-coding-instructions"));
            if (fm.containsKey("force-for-plugin")) {
                LOG.warn("Output style \"{}\" has force-for-plugin set outside a plugin; ignoring", name);
            }
            return new OutputStyleConfig(name, description,
                parsed.body() == null ? "" : parsed.body().trim(), keepCoding, source, false);
        } catch (Exception e) {
            LOG.warn("Failed to load output style from {}: {}", file, e.getMessage());
            return null;
        }
    }

    private static void applySource(Map<String, OutputStyleConfig> target,
                                    List<OutputStyleConfig> styles,
                                    OutputStyleConfig.Source source) {
        styles.stream().filter(style -> style.source() == source)
            .forEach(style -> target.put(style.name(), style));
    }

    private static List<Path> markdownFiles(Path dir) {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> stream = Files.walk(dir)) {
            return stream.filter(Files::isRegularFile)
                .filter(path -> Strings.CI.endsWith(path.getFileName().toString(), ".md"))
                .sorted()
                .toList();
        } catch (IOException e) {
            LOG.debug("Failed to scan output-style directory {}: {}", dir, e.getMessage());
            return List.of();
        }
    }

    private static String scalar(Object value) {
        if (value == null) return null;
        if (value instanceof String string) return string.trim();
        if (value instanceof Number || value instanceof Boolean) return String.valueOf(value);
        return null;
    }

    private static boolean parseOptionalBoolean(Object value) {
        if (value instanceof Boolean bool) return bool;
        return value instanceof String string && Strings.CS.equals("true", string);
    }

    private static String extractDescription(String content, String fallback) {
        if (content != null) {
            for (String line : content.split("\\n")) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    String text = trimmed.replaceFirst("^#+\\s+", "");
                    return text.length() > 100 ? text.substring(0, 97) + "..." : text;
                }
            }
        }
        return fallback;
    }

    private static String stripMarkdownSuffix(String name) {
        return Strings.CI.endsWith(name, ".md") ? name.substring(0, name.length() - 3) : name;
    }

    private static Path normalize(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException _) {
            return path.toAbsolutePath().normalize();
        }
    }

    private record SourceDirectory(Path path, OutputStyleConfig.Source source) {}
}
