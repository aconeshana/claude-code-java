package com.claudecode.tools.skills;

import static com.claudecode.core.config.EnvUtils.isEnvTruthy;
import com.claudecode.core.process.SubprocessEnvironment;
import com.claudecode.tools.ToolRegistry;
import com.claudecode.core.config.ClaudeConfigDirectories;
import com.claudecode.core.config.ClaudePaths;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Discovers and registers skill-related tools into the tool registry.
 */
public class SkillToolProvider {

    private static final Logger LOG = LoggerFactory.getLogger(SkillToolProvider.class);

    private final SkillLoader skillLoader;
    private final ShellVariableInjector variableInjector;
    private final Path configHome;
    private final Path managedRoot;
    private boolean initialized = false;

    public SkillToolProvider() {
        this(new SkillLoader(), new ShellVariableInjector());
    }

    public SkillToolProvider(SkillLoader skillLoader, ShellVariableInjector variableInjector) {
        this(skillLoader, variableInjector, ClaudePaths.CLAUDE_HOME, ClaudePaths.managedRoot());
    }

    SkillToolProvider(SkillLoader skillLoader, ShellVariableInjector variableInjector,
                      Path configHome, Path managedRoot) {
        this.skillLoader = skillLoader;
        this.variableInjector = variableInjector;
        this.configHome = configHome;
        this.managedRoot = managedRoot;
    }

    public void initialize(Path projectDir, ToolRegistry registry) {
        initialize(projectDir, registry, true, true, true);
    }

/** Initializes skill sources, or installs the configured all-skills-off gate. */
    public void initialize(Path projectDir, ToolRegistry registry, boolean skillsEnabled) {
        initialize(projectDir, registry, skillsEnabled, true, true);
    }

    


    public void initialize(Path projectDir, ToolRegistry registry, boolean skillsEnabled,
                           boolean userSourceEnabled, boolean projectSourceEnabled) {
        initialize(projectDir, registry, skillsEnabled,
            userSourceEnabled, projectSourceEnabled, true);
    }

    /**
     * Configures skill sources and optionally verifies them immediately. The
     * interactive CLI disables the immediate scan and warms the same loader on
     * a startup virtual thread; headless and compatibility callers retain the
     * synchronous behavior.
     */
    public void initialize(Path projectDir, ToolRegistry registry, boolean skillsEnabled,
                           boolean userSourceEnabled, boolean projectSourceEnabled,
                           boolean preload) {
        if (initialized) {
            LOG.warn("SkillToolProvider already initialized");
            return;
        }

        if (!skillsEnabled) {
            skillLoader.setEnabled(false);
            initialized = true;
            LOG.info("Skill tools disabled by CLI gate");
            return;
        }

        Path managedDir = managedRoot.resolve(".claude").resolve("skills");
        Path managedCommandsDir = managedRoot.resolve(".claude").resolve("commands");
        if (!isEnvTruthy(SubprocessEnvironment.get(
                "CLAUDE_CODE_DISABLE_POLICY_SKILLS"))) {
            skillLoader.addSource(Skill.SkillSource.MANAGED, managedDir);
            skillLoader.addLegacyCommandsSource(Skill.SkillSource.MANAGED, managedCommandsDir);
        }

        Path userDir = configHome.resolve("skills");
        if (userSourceEnabled) {
            skillLoader.addSource(Skill.SkillSource.USER, userDir);
            skillLoader.addLegacyCommandsSource(
                Skill.SkillSource.USER, configHome.resolve("commands"));
        }

        if (projectSourceEnabled && projectDir != null) {
            for (Path projectSkillsDir : ClaudeConfigDirectories.projectDirs(projectDir, "skills")) {
                skillLoader.addSource(Skill.SkillSource.PROJECT, projectSkillsDir);
            }
            for (Path projectCommandsDir : ClaudeConfigDirectories.projectDirs(projectDir, "commands")) {
                skillLoader.addLegacyCommandsSource(Skill.SkillSource.PROJECT, projectCommandsDir);
            }
        }

        // Set shell variables — skills are addressed from the user skills root.
        variableInjector.setSkillDir(userDir);


        skillLoader.setBundledSkillsBeforePlugins(BundledSkillCatalog.loadBeforePlugins());
        skillLoader.setBundledSkills(BundledSkillCatalog.loadAfterPlugins());


        registry.register(new SkillTool(skillLoader, variableInjector));

        // Pre-load skills to verify configuration
        if (preload) {
            try {
                var skills = skillLoader.loadAll();
                LOG.info("Skill tools initialized: {} skills loaded", skills.size());
            } catch (Exception e) {
                LOG.warn("Failed to pre-load skills during initialization", e);
            }
        }

        initialized = true;
    }

    /**
     * Returns the skill loader for direct access if needed.
     */
    public SkillLoader getSkillLoader() {
        return skillLoader;
    }

}
