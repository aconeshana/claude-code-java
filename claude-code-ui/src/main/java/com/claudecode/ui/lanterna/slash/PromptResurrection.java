package com.claudecode.ui.lanterna.slash;

import com.claudecode.commands.CommandOutputChannel;
import com.claudecode.commands.CommandResult;
import com.claudecode.commands.CommandResultDisplay;
import com.claudecode.commands.metadata.CommandMetadataEncoder;
import com.claudecode.commands.prompt.PromptInvocation;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.MessageConstants;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.UserMessage;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * The queue values a prompt-command result resurrects to when its turn cannot start because
 * another turn already holds the session.
 *
 * <p>TS coverage:
 * <ul>
 *   <li>{@code screens/REPL.tsx} {@code QueryRunner.run} — the {@code guard.tryStart() === null}
 *       branch: non-meta user messages are re-enqueued by their text ({@code FHe}); isMeta
 *       prompt bodies are skipped, and so are non-user (system) messages — also for the late
 *       result of a force-ended local or {@code !} command (authoritative logic from the
 *       2.1.236 bundle)</li>
 *   <li>{@code utils/processUserInput/processSlashCommand.tsx} — the user messages a
 *       {@code local} / {@code local-jsx} command's result carries: the {@code Uyt} input
 *       breadcrumb, and for {@code local-jsx} the stdout or {@code (no content)}; system-shown
 *       ({@code wL}) output is not a user message</li>
 * </ul>
 */
public final class PromptResurrection {

    private PromptResurrection() {
    }

    /**
     * Texts of the user messages the command's turn would show: the preceding breadcrumbs, plus
     * the body only when it is a visible local-command output (QueryLoop marks every other slash
     * body isMeta). A body-only invocation still carries the command breadcrumb in 236.
     */
    static List<String> visibleTexts(PromptInvocation invocation, String cmdName, String cmdArgs) {
        List<String> texts = new ArrayList<>();
        for (MessageContent preceding : invocation.precedingUserMessages()) {
            String text = textOf(preceding);
            if (StringUtils.isNotEmpty(text)) texts.add(text);
        }
        String body = invocation.textContent();
        if (Strings.CS.startsWith(body, "<local-command-stdout>")
                || Strings.CS.startsWith(body, "<local-command-stderr>")) {
            texts.add(body);
        }
        if (texts.isEmpty()) {
            texts.add(CommandMetadataEncoder.encodeSlashCommandLoading(
                cmdName, StringUtils.isEmpty(cmdArgs) ? null : cmdArgs));
        }
        return List.copyOf(texts);
    }

    /**
     * Texts of the user messages 236's {@code processSlashCommand} gives a non-prompt command's
     * result — what {@code run} re-enqueues when that result lands behind a running turn:
     * <ul>
     *   <li>{@code local}: the input breadcrumb only; the output ({@code wL}) is a system
     *       message;</li>
     *   <li>{@code local-jsx}: nothing when the result is shown as a system message, otherwise
     *       the breadcrumb and the stdout ({@code (no content)} when empty);</li>
     *   <li>a skipped result: nothing.</li>
     * </ul>
     */
    static List<String> localResultTexts(boolean localJsx, String cmdName, String cmdArgs,
                                         CommandResult result) {
        if (result == null || result.display() == CommandResultDisplay.SKIP) return List.of();
        String breadcrumb = CommandMetadataEncoder.encodeCommandInputTags(cmdName, cmdArgs);
        if (!localJsx) return List.of(breadcrumb);
        if (result.display() == CommandResultDisplay.SYSTEM) return List.of();
        String output = StringUtils.isEmpty(result.output())
            ? MessageConstants.NO_CONTENT_MESSAGE : result.output();
        return List.of(breadcrumb, CommandOutputChannel.STDOUT.wrap(output));
    }

    /**
     * Texts of the non-meta user messages among a late command result — what 236 re-enqueues
     * for a force-ended local or {@code !} command whose result lands behind a running turn.
     */
    public static List<String> userTexts(List<UserMessage> messages) {
        List<String> texts = new ArrayList<>();
        for (UserMessage message : messages) {
            if (message == null || message.isMeta() || message.message() == null) continue;
            String text = textOf(message.message());
            if (StringUtils.isNotEmpty(text)) texts.add(text);
        }
        return List.copyOf(texts);
    }

    private static String textOf(MessageContent content) {
        if (content.text() != null) return content.text();
        if (content.blocks() == null) return "";
        List<String> parts = new ArrayList<>();
        for (ContentBlock block : content.blocks()) {
            if (block instanceof TextBlock(String text) && text != null) parts.add(text);
        }
        return String.join("\n", parts);
    }
}
