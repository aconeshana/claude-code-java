# Built-in tool text resources

Tool text is organized by provenance, released version, and semantic channel:

```text
tool-text/<provenance>/<version>/
  prompts/
  descriptions/
```

- `prompts` contains model-facing tool-definition text and is required.
- `descriptions` is a sparse presentation-text overlay. When an override is
  absent, the description intentionally inherits the matching prompt.
- Tools with runtime variants use a tool directory, for example
  `prompts/CronCreate/durable.txt`.
- Official wire extracts, internal-only tools, and Java extensions must use
  separate provenance directories; they must not share a baseline directory.

## Active baseline and staged baselines

`ToolTexts` loads only `official/2.1.197/`. Other version directories are
staged for a future baseline bump and are not read at runtime.

`official/2.1.236/prompts/Edit*` is staged from the 2.1.236 bundle (`KwS`).
`Edit.txt` is a template; the `Edit/` fragments fill its placeholders by gate:

| Gate in the 236 bundle | Fragment |
|---|---|
| `tengu_tab_read_sep` on | `line-prefix-tab-or-colon.txt` (else `line-prefix.txt`) |
| `preReadLineDropped` on (and not `CLAUDE_CODE_SIMPLE`) | `read-requirement-outside-cwd.txt` (else `read-requirement.txt`) |
| `tengu_edit_minimalanchor_jrn` on | `uniqueness-minimal-anchor.txt` (else `uniqueness.txt`) |
| `leanPrompt` on | `lean.txt` with the `lean-*` fragments instead of `Edit.txt` |

Differences from 197: no blank line between the first line and `Usage:`, and the
three gated fragments plus the lean template do not exist in 197. The lean
template was reconstructed from the bundle's template-literal pieces (the
`${r?...}` conditional is absent from the extracted source), so confirm it
against the binary before wiring it up.
