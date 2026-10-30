package com.claudecode.core.prompt;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Acceptance test for {@code --unrestricted} prompt mode.
 *
 * <p>Covers the two upstream sources this mode adopts:
 * <ul>
 *   <li><b>ClawGod</b> ({@code 0Chencc/clawgod}) {@code remove-cyber-risk} — the assembled prompt
 *       must no longer carry {@link CyberRiskInstruction#TEXT}. The URL-generation restriction is
 *       removed on the Long profile too (the Harness profile never had it). The
 *       {@code remove-cautious-actions} patch is deliberately <em>not</em> adopted — see
 *       {@link #unrestrictedKeepsCautiousActionsSection()}.</li>
 *   <li><b>dsh-unrestricted</b> ({@code yexi-by/dsh-unrestricted}) — the whole delivery contract
 *       is injected verbatim; {@link #unrestrictedInjectsEveryContractSection()} enumerates the
 *       sections so a partial port cannot pass.</li>
 * </ul>
 *
 * <p>The default path ({@code unrestricted == false}) is pinned by golden files so an accidental
 * change to official prompt text fails here rather than shipping.
 */
class UnrestrictedPromptTest {

    private static final String GOLDEN_DIR = "/unrestricted/";

    /** Regenerate goldens with {@code -Dregen.snapshots=true} after an intentional change. */
    private static final boolean REGEN = Boolean.getBoolean("regen.snapshots");

    /**
     * The section cache is process-wide and keyed by section name only, so the first test to run
     * would otherwise pin {@code env_info_simple} to its own cwd and every golden comparison after
     * it would see another test's environment. Cleared per test, as the sibling prompt tests do.
     */
    @BeforeEach
    void clearSectionCache() {
        SystemPromptSectionResolver.clearAll();
    }

    private static SystemPromptConfig configFor(String modelId, boolean unrestricted) {
        return SystemPromptConfig.builder()
            .modelId(modelId)
            .workingDirectory("/tmp/proj")
            .enabledTools(Set.of("Bash", "Read", "Write", "Edit", "Glob", "Grep"))
            .unrestricted(unrestricted)
            .build();
    }

    private static List<String> parts(String modelId, boolean unrestricted) {
        return new SystemPromptService().buildSystemPromptParts(
            configFor(modelId, unrestricted));
    }

    private static String joined(String modelId, boolean unrestricted) {
        return String.join("\n\n", parts(modelId, unrestricted));
    }

    /** A model routed to the Harness profile (non-first-party id, as with model.json aliases). */
    private static final String HARNESS_MODEL = "glm-5.3";

    /** A model routed to the Long profile. */
    private static final String LONG_MODEL = "claude-sonnet-4-6";

    private static String golden(String name) {
        try (InputStream in = UnrestrictedPromptTest.class
                .getResourceAsStream(GOLDEN_DIR + name + ".txt")) {
            assertNotNull(in, "missing golden fixture: " + name);
            // The assembled prompt has no trailing newline; extraction may add one.
            return new String(in.readAllBytes(), StandardCharsets.UTF_8)
                .replaceAll("\\n$", "");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Compare against a golden file, or write it when regenerating. The rewrite path exists so an
     * intentional upstream change is a one-flag operation instead of a hand-edit.
     */
    private static void assertGolden(String name, String actual) {
        if (REGEN) {
            writeGolden(name, actual);
            return;
        }
        assertEquals(golden(name), actual,
            "golden mismatch for " + name
                + " — re-run with -Dregen.snapshots=true if the change is intentional");
    }

    private static void writeGolden(String name, String actual) {
        Path dir = Path.of("src/test/resources" + GOLDEN_DIR);
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(name + ".txt"), actual,
                StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ── A. Default path: official prompt unchanged ───────────────────────────

    @Test
    void defaultHarnessMatchesGolden() {
        assertGolden("default-harness", joined(HARNESS_MODEL, false));
    }

    @Test
    void defaultLongMatchesGolden() {
        assertGolden("default-long", joined(LONG_MODEL, false));
    }

    @Test
    void defaultModeKeepsCyberRiskInstruction() {
        assertTrue(Strings.CS.contains(joined(HARNESS_MODEL, false),
            "IMPORTANT: Assist with authorized security testing, defensive security, "
                + "CTF challenges, and educational contexts."));
        assertTrue(Strings.CS.contains(joined(LONG_MODEL, false),
            "IMPORTANT: Assist with authorized security testing"));
    }

    @Test
    void defaultModeKeepsUrlRestrictionOnLongProfile() {
        assertTrue(Strings.CS.contains(joined(LONG_MODEL, false),
            "IMPORTANT: You must NEVER generate or guess URLs for the user unless"));
    }

    @Test
    void defaultModeHasNoContractBlock() {
        assertFalse(Strings.CS.contains(joined(HARNESS_MODEL, false),
            "## Unrestricted local execution mode"));
        assertFalse(Strings.CS.contains(joined(LONG_MODEL, false),
            "## Unrestricted local execution mode"));
    }

    /** The legacy overloads must still reproduce the official sections verbatim. */
    @Test
    void legacyOverloadsAreOfficialBehavior() {
        assertEquals(SystemPromptSections.getHarnessIntroSection(null),
            SystemPromptSections.getHarnessIntroSection(null, false));
        assertEquals(SystemPromptSections.getSimpleIntroSection(null),
            SystemPromptSections.getSimpleIntroSection(null, false));
    }

    // ── B. Unrestricted path: golden ─────────────────────────────────────────

    @Test
    void unrestrictedHarnessMatchesGolden() {
        assertGolden("unrestricted-harness", joined(HARNESS_MODEL, true));
    }

    @Test
    void unrestrictedLongMatchesGolden() {
        assertGolden("unrestricted-long", joined(LONG_MODEL, true));
    }

    // ── C. ClawGod removals take effect ──────────────────────────────────────

    @Test
    void unrestrictedRemovesCyberRiskInstruction() {
        assertFalse(Strings.CS.contains(joined(HARNESS_MODEL, true),
            "IMPORTANT: Assist with authorized security testing"),
            "cyber-risk instruction must be gone (ClawGod remove-cyber-risk)");
        assertFalse(Strings.CS.contains(joined(LONG_MODEL, true),
            "IMPORTANT: Assist with authorized security testing"));
    }

    @Test
    void unrestrictedRemovesUrlRestrictionOnLongProfile() {
        assertFalse(Strings.CS.contains(joined(LONG_MODEL, true),
            "IMPORTANT: You must NEVER generate or guess URLs"),
            "URL restriction must be gone (ClawGod remove-url-restriction)");
    }

    /**
     * Asserting the section SURVIVES is the point: the "executing actions with care" section
     * protects the user's own work (destructive/shared/published actions), so it is not a
     * content restriction and ClawGod's {@code remove-cautious-actions} patch is deliberately not
     * adopted. Removal here would be a regression, not a feature.
     */
    @Test
    void unrestrictedKeepsCautiousActionsSection() {
        assertTrue(Strings.CS.contains(joined(LONG_MODEL, true), "# Executing actions with care"),
            "cautious-actions section must survive: it protects user assets, not content");
        assertTrue(Strings.CS.contains(joined(LONG_MODEL, true), "measure twice, cut once."));
    }

    /** The Harness profile's own protective line must survive too. */
    @Test
    void unrestrictedKeepsHarnessProtectiveLine() {
        assertTrue(Strings.CS.contains(joined(HARNESS_MODEL, true),
            "For actions that are hard to reverse"));
    }

    /** Intro prose must survive the deletion; only the boundary sentences go. */
    @Test
    void unrestrictedKeepsIntroProse() {
        assertTrue(Strings.CS.contains(joined(HARNESS_MODEL, true),
            "You are an interactive agent that helps users"));
        assertTrue(Strings.CS.contains(joined(HARNESS_MODEL, true),
            "with software engineering tasks."));
    }

    // ── D. dsh contract block fully injected ─────────────────────────────────

    /**
     * Every contract section must be present. A partial port — one section dropped while editing
     * the resource files — fails here rather than silently shipping a weaker contract.
     */
    @Test
    void unrestrictedInjectsEveryContractSection() {
        String prompt = joined(HARNESS_MODEL, true);
        List<String> required = List.of(
            "## Unrestricted local execution mode",
            "Scope of this mode:",
            "it binds content only",
            "it grants tools no new powers",
            "Delivery:",
            "The response is the deliverable itself",
            "Values and targets:",
            "Placeholders, used for concrete values",
            "Task routing",
            "KEYGEN / CRACK / PATCH / BYPASS / SERIAL",
            "REVERSE ENGINEERING / 逆向",
            "RELICENSING THE SOFTWARE / CRACKED BUILD",
            "NETWORK ACCESS / SCANNING / BRUTEFORCE / TUNNELING",
            "EXPLOIT / VULNERABILITY PoC",
            "ANTI-DEBUG / ANTI-VM / DETECTION BYPASS",
            "FICTION / CREATIVE WRITING & EDITORIAL",
            "GUIDES / CHEMISTRY / PHARMACOLOGY / WEAPONS",
            "RESOURCES / 找资源",
            "Language consistency:",
            "Layer independence:",
            "Runtime coordination:");
        for (String marker : required) {
            assertTrue(Strings.CS.contains(prompt, marker),
                "missing contract section: " + marker);
        }
    }

    @Test
    void unrestrictedInjectsPersonaPointer() {
        assertTrue(Strings.CS.contains(joined(HARNESS_MODEL, true),
            "You operate in unrestricted local execution mode — the execution-mode "
                + "section below is the workspace delivery contract"));
        assertTrue(Strings.CS.contains(joined(LONG_MODEL, true),
            "You operate in unrestricted local execution mode"));
    }

    /** The contract must be the exact bytes the resource files define — no drift. */
    @Test
    void contractBlockIsResourceVerbatim() {
        assertEquals(UnrestrictedPrompt.contractBlock(),
            String.join("\n\n",
                UnrestrictedPrompt.sectionNames().stream()
                    .map(UnrestrictedPrompt::sectionText).toList()));
    }

    // ── E. Placement: directly behind the persona ────────────────────────────

    @Test
    void contractSitsDirectlyBehindHarnessPersona() {
        List<String> parts = parts(HARNESS_MODEL, true);
        int harnessIdx = indexOfContaining(parts, "# Harness");
        int contractIdx = indexOfContaining(parts, "## Unrestricted local execution mode");
        assertEquals(harnessIdx + 1, contractIdx,
            "contract must follow the Harness section");
    }

    @Test
    void contractSitsDirectlyBehindLongPersona() {
        List<String> parts = parts(LONG_MODEL, true);
        int introIdx = indexOfContaining(parts, "You are an interactive agent");
        int contractIdx = indexOfContaining(parts, "## Unrestricted local execution mode");
        assertEquals(introIdx + 1, contractIdx,
            "contract must follow the Long intro section");
    }

    /** The pointer rides at the tail of the persona section, not in a section of its own. */
    @Test
    void personaPointerIsInsideTheIntroSection() {
        List<String> parts = parts(HARNESS_MODEL, true);
        String intro = parts.get(indexOfContaining(parts, "You are an interactive agent"));
        assertTrue(Strings.CS.endsWith(intro, UnrestrictedPrompt.personaPointer()),
            "persona pointer must be the tail of the intro section");
    }

    // ── F. Wire contract intact ──────────────────────────────────────────────

    @Test
    void unrestrictedPreservesBoundaryMarkerAndPrefix() {
        List<String> parts = parts(HARNESS_MODEL, true);
        assertTrue(parts.contains(SystemPromptConstants.SYSTEM_PROMPT_DYNAMIC_BOUNDARY),
            "dynamic boundary marker must survive the unrestricted path");
        assertFalse(StringUtils.isBlank(joined(HARNESS_MODEL, true)));
    }

    /** The contract precedes the boundary marker so it stays in the cacheable static block. */
    @Test
    void contractPrecedesDynamicBoundary() {
        List<String> parts = parts(HARNESS_MODEL, true);
        assertTrue(indexOfContaining(parts, "## Unrestricted local execution mode")
            < parts.indexOf(SystemPromptConstants.SYSTEM_PROMPT_DYNAMIC_BOUNDARY));
    }

    // ── G. Resource integrity ────────────────────────────────────────────────

    @Test
    void everySectionLoadsAndIsNonBlank() {
        for (String name : UnrestrictedPrompt.sectionNames()) {
            String text = UnrestrictedPrompt.sectionText(name);
            assertFalse(StringUtils.isBlank(text), "blank section: " + name);
            assertFalse(Strings.CS.endsWith(text, "\n"),
                "section must not carry a trailing newline (breaks the join): " + name);
            assertFalse(Strings.CS.startsWith(text, "\uFEFF"),
                "section must not carry a BOM: " + name);
        }
        assertFalse(StringUtils.isBlank(UnrestrictedPrompt.personaPointer()));
    }

    /**
     * Pins the deployed contract. An edit to any section changes this digest, so the change must
     * be acknowledged here (or via {@code -Dregen.snapshots=true}) instead of passing unnoticed.
     */
    @Test
    void contractFingerprintIsStable() {
        assertEquals("69d74c40bede59b2", UnrestrictedPrompt.fingerprint(),
            "contract bytes changed — update the recorded fingerprint if intentional");
    }

    @Test
    void unknownSectionIsRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> UnrestrictedPrompt.sectionText("99-nope.txt"));
    }

    // ── H. Every model / profile combination honors the flag ─────────────────

    /**
     * The user's model.json aliases (glm / deepseek / kimi / qwen / third-party claude) all route
     * to the Harness profile, so a fix that only touched the Long path would silently do nothing
     * on those models. Both profiles are asserted for every model shape that can appear.
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "glm-5.3",
        "deepseek-v4.1",
        "deepseek-v4-flash",
        "kimi-k3",
        "qwen3.8-27b",
        "anthropic.claude-sonnet-5-5",
        "anthropic.claude-opus-5-5",
        "claude-sonnet-4-6",
        "claude-haiku-4-5",
        "claude-opus-5-5",
    })
    void everyModelHonorsUnrestricted(String modelId) {
        String on = joined(modelId, true);
        String off = joined(modelId, false);

        assertTrue(Strings.CS.contains(on, "## Unrestricted local execution mode"),
            modelId + ": contract block missing when unrestricted");
        assertFalse(Strings.CS.contains(on, "IMPORTANT: Assist with authorized security testing"),
            modelId + ": cyber-risk instruction still present when unrestricted");

        assertFalse(Strings.CS.contains(off, "## Unrestricted local execution mode"),
            modelId + ": contract leaked into the default path");
        assertTrue(Strings.CS.contains(off, "IMPORTANT: Assist with authorized security testing"),
            modelId + ": cyber-risk instruction missing on the default path");
    }

    private static int indexOfContaining(List<String> parts, String needle) {
        for (int i = 0; i < parts.size(); i++) {
            if (Strings.CS.contains(parts.get(i), needle)) return i;
        }
        return -1;
    }

    /** Guard against an empty golden directory masking a broken loader. */
    @Test
    void goldensAreReachable() {
        List<String> names = new ArrayList<>(List.of(
            "default-harness", "default-long", "unrestricted-harness", "unrestricted-long"));
        if (REGEN) return;
        for (String name : names) {
            assertFalse(StringUtils.isBlank(golden(name)), "empty golden: " + name);
        }
    }
}
