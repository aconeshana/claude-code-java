# DiffRenderer Guide

`com.claudecode.ui.DiffRenderer` in the `claude-code-ui` module provides two
related rendering paths for unified diffs:

1. The ANSI string API colors raw unified-diff text for terminal-oriented
   output.
2. The structured hunk API converts `StructuredPatchHunk` values from
   `claude-code-core` into gutter-aware, word-diffed line views for Lanterna
   consumers.

Do not mix the two paths. The ANSI API returns strings containing terminal SGR
sequences when color is supported. The structured API returns `DiffLineView`
values for direct Lanterna drawing; Lanterna does not interpret ANSI escape
sequences passed through its `TextColor` drawing path.

## 1. ANSI string API

| Method | Purpose | Notes |
|---|---|---|
| `String renderDiffLine(String line)` | Color one unified-diff line | `@@` uses the theme's subtle color, `+` uses `diffAdded`, `-` uses `diffRemoved`, other content uses dim styling, and `---`/`+++` file headers use bold styling |
| `String renderUnifiedDiff(String diff)` | Color every line | Null and empty input return an empty string; non-empty output ends with a newline |
| `String generateDiff(String oldText, String newText, String fileName, int contextLines)` | Generate unified-diff text | Uses the package-private LCS implementation and hunk grouping logic in `DiffRenderer` |
| `String generateAndRenderDiff(String oldText, String newText, String fileName)` | Generate and color a diff | Uses three context lines, then calls `renderUnifiedDiff` |
| `List<DiffLine> computeDiff(String[] oldLines, String[] newLines)` | Run the line-level LCS algorithm | Package-private and used by `generateDiff` and tests |

The added, removed, and hunk-header colors come from
`LanternaTheme.activeTheme()`. `Ansi.isColorSupported()` controls whether the
renderer emits SGR sequences at all. For RGB theme colors, chalk level 3 emits
24-bit RGB SGR; lower color-enabled levels use xterm-256 quantization.

~~~java
String colored = DiffRenderer.renderUnifiedDiff(rawDiffText);
System.out.print(colored);

String diff = DiffRenderer.generateAndRenderDiff(oldCode, newCode, "Foo.java");
~~~

## 2. Structured hunk API

The structured API has two overloads:

~~~java
static List<DiffLineView> renderHunk(StructuredPatchHunk hunk)
static List<DiffLineView> renderHunk(StructuredPatchHunk hunk, String language)
~~~

The first overload produces diff segmentation without syntax foreground
colors. The second optionally decorates the segments with TextMate-derived
foreground colors when the language is a registered `TmTokenizer` alias and
syntax highlighting is not disabled by the effective
`syntaxHighlightingDisabled` setting. Unsupported languages, disabled syntax
highlighting, and grammar-loading failures fall back to the undecorated
structured diff.

### 2.1 Input

`StructuredPatchHunk` is defined in `claude-code-core` and contains:

- `oldStart`, `oldLines`, `newStart`, and `newLines` from the unified hunk
  header;
- an immutable `lines` list whose change and context prefixes are preserved as
  `+`, `-`, or a space. The renderer also treats an empty string as an empty
  context line.

### 2.2 Output model

~~~java
enum SegKind { COMMON, ADDED, REMOVED, HUNK }
record Segment(String text, SegKind kind, RgbColor foreground) {}
record DiffLineView(Integer lineNo, char marker, List<Segment> segments) {}
~~~

`Segment.foreground` is null for an undecorated diff segment. When a language is
provided and tokenization succeeds, the renderer may split an existing segment
at token boundaries and assign syntax foreground colors without changing its
`SegKind`.

Each output row has:

| Field | Meaning |
|---|---|
| `lineNo` | The single displayed line number. Context and added rows advance the counter. A consecutive removed block receives sequential numbers, after which the counter returns to the block's first number; an immediately following added block reuses numbers from that position. After a removal-only block, the next context or added row also starts at the block's first number. The synthetic hunk header has no line number. |
| `marker` | The source prefix character: space, `+`, `-`, or `@` for the synthetic header |
| `segments` | The content with the source prefix removed, split into diff and optional syntax-colored runs |

### 2.3 Word-level diff

`renderHunk` pairs a consecutive block of removed rows with the immediately
following block of added rows and performs a token-level LCS for each pair.
Unchanged tokens remain `COMMON`; changed tokens receive `ADDED` or `REMOVED`
classification.

- Tokens are split with the equivalent of `(\s+|[^\s]+)`, preserving
  whitespace.
- `removes[k]` pairs with `adds[k]`, and output order remains all removals
  followed by all additions.
- Unpaired rows are classified as full-line `ADDED` or `REMOVED` content.
- If changed characters exceed 0.4 of the combined character count for a pair,
  both rows fall back to full-line change classification.

~~~java
var hunk = new StructuredPatchHunk(1, 2, 1, 2, List.of(
        "-const foo = 1",
        "+const bar = 1"));
List<DiffRenderer.DiffLineView> output = DiffRenderer.renderHunk(hunk, "java");
~~~

The output starts with a synthetic hunk header. Subject to the change-threshold
calculation, shared text remains `COMMON`, `foo` is `REMOVED`, and `bar` is
`ADDED`. The `java` language argument may add syntax foreground colors to those
same segments.

### 2.4 Synthetic hunk header

`renderHunk` always synthesizes an `@@ -a,b +c,d @@` row from the hunk
coordinates. The first row has marker `@`, `lineNo` null, and `SegKind.HUNK`.
`StructuredPatchHunk` normally contains only hunk content lines. If its `lines`
list includes an embedded `@@` header, the renderer skips that row because the
canonical header has already been synthesized.

## 3. Lanterna consumers

The structured renderer is shared by several UI paths:

- `DiffDialog` renders interactive `/diff` details.
- `PermissionDialog` renders file-edit, notebook-edit, and sed-edit previews.
- `LanternaMessageDispatcher` renders structured edit results and rejected diff
  previews inline in the transcript.

Consumers should pass a language identifier when it can be derived from the
file path or request metadata. This preserves the common word-diff behavior and
enables syntax foreground colors without duplicating tokenization or LCS logic.

`DiffDialog` uses the following pipeline:

~~~java
List<DiffRenderer.DiffLineView> views = DiffRenderer.renderHunk(hunk, language);
for (DiffRenderer.DiffLineView view : views) {
    List<DiffRenderer.DiffLineView> wrapped = wrapDiffLineView(view, availableWidth);
    for (int i = 0; i < wrapped.size(); i++) {
        out.add(Row.diffView(wrapped.get(i), gutterWidth, i > 0));
    }
}
~~~

Its drawing path:

1. Computes a gutter width from the largest displayed line number: digit count
   plus three characters for spacing and the marker.
2. Right-aligns the line number and draws the marker in the gutter.
3. Uses `LanternaTheme.diffRenderPalette()` for added and removed line
   backgrounds, changed-word backgrounds, and gutter decorations. ANSI-only
   themes provide decorations without background fills.
4. Uses a segment's syntax foreground when present; otherwise it uses
   `inputText`, except that `HUNK` uses `subtle`.
5. Wraps long rows to the available content width. Continuation rows display a
   blank gutter while retaining the row marker internally so their change
   background remains consistent.

The transcript renderer also computes its gutter width from the rendered line
numbers and maps the same line, word, decoration, and syntax colors into
`MessagePanel` segments. Rejected previews dim those colors. Permission previews
consume the same structured views, but use a fixed four-character line-number
field plus their surrounding indentation.

## 4. Rendering contract and limitations

The structured renderer combines three independent layers:

- row classification from the original unified-diff prefix;
- token-level added, removed, and common segmentation for adjacent change
  pairs;
- optional TextMate syntax foreground colors from `TmTokenizer` and
  `ScopeColorMap`.

Diff classification is retained when syntax colors are applied. Consumers are
responsible for mapping `SegKind` to line and word backgrounds and for drawing
the gutter. They should not parse ANSI output or implement a separate word-diff
algorithm.

`DiffDialog` currently wraps by Java string length and substring boundaries,
not terminal display-cell width. Wide and combining characters therefore may
not wrap at the same visual column as plain ASCII text.

## 5. Verification and common pitfalls

- Preserve the prefix character in every `StructuredPatchHunk.lines()` entry.
  Removing the leading context space changes row classification and strips the
  first content character.
- Keep the diff kind when splitting segments to add syntax foreground colors.
- Choose the gutter policy for the consumer: `DiffDialog` and the transcript
  renderer derive the width from rendered line numbers, while permission
  previews intentionally use a fixed four-character number field.
- Theme-dependent syntax foregrounds are resolved by `renderHunk`, while
  consumer palettes are resolved during drawing. Rebuild structured views after
  a theme change if previously rendered views are retained.
- ANSI assertions must account for both `Ansi.isColorSupported()` and
  `LanternaTheme.chalkLevel()`.
- `DiffRendererTest` covers ANSI rendering, unified-diff generation, structured
  numbering and segmentation, syntax decoration, and the
  `syntaxHighlightingDisabled` fallback. `DiffRenderPaletteTest` checks
  representative dark, light, daltonized, and ANSI palette behavior.

Run the focused UI tests with:

~~~bash
./gradlew :claude-code-ui:test
~~~
