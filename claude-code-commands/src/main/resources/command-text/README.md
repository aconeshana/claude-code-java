# Built-in command text resources

Model-facing slash-command prompt text is organized by provenance and released
version, mirroring `claude-code-tools`'s `tool-text` layout:

```text
command-text/<provenance>/<version>/<command>/
```

Files are **verbatim wire extracts**: the bytes on disk are exactly the bytes
that go into the prompt. Blank lines around section heads, leading newlines on
appended sections, and trailing whitespace are all part of the contract, so
nothing is stripped or re-indented when loading.

- Official wire extracts, internal-only text, and Java extensions must use
  separate provenance directories; they must not share a baseline directory.
- `{{PLACEHOLDER}}` slots are the only templating. Shell-style variables
  (`${...}`) and angle-bracket metavariables (`<pr#>`) are literal prompt text
  and must survive loading unchanged.
- A file naming a bundle function (`phase-1-head-I3g.txt`) keeps the minified
  symbol it was extracted from, so a future re-capture can re-derive it.

## official/2.1.236/code-review

Extracted from the released 2.1.236 bundle's `/code-review` prompt assembly
(`FWE` and the level templates it branches on). Assembly order, level gating,
and which segments belong to which rung live in
`CodeReviewCommand#buildPrompt`; the files here carry only text.

| File | Bundle symbol | Notes |
|------|---------------|-------|
| `template-low.txt` | `N3g` | Self-contained: tag, two turns, own one-line output contract |
| `lead-in-medium.txt` | `$3g` head | Not captured on the wire (no medium variant); rebuilt from the bundle dump |
| `lead-in-high.txt` | `B3g` head | |
| `lead-in-extended.txt` | `U3g` head | `{{EFFORT_ADJECTIVE}}` → `extra-high` / `maximum` |
| `phase-0-gather-diff.txt` | `M8e` | |
| `phase-1-head-j_s.txt` | `j_s` | medium/high |
| `phase-1-head-I3g.txt` | `I3g` | xhigh/max |
| `angles-a-to-c.txt` | — | 3 correctness angles |
| `angles-d-e.txt` | — | 2 extra correctness angles (xhigh/max only) |
| `cleanup-angles.txt` | `GEr` tail | 5 cleanup angles |
| `pass-every-candidate.txt` | `GEr` tail | medium/high only, after the cleanup block |
| `verify-plain.txt` | `D3g` | plain 1-vote 3-state |
| `verify-recall-clause.txt` | — | recall-mode clause, xhigh/max only |
| `verify-recall-biased.txt` | `_WE` | high's recall-biased verify |
| `phase-3-sweep.txt` | `bWE` | xhigh/max only |
| `output-contract.txt` | `L3g` | `{{CAP}}` → 8 / 10 / 15 by level |
| `post-comment.txt` | `RWE` | `--comment`; file carries its own leading newlines |
| `apply-fixes.txt` | `IWE` | `--fix`; file carries its own leading newlines |

Frozen baseline: `claude-code-wire-tests` `S236-P1-CODE-REVIEW` (8 variants,
`request-01` prompt byte-identical).
