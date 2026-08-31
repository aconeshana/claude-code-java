package com.claudecode.tools;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Byte-level guard for fixed model-facing prompt resources. */
class Released197ToolPromptParityTest {

    private static final Map<String, Expected> EXPECTED = Map.ofEntries(
        Map.entry("Agent", new Expected(5454, "df27a31e487ee8d87853984a4e16c4d0288dd25f37aed06c442d45f69f72ae3d")),
        Map.entry("AskUserQuestion", new Expected(842, "0545e1c2b9986916808b78ad0b684e86e16f72dbb3a9556a1648fc17514c3c2d")),
        Map.entry("Bash", new Expected(10397, "f72edbeacb251a40cdf380c9a447d2a1ef8204d6fa7e511d864a9292381097c3")),
        Map.entry("Edit", new Expected(1094, "f907faa95966fdc0ad02fd6c3c69d70d7ada8e9dae2d17d45c7213fb81600e71")),
        Map.entry("EnterPlanMode", new Expected(4030, "5254ed460574cc4edda53c0b2e8cb252dc225fe30ace446a17c9f40ea67564bb")),
        Map.entry("EnterWorktree", new Expected(3031, "d76c37ab7c68f95a9301a200ed7680edf81c26af756c3e08ce628c0b09633c74")),
        Map.entry("ExitWorktree", new Expected(1923, "63e763304c955ebf3f58ec48bffa71fcf54b87ad4ef8319cf3bf284fe7e19411")),
        Map.entry("Read", new Expected(1830, "da6bb571d8b3f6f9b93c4055797514439a866b5290c7f38d1f872c35146065e2")),
        Map.entry("Skill", new Expected(1713, "cccf0377cfa4d63cc05321f27be4ccd1e82997123748b68055ffd0f3da899d0e")),
        Map.entry("TaskCreate", new Expected(2146, "995575dbea8fdf0b41cbdab23e6735e9442cc95e36b730d20d836e904b61695b")),
        Map.entry("TaskGet", new Expected(732, "55bdb174a3bcc65305f3a4ab6dcd525eec51257930cc1bf1cf60061808a88d3a")),
        Map.entry("TaskList", new Expected(998, "e179dcc4c7554a22438e499de2dde3431501029a5378709e51fc3555a3fbd26d")),
        Map.entry("TaskUpdate", new Expected(2243, "f7d5cbed53a841835f4fd6ea477c1977872f9994d65da77d0a93287e10fc7ff1")),
        Map.entry("WebFetch", new Expected(1479, "83996f4e628a4d0ecf8e355c90106e4d8a20b6e31e1fd31db552eac00f0de732")),
        Map.entry("WebSearch", new Expected(1317, "dfd0cf7dd417ba8b15ce3d21c34eea63df2ba872a3d7b34a596b6c6334c57e86")),
        Map.entry("Write", new Expected(618, "70103362310e68e4354843200e7d54db8cd08aefd058617ac9ddd98fc5b8eae0"))
    );

    @Test
    void releasedPromptsMatchWireHashes() throws Exception {
        for (Map.Entry<String, Expected> entry : EXPECTED.entrySet()) {
            String prompt = ToolTexts.prompt(entry.getKey());
            assertEquals(entry.getValue().length(),
                prompt.codePointCount(0, prompt.length()), entry.getKey());
            assertEquals(entry.getValue().sha256(), sha256(prompt), entry.getKey());
        }
    }

    @Test
    void todoWriteReleasedTextVariantsMatchBinaryHashes() throws Exception {
        assertExpected("TodoWrite/long", ToolTexts.prompt("TodoWrite", "long"),
            new Expected(9078,
                "261f7343e8440bedd1b5f71dd7c0f98bcbd5a6bff981de9c941303d6d02a1fc9"));
        assertExpected("TodoWrite/harness", ToolTexts.prompt("TodoWrite", "harness"),
            new Expected(390,
                "863d3a2d90b3c43e3427996944337b28f0083813b043f8f07592669b9171b03b"));
        assertExpected("TodoWrite/description", ToolTexts.description("TodoWrite"),
            new Expected(269,
                "2abae6e6f4f3e3dbc1014471bc232c2f4052a4c2480a49f1450c0fce1ade49e4"));
    }

    @Test
    void taskToolShortDescriptionsMatchReleased197() {
        assertEquals("Create a new task in the task list",
            ToolTexts.description("TaskCreate"));
        assertEquals("Get a task by ID from the task list",
            ToolTexts.description("TaskGet"));
        assertEquals("List all tasks in the task list",
            ToolTexts.description("TaskList"));
        assertEquals("Update a task in the task list",
            ToolTexts.description("TaskUpdate"));
    }

    @Test
    void taskToolAgentTeamPromptsMatchReleasedBinaryHashes() throws Exception {
        assertExpected("TaskCreate/teammate", ToolTexts.prompt("TaskCreate", "teammate"),
            new Expected(2399,
                "076f629c98413f4f86999e530aef8a19bb33acd0ced876eedc3a8dd7a4460950"));
        assertExpected("TaskList/teammate", ToolTexts.prompt("TaskList", "teammate"),
            new Expected(1564,
                "5df0178d877fdfeb3ac2f566a4d86779618172d2fb30ef1a777e3e804859fb98"));
    }

    @Test
    void structuredOutputChannelsMatchReleasedBinaryHashes() throws Exception {
        assertExpected("StructuredOutput/description", ToolTexts.description("StructuredOutput"),
            new Expected(48,
                "a583ed878f8836200b06ce2ebbf39e99c162cd325a4d9abdf3d857d14077fa69"));
        assertExpected("StructuredOutput/prompt", ToolTexts.prompt("StructuredOutput"),
            new Expected(178,
                "6f7411439848bfc14e872b8084279b7fc5f48a1fa563973ef7e641bab78370cf"));
    }

    private static void assertExpected(String label, String text, Expected expected)
            throws Exception {
        assertEquals(expected.length(), text.codePointCount(0, text.length()), label);
        assertEquals(expected.sha256(), sha256(text), label);
    }

    private static String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
            .digest(value.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }

    private record Expected(int length, String sha256) {}
}
