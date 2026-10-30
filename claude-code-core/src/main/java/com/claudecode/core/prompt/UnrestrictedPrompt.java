package com.claudecode.core.prompt;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * The "unrestricted" workspace delivery contract, loaded from split resource files.
 *
 * <p>Enabled by the {@code --unrestricted} launch flag. When active, the assembled system
 * prompt drops the cyber-risk instruction (see {@link CyberRiskInstruction}) and appends
 * the contract block below, which redefines what a finished answer looks like so the model
 * delivers complete, unabridged artifacts instead of a policy-shaped summary.
 *
 * <ul>
 *   <li>Prompt text for {@code --unrestricted}: the contract block and its persona pointer,
 *       adapted verbatim from the {@code dsh-unrestricted} plugin
 *       ({@code yexi-by/dsh-unrestricted}, MIT) and its upstream {@code codex-keysmith}
 *       {@code examples/gpt-unrestricted.md}. The removal of the cyber-risk instruction
 *       mirrors {@code ClawGod}'s {@code remove-cyber-risk} patch ({@code 0Chencc/clawgod}) —
 *       that patch empties the same constant; we branch instead of patching a bundle.</li>
 * </ul>
 *
 * <h2>Why resources instead of Java text blocks</h2>
 *
 * <p>Every other prompt in this package is a hardcoded Java constant. This block is
 * deliberately different: it is ~10 KB of prose that upstream iterates on, and two concrete
 * properties are wanted. (1) Editing it must not require a recompile. (2) The block is split
 * on {@code dsh-unrestricted}'s section boundaries
 * ({@code 01-scope} … {@code 07-runtime-coordination}) so that when upstream revises one
 * section, the diff and the review stay local to that section. Byte fidelity is a hard
 * requirement — see {@link #fingerprint()}, which pins the assembled text so an accidental
 * edit in any section fails a test rather than silently shipping.
 *
 * <p>Files are concatenated with {@code "\n\n"} in filename order ({@link #SECTIONS}).
 * The files themselves carry no trailing newline, so the join is exact and callers never
 * need to normalize one away.
 */
public final class UnrestrictedPrompt {

    /**
     * Contract sections in assembly order. Filenames are the ordering key, so a new section
     * must be numbered to sort where it belongs.
     *
     * <p>{@code 00-pointer} is deliberately absent: it is appended to the persona rather
     * than joining the block, so it is loaded separately by {@link #personaPointer()}.
     */
    private static final List<String> SECTIONS = List.of(
        "01-scope.txt",
        "02-delivery.txt",
        "03-values-targets.txt",
        "04-task-routing.txt",
        "05-language.txt",
        "06-layer-independence.txt",
        "07-runtime-coordination.txt");

    private static final String RESOURCE_DIR = "/prompt/unrestricted/";
    private static final String POINTER_RESOURCE = RESOURCE_DIR + "00-pointer.txt";

    /** Separator between sections; matches the paragraph gap used inside each section. */
    private static final String SECTION_JOIN = "\n\n";

    private static final String CONTRACT_BLOCK = loadContractBlock();
    private static final String PERSONA_POINTER = readResource(POINTER_RESOURCE);

    private UnrestrictedPrompt() {}

    /**
     * The full contract block: every section joined by {@link #SECTION_JOIN}. This is what the
     * assembler appends to the system prompt, and what {@link #fingerprint()} pins.
     */
    public static String contractBlock() {
        return CONTRACT_BLOCK;
    }

    /**
     * The one-sentence pointer appended to the end of the persona/intro section, directing the
     * model to the contract block. It leads with a space so it can be concatenated onto an
     * existing sentence without the caller having to add one.
     */
    public static String personaPointer() {
        return PERSONA_POINTER;
    }

    /**
     * Section filenames in assembly order, for diagnostics and section-level assertions.
     */
    public static List<String> sectionNames() {
        return SECTIONS;
    }

    /**
     * First 16 hex digits of the SHA-256 over the deployed contract bytes. Upstream keys its
     * deployments on a prompt hash; the same identity is useful here so a live prompt can be
     * matched against the bytes it was built from, and so a test can fail loudly when a section
     * is edited without updating the recorded fingerprint.
     *
     * @return lowercase hex digest prefix of {@link #contractBlock()}.
     */
    public static String fingerprint() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(CONTRACT_BLOCK.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every conforming JRE; absence is not a recoverable state.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** The exact bytes of one section as deployed. Test-facing; production uses {@link #contractBlock()}. */
    static String sectionText(String sectionName) {
        if (!SECTIONS.contains(sectionName)) {
            throw new IllegalArgumentException("Unknown section: " + sectionName);
        }
        return readResource(RESOURCE_DIR + sectionName);
    }

    private static String loadContractBlock() {
        List<String> parts = new ArrayList<>(SECTIONS.size());
        for (String section : SECTIONS) {
            parts.add(readResource(RESOURCE_DIR + section));
        }
        return String.join(SECTION_JOIN, parts);
    }

    private static String readResource(String resource) {
        InputStream stream = UnrestrictedPrompt.class.getResourceAsStream(resource);
        if (stream == null) throw new IllegalStateException("Missing " + resource);
        try (stream) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + resource, e);
        }
    }
}