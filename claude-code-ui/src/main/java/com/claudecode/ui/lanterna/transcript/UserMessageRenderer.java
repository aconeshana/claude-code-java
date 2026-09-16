package com.claudecode.ui.lanterna.transcript;

import static com.claudecode.ui.lanterna.transcript.ToolResultLines.INDENT_CONT;
import static com.claudecode.ui.lanterna.transcript.ToolResultLines.INDENT_PREFIX;

import com.claudecode.commands.XmlConstants;
import com.claudecode.core.constants.Figures;
import com.claudecode.core.imagestore.ImageStore;
import com.claudecode.core.mcp.ChannelMessageWrapper;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.ImageBlock;
import com.claudecode.core.message.MessageConstants;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.SummarizeMetadata;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ToolResultBlock;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.text.FormatUtils;
import com.claudecode.core.text.XmlTagUtils;
import com.claudecode.ui.Ansi;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TextColor;
import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Renders {@link SDKMessage.User} events: the {@code ❯} prompt echo (plain, slash command,
 * skill), bash-mode input/output, local command output, MCP channel messages, background
 * task notifications, compact summaries, pasted-image chips and the {@code Current Plan}
 * block. Tool-result blocks are handed back to the dispatcher's result chain.
 *
 * <p>Owns the live-turn echo suppression: {@code executeQuery} paints the prompt and its
 * {@code [Image #N]} chips synchronously, so the SDK's echo of the same user message must
 * be dropped exactly once, and image chips already painted must not be repeated.
 *
 * <ul>
 *   <li>{@code src/components/messages/UserTextMessage.tsx},
 *       {@code UserCommandMessage.tsx} — {@code ❯} echo, {@code /command args} and
 *       {@code Skill(name)} display.</li>
 *   <li>{@code src/components/messages/UserBashInputMessage.tsx},
 *       {@code UserBashOutputMessage.tsx} — {@code !} banner and indented stdout/stderr.</li>
 *   <li>{@code src/components/messages/UserLocalCommandOutputMessage.tsx} — local command
 *       output, cloud-launch {@code ◆} rows and the {@code Current Plan} layout.</li>
 *   <li>{@code src/components/messages/UserChannelMessage.tsx} — {@code ↩ [server · user]}
 *       preview truncated to 60 chars.</li>
 *   <li>{@code src/components/messages/CompactSummaryMessage.tsx} — summarized-conversation
 *       header and metadata rows.</li>
 *   <li>{@code src/components/messages/UserImageMessage.tsx} — {@code [Image #N]} chip
 *       hyperlinked to the cached file.</li>
 *   <li>{@code src/components/messages/TaskNotificationMessage.tsx} — background
 *       agent completion summary coloured by status.</li>
 * </ul>
 */
final class UserMessageRenderer {

    /** Dispatcher services the user renderer relies on. */
    interface Host {
        boolean transcriptMode();
        String expandShortcut();
        /** Routes a tool_result block through the structured/registered/generic result chain. */
        void renderToolResult(ToolResultBlock result, Object toolUseResult, MessagePanel panel);
    }

    private static final String BLACK_CIRCLE = ToolResultLines.BLACK_CIRCLE;

    private final Host host;

    UserMessageRenderer(Host host) {
        this.host = host;
    }

    void resetTurn() {
        imagesRenderedInlineThisTurn.clear();
        suppressNextUserEcho = false;
    }

    /** Parses {@code <channel source="..." [attrs]>content</channel>} — compiled once. */
    private static final Pattern CHANNEL_RE = Pattern.compile(
        "<channel\\s+source=\"([^\"]+)\"([^>]*)>\\n?([\\s\\S]*?)\\n?</channel>",
        Pattern.CASE_INSENSITIVE
    );
    /** Extracts the optional {@code user} attribute from the channel tag's attribute string. */
    private static final Pattern CHANNEL_USER_ATTR_RE = Pattern.compile("\\buser=\"([^\"]+)\"");

    /**
     * Paste ids whose {@code [Image #N]} tag has already been rendered synchronously in the current
     * live turn's echo pass (see {@code LanternaReplScreen.renderInlineImages}).
     */
    private final Set<Integer> imagesRenderedInlineThisTurn = new HashSet<>();

    /**
     * Called by {@code LanternaReplScreen.executeQuery} after painting the
     * {@code ⎿ [Image #N]} lines synchronously, so they show up together with
     * the {@code ❯ text} echo instead of lagging behind the ImageResizer step.
     */
    void markImagesRenderedInline(Collection<Integer> pasteIds) {
        if (pasteIds != null) imagesRenderedInlineThisTurn.addAll(pasteIds);
    }

    /**
     * One-shot flag: when true, the next {@link SDKMessage.User} event whose content is user-authored
     * (text + images, no {@link ToolResultBlock}) is dropped instead of rendered — because {@code
     * executeQuery} already painted both the {@code ❯ text} echo and any inline {@code ⎿ [Image #N]}
     * lines synchronously.
     */
    private boolean suppressNextUserEcho = false;

    void suppressNextEcho() {
        this.suppressNextUserEcho = true;
    }

    /**
     * User messages carry tool results and optional command metadata.
     */
    void render(SDKMessage.User msg, MessagePanel panel) {
        if (msg.message() == null || msg.message().message() == null) return;
        // Meta messages are model-facing only. Transcript-only messages (notably
        // compact summaries) are hidden in the normal view and replayed by the
        // dedicated Ctrl+O dispatcher, whose host.transcriptMode() flag is true.
        if (!MessageConstants.shouldShowUserMessage(msg.message(), host.transcriptMode())) return;
        if (msg.message().isCompactSummary()) {
            renderCompactSummary(msg.message(), panel);
            return;
        }
        MessageContent mc = msg.message().message();
        if (mc.blocks() == null) {
            // Text-shaped content — the {"text": ...} shape every plain prompt


            if (suppressNextUserEcho) {
                suppressNextUserEcho = false;
                panel.bindLatestUnboundUserSourceUuid(msg.message().uuid());
                return;
            }
            String text = mc.text();
            int start = panel.snapshotLineCount();
            renderTextBlock(text, panel);
            registerUserLogicalMessage(
                msg.message().uuid(), msg.message().uuid(), text, start, panel);
            return;
        }
        // One-shot echo suppression: skip the outgoing live-turn user message
        // that executeQuery already painted synchronously. Only applies when
        // ALL blocks are user-authored (text/image) — a tool_result carrier
        // still needs to render (tool feedback in the agentic loop).
        if (suppressNextUserEcho) {
            boolean hasToolResult = mc.blocks().stream()
                .anyMatch(ToolResultBlock.class::isInstance);
            if (!hasToolResult) {
                suppressNextUserEcho = false;
                panel.bindLatestUnboundUserSourceUuid(msg.message().uuid());
                return;
            }
        }
// imagePasteIds is a parallel list carrying the pasted-content id for each ImageBlock, in
// order of appearance.

        List<Integer> imagePasteIds = msg.message().imagePasteIds();
        int imageBlockIndex = 0;
        int blockIndex = 0;
        for (ContentBlock block : mc.blocks()) {
            int currentBlockIndex = blockIndex++;
            if (block instanceof ToolResultBlock result) {
                host.renderToolResult(result, msg.message().toolUseResult(), panel);
            } else if (block instanceof TextBlock(String text)) {
                int start = panel.snapshotLineCount();
                renderTextBlock(text, panel);
                registerUserLogicalMessage(
                    msg.message().uuid() + ":" + currentBlockIndex,
                    msg.message().uuid(), text, start, panel);
            } else if (block instanceof ImageBlock) {


                // "[Image #N]" chip below the ❯ query line, hyperlinked to the
                // locally-cached PNG so iTerm/Kitty/etc. Cmd+click can open it.
                Integer imageId = (imagePasteIds != null
                        && imageBlockIndex < imagePasteIds.size())
                    ? imagePasteIds.get(imageBlockIndex) : null;
                imageBlockIndex++;
                // Skip if executeQuery already painted this [Image #N] line
                // synchronously in the echo pass — otherwise we'd double the
                // ⎿ line once ImageResizer completes and the SDK User event
                // finally flows through the stream. See
                // imagesRenderedInlineThisTurn's field doc.
                if (imageId != null && imagesRenderedInlineThisTurn.contains(imageId)) {
                    continue;
                }
                renderImage(imageId, panel);
            }
        }
    }

    private void renderCompactSummary(UserMessage message, MessagePanel panel) {
        SummarizeMetadata metadata = message.summarizeMetadata();
        panel.appendLine("", LanternaTheme.welcomeDim());

        String title = metadata == null ? "Compact summary" : "Summarized conversation";
        List<MessagePanel.Segment> header = new ArrayList<>();
        header.add(new MessagePanel.Segment(BLACK_CIRCLE, TextColor.ANSI.DEFAULT));
        header.add(new MessagePanel.Segment(
            title, TextColor.ANSI.DEFAULT, null, null, Set.of(SGR.BOLD)));
        if (metadata == null && !host.transcriptMode()) {
            header.add(new MessagePanel.Segment(
                " (" + host.expandShortcut() + " to expand)", LanternaTheme.welcomeDim()));
        }
        panel.appendMixed(header);

        if (host.transcriptMode()) {
            appendCompactSummaryBody(message.message(), panel);
            return;
        }
        if (metadata == null) return;

        String position = Strings.CS.equals("up_to", metadata.direction())
            ? "up to this point" : "from this point";
        panel.appendLine(INDENT_PREFIX + "Summarized " + metadata.messagesSummarized()
            + " messages " + position, LanternaTheme.welcomeDim());
        if (StringUtils.isNotBlank(metadata.userContext())) {
            panel.appendLine(INDENT_CONT + "Context: “" + metadata.userContext() + "”",
                LanternaTheme.welcomeDim());
        }
        panel.appendLine(INDENT_CONT + "(" + host.expandShortcut() + " to expand history)",
            LanternaTheme.welcomeDim());
    }

    private static void appendCompactSummaryBody(MessageContent content, MessagePanel panel) {
        if (content == null) return;
        String text = content.text();
        if (text == null && content.blocks() != null) {
            text = content.blocks().stream()
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::text)
                .filter(Objects::nonNull)
                .collect(Collectors.joining("\n"));
        }
        if (text == null) return;
        String[] lines = text.split("\\R", -1);
        for (int index = 0; index < lines.length; index++) {
            panel.appendLine((index == 0 ? INDENT_PREFIX : INDENT_CONT) + lines[index],
                TextColor.ANSI.DEFAULT);
        }
    }

    private static void registerUserLogicalMessage(
            String id, String sourceUuid, String rawText, int startLine, MessagePanel panel) {
        int endLine = panel.snapshotLineCount() - 1;
        String text = MessageConstants.stripSystemReminders(rawText);
        if (endLine < startLine || StringUtils.isBlank(text) || Strings.CS.startsWith(text, "<")
                || MessageConstants.INTERRUPT_MESSAGE.equals(text)
                || MessageConstants.INTERRUPT_MESSAGE_FOR_TOOL_USE.equals(text)) {
            return;
        }
        panel.registerLogicalMessage(
            id,
            sourceUuid,
            MessagePanel.LogicalMessageKind.USER,
            startLine,
            endLine,
            text,
            text,
            null,
            null,
            false);
    }

    void renderImage(Integer imageId, MessagePanel panel) {
        String label = imageId != null ? "[Image #" + imageId + "]" : "[Image]";
        // Attach the hyperlink via Segment.hyperlink so the Screen's diff loop
        // emits the OSC 8 sequence. Embedding raw ESC ] 8 ; ; URL ESC \ in the
        // segment text would render as literal characters — drawSegments does
        // not parse ANSI escapes out of segment text (only drawLine does).
        String url = null;
        if (imageId != null && Ansi.supportsHyperlinks()) {
            String path = ImageStore.getStoredImagePath(imageId);
            if (path != null) {
// new File(...).toURI produces file:///... with proper

                // pathToFileURL(imagePath).href.
                url = new File(path).toURI().toString();
            }
        }
        MessagePanel.Segment labelSeg = url != null
            ? MessagePanel.Segment.hyperlink(label, TextColor.ANSI.DEFAULT, url)
            : new MessagePanel.Segment(label, TextColor.ANSI.DEFAULT);
        panel.appendMixed(List.of(
            new MessagePanel.Segment(INDENT_PREFIX, LanternaTheme.welcomeDim()),
            labelSeg
        ));
    }

    /**
     * Renders a TextBlock in a user message that may contain command metadata.
     */
    void renderTextBlock(String text, MessagePanel panel) {
        if (text == null) return;

// Background Agent/Task completion — the XML is an internal model-facing protocol, not
// user-authored terminal content. The underscore spelling covers sessions persisted
// before the builder adopted the official hyphen tags; replaying those must not
// leak raw XML either.
        if (Strings.CS.contains(text, "<" + XmlConstants.TASK_NOTIFICATION_TAG)
                || Strings.CS.contains(text, "<task_notification")) {
            renderAgentNotification(text, panel);
            return;
        }



        // <bash-input>/<bash-stdout>/<bash-stderr> UserMessages appended by
        // processBashCommand (LanternaReplScreen.handleBashModeInput in Java).
        if (Strings.CS.startsWith(text, "<" + XmlConstants.BASH_INPUT_TAG)) {
            renderBashInput(text, panel);
            return;
        }
        if (Strings.CS.startsWith(text, "<" + XmlConstants.BASH_STDOUT_TAG)
                || Strings.CS.startsWith(text, "<" + XmlConstants.BASH_STDERR_TAG)) {
            renderBashOutput(text, panel);
            return;
        }


        if (Strings.CS.startsWith(text, "<" + XmlConstants.LOCAL_COMMAND_STDOUT_TAG)
                || Strings.CS.startsWith(text, "<" + XmlConstants.LOCAL_COMMAND_STDERR_TAG)) {
            renderLocalCommandOutput(text, panel);
            return;
        }


        // <InterruptedByUser/> here. In Java the live turn already paints that
        // line from TurnOutcome (LanternaSessionSink), so the streamed message
        // is swallowed; session replay paints it in
        // SessionController.replayLoadedMessages instead. Rendering here too
        // would double the line on every live interrupt.
        if (MessageConstants.INTERRUPT_MESSAGE.equals(text)
                || MessageConstants.INTERRUPT_MESSAGE_FOR_TOOL_USE.equals(text)) {
            return;
        }


        if (Strings.CS.startsWith(text, "<" + ChannelMessageWrapper.CHANNEL_TAG)) {
            renderChannelMessage(text, panel);
            return;
        }

        String commandMessage = XmlTagUtils.extractTag(text, XmlConstants.COMMAND_MESSAGE_TAG).orElse(null);
        if (StringUtils.isBlank(commandMessage)) {
            // Plain-text user message — accept feedback (Tab amend) lands here after the
// tool_result block.
            String trimmed = UserMessageStyle.truncateForDisplay(text).strip();
            if (trimmed.isEmpty()) return;
            panel.appendMixed(List.of());
            // Multi-line prompts: each line is a separate MessagePanel row so
            // \n renders as an actual line break. First row carries the "❯ "
            // marker; continuation rows are indented to line up under it.
            String[] userLines = trimmed.split("\n", -1);
            for (int i = 0; i < userLines.length; i++) {
                String prefix = (i == 0) ? "❯ " : "  ";
                panel.appendMixed(List.of(
                    new MessagePanel.Segment(prefix, LanternaTheme.welcomeDim()),
                    new MessagePanel.Segment(userLines[i], LanternaTheme.inputText())
                ));
            }
            return;
        }
        String args = XmlTagUtils.extractTag(text, XmlConstants.COMMAND_ARGS_TAG).orElse(null);
        boolean isSkillFormat = Strings.CS.equals("true", XmlTagUtils.extractTag(text, XmlConstants.SKILL_FORMAT_TAG).orElse(null));

        String display;
        if (isSkillFormat) {
            display = "Skill(" + commandMessage + ")";
        } else {
            display = "/" + commandMessage + (StringUtils.isNotBlank(args) ? " " + args : "");
        }

        panel.appendMixed(List.of());
        panel.appendMixed(List.of(
            new MessagePanel.Segment("❯ ", LanternaTheme.welcomeDim()),
            new MessagePanel.Segment(display, LanternaTheme.inputText())
        ));
    }

    private void renderAgentNotification(String text, MessagePanel panel) {
        String summary = XmlTagUtils.extractTag(text, XmlConstants.SUMMARY_TAG).orElse(null);
        if (StringUtils.isBlank(summary)) return;

        String status = XmlTagUtils.extractTag(text, XmlConstants.STATUS_TAG).orElse(null);
        TextColor statusColor = switch (status == null ? "" : status) {
            case "completed" -> LanternaTheme.toolSuccess();
            case "failed" -> LanternaTheme.toolError();
            case "killed" -> LanternaTheme.toolWarning();
            default -> LanternaTheme.inputText();
        };


        panel.appendMixed(List.of());
        panel.appendMixed(List.of(
            new MessagePanel.Segment(BLACK_CIRCLE, statusColor),
            new MessagePanel.Segment(summary.strip(), LanternaTheme.inputText())
        ));
    }

    private void renderBashInput(String text, MessagePanel panel) {
        String cmd = XmlTagUtils.extractTag(text, XmlConstants.BASH_INPUT_TAG).orElse("");
        panel.appendMixed(List.of());  // marginTop=1 equivalent
        panel.appendMixed(List.of(
            new MessagePanel.Segment("! ",  LanternaTheme.bashBorder(), LanternaTheme.bashBg()),
            new MessagePanel.Segment(cmd,   LanternaTheme.inputText(),  LanternaTheme.bashBg()),
            new MessagePanel.Segment(" ",   LanternaTheme.inputText(),  LanternaTheme.bashBg())
        ));
    }

    /**
     * Render a {@code <bash-stdout>…</bash-stdout><bash-stderr>…</bash-stderr>} UserMessage.
     */
    private void renderBashOutput(String text, MessagePanel panel) {
        String stdout = XmlTagUtils.extractTag(text, XmlConstants.BASH_STDOUT_TAG).orElse(null);
        String stderr = XmlTagUtils.extractTag(text, XmlConstants.BASH_STDERR_TAG).orElse(null);
        boolean hasStdout = stdout != null && !stdout.trim().isEmpty();
        boolean hasStderr = stderr != null && !stderr.trim().isEmpty();
        if (!hasStdout && !hasStderr) {
            panel.appendMixed(List.of(
                new MessagePanel.Segment("    (No output)", LanternaTheme.welcomeDim())
            ));
            return;
        }
        if (hasStdout) renderBashOutputBlock(stdout.trim(), panel, TextColor.ANSI.DEFAULT);
        if (hasStderr) renderBashOutputBlock(stderr.trim(), panel, LanternaTheme.toolError());
    }

    private void renderBashOutputBlock(String content, MessagePanel panel, TextColor color) {

        String formatted = ShellOutputFormatter.linkifyUrls(
            ShellOutputFormatter.tryJsonFormatContent(content));
        String normalized = formatted.replace("\r\n", "\n").replace("\r", "\n");
        String[] lines = normalized.split("\n", -1);
        int last = lines.length;
        while (last > 0 && lines[last - 1].isEmpty()) last--;
        for (int i = 0; i < last; i++) {
            panel.appendMixed(List.of(
                new MessagePanel.Segment("    " + lines[i], color)
            ));
        }
    }

    /**
     * Render {@code <local-command-stdout>} / {@code <local-command-stderr>} user message.
     */
    private void renderLocalCommandOutput(String text, MessagePanel panel) {
        String stdout = XmlTagUtils.extractTag(text, XmlConstants.LOCAL_COMMAND_STDOUT_TAG).orElse(null);
        String stderr = XmlTagUtils.extractTag(text, XmlConstants.LOCAL_COMMAND_STDERR_TAG).orElse(null);

        boolean hasContent = (stdout != null && !stdout.trim().isEmpty())
                          || (stderr != null && !stderr.trim().isEmpty());
        if (!hasContent) {

            panel.appendMixed(List.of(
                new MessagePanel.Segment("(no content)", LanternaTheme.welcomeDim())
            ));
            return;
        }

        if (stdout != null && !stdout.trim().isEmpty()) {
            renderIndentedLocalContent(stdout.trim(), panel);
        }
        if (stderr != null && !stderr.trim().isEmpty()) {
            renderIndentedLocalContent(stderr.trim(), panel);
        }
    }

    /**
     * Render a channel message pushed by an MCP server via {@code notifications/claude/channel}.
     */
    private void renderChannelMessage(String text, MessagePanel panel) {
        Matcher m = CHANNEL_RE.matcher(text);
        if (!m.find()) {
            // Unrecognised format — fall through to generic display
            panel.appendMixed(List.of(
                new MessagePanel.Segment(Figures.CHANNEL_ARROW + " ", LanternaTheme.welcomeDim()),
                new MessagePanel.Segment(text.strip(), TextColor.ANSI.DEFAULT)
            ));
            return;
        }
        String source = m.group(1);
        String attrs  = m.group(2) != null ? m.group(2) : "";
        String content = m.group(3) != null ? m.group(3).trim() : "";

        // Extract optional user attribute
        Matcher userM = CHANNEL_USER_ATTR_RE.matcher(attrs);
        String user = userM.find() ? userM.group(1) : null;

        // Plugin servers have names like plugin:slack-channel:slack — show the leaf
        String displayServer = source;
        int colonIdx = source.lastIndexOf(':');
        if (colonIdx != -1) displayServer = source.substring(colonIdx + 1);

        // Collapse whitespace and truncate to ~60 chars for the dim preview
        String body = content.replaceAll("\\s+", " ");
        int truncAt = 60;
        String truncated = FormatUtils.truncate(body, truncAt);

        // Render: "↩ [server] content" or "↩ [server · user] content"
        String label = user != null
            ? Figures.CHANNEL_ARROW + " [" + displayServer + " · " + user + "] "
            : Figures.CHANNEL_ARROW + " [" + displayServer + "] ";
        panel.appendMixed(List.of(
            new MessagePanel.Segment(label, LanternaTheme.welcomeDim()),
            new MessagePanel.Segment(truncated, TextColor.ANSI.DEFAULT)
        ));
    }

    private void renderIndentedLocalContent(String content, MessagePanel panel) {
        if (Strings.CS.startsWith(content, Figures.DIAMOND_OPEN + " ")
                || Strings.CS.startsWith(content, Figures.DIAMOND_FILLED + " ")) {
            renderCloudLaunchContent(content, panel);
            return;
        }
        if (renderCurrentPlan(content, panel)) return;

        String normalized = content.replace("\r\n", "\n").replace("\r", "\n");
        String[] lines = normalized.split("\n", -1);
        int last = lines.length;
        while (last > 0 && lines[last - 1].isEmpty()) last--;
        for (int i = 0; i < last; i++) {
            String prefix = (i == 0) ? INDENT_PREFIX : INDENT_CONT;
            panel.appendMixed(List.of(
                new MessagePanel.Segment(prefix, LanternaTheme.welcomeDim()),
                new MessagePanel.Segment(lines[i], TextColor.ANSI.DEFAULT)
            ));
        }
    }

    private boolean renderCurrentPlan(String content, MessagePanel panel) {
        String normalized = content.replace("\r\n", "\n").replace("\r", "\n");
        String prefix = "Current Plan\n";
        if (!Strings.CS.startsWith(normalized, prefix)) return false;
        int bodySeparator = normalized.indexOf("\n\n", prefix.length());
        if (bodySeparator < 0) return false;
        String path = normalized.substring(prefix.length(), bodySeparator);
        if (StringUtils.isBlank(path) || Strings.CS.contains(path, "\n")) return false;

        String bodyAndHint = normalized.substring(bodySeparator + 2);
        String hintPrefix = "\n\n\"/plan open\" to edit this plan in ";
        int hintAt = bodyAndHint.lastIndexOf(hintPrefix);
        String body = hintAt >= 0 ? bodyAndHint.substring(0, hintAt) : bodyAndHint;
        String editor = hintAt >= 0
            ? bodyAndHint.substring(hintAt + hintPrefix.length()) : null;

        panel.appendMixed(List.of(
            new MessagePanel.Segment(INDENT_PREFIX, LanternaTheme.welcomeDim()),
            new MessagePanel.Segment("Current Plan", TextColor.ANSI.DEFAULT,
                null, null, Set.of(SGR.BOLD))));
        panel.appendLine(INDENT_CONT + path, LanternaTheme.welcomeDim());
        if (!body.isEmpty()) {
            panel.appendLine("", TextColor.ANSI.DEFAULT);
            String[] bodyLines = body.split("\n", -1);
            for (String line : bodyLines) {
                panel.appendLine(line.isEmpty() ? "" : INDENT_CONT + line,
                    TextColor.ANSI.DEFAULT);
            }
        }
        if (editor != null) {
            panel.appendLine("", TextColor.ANSI.DEFAULT);
            panel.appendMixed(List.of(
                new MessagePanel.Segment(INDENT_CONT + "\"/plan open\" to edit this plan in ",
                    LanternaTheme.welcomeDim()),
                new MessagePanel.Segment(editor, LanternaTheme.welcomeDim(),
                    null, null, Set.of(SGR.BOLD))));
        }
        return true;
    }

    private void renderCloudLaunchContent(String content, MessagePanel panel) {
        // diamond = first char (◆ or ◇); skip "◆ " prefix (slice(2))
        String diamond = content.substring(0, 1);
        String body = content.substring(2);  // after "◆ " or "◇ "

        int nl = body.indexOf('\n');
        String header = nl == -1 ? body : body.substring(0, nl);
        String rest = nl == -1 ? "" : body.substring(nl + 1).trim();


        int sep = header.indexOf(" · ");
        String label = sep == -1 ? header : header.substring(0, sep);
        String suffix = sep == -1 ? "" : header.substring(sep);


        List<MessagePanel.Segment> line1 = new ArrayList<>();
        line1.add(new MessagePanel.Segment(diamond + " ", LanternaTheme.welcomeDim()));
        line1.add(new MessagePanel.Segment(label, LanternaTheme.inputText()));  // bold via theme
        if (!suffix.isEmpty()) {
            line1.add(new MessagePanel.Segment(suffix, LanternaTheme.welcomeDim()));
        }
        panel.appendMixed(line1);

        // Line 2: RESULT_PREFIX dim + rest dim
        if (!rest.isEmpty()) {
            panel.appendMixed(List.of(
                new MessagePanel.Segment(INDENT_PREFIX, LanternaTheme.welcomeDim()),
                new MessagePanel.Segment(rest, LanternaTheme.welcomeDim())
            ));
        }
    }
}
