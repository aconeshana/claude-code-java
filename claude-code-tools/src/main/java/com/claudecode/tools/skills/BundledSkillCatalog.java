package com.claudecode.tools.skills;

import com.claudecode.tools.bundled.BundledResourceCatalog;
import com.claudecode.tools.bundled.BundledResourceCatalog.SkillPlacement;
import com.claudecode.tools.bundled.BundledResourceCatalog.SkillResource;
import com.claudecode.tools.cron.CronFeatureGate;
import com.claudecode.tools.loop.LoopFeatureGate;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.commons.lang3.Strings;

/**
 * Registry for model-invocable skills from the currently bound bundled release.
 */
final class BundledSkillCatalog {

    private static final String PROCESS_NONCE = UUID.randomUUID().toString().replace("-", "");
    private static final String LOOP_WHEN_TO_USE =
        "When the user wants to set up a recurring task, poll for status, or run "
            + "something repeatedly on an interval (e.g. \"check the deploy every "
            + "5 minutes\", \"keep running /babysit-prs\"). Do NOT invoke for one-off tasks.";

    private BundledSkillCatalog() {}

    static List<Skill> load() {
        return load(BundledResourceCatalog.current(), CronFeatureGate.system().cronEnabled());
    }

    static List<Skill> loadBeforePlugins() {
        return load(BundledResourceCatalog.current(), SkillPlacement.BEFORE_PLUGINS,
            CronFeatureGate.system().cronEnabled());
    }

    static List<Skill> loadAfterPlugins() {
        return load(BundledResourceCatalog.current(), SkillPlacement.AFTER_PLUGINS,
            CronFeatureGate.system().cronEnabled());
    }

    static List<Skill> load(BundledResourceCatalog resources) {
        return load(resources, CronFeatureGate.system().cronEnabled());
    }

    static List<Skill> load(BundledResourceCatalog resources, boolean cronEnabled) {
        return resources.skills().stream()
            .filter(resource -> enabled(resource, cronEnabled))
            .map(resource -> loadSkill(resources, resource))
            .toList();
    }

    private static List<Skill> load(
        BundledResourceCatalog resources,
        SkillPlacement placement,
        boolean cronEnabled
    ) {
        return resources.skills().stream()
            .filter(resource -> placement == resource.placement())
            .filter(resource -> enabled(resource, cronEnabled))
            .map(resource -> loadSkill(resources, resource))
            .toList();
    }

    private static boolean enabled(SkillResource resource, boolean cronEnabled) {
        return cronEnabled || !Strings.CS.equals("loop", resource.name());
    }

    private static Skill loadSkill(BundledResourceCatalog resources, SkillResource resource) {
        String content = resources.readText(resource.path());
        String marker = "{{BUNDLED_SKILL_BASE:" + resource.name() + "}}";
        if (Strings.CS.contains(content, marker)) {
            content = content.replace(marker,
                bundledSkillBase(resources.version(), resource.name()).toString());
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (!resource.aliases().isEmpty()) metadata.put("aliases", resource.aliases());
        if (resource.argumentHint() != null) {
            String hint = Strings.CS.equals("loop", resource.name())
                && LoopFeatureGate.system().defaultPromptEnabled()
                    ? "[interval] [prompt]" : resource.argumentHint();
            metadata.put("argumentHint", hint);
        }
        if (resource.userInvocable() != null) {
            metadata.put("userInvocable", resource.userInvocable());
        }
        if (resource.commandProjection() != null) {
            metadata.put("commandProjection", resource.commandProjection());
        }
        if (Boolean.TRUE.equals(resource.commandProjection())) {
            metadata.put("commandDescription", commandDescriptionFor(
                resource, LoopFeatureGate.system().dynamicEnabled()));
        }
        if (resource.menuDescription() != null) {
            metadata.put("menuDescription", resource.menuDescription());
        }
        return new Skill(
            resource.name(),
            descriptionFor(resource, LoopFeatureGate.system().dynamicEnabled()),
            resource.allowedTools(),
            content,
            null,
            Skill.SkillSource.valueOf(resource.source().name()),
            null,
            null,
            null,
            Map.copyOf(metadata)
        );
    }

    static String descriptionFor(String name, boolean dynamicEnabled) {
        SkillResource resource = BundledResourceCatalog.current().skills().stream()
            .filter(candidate -> Strings.CS.equals(name, candidate.name()))
            .findFirst()
            .orElse(null);
        return resource == null ? null : descriptionFor(resource, dynamicEnabled);
    }

    private static String descriptionFor(SkillResource resource, boolean dynamicEnabled) {
        if (Strings.CS.equals("loop", resource.name()) && dynamicEnabled) {
            return "Run a prompt or slash command on a recurring interval (e.g. /loop 5m /foo). Omit the interval to let the model self-pace. - "
                + LOOP_WHEN_TO_USE;
        }
        return resource.description();
    }

    private static String commandDescriptionFor(SkillResource resource, boolean dynamicEnabled) {
        if (Strings.CS.equals("loop", resource.name()) && dynamicEnabled) {
            return "Run a prompt or slash command on a recurring interval (e.g. /loop 5m /foo). Omit the interval to let the model self-pace.";
        }
        String description = resource.description();
        int suffix = description == null ? -1 : description.indexOf(" - " + LOOP_WHEN_TO_USE);
        return suffix < 0 ? description : description.substring(0, suffix);
    }

    private static Path bundledSkillBase(String version, String name) {
        Path base = Path.of(System.getProperty("java.io.tmpdir"),
            "claude-code-java", "bundled-skills", version, PROCESS_NONCE, name);
        try {
            Files.createDirectories(base);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create bundled skill base: " + base, e);
        }
        return base;
    }
}
