package com.claudecode.services.outputstyle;

import com.claudecode.core.prompt.OutputStyleConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutputStyleServiceTest {

    @TempDir Path tmp;

    @Test
    void mergesBuiltInPluginUserProjectAndManagedWithTsPriority() throws IOException {
        Path configHome = tmp.resolve("config");
        Path managedRoot = tmp.resolve("managed");
        Path cwd = tmp.resolve("project");
        Files.createDirectories(cwd);

        write(configHome.resolve("output-styles/shared.md"), style("Shared", "user", true));
        write(cwd.resolve(".claude/output-styles/shared.md"), style("Shared", "project", false));
        write(managedRoot.resolve(".claude/output-styles/shared.md"), style("Shared", "managed", true));
        OutputStyleConfig plugin = new OutputStyleConfig(
            "Shared", "plugin", "plugin prompt", false,
            OutputStyleConfig.Source.PLUGIN, false);

        OutputStyleService service = new OutputStyleService(
            configHome, managedRoot, () -> List.of(plugin));
        Map<String, OutputStyleConfig> styles = service.allStyles(cwd);

        assertTrue(styles.containsKey("default"));
        assertNull(styles.get("default"));
        assertEquals("managed", styles.get("Shared").description(),
            "TS priority is built-in < plugin < user < project < managed");
        assertEquals("managed prompt", styles.get("Shared").prompt());
        assertTrue(styles.get("Shared").keepCodingInstructions());
        assertEquals(OutputStyleConfig.Source.POLICY_SETTINGS, styles.get("Shared").source());
    }

    @Test
    void parsesCustomStyleFrontmatterAndIgnoresForceForPlugin() throws IOException {
        Path configHome = tmp.resolve("config");
        Path cwd = tmp.resolve("project");
        write(configHome.resolve("output-styles/mentor.md"), """
            ---
            name: Mentor
            description: 42
            keep-coding-instructions: "false"
            force-for-plugin: true
            ---

            Mentor prompt.
            """);

        OutputStyleConfig style = new OutputStyleService(
            configHome, tmp.resolve("managed"), List::of)
            .allStyles(cwd).get("Mentor");

        assertEquals("42", style.description());
        assertEquals("Mentor prompt.", style.prompt());
        assertFalse(style.keepCodingInstructions());
        assertFalse(style.forceForPlugin());
        assertEquals(OutputStyleConfig.Source.USER_SETTINGS, style.source());
    }

    @Test
    void forcedPluginStyleOverridesSelectedSetting() {
        Path cwd = tmp.resolve("project");
        OutputStyleConfig forced = new OutputStyleConfig(
            "plugin:forced", "forced", "forced prompt", false,
            OutputStyleConfig.Source.PLUGIN, true);
        OutputStyleService service = new OutputStyleService(
            tmp.resolve("config"), tmp.resolve("managed"), () -> List.of(forced));

        assertEquals(forced, service.resolve(cwd, "Learning"));
        OutputStyleService withoutForced = new OutputStyleService(
            tmp.resolve("config"), tmp.resolve("managed"), List::of);
        assertNull(withoutForced.resolve(cwd, "missing"),
            "unknown selected names fall back to the default output style");
    }

    @Test
    void builtInSelectionUsesTheOfficialCaseSensitiveKey() {
        OutputStyleService service = new OutputStyleService(
            tmp.resolve("config"), tmp.resolve("managed"), List::of);

        assertEquals("Explanatory", service.resolve(tmp.resolve("project"), "Explanatory").name());
        assertNull(service.resolve(tmp.resolve("project"), "explanatory"),
            "TS indexes OUTPUT_STYLE_CONFIG by the exact setting value");
    }

    @Test
    void whitespaceSelectionIsNotCoercedToDefault() {
        OutputStyleService service = new OutputStyleService(
            tmp.resolve("config"), tmp.resolve("managed"), List::of);

        assertNull(service.resolve(tmp.resolve("project"), "  "),
            "TS treats a whitespace outputStyle as an unknown exact key");
    }

    private static String style(String name, String description, boolean keepCoding) {
        return """
            ---
            name: %s
            description: %s
            keep-coding-instructions: %s
            ---
            %s prompt
            """.formatted(name, description, keepCoding, description);
    }

    private static void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }
}
