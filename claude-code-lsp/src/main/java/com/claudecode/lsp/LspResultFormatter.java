package com.claudecode.lsp;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.text.StringUtils;

import org.eclipse.lsp4j.CallHierarchyIncomingCall;
import org.eclipse.lsp4j.CallHierarchyItem;
import org.eclipse.lsp4j.CallHierarchyOutgoingCall;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.MarkedString;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.WorkspaceSymbol;
import org.eclipse.lsp4j.WorkspaceSymbolLocation;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;


final class LspResultFormatter {

    private LspResultFormatter() {}



    private static final String NO_DEFINITION =
        "No definition found. This may occur if the cursor is not on a symbol, "
            + "or if the definition is in an external library not indexed by the LSP server.";
    private static final String NO_REFERENCES =
        "No references found. This may occur if the symbol has no usages, or if the "
            + "LSP server has not fully indexed the workspace.";
    private static final String NO_HOVER =
        "No hover information available. This may occur if the cursor is not on a "
            + "symbol, or if the LSP server has not fully indexed the file.";
    private static final String NO_SYMBOLS =
        "No symbols found in document. This may occur if the file is empty, not "
            + "supported by the LSP server, or if the server has not fully indexed the file.";
    private static final String NO_WORKSPACE_SYMBOLS =
        "No symbols found in workspace. This may occur if the workspace is empty, "
            + "or if the LSP server has not finished indexing the project.";
    private static final String NO_CALL_HIERARCHY_ITEM = "No call hierarchy item found at this position";
    private static final String NO_INCOMING = "No incoming calls found (nothing calls this function)";
    private static final String NO_OUTGOING = "No outgoing calls found (this function calls nothing)";

    // ── goToDefinition / implementation ──────────────────────────────────────

    static List<String> formatDefinition(
            Either<List<? extends Location>, List<? extends LocationLink>> result, String cwd) {
        if (result == null) {
            return List.of(NO_DEFINITION);
        }
        List<Location> locations = toLocations(result);
        if (locations.isEmpty()) {
            return List.of(NO_DEFINITION);
        }
        if (locations.size() == 1) {
            return List.of("Defined in " + formatLocation(locations.getFirst(), cwd));
        }
        List<String> lines = new ArrayList<>();
        lines.add("Found " + locations.size() + " definitions:");
        for (Location loc : locations) {
            lines.add("  " + formatLocation(loc, cwd));
        }
        return lines;
    }

    // ── findReferences ───────────────────────────────────────────────────────

    static List<String> formatReferences(List<? extends Location> result, String cwd) {
        if (result == null || result.isEmpty()) {
            return List.of(NO_REFERENCES);
        }
        List<Location> valid = new ArrayList<>();
        for (Location l : result) {
            if (l != null && l.getUri() != null) {
                valid.add(l);
            }
        }
        if (valid.isEmpty()) {
            return List.of(NO_REFERENCES);
        }
        if (valid.size() == 1) {
            return List.of("Found 1 reference:\n  " + formatLocation(valid.getFirst(), cwd));
        }
        Map<String, List<Location>> byFile = groupLocationsByFile(valid, cwd);
        List<String> lines = new ArrayList<>();
        lines.add("Found " + valid.size() + " references across " + byFile.size() + " files:");
        for (Map.Entry<String, List<Location>> entry : byFile.entrySet()) {
            lines.add("");
            lines.add(entry.getKey() + ":");
            for (Location loc : entry.getValue()) {
                int line = loc.getRange().getStart().getLine() + 1;
                int character = loc.getRange().getStart().getCharacter() + 1;
                lines.add("  Line " + line + ":" + character);
            }
        }
        return lines;
    }

    // ── hover ─────────────────────────────────────────────────────────────────

    static String formatHover(Hover result) {
        if (result == null) {
            return NO_HOVER;
        }
        String content = extractMarkupText(result.getContents());
        if (result.getRange() != null) {
            int line = result.getRange().getStart().getLine() + 1;
            int character = result.getRange().getStart().getCharacter() + 1;
            return "Hover info at " + line + ":" + character + ":\n\n" + content;
        }
        return content;
    }

    // ── documentSymbol ─────────────────────────────────────────────────────────

    static List<String> formatDocumentSymbol(
            List<Either<SymbolInformation, DocumentSymbol>> result, String cwd) {
        if (result == null || result.isEmpty()) {
            return List.of(NO_SYMBOLS);
        }
        boolean isSymbolInformation = result.stream()
            .filter(Objects::nonNull)
            .anyMatch(Either::isLeft);
        if (isSymbolInformation) {
            List<SymbolEntry> entries = result.stream()
                .filter(Objects::nonNull)
                .filter(Either::isLeft)
                .map(Either::getLeft)
                .map(LspResultFormatter::toSymbolEntry)
                .toList();
            return formatWorkspaceSymbolFromList(entries, cwd);
        }
        List<DocumentSymbol> symbols = result.stream()
                .filter(Objects::nonNull)
                .filter(Either::isRight)
                .map(Either::getRight)
                .toList();
        List<String> lines = new ArrayList<>();
        lines.add("Found " + countDocumentSymbols(symbols) + " symbols:");
        for (DocumentSymbol symbol : symbols) {
            lines.addAll(formatDocumentSymbolNode(symbol, 0));
        }
        return lines;
    }

    /**
     * Recursively counts total document symbols including nested children.
     */
    private static int countDocumentSymbols(List<DocumentSymbol> symbols) {
        int count = 0;
        for (DocumentSymbol symbol : symbols) {
            if (symbol == null) {
                continue;
            }
            count += 1 + countDocumentSymbols(symbol.getChildren());
        }
        return count;
    }

    private static List<String> formatDocumentSymbolNode(DocumentSymbol symbol, int indent) {
        List<String> lines = new ArrayList<>();
        String prefix = "  ".repeat(indent);
        String kind = symbolKindToString(symbol.getKind());
        StringBuilder line = new StringBuilder(prefix)
            .append(symbol.getName()).append(" (").append(kind).append(")");
        if (org.apache.commons.lang3.StringUtils.isNotBlank(symbol.getDetail())) {
            line.append(' ').append(symbol.getDetail());
        }
        int symbolLine = symbol.getRange().getStart().getLine() + 1;
        line.append(" - Line ").append(symbolLine);
        lines.add(line.toString());
        if (symbol.getChildren() != null) {
            for (DocumentSymbol child : symbol.getChildren()) {
                lines.addAll(formatDocumentSymbolNode(child, indent + 1));
            }
        }
        return lines;
    }

    // ── workspaceSymbol ───────────────────────────────────────────────────────

    static List<String> formatWorkspaceSymbol(
            Either<List<? extends SymbolInformation>, List<? extends WorkspaceSymbol>> result, String cwd) {
        if (result == null) {
            return List.of(NO_WORKSPACE_SYMBOLS);
        }
        List<SymbolEntry> entries = new ArrayList<>();
        if (result.isLeft()) {
            for (SymbolInformation s : result.getLeft()) {
                if (s != null) entries.add(toSymbolEntry(s));
            }
        } else {
            for (WorkspaceSymbol s : result.getRight()) {
                if (s != null) {
                    entries.add(new SymbolEntry(s.getName(), s.getKind(),
                        workspaceSymbolToLocation(s.getLocation()), s.getContainerName()));
                }
            }
        }
        return formatWorkspaceSymbolFromList(entries, cwd);
    }

    /**
     * Internal, non-deprecated stand-in for {@link SymbolInformation} used to
     * normalize both {@code workspace/symbol} response shapes (legacy
     * {@code SymbolInformation[]} and current {@code WorkspaceSymbol[]}) into a
     * single shape for {@link #formatWorkspaceSymbolFromList}, without
     * constructing a deprecated {@code SymbolInformation} instance for the
     * {@code WorkspaceSymbol} branch.
     */
    private record SymbolEntry(String name, SymbolKind kind, Location location, String containerName) {}

    /**
     * Reads a legacy {@link SymbolInformation} response into a {@link SymbolEntry}.
     * The deprecated accessors are unavoidable here — the LSP server, not this
     * code, decides whether {@code workspace/symbol} or {@code documentSymbol}
     * replies in the legacy shape.
     */
    @SuppressWarnings("deprecation")
    private static SymbolEntry toSymbolEntry(SymbolInformation s) {
        return new SymbolEntry(s.getName(), s.getKind(), s.getLocation(), s.getContainerName());
    }

    private static List<String> formatWorkspaceSymbolFromList(List<SymbolEntry> symbols, String cwd) {
        if (symbols.isEmpty()) {
            return List.of(NO_WORKSPACE_SYMBOLS);
        }
        List<String> lines = new ArrayList<>();
        lines.add("Found " + symbols.size() + " " + StringUtils.plural(symbols.size(), "symbol") + " in workspace:");
        Map<String, List<SymbolEntry>> byFile = groupSymbolsByFile(symbols, cwd);
        for (Map.Entry<String, List<SymbolEntry>> entry : byFile.entrySet()) {
            lines.add("");
            lines.add(entry.getKey() + ":");
            for (SymbolEntry s : entry.getValue()) {
                String kind = symbolKindToString(s.kind());
                int line = s.location().getRange().getStart().getLine() + 1;
                String symbolLine = "  " + s.name() + " (" + kind + ") - Line " + line;
                if (org.apache.commons.lang3.StringUtils.isNotBlank(s.containerName())) {
                    symbolLine += " in " + s.containerName();
                }
                lines.add(symbolLine);
            }
        }
        return lines;
    }

    // ── prepareCallHierarchy ────────────────────────────────────────────────────

    static List<String> formatPrepareCallHierarchy(List<? extends CallHierarchyItem> result, String cwd) {
        if (result == null || result.isEmpty()) {
            return List.of(NO_CALL_HIERARCHY_ITEM);
        }
        if (result.size() == 1) {
            return List.of("Call hierarchy item: " + formatCallHierarchyItem(result.getFirst(), cwd));
        }
        List<String> lines = new ArrayList<>();
        lines.add("Found " + result.size() + " call hierarchy items:");
        for (CallHierarchyItem item : result) {
            lines.add("  " + formatCallHierarchyItem(item, cwd));
        }
        return lines;
    }

    // ── incomingCalls / outgoingCalls ───────────────────────────────────────────

    static List<String> formatIncomingCalls(List<? extends CallHierarchyIncomingCall> result, String cwd) {
        if (result == null || result.isEmpty()) {
            return List.of(NO_INCOMING);
        }
        List<String> lines = new ArrayList<>();
        lines.add("Found " + result.size() + " incoming " + StringUtils.plural(result.size(), "call") + ":");
        Map<String, List<CallHierarchyIncomingCall>> byFile = groupIncomingByFile(result, cwd);
        for (Map.Entry<String, List<CallHierarchyIncomingCall>> entry : byFile.entrySet()) {
            lines.add("");
            lines.add(entry.getKey() + ":");
            for (CallHierarchyIncomingCall call : entry.getValue()) {
                CallHierarchyItem from = call.getFrom();
                if (from == null) {
                    continue;
                }
                String kind = symbolKindToString(from.getKind());
                int line = from.getRange().getStart().getLine() + 1;
                String callLine = "  " + from.getName() + " (" + kind + ") - Line " + line;
                if (call.getFromRanges() != null && !call.getFromRanges().isEmpty()) {
                    String callSites = call.getFromRanges().stream()
                        .map(r -> (r.getStart().getLine() + 1) + ":" + (r.getStart().getCharacter() + 1))
                        .collect(Collectors.joining(", "));
                    callLine += " [calls at: " + callSites + "]";
                }
                lines.add(callLine);
            }
        }
        return lines;
    }

    static List<String> formatOutgoingCalls(List<? extends CallHierarchyOutgoingCall> result, String cwd) {
        if (result == null || result.isEmpty()) {
            return List.of(NO_OUTGOING);
        }
        List<String> lines = new ArrayList<>();
        lines.add("Found " + result.size() + " outgoing " + StringUtils.plural(result.size(), "call") + ":");
        Map<String, List<CallHierarchyOutgoingCall>> byFile = groupOutgoingByFile(result, cwd);
        for (Map.Entry<String, List<CallHierarchyOutgoingCall>> entry : byFile.entrySet()) {
            lines.add("");
            lines.add(entry.getKey() + ":");
            for (CallHierarchyOutgoingCall call : entry.getValue()) {
                CallHierarchyItem to = call.getTo();
                if (to == null) {
                    continue;
                }
                String kind = symbolKindToString(to.getKind());
                int line = to.getRange().getStart().getLine() + 1;
                String callLine = "  " + to.getName() + " (" + kind + ") - Line " + line;
                if (call.getFromRanges() != null && !call.getFromRanges().isEmpty()) {
                    String callSites = call.getFromRanges().stream()
                        .map(r -> (r.getStart().getLine() + 1) + ":" + (r.getStart().getCharacter() + 1))
                        .collect(Collectors.joining(", "));
                    callLine += " [called from: " + callSites + "]";
                }
                lines.add(callLine);
            }
        }
        return lines;
    }



    private static List<Location> toLocations(
            Either<List<? extends Location>, List<? extends LocationLink>> result) {
        List<Location> locations = new ArrayList<>();
        if (result.isLeft()) {
            for (Location l : result.getLeft()) {
                if (l != null) locations.add(l);
            }
        } else {
            for (LocationLink l : result.getRight()) {
                if (l != null) locations.add(locationLinkToLocation(l));
            }
        }
        return locations;
    }

    private static Location locationLinkToLocation(LocationLink link) {
        return new Location(link.getTargetUri(),
            link.getTargetSelectionRange() != null ? link.getTargetSelectionRange() : link.getTargetRange());
    }

    private static String formatLocation(Location location, String cwd) {
        String filePath = formatUri(location.getUri(), cwd);
        int line = location.getRange().getStart().getLine() + 1;
        int character = location.getRange().getStart().getCharacter() + 1;
        return filePath + ":" + line + ":" + character;
    }

    private static String formatUri(String uri, String cwd) {
        if (org.apache.commons.lang3.StringUtils.isBlank(uri)) {
            return "<unknown location>";
        }
        String filePath = Strings.CS.startsWith(uri, "file://") ? uri.substring("file://".length()) : uri;
        // Windows drive-letter paths arrive as /C:/path after stripping file://.
        if (filePath.matches("/[A-Za-z]:.*")) {
            filePath = filePath.substring(1);
        }
        try {
            filePath = URLDecoder.decode(filePath, StandardCharsets.UTF_8);
        } catch (Exception _) {
            // Keep the un-decoded path; still usable.
        }
        if (org.apache.commons.lang3.StringUtils.isNotBlank(cwd)) {
            try {
                Path cwdPath = Path.of(cwd);
                Path filePathPath = Path.of(filePath);
                String relative = cwdPath.relativize(filePathPath).toString().replace('\\', '/');
                if (relative.length() < filePath.length() && !Strings.CS.startsWith(relative, "../../")) {
                    return relative;
                }
            } catch (Exception _) {
                // Different filesystem roots (e.g. Windows vs Unix) — fall through.
            }
        }
        return filePath.replace('\\', '/');
    }

    private static Map<String, List<Location>> groupLocationsByFile(List<Location> locations, String cwd) {
        Map<String, List<Location>> byFile = new LinkedHashMap<>();
        for (Location loc : locations) {
            String key = formatUri(loc.getUri(), cwd);
            byFile.computeIfAbsent(key, _ -> new ArrayList<>()).add(loc);
        }
        return byFile;
    }

    private static Map<String, List<SymbolEntry>> groupSymbolsByFile(
            List<SymbolEntry> symbols, String cwd) {
        Map<String, List<SymbolEntry>> byFile = new LinkedHashMap<>();
        for (SymbolEntry s : symbols) {
            if (s.location() == null || s.location().getUri() == null) {
                continue;
            }
            String key = formatUri(s.location().getUri(), cwd);
            byFile.computeIfAbsent(key, _ -> new ArrayList<>()).add(s);
        }
        return byFile;
    }

    private static Map<String, List<CallHierarchyIncomingCall>> groupIncomingByFile(
            List<? extends CallHierarchyIncomingCall> calls, String cwd) {
        Map<String, List<CallHierarchyIncomingCall>> byFile = new LinkedHashMap<>();
        for (CallHierarchyIncomingCall call : calls) {
            if (call.getFrom() == null || call.getFrom().getUri() == null) {
                continue;
            }
            String key = formatUri(call.getFrom().getUri(), cwd);
            byFile.computeIfAbsent(key, _ -> new ArrayList<>()).add(call);
        }
        return byFile;
    }

    private static Map<String, List<CallHierarchyOutgoingCall>> groupOutgoingByFile(
            List<? extends CallHierarchyOutgoingCall> calls, String cwd) {
        Map<String, List<CallHierarchyOutgoingCall>> byFile = new LinkedHashMap<>();
        for (CallHierarchyOutgoingCall call : calls) {
            if (call.getTo() == null || call.getTo().getUri() == null) {
                continue;
            }
            String key = formatUri(call.getTo().getUri(), cwd);
            byFile.computeIfAbsent(key, _ -> new ArrayList<>()).add(call);
        }
        return byFile;
    }

    private static String formatCallHierarchyItem(CallHierarchyItem item, String cwd) {
        if (item.getUri() == null) {
            return item.getName() + " (" + symbolKindToString(item.getKind()) + ") - <unknown location>";
        }
        String filePath = formatUri(item.getUri(), cwd);
        int line = item.getRange().getStart().getLine() + 1;
        String kind = symbolKindToString(item.getKind());
        String result = item.getName() + " (" + kind + ") - " + filePath + ":" + line;
        if (org.apache.commons.lang3.StringUtils.isNotBlank(item.getDetail())) {
            result += " [" + item.getDetail() + "]";
        }
        return result;
    }

    private static Location workspaceSymbolToLocation(
            Either<Location, WorkspaceSymbolLocation> either) {
        if (either.isLeft()) {
            return either.getLeft();
        }
        // WorkspaceSymbolLocation carries only a URI (no range) — show it at 1:1.
        WorkspaceSymbolLocation wsl = either.getRight();
        return new Location(wsl.getUri(), new Range(new Position(0, 0), new Position(0, 0)));
    }

    // MarkedString is deprecated in newer lsp4j (superseded by MarkupContent),
    // but Hover.getContents() still declares this legacy shape as one branch of
    // its Either — a server may still reply this way. Suppression is scoped to
    // this method, which only reads the value the server sent.
    @SuppressWarnings("deprecation")
    private static String extractMarkupText(
            Either<List<Either<String, MarkedString>>, MarkupContent> contents) {
        if (contents == null) {
            return "";
        }
        if (contents.isRight()) {
            MarkupContent mc = contents.getRight();
            return mc != null ? mc.getValue() : "";
        }
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Either<String, MarkedString> item : contents.getLeft()) {
            if (!first) {
                sb.append("\n\n");
            }
            first = false;
            if (item.isLeft()) {
                sb.append(item.getLeft());
            } else {
                MarkedString ms = item.getRight();
                if (ms != null) {
                    sb.append(ms.getValue());
                }
            }
        }
        return sb.toString();
    }

    private static String symbolKindToString(SymbolKind kind) {
        if (kind == null) {
            return "Unknown";
        }
        return switch (kind.getValue()) {
            case 1 -> "File";
            case 2 -> "Module";
            case 3 -> "Namespace";
            case 4 -> "Package";
            case 5 -> "Class";
            case 6 -> "Method";
            case 7 -> "Property";
            case 8 -> "Field";
            case 9 -> "Constructor";
            case 10 -> "Enum";
            case 11 -> "Interface";
            case 12 -> "Function";
            case 13 -> "Variable";
            case 14 -> "Constant";
            case 15 -> "String";
            case 16 -> "Number";
            case 17 -> "Boolean";
            case 18 -> "Array";
            case 19 -> "Object";
            case 20 -> "Key";
            case 21 -> "Null";
            case 22 -> "EnumMember";
            case 23 -> "Struct";
            case 24 -> "Event";
            case 25 -> "Operator";
            case 26 -> "TypeParameter";
            default -> "Unknown";
        };
    }

}
