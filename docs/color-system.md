# Color System

This document describes the UI theme palettes, terminal color conversion, and
color diagnostics used by the Java client.

## 1. Ownership and module boundaries

The color system is implemented in the `claude-code-ui` module. Its primary
classes are under:

`claude-code-ui/src/main/java/com/claudecode/ui/lanterna/theme/`

The relevant classes are:

- `Themes`: the built-in semantic palettes.
- `Theme`: the immutable palette record.
- `RgbColor`: an RGB tuple with optional ANSI provenance.
- `AnsiPalette`: parsing and lookup for `ansi:*` palette values.
- `LanternaTheme`: active-theme selection and conversion to Lanterna
  `TextColor` values.
- `SystemTheme` and `SystemThemeWatcher`: system appearance detection and
  periodic refresh for the `auto` selection mode.

Within this implementation, `claude-code-core` supplies
`SubprocessEnvironment`, and Lanterna supplies the terminal color types and
renderer. The current production dependencies of `claude-code-ui` are
`claude-code-core`, `claude-code-commands`, `claude-code-permissions`,
`claude-code-tools`, `claude-code-lsp`, and `claude-code-runtime`; this boundary
is also enforced by `ModuleArchitectureTest`. The assembled application and its
Logback configuration live in `claude-code-app`.

## 2. End-to-end pipeline

~~~text
Semantic theme key -> Theme / Themes -> RgbColor -> LanternaTheme.toLC()
                   -> TextColor      -> Lanterna ANSI terminal -> SGR
~~~

For RGB-backed palette entries, `LanternaTheme.toLC(RgbColor)` returns:

- `TextColor.RGB` when the resolved color level is 3.
- `TextColor.Indexed` after xterm-256 quantization when the resolved level is
  0, 1, or 2.

For ANSI-backed entries, `toLC` returns the stored `TextColor.ANSI` value
without RGB quantization. Lanterna then emits the SGR sequence represented by
the selected `TextColor`.

The numeric level names follow Chalk's convention, but the Lanterna conversion
does not implement separate no-color or 16-color output modes for RGB-backed
theme values. Levels below 3 all use indexed 256-color output. Code that emits
ANSI strings directly has its own color-support checks in `Ansi`.

## 3. Theme definitions and selection

`Themes.java` defines six built-in palettes:

| Theme name | Java field | Purpose |
|---|---|---|
| `dark` | `Themes.DARK` | Default dark palette |
| `light` | `Themes.LIGHT` | Light palette |
| `dark-ansi` | `Themes.DARK_ANSI` | Dark palette based primarily on terminal ANSI colors |
| `light-ansi` | `Themes.LIGHT_ANSI` | Light palette based on terminal ANSI colors |
| `dark-daltonized` | `Themes.DARK_DALTONIZED` | Dark deuteranopia-friendly palette |
| `light-daltonized` | `Themes.LIGHT_DALTONIZED` | Light deuteranopia-friendly palette |

`LanternaTheme.Scheme.AUTO` is a selection mode rather than a seventh palette.
It resolves to `dark` or `light` through `SystemTheme`. Detection first checks
`COLORFGBG`, then runs platform-specific commands with a 500 ms timeout on
macOS, Linux, or Windows. If neither source resolves an appearance,
`SystemTheme` uses `dark`. While `auto` is active, `SystemThemeWatcher` repeats
detection every five seconds and invokes the registered callback when the
resolved appearance changes. The selected theme is stored under `theme` in the
global configuration. Its default is `dark`; select `auto` explicitly through
the theme picker or configuration.

`RgbColor.parse` accepts decimal `rgb(r,g,b)` literals and validates each
channel as an integer from 0 through 255. `AnsiPalette.resolve` accepts both
`rgb(r,g,b)` and case-sensitive `ansi:*` values such as
`ansi:magentaBright`. Hexadecimal and `ansi256(N)` literals are not accepted by
these parsers, and the built-in palettes do not use those formats.

ANSI-backed colors retain a canonical RGB fallback for comparisons and
non-ANSI consumers, together with a `TextColor.ANSI` value for terminal output.
Consequently, ANSI themes normally leave the final displayed color to the
user's terminal palette. A small number of fields may intentionally remain
RGB-backed; for example, `Themes.DARK_ANSI.professionalBlue()` is RGB.

## 4. Color-level resolution

`LanternaTheme.resolveChalkLevel` resolves its process-wide color level once,
when `LanternaTheme` is initialized. The precedence is:

1. A numeric `FORCE_COLOR` value from 0 through 3 selects that level.
2. If `NO_COLOR` is present, select level 0.
3. If `CLAUDE_CODE_FORCE_TRUECOLOR=1`, select level 3.
4. If `TERM=dumb`, select level 1.
5. If `TERM`, `COLORTERM`, and `TERM_PROGRAM` are all absent, select level 1.
6. Otherwise, select level 2.

Invalid or out-of-range `FORCE_COLOR` values are ignored. `FORCE_COLOR`,
`NO_COLOR`, and `CLAUDE_CODE_FORCE_TRUECOLOR` are read through
`SubprocessEnvironment`, so settings and SDK runtime environment overlays can
affect them. `TERM`, `COLORTERM`, and `TERM_PROGRAM` are read directly from the
process environment.

The default for a normal terminal is deliberately level 2 rather than automatic
truecolor detection. Set `CLAUDE_CODE_FORCE_TRUECOLOR=1` or `FORCE_COLOR=3`
before starting the process to request truecolor output.

For example, at level 2, Claude orange `rgb(215,119,87)` becomes xterm index
174, whose cube sample is `rgb(215,135,135)`. Pink 600
`rgb(219,39,119)` becomes index 168, whose cube sample is
`rgb(215,95,135)`.

## 5. 256-color quantization

`LanternaTheme.rgbToAnsi256(r, g, b)` follows the `color-convert` algorithm used
by Chalk 4.x:

~~~text
Grayscale (r == g == b):
  r < 8       -> 16
  r > 248     -> 231
  otherwise   -> 232 + round((r - 8) / 247 * 24)

Color:
  16 + 36 * round(r / 255 * 5)
     +  6 * round(g / 255 * 5)
     +      round(b / 255 * 5)
~~~

The use of `round`, rather than integer truncation, is significant near color
cube boundaries. The corresponding xterm cube samples are 0, 95, 135, 175,
215, and 255.

`Ansi` contains the same private quantization formula for direct SGR string
rendering. This avoids coupling its escape-sequence construction to Lanterna
color types while keeping direct ANSI output consistent with the TUI theme
conversion.

## 6. Component-specific behavior

Most UI colors are resolved through semantic `LanternaTheme` accessors. Notable
examples include:

- Input-panel banner badges use `inverseText()` for the badge foreground and
  `agentCyan()` when no session-specific banner color is available.
- Permission risk indicators use `toolSuccess()`, `toolWarning()`, and
  `toolError()`.
- Structured diff rendering uses `LanternaTheme.diffRenderPalette()`, which has
  dedicated dark, light, daltonized, and ANSI behavior rather than reusing all
  general-purpose diff theme keys.

`EffortSliderDialog` is an exception: its effort indicators use hard-coded
`TextColor.RGB` values, including five level colors and a multicolor `max`
label. These values do not derive from the active palette or pass through
`LanternaTheme.toLC()`.

## 7. Verification and diagnostics

Theme palette and conversion behavior is covered primarily by:

- `ThemesTest`
- `AnsiPaletteAndAnsiThemesTest`
- `LanternaThemeIntegrationTest`
- `DiffRenderPaletteTest`
- renderer and component tests under `claude-code-ui/src/test/java`

Run the focused theme tests with:

~~~bash
./gradlew :claude-code-ui:test --tests 'com.claudecode.ui.lanterna.theme.*'
~~~

At startup, `LanternaReplScreen.run` logs a diagnostic line similar to:

~~~text
[LANTERNA] chalkLevel=2 (3=truecolor, 2=256-color, 1=16-color); TMUX=false, TERM_PROGRAM=iTerm.app, COLORTERM=truecolor, TERM=xterm-256color
~~~

The parenthetical text is Chalk-style level terminology; the diagnostic does
not list level 0 even though `chalkLevel()` may return it. As described above,
RGB-backed theme values at levels 0 through 2 currently become Lanterna indexed
colors.

The default application log is `/tmp/claude-code-java.log`. Logback is
configured in `claude-code-app/src/main/resources/logback.xml`, and the default
log level is `INFO`. Set `CLAUDE_CODE_LOG_LEVEL=DEBUG` before launch for more
verbose diagnostics.

To compare raw truecolor and quantized output manually:

~~~bash
printf "level=3: \033[48;2;219;39;119m\033[97m hoho \033[0m\n"
printf "level=2: \033[48;5;168m\033[97m hoho \033[0m\n"
~~~

When sampling a terminal screenshot, measure the center of a solid color block,
away from glyph strokes. Antialiasing blends thin glyphs and line characters
with the terminal background, so a pixel sampled from a stroke may be much
darker than the emitted foreground color.
