# board-svg

`BoardSvg` draws a training board the way it looks in a gym: a dark textured panel with
a route lit on it. `BoardLegend` is the key for the role cues. Everything is drawn from
scratch in SVG. There are no board-maker images, logos or artwork.

## The holds are made up (deterministically)

The contract gives each `BoardHold` only `hold_id`, `x` and `y`. It has no type, size,
colour or picture. So the silhouette at each position is invented, and it will not match
the real hold on that bolt. Real shapes and sizes would need the catalog's hold-set data
and a contract change.

The invented look is stable. `holdLook(hold_id, rise)` in `geometry.ts` works like this:

1. `hashSeed` hashes the id with 32-bit FNV-1a.
2. The hash seeds `seededRandom`, a mulberry32 PRNG.
3. The PRNG picks, always in the same order:
   - **Shape:** one of 8 silhouettes (crimp, edge, jug, pinch, sloper, pocket, and two
     foot chips). The foot chips are used only in the bottom 10% of the board.
   - **Rotation:** in degrees.
   - **Scale:** biggest in the middle band.
   - **Resin colour:** one of 6, as `hold--r0` to `hold--r5`.

The same id always looks the same, on every render and in every test.

The 8 silhouettes (`HOLD_SHAPE_PATHS`) are built from fixed-seed jittered polar points
and smoothed with quadratic curves. Each one is pre-rotated to 8 turns (`HOLD_TURNS`), so
that the light baked into it stays fixed while the hold turns:

- a gradient lit from the top left;
- a shadow offset down and to the right.

A hold uses the nearest turn, plus at most ±22.5° of its own rotation.

Holds are sized from the board's spacing (`holdPitch`). That covers the 144-hold
synthetic board as well as a dense layout of about 700 holds. The T-nut grid step comes
from `gridStep`. All positions still go through `viewBoxFor` and `toSvgPoint`, so
NORMALIZED or MILLIMETRES units and `y_axis` UP or DOWN behave as before.

## LEDs and role cues

Each role has a Kilter-style LED colour, a ring style and a badge letter:

| Role   | LED colour (token)           | Ring        | Badge |
| ------ | ---------------------------- | ----------- | ----- |
| START  | green `--board-led-start`    | double ring | S     |
| HAND   | cyan `--board-led-hand`      | solid ring  | H     |
| FOOT   | orange `--board-led-foot`    | dashed ring | f     |
| FINISH | magenta `--board-led-finish` | ring + bar  | F     |

Colour is never the only signal. Each lit hold also has its ring style and badge letter
(`RoleCue`). The ring and badge colours have at least 6:1 contrast against the panel (3:1
is required). The letters have at least 6.8:1 against their badges. Each placement keeps
its own `<title>`, and the visually hidden ordered list stays as before, wired with
`aria-describedby`. `highlightRole` dims the other roles with `is-dim`.

## Layers and performance

All shapes, the gradient, the T-nut pattern and the single bloom blur filter are defined
once in `<defs>`. Holds are placed with `<use>` and a transform. One blur filter covers
the whole group of LED glows.

When a route is lit, the vignette darkens the board like a dark gym. The placed holds are
drawn above it, in `.board__lit`, so they show at full strength.

The film grain is a CSS `::after` overlay on the figure, so it costs no JS. Under
`prefers-reduced-motion: no-preference` the LED glow "breathes", animating opacity only.

Every colour, plus the glow and dim strengths, is a custom property at the top of
`board-svg.css`. The board stays dark in both themes; the paper border and the legend
text follow the page theme.
