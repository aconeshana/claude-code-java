package com.claudecode.commands.impl.terminal;

import org.apache.commons.lang3.Strings;

import com.claudecode.commands.AnnotatedCommand;
import com.claudecode.commands.metadata.SlashCommand;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageConstants;
import com.claudecode.core.message.TextBlock;
import com.claudecode.runtime.settings.SettingsManagementPort;
import com.claudecode.core.io.FileUtils;
import com.claudecode.core.platform.Platform;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;

/**
 * {@code /copy} — copy a recent assistant response (or one of its code blocks) to the clipboard.
 */
@SlashCommand(
    name = "copy",
    description = "Copy Claude's last response to clipboard (or /copy N for the Nth-latest)"
)
public class CopyCommand implements AnnotatedCommand {


    private static final int MAX_LOOKBACK = 20;
    private static final Parser MARKDOWN_PARSER = Parser.builder().build();


    public static final String RESPONSE_FILENAME = "response.md";

    /**
     * Test seam for the platform clipboard — the default delivers to
     * pbcopy/clip/xclip; tests swap it out so running the suite doesn't
     * clobber the developer's real clipboard.
     */
    static Predicate<String> clipboardDelivery = CopyCommand::setClipboard;

    /** Reads {@code copyFullResponse} — injectable so tests don't depend on
     *  the developer's real. */
    private final BooleanSupplier copyFullResponseOverride;

    public CopyCommand() { this(null); }

    CopyCommand(BooleanSupplier copyFullResponse) {
        this.copyFullResponseOverride = copyFullResponse;
    }

/**
     * One extracted markdown code block.
     */
    public record CodeBlock(String code, String lang) {}

    @Override
    public CommandResult execute(CommandContext context, String args) {
        List<Message> messages = context.session().messagesSupplier().get();
        List<String> texts = collectRecentAssistantTexts(messages);

        if (texts.isEmpty()) {
            return CommandResult.of("No assistant message to copy");
        }


        int age = 0;
        String arg = args != null ? args.trim() : "";
        if (!arg.isEmpty()) {
            Integer n = parseStrictInt(arg);
            if (n == null || n < 1) {
                return CommandResult.of(
                    "Usage: /copy [N] where N is 1 (latest), 2, 3, … Got: " + arg);
            }
            if (n > texts.size()) {
                String plural = texts.size() == 1 ? "message" : "messages";
                return CommandResult.of(
                    "Only " + texts.size() + " assistant " + plural + " available to copy");
            }
            age = n - 1;
        }

        String text = texts.get(age);
        List<CodeBlock> codeBlocks = extractCodeBlocks(text);
        boolean skipPicker = codeBlocks.isEmpty()
            || (copyFullResponseOverride != null
                ? copyFullResponseOverride.getAsBoolean()
                : context.application().settings().preferences().copyFullResponse());

        if (context.presentation().copyPickerLauncher() != null) {
            // Interactive: the UI owns clipboard delivery (OSC 52 + this
            // class's applyFromDialog) both for the picker and for the
            // direct skip-picker path.
            context.presentation().copyPickerLauncher().launch(text, codeBlocks, skipPicker);
            return CommandResult.skip();
        }

        // Headless: no picker to offer — copy the full response directly

        return CommandResult.of(applyCopy(text, RESPONSE_FILENAME, false, false,
            context.application().settings().preferences()));
    }

    /**
     * Executes a copy decision — shared by the headless path and the UI's picker/skip-picker result
     * handler ({@link CommandPresentationPorts#copyApplyFromDialog}).
     */
    public static String applyCopy(String text, String filename,
                                   boolean saveAlwaysPreference, boolean writeOnly) {
        return applyCopy(text, filename, saveAlwaysPreference, writeOnly,
            SettingsManagementPort.none().preferences());
    }

    public static String applyCopy(String text, String filename,
                                   boolean saveAlwaysPreference, boolean writeOnly,
                                   SettingsManagementPort.Preferences preferences) {
        if (writeOnly) {

            try {
                Path filePath = writeToFile(text, filename);
                return "Written to " + filePath;
            } catch (IOException e) {
                return "Failed to write file: " + e.getMessage();
            }
        }

        String result = copyOrWriteToFile(text, filename);
        if (saveAlwaysPreference) {

            boolean already = preferences.copyFullResponse();
            if (!already) {
                preferences.saveCopyFullResponse(true);
            }
            return result + "\nPreference saved. Use /config to change copyFullResponse";
        }
        return result;
    }


    static String copyOrWriteToFile(String text, String filename) {
        clipboardDelivery.test(text);
        int lineCount = countLines(text);
        int charCount = text.length();
        try {
            Path filePath = writeToFile(text, filename);
            return String.format(
                "Copied to clipboard (%d characters, %d lines)%nAlso written to %s",
                charCount, lineCount, filePath);
        } catch (IOException _) {
            return String.format(
                "Copied to clipboard (%d characters, %d lines)", charCount, lineCount);
        }
    }


    public static List<CodeBlock> extractCodeBlocks(String markdown) {
        List<CodeBlock> blocks = new ArrayList<>();
        Node document = MARKDOWN_PARSER.parse(MessageConstants.stripPromptXMLTags(markdown));
        document.accept(new AbstractVisitor() {
            @Override
            public void visit(FencedCodeBlock block) {
                blocks.add(new CodeBlock(
                    stripTrailingNewline(block.getLiteral()), normalizeLang(block.getInfo())));
            }

            @Override
            public void visit(IndentedCodeBlock block) {
                blocks.add(new CodeBlock(stripTrailingNewline(block.getLiteral()), null));
            }
        });
        return blocks;
    }


    public static String fileExtension(String lang) {
        if (lang != null) {
            String sanitized = lang.replaceAll("[^a-zA-Z0-9]", "");
            if (!sanitized.isEmpty() && !Strings.CS.equals(sanitized, "plaintext")) {
                return "." + sanitized;
            }
        }
        return ".txt";
    }

    /**
     * Walk messages newest-first and collect text from non-error assistant turns.
     */
    static List<String> collectRecentAssistantTexts(List<Message> messages) {
        List<String> texts = new ArrayList<>();
        for (int i = messages.size() - 1; i >= 0 && texts.size() < MAX_LOOKBACK; i--) {
            Message msg = messages.get(i);
            if (!(msg instanceof AssistantMessage am)) continue;
            if (am.isApiErrorMessage()) continue;
            if (am.message() == null || am.message().content() == null) continue;

            StringBuilder sb = new StringBuilder();
            boolean first = true;
            for (ContentBlock block : am.message().content()) {
                if (block instanceof TextBlock(String text)) {
                    if (!first) sb.append("\n\n");
                    sb.append(text != null ? text : "");
                    first = false;
                }
            }
            if (!sb.isEmpty()) texts.add(sb.toString());
        }
        return texts;
    }

    // ── helpers ──────────────────────────────────────────────────────────────


    private static Integer parseStrictInt(String arg) {
        try {
            return Integer.valueOf(arg);
        } catch (NumberFormatException _) {
            return null;
        }
    }

    private static String stripTrailingNewline(String s) {
        // commonmark literals keep the final newline; marked's token.text

        if (s != null && Strings.CS.endsWith(s, "\n")) {
            return s.substring(0, s.length() - 1);
        }
        return s;
    }

    private static String normalizeLang(String info) {
        if (info == null) return null;
        String trimmed = info.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public static int countLines(String text) {
        int count = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') count++;
        }
        return count;
    }

/**
     * Write text to {@code $TMPDIR/claude/<filename>}.
     */
    static Path writeToFile(String text, String filename) throws IOException {
        Path dir = FileUtils.createTempDir("claude");
        Files.createDirectories(dir);
        Path file = dir.resolve(filename);
        Files.writeString(file, text, StandardCharsets.UTF_8);
        return file;
    }

/**
     * Platform clipboard tool; best-effort.
     */
    private static boolean setClipboard(String text) {
        String[] cmd;
        if (Platform.IS_DARWIN) {
            cmd = new String[]{"pbcopy"};
        } else if (Platform.IS_WINDOWS) {
            cmd = new String[]{"clip"};
        } else {
            // Linux: try xclip first, then xsel.
            if (tryClipboard(new String[]{"xclip", "-selection", "clipboard"}, text)) return true;
            cmd = new String[]{"xsel", "--clipboard", "--input"};
        }
        return tryClipboard(cmd, text);
    }

    private static boolean tryClipboard(String[] cmd, String text) {
        try {
            Process p = new ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start();
            p.getOutputStream().write(text.getBytes(StandardCharsets.UTF_8));
            p.getOutputStream().close();
            // Bounded wait — clipboard managers can hold the selection lock
            // indefinitely (X11 especially); an unbounded waitFor would hang
            // the dispatching thread with it. 5s is generous for a local pipe.
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception _) {
            return false;
        }
    }
}
