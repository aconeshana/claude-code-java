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
