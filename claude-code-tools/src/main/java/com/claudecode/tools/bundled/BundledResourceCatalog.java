package com.claudecode.tools.bundled;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Resolves versioned resources bundled with the application from a manifest.
 */
public final class BundledResourceCatalog {

    private static final String CURRENT_VERSION = "2.1.197";
    private static final String RESOURCE_ROOT = "/bundled/";
    private static final ObjectMapper MAPPER = JsonUtils.getMapper();
    private static final BundledResourceCatalog CURRENT = load(CURRENT_VERSION);

    public enum SkillSource {
        BUNDLED,
        BUILTIN
    }

    public enum SkillPlacement {
        BEFORE_PLUGINS,
        AFTER_PLUGINS
    }

    public record SkillResource(
        String name,
        String description,
        String path,
        SkillSource source,
        SkillPlacement placement,
        List<String> allowedTools,
        List<String> aliases,
        String argumentHint,
        String menuDescription,
        Boolean userInvocable,
        Boolean commandProjection
    ) {
        public SkillResource {
            allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
        }
    }

    public record WorkflowResource(String name, String path, boolean hidden) {}

    private record Manifest(
        String version,
        List<SkillResource> skills,
        List<WorkflowResource> workflows
    ) {}

    private final String version;
    private final String resourceRoot;
    private final List<SkillResource> skills;
    private final List<WorkflowResource> workflows;

    private BundledResourceCatalog(Manifest manifest) {
        this.version = manifest.version();
        this.resourceRoot = RESOURCE_ROOT + version + "/";
        this.skills = List.copyOf(manifest.skills());
        this.workflows = List.copyOf(manifest.workflows());
        validateEntries();
    }

    public static BundledResourceCatalog current() {
        return CURRENT;
    }

    public static BundledResourceCatalog forVersion(String version) {
        validateVersion(version);
        return CURRENT_VERSION.equals(version) ? CURRENT : load(version);
    }

    public String version() {
        return version;
    }

    public List<SkillResource> skills() {
        return skills;
    }

    public List<WorkflowResource> workflows() {
        return workflows;
    }

    public String readText(String relativePath) {
        validateRelativePath(relativePath);
        String path = resourceRoot + relativePath;
        try (InputStream input = BundledResourceCatalog.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new IllegalStateException("Missing bundled resource: " + path);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read bundled resource: " + path, e);
        }
    }

    private static BundledResourceCatalog load(String version) {
        validateVersion(version);
        String path = RESOURCE_ROOT + version + "/manifest.json";
        try (InputStream input = BundledResourceCatalog.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new IllegalStateException("Missing bundled release manifest: " + path);
            }
            Manifest manifest = MAPPER.readValue(input, Manifest.class);
            if (!version.equals(manifest.version())) {
                throw new IllegalStateException("Bundled manifest version mismatch: expected "
                    + version + " but found " + manifest.version());
            }
            return new BundledResourceCatalog(manifest);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read bundled release manifest: " + path, e);
        }
    }

    private static void validateVersion(String version) {
        if (StringUtils.isBlank(version)
            || Strings.CS.contains(version, "..")
            || Strings.CS.contains(version, "/")
            || Strings.CS.contains(version, "\\")) {
            throw new IllegalArgumentException("Invalid bundled release version: " + version);
        }
    }

    private void validateEntries() {
        Set<String> skillNames = new HashSet<>();
        for (SkillResource skill : skills) {
            if (!skillNames.add(skill.name())) {
                throw new IllegalStateException("Duplicate bundled skill: " + skill.name());
            }
            if (skill.source() == null || skill.placement() == null) {
                throw new IllegalStateException("Incomplete bundled skill metadata: " + skill.name());
            }
            validateRelativePath(skill.path());
        }
        Set<String> workflowNames = new HashSet<>();
        for (WorkflowResource workflow : workflows) {
            if (!workflowNames.add(workflow.name())) {
                throw new IllegalStateException("Duplicate bundled workflow: " + workflow.name());
            }
            validateRelativePath(workflow.path());
        }
    }

    private static void validateRelativePath(String path) {
        if (StringUtils.isBlank(path) || Strings.CS.startsWith(path, "/")
            || Strings.CS.contains(path, "..")
            || Strings.CS.contains(path, "\\")) {
            throw new IllegalArgumentException("Invalid bundled resource path: " + path);
        }
    }
}
