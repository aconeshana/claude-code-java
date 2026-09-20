# Brand Assets

The web client's icon set lives in `webui/public/`. Vite copies that directory
verbatim into `dist/webui/`, and the gateway serves it from the classpath under
the `/webui/` prefix — which is why every `<link rel="icon">` in
`webui/index.html` carries that prefix rather than a bare root path.

## Files

| File | Source | Used for |
| --- | --- | --- |
| `code-orb-master.svg` | authored | canonical mark, 24 px and above |
| `code-orb-favicon.svg` | authored | 16 px browser tab geometry |
| `favicon-16.png` | rendered from `code-orb-favicon.svg` | 16 px tab |
| `favicon-32.png`, `icon-24/32/48/64.png` | rendered from `code-orb-master.svg` | tabs, app tiles |
| `favicon.ico` | 16 px from the favicon SVG, 32 and 48 px from the master | legacy fallback, three frames |
| `artwork/icon-180.png`, `artwork/icon-512.png` | rendered from `code-orb-master.svg` | Apple touch icon, store art |

Everything but the last row lives in `webui/public/`. The two large sizes live
in `artwork/` at the repository root and are **gitignored**: both are plain
renders of `code-orb-master.svg`, so they are regenerated rather than carried in
history.

One consequence of that is deliberate and worth stating plainly.
`webui/index.html` still declares `<link rel="apple-touch-icon"
href="/icon-180.png">`, so in a fresh clone that link resolves to nothing until
someone runs the regeneration below and copies `icon-180.png` into
`webui/public/`. Nothing else degrades — the tab favicon, the `.ico` fallback
and every in-app mark are committed.

Every raster is rendered independently from vector at its target size. None is
a downscale of a larger PNG, so small sizes keep their own edge alignment.

## Palette

| Token | Value |
| --- | --- |
| Signal red | `#EC3431` |
| Graphite | `#16181A` |
| Warm white | `#F6F6F4` |

Backgrounds are transparent. Do not add shadows, glows, or gradients.

**These values are authoritative and supersede the `code-orb-logo-delivery-v1`
delivery notes.** That document lists `#F04444` / `#181A1B` / `#F7F7F5`, which
describe a different export of the mark than the one adopted here. Measuring the
adopted rasters recovers the values in the table above; taking the delivery
note's palette instead would shift every red pixel in the mark.

## Regenerating

`rsvg-convert` renders the set from the two SVGs:

~~~bash
cd webui/public
rsvg-convert -w 16 -h 16 code-orb-favicon.svg -o favicon-16.png
for s in 24 32 48 64; do
  rsvg-convert -w $s -h $s code-orb-master.svg -o icon-$s.png
done
cp icon-32.png favicon-32.png

# The two untracked masters, rendered from the same source into artwork/.
for s in 180 512; do
  rsvg-convert -w $s -h $s code-orb-master.svg -o ../../artwork/icon-$s.png
done
~~~

`favicon.ico` is a hand-packed multi-frame container (PNG-in-ICO); Pillow's ICO
writer cannot be used directly because it rescales one source image to every
frame, which would discard the separately cut 16 px geometry.

Below 24 px, prefer `code-orb-favicon.svg` over the master: its strokes are
re-cut for the smaller grid instead of being mechanically reduced.

### Re-cutting the favicon glyph

The favicon's orb, band and hub are authored for the 16 px grid, but the `</>`
glyph is *derived* from the master rather than drawn: take the master's three
paths, express them relative to their own bounding-box centre, scale by
`2.43 / (76.5 / 32)` (this hub over the master's) times a widening factor, and
re-place the centre at `(8, 7.85 + 0.02 * 2.43)` — the master's own 2% optical
drop below the hub centre.

The widening factor exists because the glyph carries 1.36x the master's relative
stroke weight, and that extra ink grows inward from both sides of each wedge. At
the shipped 1.06 the wide wedges land within 0.1% of the master's and the tight
ones within 5%, with 0.34 of clearance left to the pale ring. Changing the
stroke weight means re-deriving the factor, not nudging coordinates.

Hand-tuning the glyph is how it broke before: a hand-cut slash leaned 1.44x the
master's angle, which crushed the lower-left and upper-right wedges to 66% of
the master's while the other two looked correct.
