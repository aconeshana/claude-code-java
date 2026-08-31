package com.claudecode.tools.skills;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.core.util.FrontmatterParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Collator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Loads skills from multiple sources: managed, user, project, bundled, and MCP paths.
 */
public class SkillLoader {

    private static final Logger LOG = LoggerFactory.getLogger(SkillLoader.class);
    private static final String SKILL_EXTENSION = ".md";
    private static final String SKILL_FILE = "SKILL.md";
    private static final long MAX_SKILL_FILE_BYTES = 1_000_000L;

    private final FrontmatterParser frontmatterParser;
    private final List<SourcePath> sourcePaths;
    private final Object cacheLock = new Object();
    private volatile List<Skill> cachedSkills;
    private volatile boolean enabled = true;
    private enum SourceLayout { SKILLS, LEGACY_COMMANDS }
    private record SourcePath(Skill.SkillSource source, Path directory, SourceLayout layout) {}

    /**
     * Skill roots contributed by enabled plugins. Unlike {@link #sourcePaths},
     * plugins each bring their own root, and skill
     * names are namespaced {@code <plugin>:<skill>} — so this is a separate,
     * wholesale-replaceable channel (swapped on {@code /reload-plugins}).
     */
    private volatile List<PluginSkillRoot> pluginSkillRoots = List.of();

    /** Parsed {@code commands/} entries contributed by enabled plugins. */
    private volatile List<Skill> pluginCommandSkills = List.of();

    /** Built-in skills registered before enabled plugin skills. */
    private volatile List<Skill> bundledSkillsBeforePlugins = List.of();

    /** Built-in skills shipped inside the CLI binary, appended after plugins. */
    private volatile List<Skill> bundledSkills = List.of();

    /** Skills discovered from connected MCP servers, appended after local inventory. */
    private volatile List<Skill> mcpSkills = List.of();

    /** Nested project skills discovered by file operations during this session. */
    private final Map<String, Skill> dynamicProjectSkills = new LinkedHashMap<>();

    /**
     * One plugin's skill root.
     */
    public record PluginSkillRoot(
            String pluginName, Path directory, String directSkillName) {
        public PluginSkillRoot(String pluginName, Path directory) {
            this(pluginName, directory, null);
        }
    }

    public SkillLoader() {
        this(FrontmatterParser.shared(), new LinkedHashMap<>());
    }

    public SkillLoader(FrontmatterParser frontmatterParser, Map<Skill.SkillSource, Path> sourcePaths) {
        this.frontmatterParser = frontmatterParser;
        this.sourcePaths = new ArrayList<>();
        sourcePaths.forEach((source, directory) ->
            this.sourcePaths.add(new SourcePath(source, directory, SourceLayout.SKILLS)));
    }

    /**
     * Configure a source path for skill loading.
     */
    public void addSource(Skill.SkillSource source, Path directory) {
        SourcePath candidate = new SourcePath(source, directory, SourceLayout.SKILLS);
        synchronized (cacheLock) {
            if (!sourcePaths.contains(candidate)) {
                sourcePaths.add(candidate);
                cachedSkills = null;
            }
        }
    }

    /** Adds one legacy {@code .claude/commands} root as model-invocable skills. */
    public void addLegacyCommandsSource(Skill.SkillSource source, Path directory) {
        SourcePath candidate = new SourcePath(source, directory, SourceLayout.LEGACY_COMMANDS);
        synchronized (cacheLock) {
            if (!sourcePaths.contains(candidate)) {
                sourcePaths.add(candidate);
                cachedSkills = null;
            }
        }
    }

    /**
     * Enables or disables every model-invocable skill source atomically.
     * Plugin refreshes may continue replacing their source lists while the
     * loader is disabled; {@link #loadAll} remains empty until re-enabled.
     */
    public void setEnabled(boolean enabled) {
        synchronized (cacheLock) {
            if (this.enabled != enabled) {
                this.enabled = enabled;
                cachedSkills = null;
            }
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Replaces the plugin skill roots in one atomic swap ({@code null} clears).
     */
    public void setPluginSkillRoots(List<PluginSkillRoot> roots) {
        synchronized (cacheLock) {
            this.pluginSkillRoots = roots == null ? List.of() : List.copyOf(roots);
            cachedSkills = null;
        }
    }

    /**
     * Replaces parsed plugin {@code commands/} in one atomic swap. The plugin
     * runtime owns parsing because command frontmatter has plugin-specific
     * variables and metadata; this loader owns their model-facing inventory.
     */
    public void setPluginCommandSkills(List<Skill> commands) {
        synchronized (cacheLock) {
            this.pluginCommandSkills = commands == null ? List.of() : List.copyOf(commands);
            cachedSkills = null;
        }
    }

    /** Replaces the built-in skill registry in one atomic swap. */
    public void setBundledSkills(List<Skill> skills) {
        synchronized (cacheLock) {
            this.bundledSkills = skills == null ? List.of() : List.copyOf(skills);
            cachedSkills = null;
        }
    }

    /** Replaces the built-in registry segment loaded before plugin skills. */
    public void setBundledSkillsBeforePlugins(List<Skill> skills) {
        synchronized (cacheLock) {
            this.bundledSkillsBeforePlugins = skills == null ? List.of() : List.copyOf(skills);
            cachedSkills = null;
        }
    }

    /**
     * Replaces the connected MCP skill snapshot atomically.
     */
    public void setMcpSkills(List<Skill> skills) {
        synchronized (cacheLock) {
            this.mcpSkills = skills == null ? List.of() : List.copyOf(skills);
            cachedSkills = null;
        }
    }


    void mergeDynamicProjectSkills(List<Skill> skills, Path workingDirectory) {
        if (skills == null || skills.isEmpty()) return;
        synchronized (dynamicProjectSkills) {
            for (Skill skill : skills) {
                dynamicProjectSkills.put(skill.name(), withDynamicScope(skill, workingDirectory));
            }
        }
        invalidateCache();
    }

    /**
     * Invalidates the parsed inventory after an explicit filesystem refresh.
     * Runtime source replacements call this automatically; callers only need
     * it when files below an unchanged source root may have changed.
     */
    public void invalidateCache() {
        synchronized (cacheLock) {
            cachedSkills = null;
        }
    }

    /**
     * Load all skills from all configured sources.
     */
    public List<Skill> loadAll() {
        if (!enabled) return List.of();
        List<Skill> snapshot = cachedSkills;
        if (snapshot != null) return snapshot;
        synchronized (cacheLock) {
            if (!enabled) return List.of();
            snapshot = cachedSkills;
            if (snapshot == null) {
                snapshot = loadAllUncached();
                cachedSkills = snapshot;
            }
            return snapshot;
        }
    }

    private List<Skill> loadAllUncached() {
        List<Skill> skills = new ArrayList<>();
        Set<String> seenFileIds = new HashSet<>();

        for (SourcePath entry : sourcePaths) {
            List<Skill> loaded = entry.layout() == SourceLayout.LEGACY_COMMANDS
                ? loadLegacyCommandsFromDirectory(entry.directory(), entry.source())
                : loadFromDirectory(entry.directory(), entry.source());
            for (Skill skill : loaded) {
                addIfNewPhysicalFile(skills, seenFileIds, skill);
            }
        }
        skills.addAll(bundledSkillsBeforePlugins);
        skills.addAll(pluginCommandSkills);
        for (PluginSkillRoot root : pluginSkillRoots) {
            for (Skill skill : loadPluginSkills(root)) {
                addIfNewPhysicalFile(skills, seenFileIds, skill);
            }
        }
        int firstBuiltin = bundledSkills.size();
        for (int i = 0; i < bundledSkills.size(); i++) {
            if (bundledSkills.get(i).source() == Skill.SkillSource.BUILTIN) {
                firstBuiltin = i;
                break;
            }
            skills.add(bundledSkills.get(i));
        }

        // Dynamic skills sit immediately before compiled builtin commands.

        // its description with the path scope. A base-name collision preserves
        // both commands by qualifying the nested one as <scope>:<name>.
        Set<String> baseNames = new HashSet<>();
        for (Skill skill : skills) baseNames.add(skill.name());
        List<Skill> dynamicSnapshot;
        synchronized (dynamicProjectSkills) {
            dynamicSnapshot = List.copyOf(dynamicProjectSkills.values());
        }
        for (Skill skill : dynamicSnapshot) {
            String scope = dynamicScope(skill);
            if (scope == null) {
                if (baseNames.add(skill.name())) skills.add(skill);
                continue;
            }
            if (baseNames.contains(skill.name())) {
                String scopedName = scope + ":" + skill.name();
                if (!baseNames.add(scopedName)) continue;
                skills.add(withDynamicIdentity(
                    skill,
                    scopedName,
                    skill.description() + " (scoped to " + scope
                        + "/ — use this instead of the unscoped \"" + skill.name()
                        + "\" skill when the files being changed are under " + scope + "/)",
                    skill.name()));
            } else {
                baseNames.add(skill.name());
                skills.add(withDynamicIdentity(
                    skill,
                    skill.name(),
                    skill.description() + " (from " + scope
                        + "/.claude/skills — applies when working on files under "
                        + scope + "/)",
                    null));
            }
        }
        for (int i = firstBuiltin; i < bundledSkills.size(); i++) {
            skills.add(bundledSkills.get(i));
        }
        skills.addAll(mcpSkills);

        return List.copyOf(skills);
    }

    private static Skill withDynamicScope(Skill skill, Path workingDirectory) {
        String scope = nestedScope(skill, workingDirectory);
        if (scope == null) return skill;
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (skill.frontmatter() != null) metadata.putAll(skill.frontmatter());
        metadata.put("dynamicScope", scope);
        return new Skill(
            skill.name(), skill.description(), skill.allowedTools(), skill.content(),
            skill.sourceFile(), skill.source(), skill.model(), skill.effort(),
            skill.context(), Map.copyOf(metadata));
    }

    private static String nestedScope(Skill skill, Path workingDirectory) {
        if (skill == null || skill.sourceFile() == null || workingDirectory == null) return null;
        Path skillRoot = skill.sourceFile().toAbsolutePath().normalize().getParent();
        if (skillRoot == null) return null;
        Path cursor = skillRoot;
        Path containingDirectory = null;
        while (cursor != null) {
            Path name = cursor.getFileName();
            if (name != null && Strings.CS.equals(".claude", name.toString())) {
                containingDirectory = cursor.getParent();
                break;
            }
            cursor = cursor.getParent();
        }
        if (containingDirectory == null) return null;
        Path cwd = workingDirectory.toAbsolutePath().normalize();
        if (!containingDirectory.startsWith(cwd)) return null;
        Path relative = cwd.relativize(containingDirectory);
        if (relative.getNameCount() == 0 || relative.toString().isEmpty()) return null;
        String scope = relative.toString().replace(File.separatorChar, '/');
        return Strings.CS.startsWith(scope, "../") || Path.of(scope).isAbsolute() ? null : scope;
    }

    private static String dynamicScope(Skill skill) {
        if (skill.frontmatter() == null) return null;
        Object value = skill.frontmatter().get("dynamicScope");
        return value instanceof String text && !StringUtils.isBlank(text) ? text : null;
    }

    private static Skill withDynamicIdentity(
            Skill skill, String name, String description, String unqualifiedName) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (skill.frontmatter() != null) metadata.putAll(skill.frontmatter());
        if (unqualifiedName != null) metadata.put("unqualifiedName", unqualifiedName);
        return new Skill(
            name, description, skill.allowedTools(), skill.content(), skill.sourceFile(),
            skill.source(), skill.model(), skill.effort(), skill.context(), Map.copyOf(metadata));
    }

    /**
     * Loads the deprecated recursive {@code /commands/} layout. File paths are
     * converted to colon-separated command names ({@code ccpanes/workspace.md}
     * → {@code ccpanes:workspace}). A directory containing {@code SKILL.md}
     * contributes that file under the directory name and suppresses sibling
     * markdown files in the same directory.
     */
    public List<Skill> loadLegacyCommandsFromDirectory(
            Path directory, Skill.SkillSource source) {
        if (directory == null || !Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.walk(directory)) {
            Collator collator = Collator.getInstance(Locale.ENGLISH);
            collator.setStrength(Collator.TERTIARY);
            List<Path> markdown = stream
                .filter(Files::isRegularFile)
                .filter(path -> Strings.CI.endsWith(path.getFileName().toString(), SKILL_EXTENSION))
                .sorted((left, right) -> {
                    int compared = collator.compare(
                        directory.relativize(left).toString(),
                        directory.relativize(right).toString());
                    return compared != 0 ? compared : left.compareTo(right);
                })
                .toList();
            Set<Path> skillDirs = new HashSet<>();
            for (Path file : markdown) {
                if (SKILL_FILE.equalsIgnoreCase(file.getFileName().toString())) {
                    skillDirs.add(file.getParent());
                }
            }

            List<Skill> skills = new ArrayList<>();
            for (Path file : markdown) {
                if (skillDirs.contains(file.getParent())
                        && !SKILL_FILE.equalsIgnoreCase(file.getFileName().toString())) {
                    continue;
                }
                String name = legacyCommandName(directory, file);
                Skill skill = loadLegacyCommandFile(file, name, source);
                if (skill != null) skills.add(skill);
            }
            return skills;
        } catch (IOException e) {
            LOG.warn("Failed to list legacy commands directory: {}", directory, e);
            return List.of();
        }
    }

    private static String legacyCommandName(Path root, Path file) {
        Path relative;
        if (SKILL_FILE.equalsIgnoreCase(file.getFileName().toString())) {
            relative = root.relativize(file.getParent());
            if (relative.getNameCount() == 0 || relative.toString().isEmpty()) {
                return root.getFileName().toString();
            }
        } else {
            relative = root.relativize(file);
        }
        List<String> parts = new ArrayList<>();
        for (Path part : relative) parts.add(part.toString());
        int last = parts.size() - 1;
        if (!SKILL_FILE.equalsIgnoreCase(file.getFileName().toString())) {
            String leaf = parts.get(last);
            parts.set(last, leaf.substring(0, leaf.length() - SKILL_EXTENSION.length()));
        }
        return String.join(":", parts);
    }

    private Skill loadLegacyCommandFile(Path file, String name, Skill.SkillSource source) {
        try {
            String content = Files.readString(file);
            FrontmatterParser.ParseResult parsed = frontmatterParser.parse(content);
            Object disabled = parsed.metadata().get("disable-model-invocation");
            if (disabled instanceof Boolean b && b
                    || disabled instanceof String s && Strings.CI.equals("true", s.trim())) {
                return null;
            }
            String description = descriptionOrFallback(parsed, "Custom command");
            return new Skill(
                name, description, parsed.allowedTools(), parsed.body(), file, source,
                parsed.model(), parsed.effort(), parsed.context(), parsed.metadata());
        } catch (IOException e) {
            LOG.warn("Failed to load legacy command skill: {}", file, e);
            return null;
        }
    }

    private static void addIfNewPhysicalFile(
            List<Skill> target, Set<String> seenFileIds, Skill skill) {
        String fileId = fileIdentity(skill.sourceFile());
        if (fileId == null || seenFileIds.add(fileId)) {
            target.add(skill);
        } else {
            LOG.debug("Skipping duplicate skill file: {}", skill.sourceFile());
        }
    }

    private static String fileIdentity(Path path) {
        try {
            return path.toRealPath().toString();
        } catch (IOException | SecurityException _) {

            return null;
        }
    }

    /**
     * Loads one plugin skill root.
     */
    List<Skill> loadPluginSkills(PluginSkillRoot root) {
        Path dir = root.directory();
        if (dir == null || !Files.isDirectory(dir)) {
            return List.of();
        }
        List<Skill> skills = new ArrayList<>();
        Path directSkill = dir.resolve(SKILL_FILE);
        if (Files.isRegularFile(directSkill)) {
            String directName = root.directSkillName() == null
                ? dir.getFileName().toString()
                : root.directSkillName();
            Skill skill = loadPluginSkillFile(directSkill,
                root.pluginName() + ":" + directName);
            if (skill != null) skills.add(skill);
            return skills;
        }
        try (Stream<Path> entries = Files.list(dir)) {
            entries.filter(Files::isDirectory).sorted().forEach(sub -> {
                Path skillMd = sub.resolve(SKILL_FILE);
                if (!Files.isRegularFile(skillMd)) return;
                Skill skill = loadPluginSkillFile(skillMd,
                    root.pluginName() + ":" + sub.getFileName());
                if (skill != null) skills.add(skill);
            });
        } catch (IOException e) {
            LOG.warn("Failed to list plugin skill directory: {}", dir, e);
        }
        return skills;
    }

    private Skill loadPluginSkillFile(Path file, String name) {
        try {
            String content = Files.readString(file);
            FrontmatterParser.ParseResult parsed = frontmatterParser.parse(content);
            return new Skill(
                    name,
                    descriptionOrFallback(parsed, "Plugin skill"),
                    parsed.allowedTools(),
                    parsed.body(),
                    file,
                    Skill.SkillSource.PLUGIN,
                    parsed.model(),
                    parsed.effort(),
                    parsed.context(),
                    parsed.metadata()
            );
        } catch (IOException e) {
            LOG.warn("Failed to load plugin skill: {}", file, e);
            return null;
        }
    }

    /**
     * Load skills from a single directory.
     *
     * @param directory the directory to scan
     * @param source    the source type
     * @return list of skills found
     */
    public List<Skill> loadFromDirectory(Path directory, Skill.SkillSource source) {
        if (directory == null || !Files.isDirectory(directory)) {
            return List.of();
        }

        List<Skill> skills = new ArrayList<>();
        try (Stream<Path> entries = Files.list(directory)) {
            entries.sorted().forEach(entry -> {
                try {

// follows symlinks, matching entry.isSymbolicLink support.
                    if (!Files.isDirectory(entry)) return;
                    Path skillMd = entry.resolve(SKILL_FILE);
                    if (!Files.isRegularFile(skillMd)) return;
                    long size = Files.size(skillMd);
                    if (size > MAX_SKILL_FILE_BYTES) {
                        LOG.warn("Skipping oversized skill file: {} ({} bytes; limit is {})",
                            skillMd, size, MAX_SKILL_FILE_BYTES);
                        return;
                    }
                    Skill skill = loadSkillFile(
                        skillMd, entry.getFileName().toString(), source);
                    if (skill != null) skills.add(skill);
                } catch (IOException e) {
                    LOG.warn("Failed to load skill entry: {}", entry, e);
                }
            });
        } catch (IOException e) {
            LOG.warn("Failed to list skill directory: {}", directory, e);
        }

        return skills;
    }

    /**
     * Load a single skill from {@code file}.
     */
    Skill loadSkillFile(Path file, String skillName, Skill.SkillSource source) throws IOException {
        String content = Files.readString(file);
        FrontmatterParser.ParseResult parsed = frontmatterParser.parse(content);

        return new Skill(
                skillName,
                descriptionOrFallback(parsed, "Skill"),
                parsed.allowedTools(),
                parsed.body(),
                file,
                source,
                parsed.model(),
                parsed.effort(),
                parsed.context(),
                parsed.metadata()
        );
    }

    private static String descriptionOrFallback(
            FrontmatterParser.ParseResult parsed, String fallback) {
        String description = parsed.description();
        if (StringUtils.isNotBlank(description)) return description;
        return parsed.body().lines()
            .map(String::trim)
            .filter(line -> !line.isEmpty())
            .map(line -> line.replaceFirst("^#+\\s*", ""))
            .findFirst()
            .orElse(fallback);
    }
}
