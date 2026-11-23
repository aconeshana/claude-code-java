package com.claudecode.api;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Rescues pseudo-text tool calls written into assistant text during long-context drift
 * (Hermes {@code <tool_call>}, DeepSeek {@code <｜DSML｜invoke>}, GLM {@code <arg_key>},
 * MiniMax {@code <minimax:tool_call>}, Claude {@code <function_calls>}, and Gemini
 * {@code call:default_api:name{...}}), while preserving Markdown fenced code blocks untouched.
 *
 * <p>Replicated from {@code yetone/magpie} ({@code internal/gateway/textcalls.go} and
 * {@code internal/gateway/codeassist.go}).
 */
@Explanation("Long-context text-written pseudo-tool-call parser and streaming rescue")
final class TextCallRescue {

    private static final AtomicLong SEQ = new AtomicLong();
    private static final String GEMINI_CALL_MARKER = "call:default_api:";
    private static final String GEMINI_CTRL_QUOTE = "<" + "ctrl46>";

    private static final List<String> OPEN_MARKS = List.of(
        "<tool_call>", "<invoke name=", "<function_calls>", "<minimax:tool_call>", GEMINI_CALL_MARKER
    );
    private static final List<String> CUT_MARKS = List.of(
        "</tool_call>", "</parameter>", "</invoke>", "</function_calls>", "</minimax:tool_call>",
        "<tool_call>", "<invoke name=", "<function_calls>", "<minimax:tool_call>", GEMINI_CALL_MARKER
    );

    private static final String DSML_TAG = "<\\s*/?\\s*[|｜][\\s|｜]*DSML[\\s|｜]*";
    private static final String INVOKE_PRE = "(?:[|｜][\\s|｜]*DSML[\\s|｜]*|[A-Za-z_][\\w-]*:)?";
    private static final Pattern DSML_INVOKE = Pattern.compile(
        "<\\s*" + INVOKE_PRE + "invoke\\s+name\\s*=\\s*\"([^\"]+)\"\\s*>");
    private static final Pattern DSML_PARAM = Pattern.compile(
        "<\\s*" + INVOKE_PRE + "parameter\\s+name\\s*=\\s*\"([^\"]+)\""
            + "((?:\\s+\\w+\\s*=\\s*\"[^\"]*\")*)\\s*>(.*?)<\\s*/?\\s*" + INVOKE_PRE + "parameter\\s*>",
        Pattern.DOTALL);
    private static final Pattern DSML_CLOSE = Pattern.compile(
        "<\\s*/\\s*" + INVOKE_PRE + "invoke\\s*>");
    private static final Pattern DSML_STRING = Pattern.compile("string\\s*=\\s*\"true\"");
    private static final Pattern TEXT_CALL_JUNK = Pattern.compile(
        DSML_TAG + "[^<>]*>?|[}\\]\"'>]*\\s*</tool_calls?>|<tool_calls?>"
            + "|<\\s*/?\\s*(?:[A-Za-z_][\\w-]*:)?(?:invoke|parameter|function_calls|tool_calls?)\\b[^<>]*>");
    private static final Pattern PARAM_HEAD = Pattern.compile(
        "^\\s*\\{\\s*\"name\"\\s*:\\s*\"([^\"]+)\"\\s*\\}?\\s*>?");
    private static final Pattern PARAM_OPEN = Pattern.compile(
        "^\\s*<parameter(?:\\s+name\\s*=\\s*\"([^\"]+)\"|=([^\\s>]+))\\s*>");
    private static final Pattern GLM_PAIR = Pattern.compile(
        "^\\s*<arg_key>(.*?)</arg_key>\\s*<arg_value>(.*?)</arg_value>", Pattern.DOTALL);
    private static final Pattern GLM_NAME = Pattern.compile("^\\s*([^\\s<>{}\"]+)\\s*");

    private TextCallRescue() {}

    record WrittenCall(String name, ObjectNode args) {}

    record RescueOutcome(List<ContentBlock> blocks, boolean rescuedTools) {}

    static Set<String> offeredToolNames(List<CreateMessageRequest.ToolDefinition> tools) {
        if (tools == null || tools.isEmpty()) return Set.of();
        Set<String> names = new LinkedHashSet<>(tools.size());
        for (CreateMessageRequest.ToolDefinition tool : tools) {
            if (tool != null && StringUtils.isNotBlank(tool.name())) {
                names.add(tool.name());
            }
        }
        return Set.copyOf(names);
    }

    static String nextCallId() {
        return "call_text_" + SEQ.incrementAndGet();
    }

    /**
     * Rescues text-written tool calls in unary content blocks when {@code offeredTools} is non-empty
     * and the blocks contain no structured {@link ToolUseBlock}.
     */
    static RescueOutcome rescueBlocks(List<ContentBlock> blocks, Set<String> offeredTools) {
        if (blocks == null || blocks.isEmpty() || offeredTools == null || offeredTools.isEmpty()) {
            return new RescueOutcome(blocks == null ? List.of() : blocks, false);
        }
        for (ContentBlock block : blocks) {
            if (block instanceof ToolUseBlock) {
                return new RescueOutcome(blocks, false);
            }
        }

        List<ContentBlock> out = new ArrayList<>(blocks.size() + 2);
        boolean rescuedAny = false;
        for (ContentBlock block : blocks) {
            if (!(block instanceof TextBlock(String text)) || StringUtils.isEmpty(text)) {
                out.add(block);
                continue;
            }
            int markAt = findUnfencedCallStart(text);
            if (markAt < 0) {
                out.add(block);
                continue;
            }
            String prefix = text.substring(0, markAt);
            String candidate = text.substring(markAt);
            ParsedCalls parsed = parseTextCalls(candidate, offeredTools);
            if (!parsed.ok() || parsed.calls().isEmpty()) {
                out.add(block);
                continue;
            }
            rescuedAny = true;
            String leading = prefix + (parsed.rest().isEmpty() ? "" : parsed.rest());
            if (StringUtils.isNotEmpty(leading)) {
                out.add(new TextBlock(leading));
            }
            for (WrittenCall call : parsed.calls()) {
                out.add(new ToolUseBlock(nextCallId(), call.name(), call.args()));
            }
        }
        return new RescueOutcome(List.copyOf(out), rescuedAny);
    }

    /**
     * Finds the first unfenced pseudo-call opening marker in {@code text}, or {@code -1} if none
     * exists outside Markdown fenced code blocks ({@code ```} / {@code ~~~}).
     */
    static int findUnfencedCallStart(String text) {
        if (StringUtils.isEmpty(text)) return -1;
        char fenceChar = 0;
        int fenceLen = 0;
        boolean atLineStart = true;
        int leadingSpaces = 0;
        char runChar = 0;
        int runLen = 0;

        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\n') {
                if (runLen >= 3) {
                    if (fenceLen == 0) {
                        fenceChar = runChar;
                        fenceLen = runLen;
                    } else if (runChar == fenceChar && runLen >= fenceLen) {
                        fenceChar = 0;
                        fenceLen = 0;
                    }
                }
                atLineStart = true;
                leadingSpaces = 0;
                runChar = 0;
                runLen = 0;
                continue;
            }
            if (ch == '\r') continue;
            if (atLineStart) {
                if (runLen == 0 && ch == ' ' && leadingSpaces < 3) {
                    leadingSpaces++;
                    continue;
                }
                if ((ch == '`' || ch == '~') && (runLen == 0 || ch == runChar)) {
                    runChar = ch;
                    runLen++;
                    continue;
                }
                atLineStart = false;
                if (runLen >= 3 && fenceLen == 0) {
                    fenceChar = runChar;
                    fenceLen = runLen;
                }
                runChar = 0;
                runLen = 0;
            }
            if (fenceLen == 0 && (ch == '<' || ch == 'c')) {
                String rem = text.substring(i);
                if (startsWithAnyMark(rem) || dsmlMarkLength(rem, true).length() > 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    static TailScan scanStreamingText(String text) {
        int at = findUnfencedCallStart(text);
        if (at >= 0) {
            return new TailScan(at, 0);
        }
        int tail = 0;
        for (String mark : OPEN_MARKS) {
            int max = Math.min(mark.length() - 1, text.length());
            for (int n = max; n > tail; n--) {
                String suffix = text.substring(text.length() - n);
                if (Strings.CS.startsWith(mark, suffix)) {
                    tail = n;
                    break;
                }
            }
        }
        int lastLt = text.lastIndexOf('<');
        if (lastLt >= 0 && text.length() - lastLt > tail) {
            DsmlMark dm = dsmlMarkLength(text.substring(lastLt), true);
            if (dm.more()) {
                tail = text.length() - lastLt;
            }
        }
        return new TailScan(-1, tail);
    }

    record TailScan(int startAt, int partialTailLength) {}

    private static boolean startsWithAnyMark(String rem) {
        for (String mark : OPEN_MARKS) {
            if (Strings.CS.startsWith(rem, mark)) return true;
        }
        return false;
    }

    private record DsmlMark(int length, boolean more) {}

    private static DsmlMark dsmlMarkLength(String s, boolean allowClose) {
        if (!Strings.CS.startsWith(s, "<")) {
            return new DsmlMark(0, false);
        }
        int i = 1;
        while (i < s.length() && isSpace(s.charAt(i))) i++;
        if (allowClose && i < s.length() && s.charAt(i) == '/') {
            i++;
            while (i < s.length() && isSpace(s.charAt(i))) i++;
        }
        if (i == s.length()) return new DsmlMark(0, true);
        if (!isPipeChar(s.charAt(i))) return new DsmlMark(0, false);
        i++;
        while (i < s.length() && (isSpace(s.charAt(i)) || isPipeChar(s.charAt(i)))) {
            i++;
        }
        if (i == s.length()) return new DsmlMark(0, true);
        String rest = s.substring(i);
        if (!Strings.CS.startsWith(rest, "DSML")) {
            return new DsmlMark(0, Strings.CS.startsWith("DSML", rest));
        }
        i += "DSML".length();
        while (i < s.length() && (isSpace(s.charAt(i)) || isPipeChar(s.charAt(i)))) {
            i++;
        }
        if (i == s.length()) return new DsmlMark(0, true);
        return new DsmlMark(i, false);
    }

    private static boolean isPipeChar(char c) {
        return c == '|' || c == '｜';
    }

    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t' || c == '\r' || c == '\n';
    }

    record ParsedCalls(List<WrittenCall> calls, String rest, boolean ok) {}

    static ParsedCalls parseTextCalls(String input, Set<String> offeredTools) {
        List<WrittenCall> calls = new ArrayList<>();
        StringBuilder left = new StringBuilder();
        String s = input == null ? "" : input;

        while (!s.isEmpty()) {
            int h = s.indexOf("<tool_call>");
            int g = s.indexOf(GEMINI_CALL_MARKER);
            Matcher dm = DSML_INVOKE.matcher(s);
            int d = dm.find() ? dm.start() : -1;

            int earliest = minNonNegative(h, g, d);
            if (earliest < 0) {
                appendNonCallText(s, calls, left);
                break;
            }

            if (!appendNonCallText(s.substring(0, earliest), calls, left)) {
                break;
            }

            if (earliest == g) {
                CallRead gem = parseGeminiTextCall(s.substring(g), offeredTools);
                if (!gem.good()) {
                    return new ParsedCalls(List.of(), "", false);
                }
                calls.add(gem.call());
                s = s.substring(g + gem.consumed());
                continue;
            }

            if (earliest == h) {
                String afterTag = s.substring(h + "<tool_call>".length());
                String trimmed = stripLeadingWhitespace(afterTag);
                if (trimmed.isEmpty() || Strings.CS.startsWith(trimmed, "<")) {
                    s = trimmed;
                    continue;
                }
                CallRead parsed = parseParamCall(afterTag, offeredTools);
                if (!parsed.good()) {
                    parsed = parseHermesCall(afterTag, offeredTools);
                }
                if (!parsed.good() && !Strings.CS.startsWith(trimmed, "{")) {
                    parsed = parseGlmCall(afterTag, offeredTools);
                }
                if (!parsed.good()) {
                    return new ParsedCalls(List.of(), "", false);
                }
                calls.add(parsed.call());
                s = afterTag.substring(parsed.consumed());
                continue;
            }

            String invokeName = dm.group(1);
            int openLen = dm.end() - dm.start();
            CallRead dsml = parseDsmlCall(s.substring(d), invokeName, openLen, offeredTools);
            if (!dsml.good()) {
                return new ParsedCalls(List.of(), "", false);
            }
            calls.add(dsml.call());
            s = s.substring(d + dsml.consumed());
        }

        String rest = TEXT_CALL_JUNK.matcher(left.toString()).replaceAll("").trim();
        String cut = cutMarkSuffix(rest);
        if (!cut.isEmpty()) {
            rest = rest.substring(0, rest.length() - cut.length()).trim();
        }
        return new ParsedCalls(List.copyOf(calls), rest, !calls.isEmpty() || rest.isEmpty());
    }

    private static int minNonNegative(int a, int b, int c) {
        int res = -1;
        for (int v : new int[] {a, b, c}) {
            if (v >= 0 && (res < 0 || v < res)) res = v;
        }
        return res;
    }

    private static boolean appendNonCallText(String chunk, List<WrittenCall> calls, StringBuilder left) {
        if (!calls.isEmpty()) {
            String cleaned = TEXT_CALL_JUNK.matcher(chunk).replaceAll("").trim();
            String cut = cutMarkSuffix(cleaned);
            if (!cut.isEmpty()) {
                cleaned = cleaned.substring(0, cleaned.length() - cut.length()).trim();
            }
            if (!cleaned.isEmpty()) {
                return false;
            }
        }
        left.append(chunk);
        return true;
    }

    private static String cutMarkSuffix(String s) {
        if (s.isEmpty()) return "";
        for (String m : CUT_MARKS) {
            for (int n = m.length() - 1; n >= 2; n--) {
                if (n <= s.length() && Strings.CS.endsWith(s, m.substring(0, n))) {
                    return m.substring(0, n);
                }
            }
        }
        int lastLt = s.lastIndexOf('<');
        if (lastLt >= 0) {
            String sub = s.substring(lastLt);
            if (Strings.CS.contains(sub, "|") || Strings.CS.contains(sub, "｜")) {
                if (dsmlMarkLength(sub, true).more()) {
                    return sub;
                }
            }
        }
        return "";
    }

    record CallRead(WrittenCall call, int consumed, boolean good) {
        static CallRead fail() {
            return new CallRead(null, 0, false);
        }
    }

    private static CallRead parseHermesCall(String s, Set<String> names) {
        if (names == null || names.isEmpty()) return CallRead.fail();
        int lead = leadingWhitespaceCount(s);
        String body = s.substring(lead);
        if (!Strings.CS.startsWith(body, "{")) return CallRead.fail();
        int closeBrace = findJsonObjectEnd(body);
        if (closeBrace < 0) return CallRead.fail();
        String rawJson = escapeRawControlCharsInStrings(body.substring(0, closeBrace + 1));
        JsonNode obj;
        try {
            obj = JsonUtils.getMapper().readTree(rawJson);
        } catch (Exception _) {
            return CallRead.fail();
        }
        if (obj == null || !obj.isObject()) return CallRead.fail();
        String name = obj.path("name").asText("");
        if (!names.contains(name)) return CallRead.fail();

        JsonNode rawArgs = obj.get("arguments");
        if (rawArgs == null || rawArgs.isNull()) rawArgs = obj.get("parameters");
        if (rawArgs == null || rawArgs.isNull()) rawArgs = obj.get("input");
        ObjectNode args = coerceArgsObject(rawArgs);
        if (args == null) return CallRead.fail();

        int endPos = lead + closeBrace + 1;
        int junk = leadingCloseJunkCount(s.substring(endPos));
        String next = s.substring(endPos + junk);
        if (Strings.CS.startsWith(next, "</tool_call>")) {
            return new CallRead(new WrittenCall(name, args),
                endPos + junk + "</tool_call>".length(), true);
        }
        String junkText = stripLeadingWhitespace(s.substring(endPos, endPos + junk));
        if ((!junkText.isEmpty() || (junk == 0 && !next.isEmpty() && next.charAt(0) != '<'))
                && Strings.CS.contains(next, "</tool_call>")) {
            return CallRead.fail();
        }
        return new CallRead(new WrittenCall(name, args), endPos, true);
    }

    private static CallRead parseParamCall(String s, Set<String> names) {
        if (names == null || names.isEmpty()) return CallRead.fail();
        Matcher m = PARAM_HEAD.matcher(s);
        if (!m.find()) return CallRead.fail();
        String name = m.group(1);
        if (!names.contains(name)) return CallRead.fail();
        int n = m.end();
        ObjectNode args = JsonUtils.getMapper().createObjectNode();
        boolean anyParam = false;
        while (n < s.length()) {
            Matcher pm = PARAM_OPEN.matcher(s.substring(n));
            if (!pm.find()) break;
            String key = pm.group(1) != null ? pm.group(1) : pm.group(2);
            n += pm.end();
            int end = s.length();
            int next = s.length();
            for (String stop : List.of("</parameter>", "<parameter", "</tool_call>", "<tool_call>")) {
                int idx = s.substring(n).indexOf(stop);
                if (idx >= 0 && n + idx < end) {
                    end = n + idx;
                    next = n + idx + (Strings.CS.equals("</parameter>", stop) ? stop.length() : 0);
                }
            }
            String val = trimCrLf(s.substring(n, end));
            String cut = cutMarkSuffix(val);
            if (!cut.isEmpty()) val = val.substring(0, val.length() - cut.length());
            putTypedOrString(args, key, val, false);
            anyParam = true;
            n = next;
        }
        if (!anyParam) return CallRead.fail();
        String tail = stripLeadingWhitespace(s.substring(n));
        if (Strings.CS.startsWith(tail, "</tool_call>")) {
            n = s.length() - tail.length() + "</tool_call>".length();
        }
        return new CallRead(new WrittenCall(name, args), n, true);
    }

    private static CallRead parseGlmCall(String s, Set<String> names) {
        if (names == null || names.isEmpty()) return CallRead.fail();
        Matcher nm = GLM_NAME.matcher(s);
        if (!nm.find()) return CallRead.fail();
        String name = nm.group(1);
        if (!names.contains(name)) return CallRead.fail();
        int n = nm.end();
        ObjectNode args = JsonUtils.getMapper().createObjectNode();
        while (n < s.length()) {
            Matcher pm = GLM_PAIR.matcher(s.substring(n));
            if (!pm.find()) break;
            String key = pm.group(1).trim();
            String val = pm.group(2);
            putTypedOrString(args, key, val, false);
            n += pm.end();
        }
        String tail = stripLeadingWhitespace(s.substring(n));
        if (Strings.CS.startsWith(tail, "</tool_call>")) {
            n = s.length() - tail.length() + "</tool_call>".length();
        } else if (!tail.isEmpty() && !Strings.CS.startsWith(tail, "<tool_call>")) {
            return CallRead.fail();
        }
        return new CallRead(new WrittenCall(name, args), n, true);
    }

    private static CallRead parseDsmlCall(String s, String name, int openLen, Set<String> names) {
        if (names == null || !names.contains(name)) return CallRead.fail();
        int end = s.length();
        Matcher closeMatcher = DSML_CLOSE.matcher(s.substring(openLen));
        if (closeMatcher.find()) {
            end = openLen + closeMatcher.end();
        }
        Matcher nextInvoke = DSML_INVOKE.matcher(s.substring(openLen));
        if (nextInvoke.find() && openLen + nextInvoke.start() < end) {
            end = openLen + nextInvoke.start();
        }
        ObjectNode args = JsonUtils.getMapper().createObjectNode();
        Matcher pm = DSML_PARAM.matcher(s.substring(openLen, end));
        while (pm.find()) {
            String key = pm.group(1);
            String attrs = pm.group(2) == null ? "" : pm.group(2);
            String val = pm.group(3);
            boolean forceString = DSML_STRING.matcher(attrs).find();
            putTypedOrString(args, key, val, forceString);
        }
        return new CallRead(new WrittenCall(name, args), end, true);
    }

    static CallRead parseGeminiTextCall(String s, Set<String> names) {
        if (!Strings.CS.startsWith(s, GEMINI_CALL_MARKER)) return CallRead.fail();
        int j = GEMINI_CALL_MARKER.length();
        while (j < s.length() && isIdent(s.charAt(j))) j++;
        if (j >= s.length() || s.charAt(j) != '{') return CallRead.fail();
        String rawName = s.substring(GEMINI_CALL_MARKER.length(), j);
        String name = Strings.CS.startsWith(rawName, "_x_") ? rawName.substring(3) : rawName;
        if (name.isEmpty() || (names != null && !names.isEmpty() && !names.contains(name))) {
            return CallRead.fail();
        }
        int close = closeGeminiBrace(s, j);
        if (close < 0) return CallRead.fail();
        ObjectNode args = parseGeminiTextArgs(s.substring(j + 1, close));
        if (args == null) return CallRead.fail();
        return new CallRead(new WrittenCall(name, args), close + 1, true);
    }

    private static int closeGeminiBrace(String s, int start) {
        int depth = 0;
        int i = start;
        while (i < s.length()) {
            if (Strings.CS.startsWith(s.substring(i), GEMINI_CTRL_QUOTE)) {
                int k = s.substring(i + GEMINI_CTRL_QUOTE.length()).indexOf(GEMINI_CTRL_QUOTE);
                if (k < 0) return -1;
                i += 2 * GEMINI_CTRL_QUOTE.length() + k;
                continue;
            }
            char c = s.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
            i++;
        }
        return -1;
    }

    private static ObjectNode parseGeminiTextArgs(String rawBody) {
        String body = rawBody.trim();
        if (body.isEmpty()) return JsonUtils.getMapper().createObjectNode();
        try {
            JsonNode direct = JsonUtils.getMapper().readTree("{" + body + "}");
            if (direct != null && direct.isObject()) return (ObjectNode) direct;
        } catch (Exception _) {
            // Fall through to Gemini bare key:value syntax.
        }
        ObjectNode out = JsonUtils.getMapper().createObjectNode();
        while (!body.isEmpty()) {
            int k = 0;
            while (k < body.length() && isIdent(body.charAt(k))) k++;
            if (k == 0 || k >= body.length() || body.charAt(k) != ':') return null;
            String key = body.substring(0, k);
            body = body.substring(k + 1);
            if (Strings.CS.startsWith(body, GEMINI_CTRL_QUOTE)) {
                String rest = body.substring(GEMINI_CTRL_QUOTE.length());
                int e = rest.indexOf(GEMINI_CTRL_QUOTE);
                if (e < 0) return null;
                out.put(key, rest.substring(0, e));
                body = rest.substring(e + GEMINI_CTRL_QUOTE.length()).trim();
                if (!body.isEmpty()) {
                    if (body.charAt(0) != ',') return null;
                    body = body.substring(1).trim();
                }
            } else {
                int e = nextGeminiKeyComma(body);
                String val = body.substring(0, e).trim();
                putTypedOrString(out, key, val, false);
                body = body.substring(e);
                if (!body.isEmpty()) body = body.substring(1).trim();
            }
        }
        return out;
    }

    private static int nextGeminiKeyComma(String s) {
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{' || c == '[') depth++;
            else if (c == '}' || c == ']') depth--;
            else if (c == ',' && depth == 0) {
                int k = i + 1;
                while (k < s.length() && s.charAt(k) == ' ') k++;
                int start = k;
                while (k < s.length() && isIdent(s.charAt(k))) k++;
                if (k > start && k < s.length() && s.charAt(k) == ':') {
                    return i;
                }
            }
        }
        return s.length();
    }

    private static boolean isIdent(char c) {
        return c == '_' || c == '-' || c == '.'
            || (c >= '0' && c <= '9')
            || (c >= 'a' && c <= 'z')
            || (c >= 'A' && c <= 'Z');
    }

    private static void putTypedOrString(ObjectNode target, String key, String rawVal, boolean forceString) {
        if (forceString) {
            target.put(key, rawVal);
            return;
        }
        String trimmed = rawVal.trim();
        if (!trimmed.isEmpty()) {
            try {
                JsonNode parsed = JsonUtils.getMapper().readTree(trimmed);
                if (parsed != null && !parsed.isMissingNode()) {
                    target.set(key, parsed);
                    return;
                }
            } catch (Exception _) {
                // Treat as literal string below.
            }
        }
        target.put(key, rawVal);
    }

    private static ObjectNode coerceArgsObject(JsonNode raw) {
        if (raw == null || raw.isNull()) {
            return JsonUtils.getMapper().createObjectNode();
        }
        if (raw.isObject()) {
            return (ObjectNode) raw.deepCopy();
        }
        if (raw.isTextual()) {
            String text = raw.asText().trim();
            if (text.isEmpty()) return JsonUtils.getMapper().createObjectNode();
            try {
                JsonNode inner = JsonUtils.getMapper().readTree(escapeRawControlCharsInStrings(text));
                if (inner != null && inner.isObject()) return (ObjectNode) inner;
            } catch (Exception _) {
                return null;
            }
        }
        return null;
    }

    private static int findJsonObjectEnd(String s) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (inString && c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (!inString) {
                if (c == '{') depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0) return i;
                }
            }
        }
        return -1;
    }

    private static String escapeRawControlCharsInStrings(String s) {
        StringBuilder out = new StringBuilder(s.length() + 8);
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (escaped) {
                escaped = false;
                out.append(c);
                continue;
            }
            if (inString && c == '\\') {
                escaped = true;
                out.append(c);
                continue;
            }
            if (c == '"') {
                inString = !inString;
                out.append(c);
                continue;
            }
            if (inString) {
                if (c == '\n') {
                    out.append("\\n");
                    continue;
                }
                if (c == '\r') {
                    out.append("\\r");
                    continue;
                }
                if (c == '\t') {
                    out.append("\\t");
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    private static int leadingWhitespaceCount(String s) {
        int i = 0;
        while (i < s.length() && isSpace(s.charAt(i))) i++;
        return i;
    }

    private static String stripLeadingWhitespace(String s) {
        int i = leadingWhitespaceCount(s);
        return i == 0 ? s : s.substring(i);
    }

    private static int leadingCloseJunkCount(String s) {
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (isSpace(c) || c == '}' || c == ']' || c == '"' || c == '\'' || c == '>') {
                i++;
            } else {
                break;
            }
        }
        return i;
    }

    private static String trimCrLf(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && (s.charAt(start) == '\r' || s.charAt(start) == '\n')) start++;
        while (end > start && (s.charAt(end - 1) == '\r' || s.charAt(end - 1) == '\n')) end--;
        return s.substring(start, end);
    }

    /**
     * Stateful streaming text-call rescue buffer. Holds text once a potential pseudo-call marker
     * opens (outside Markdown code fences) and flushes either rescued {@link WrittenCall}s or
     * literal text when the reply finishes.
     */
    static final class StreamBuffer {
        private final Set<String> offeredTools;
        private final StringBuilder safePrefix = new StringBuilder();
        private String pending = "";
        private final StringBuilder held = new StringBuilder();
        private boolean holding;
        private boolean nativeToolCallSeen;

        StreamBuffer(Set<String> offeredTools) {
            this.offeredTools = offeredTools == null ? Set.of() : offeredTools;
        }

        void markNativeToolCall() {
            this.nativeToolCallSeen = true;
        }

        String onTextDelta(String delta) {
            if (StringUtils.isEmpty(delta)) return "";
            if (offeredTools.isEmpty()) return delta;
            if (holding) {
                held.append(delta);
                return "";
            }
            String combined = pending + delta;
            pending = "";
            String fullContext = safePrefix.toString() + combined;
            TailScan scan = scanStreamingText(fullContext);
            if (scan.startAt() >= 0 && scan.startAt() >= safePrefix.length()) {
                int localAt = scan.startAt() - safePrefix.length();
                String emitNow = combined.substring(0, localAt);
                safePrefix.append(emitNow);
                held.append(combined.substring(localAt));
                holding = true;
                return emitNow;
            }
            if (scan.partialTailLength() > 0 && scan.partialTailLength() <= combined.length()) {
                int emitLen = combined.length() - scan.partialTailLength();
                String emitNow = combined.substring(0, emitLen);
                safePrefix.append(emitNow);
                pending = combined.substring(emitLen);
                return emitNow;
            }
            safePrefix.append(combined);
            return combined;
        }

        StreamFlushResult finish() {
            if (!holding) {
                String tail = pending;
                pending = "";
                if (nativeToolCallSeen && StringUtils.isBlank(tail)) {
                    tail = "";
                }
                return new StreamFlushResult(tail, List.of());
            }
            String heldStr = pending + held;
            pending = "";
            held.setLength(0);
            holding = false;
            if (nativeToolCallSeen) {
                ParsedCalls tagsCheck = parseTextCalls(heldStr, Set.of());
                if (tagsCheck.ok() && tagsCheck.calls().isEmpty() && tagsCheck.rest().isEmpty()) {
                    return new StreamFlushResult("", List.of());
                }
                return new StreamFlushResult(heldStr, List.of());
            }
            ParsedCalls parsed = parseTextCalls(heldStr, offeredTools);
            if (!parsed.ok() || parsed.calls().isEmpty()) {
                if (parsed.ok() && parsed.rest().isEmpty()) {
                    return new StreamFlushResult("", List.of());
                }
                return new StreamFlushResult(heldStr, List.of());
            }
            return new StreamFlushResult(parsed.rest(), parsed.calls());
        }
    }

    record StreamFlushResult(String trailingText, List<WrittenCall> calls) {}
}
