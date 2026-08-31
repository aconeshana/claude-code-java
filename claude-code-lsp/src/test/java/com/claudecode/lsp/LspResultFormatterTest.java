package com.claudecode.lsp;

import org.apache.commons.lang3.Strings;

import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;


class LspResultFormatterTest {

    private static Range range() {
        return new Range(new Position(0, 0), new Position(0, 1));
    }

    private static DocumentSymbol sym(String name, List<DocumentSymbol> children) {
        return new DocumentSymbol(name, SymbolKind.Function, range(), range(), null, children);
    }

    @Test
    void documentSymbolHeaderCountsNestedChildren() {
        // root1 has 2 nested children, root2 has none → 4 symbols total.
        DocumentSymbol childA = sym("childA", List.of());
        DocumentSymbol childB = sym("childB", List.of());
        DocumentSymbol root1 = sym("root1", List.of(childA, childB));
        DocumentSymbol root2 = sym("root2", List.of());

        List<Either<SymbolInformation, DocumentSymbol>> input = List.of(
            Either.forRight(root1), Either.forRight(root2));

        List<String> out = LspResultFormatter.formatDocumentSymbol(input, "/wd");
        assertEquals("Found 4 symbols:", out.getFirst());
    }

    @Test
    void documentSymbolHeaderHandlesEmpty() {
        List<String> out = LspResultFormatter.formatDocumentSymbol(List.of(), "/wd");
        assertEquals(1, out.size());
        assertTrue(Strings.CS.startsWith(out.getFirst(), "No symbols found"));
    }
}
