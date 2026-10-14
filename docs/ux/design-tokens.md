# Beacon Climb design tokens (BC-0008)

Status: DRAFT for review. Owner: product-designer. Contract basis: `contracts/openapi.yaml` 1.0.0 (CONTRACT-v1, tag pending human review).
Consumer: `apps/web/src/styles/tokens.css` (BC-0114, later; not written here). Every value below is a design decision, not a measurement. Contrast ratios in section 2 were computed with a WCAG 2.x relative-luminance script on the exact hex values listed; they are checked values, and the stated thresholds (text >= 4.5:1, UI components and graphics >= 3:1) remain the acceptance targets for later visual/axe tests.

## 1. Style direction: "Topo Field Guide"

A printed climbing-guidebook topo, not a dashboard. Warm uncoated paper, ink-black type, survey-map linework, stamped labels, and a board drawn as a plywood sheet with hold glyphs that read like topo symbols (a hold role is a shape first, a color second).

Principles that drive every token:

1. The board SVG is the hero. It gets the largest surface, the only texture, and the only layered depth on screen.
2. Ink is the primary action color. The single accent ("beacon teal") is reserved for recommendations and nothing else, so teal on screen always means "Beacon suggested this".
3. Warning (ochre) and error (brick) are separate semantic channels and never reuse the accent or a hold-role color.
4. Role and state are always carried by shape, pattern or text; color only reinforces (PRD 4.3).
5. No "AI sparkle" visuals (PRD 4.1). Explanations are plain labelled lists.

Deliberate departures from template UI: warm paper surface instead of white/gray; a serif display face for route names and grades; hard offset "sheet" shadows instead of soft blurred cards; non-uniform radii (square stamps, round sheets); a contour-line texture only behind the board; hold glyphs with distinct silhouettes.

## 2. Color tokens

Both sets define the same names. Dark set ("Lamp-lit crag") is selected with `prefers-color-scheme: dark` and may be forced with `[data-theme]`. Contrast values are measured against the stated background.

```css
:root,
[data-theme="light"] {
  color-scheme: light;

  /* Surfaces: paper stack */
  --color-surface: #f6f1e7;          /* page */
  --color-surface-raised: #fffdf8;   /* sheets, inputs */
  --color-surface-sunken: #ece5d6;   /* wells, table stripes */
  --color-board-face: #e6d8b9;       /* plywood face behind holds */
  --color-border: #857e6c;           /* UI boundary: 3.59:1 on surface, 3.97:1 on raised */
  --color-border-subtle: #d3cab6;    /* decorative only, never the sole boundary */

  /* Text */
  --color-text: #1d1b16;             /* 15.28:1 on surface, 16.92:1 on raised */
  --color-text-muted: #5a5446;       /* 6.68:1 on surface, 6.00:1 on sunken */
  --color-text-inverse: #fffdf8;     /* on ink buttons */

  /* Primary action = ink (NOT the accent) */
  --color-action: #1d1b16;
  --color-action-hover: #3a362b;

  /* The one accent: recommendations only */
  --color-accent: #00606e;           /* 6.44:1 on surface, 5.14:1 on board face */
  --color-accent-bg: #dcefef;        /* accent text on this: 6.09:1 */
  --color-on-accent: #ffffff;        /* on solid accent: 7.25:1 */

  /* Semantics (text on surface / text on own bg) */
  --color-warning: #7a4e00;          /* 6.39 / 6.11 */
  --color-warning-bg: #fbebc8;
  --color-error: #a3231b;            /* 6.63 / 5.81 */
  --color-error-bg: #f8ddd9;
  --color-success: #2e5e2a;          /* 6.78 / 6.16 (saved / synced only) */
  --color-success-bg: #ddebd8;

  /* Hold roles, each vs --color-board-face (graphics need >= 3:1) */
  --color-hold-start: #3f5a1e;       /* 5.53:1 */
  --color-hold-hand: #243556;        /* 8.66:1 */
  --color-hold-foot: #7f3f22;        /* 5.65:1 */
  --color-hold-finish: #612857;      /* 7.64:1 */
  --color-hold-neutral: #857e6c;     /* unknown role / insufficient data; no contrast claim, always paired with the "?" glyph and text */

  /* Focus: two-tone ring (ink + surface gap) works on paper and on board */
  --color-focus: #1d1b16;            /* 15.28:1 on surface, 12.2:1 on board face */
  --color-focus-gap: #fffdf8;

  --texture-contour-opacity: 0.07;
}

/* tokens.css (BC-0114) declares the dark block twice: once under
   @media (prefers-color-scheme: dark) { :root:not([data-theme="light"]) { ... } }
   and once under [data-theme="dark"]. Values are identical to the block below. */

[data-theme="dark"] {
  color-scheme: dark;

  --color-surface: #16140f;
  --color-surface-raised: #201d16;
  --color-surface-sunken: #0f0e0a;
  --color-board-face: #2a261c;
  --color-border: #7d7664;           /* 4.07:1 on surface, 3.72:1 on raised */
  --color-border-subtle: #3a362b;

  --color-text: #f0eadb;             /* 15.33:1 on surface, 14.01:1 on raised */
  --color-text-muted: #b3ac9a;       /* 8.14:1 on surface */
  --color-text-inverse: #16140f;

  --color-action: #f0eadb;
  --color-action-hover: #d9d2bf;

  --color-accent: #6cd0dc;           /* 10.25:1 on surface, 8.40:1 on board face */
  --color-accent-bg: #14363b;        /* accent text on this: 7.22:1 */
  --color-on-accent: #0f0e0a;        /* on solid accent: 10.75:1 */

  --color-warning: #f0b84a;          /* 10.22 / 7.69 */
  --color-warning-bg: #3a2a0a;
  --color-error: #ff8a80;            /* 8.06 / 6.88 */
  --color-error-bg: #3e1714;
  --color-success: #9ed08f;          /* 10.40 / 8.04 */
  --color-success-bg: #18301a;

  --color-hold-start: #a9cb73;       /* 8.24:1 on board face */
  --color-hold-hand: #9fb6e8;        /* 7.42:1 */
  --color-hold-foot: #e6a07a;        /* 6.95:1 */
  --color-hold-finish: #d69ac9;      /* 6.70:1 */
  --color-hold-neutral: #7d7664;

  --color-focus: #f0eadb;            /* 15.33:1 on surface, 12.57:1 on board face */
  --color-focus-gap: #16140f;

  --texture-contour-opacity: 0.1;
}
```

Rules:

- Accent use list (exhaustive): recommendation badge, recommended-route hold rays on the board, "Why this route" link inside a recommendation card, the freshness chip on a recommendation lineup. Nothing else, including primary buttons, links in nav, or focus.
- Warning is used for: simplified-recommendation (fallback) banner, stale/cached data, offline draft not yet synced, grade not established. Error is used for failed requests and validation. Success is used only for saved/synced state, always with text.
- `--color-hold-neutral` has no contrast claim; a hold in neutral is always accompanied by the "?" glyph and text, never relied on alone.
- Disabled controls are exempt from contrast rules but still show a text reason when the reason is not obvious (WCAG 1.4.3 exception; we go further).
- High-contrast / forced-colors: all hold glyphs use `stroke: CanvasText` / `fill` system colors in `@media (forced-colors: active)`; shapes and labels remain, so meaning survives.

## 3. Typography

Two families, both open-licensed (OFL), self-hosted as subsetted WOFF2 (Latin), `font-display: swap`, so CSP stays `font-src 'self'`. Preload only IBM Plex Sans 400 (the primary text weight).

| Role | Family | Why |
|---|---|---|
| Display: screen titles, route names, grade numerals, "stamp" headings | Fraunces (variable; optical size axis on, `SOFT` low) | Warm, bookish serif with character; reads as guidebook, not SaaS |
| UI + body + data: labels, controls, copy, tables, coordinates | IBM Plex Sans (400, 500, 600) with `font-variant-numeric: tabular-nums` | Legible at small sizes, true tabular figures for grades, counts and timestamps; no third mono face needed |

```css
:root {
  --font-display: "Fraunces", "Iowan Old Style", Georgia, serif;
  --font-ui: "IBM Plex Sans", system-ui, -apple-system, "Segoe UI", sans-serif;

  /* Fluid scale: min at 320px viewport, max at 1440px */
  --text-xs: clamp(0.75rem, 0.73rem + 0.1vw, 0.8125rem);    /* captions, hold labels; never below 12px */
  --text-sm: clamp(0.875rem, 0.85rem + 0.12vw, 0.9375rem);  /* secondary, table cells */
  --text-base: clamp(1rem, 0.96rem + 0.2vw, 1.0625rem);    /* body, form controls (>= 16px avoids iOS zoom) */
  --text-md: clamp(1.125rem, 1.06rem + 0.3vw, 1.25rem);
  --text-lg: clamp(1.375rem, 1.2rem + 0.8vw, 1.75rem);     /* section headings (display font) */
  --text-xl: clamp(1.75rem, 1.4rem + 1.5vw, 2.5rem);       /* screen titles (display font) */
  --text-grade: clamp(2.25rem, 1.8rem + 2vw, 3.5rem);      /* grade numeral on route sheet (display font) */

  --leading-tight: 1.15;
  --leading-body: 1.5;
  --tracking-stamp: 0.08em;   /* uppercase stamp labels: START, RECOMMENDED, SIMPLIFIED */
  --measure: 62ch;            /* max line length for explanations */
}
```

Headings use `--font-display` with weight 600; stamps (small uppercase badges) use `--font-ui` 600 with `--tracking-stamp`. Text must reflow at 200% zoom and 320px width without horizontal scroll (WCAG 1.4.4, 1.4.10); no fixed-height text containers.

## 4. Spacing, radius, elevation

```css
:root {
  /* 4px base; deliberate rhythm: tight inside components, generous between sections */
  --space-1: 0.25rem;  --space-2: 0.5rem;   --space-3: 0.75rem;
  --space-4: 1rem;     --space-5: 1.5rem;   --space-6: 2rem;
  --space-7: 3rem;     --space-8: 4.5rem;
  --space-gutter: clamp(1rem, 0.6rem + 2vw, 2rem);
  --space-section: clamp(2rem, 1.4rem + 3vw, 4.5rem);
  --space-board-pad: clamp(0.5rem, 0.3rem + 1.5vw, 1.5rem);

  --size-hit-min: 44px;         /* pointer target for buttons, list rows, hold hit areas at the default zoom */
  --size-content-max: 72rem;
  --size-reading-max: 40rem;

  /* Non-uniform radii: stamps are square, sheets are soft, inputs sit between */
  --radius-stamp: 2px;
  --radius-input: 6px;
  --radius-sheet: 16px;
  --radius-pill: 999px;
  --border-width: 1.5px;

  /* Elevation = stacked paper: hard offset shadow, no blur */
  --elevation-0: none;
  --elevation-sheet: 3px 3px 0 var(--color-border-subtle);           /* cards/lineup items */
  --elevation-board: 6px 6px 0 var(--color-border-subtle), 0 0 0 var(--border-width) var(--color-border);
  --elevation-overlay: 8px 8px 0 var(--color-text);                    /* dialogs, bottom sheet */
  --z-board-controls: 10;  --z-sticky: 20;  --z-overlay: 40;  --z-toast: 50;
}
```

Layering: board sits on a sheet (elevation-board) over the page; a hold callout and the bottom sheet overlap the board edge on mobile (depth through overlap), not through blur.

Breakpoints (min-width, mobile-first): 0 (320 verified), 480, 768 (two-column forms and session list beside board), 1024 (three-zone layout: nav rail, main, aside), 1440 (wider board, aside stays 22rem). Test widths: 320, 375, 768, 1024, 1440, 1920.

## 5. Motion

Only `transform`, `opacity` and `clip-path` are animated. No animation of width/height/top/left/margin/padding/border/font-size. Motion explains state change (selection, new lineup, sheet open); it is never decorative looping.

```css
:root {
  --duration-instant: 90ms;
  --duration-fast: 150ms;     /* hold select, button press */
  --duration-normal: 240ms;   /* sheet open, banner in */
  --duration-slow: 400ms;     /* lineup reveal (clip-path wipe) */
  --ease-out: cubic-bezier(0.16, 1, 0.3, 1);
  --ease-in-out: cubic-bezier(0.65, 0, 0.35, 1);
  --stagger-step: 40ms;       /* lineup cards */
  --beacon-pulse-count: 2;    /* recommended rays pulse twice, then rest (WCAG 2.2.2: nothing auto-plays > 5 s) */
}

@media (prefers-reduced-motion: reduce) {
  :root {
    --duration-instant: 0.01ms; --duration-fast: 0.01ms;
    --duration-normal: 0.01ms;  --duration-slow: 0.01ms;
    --stagger-step: 0ms;        --beacon-pulse-count: 0;
  }
}
```

Reduced-motion behaviour (required, tested): no stagger, no clip-path wipe (content appears at final state), no beacon pulse, no animated zoom/pan of the board (jump to target), no parallax or scroll-linked effects; opacity cross-fade of a state change may remain at 0.01ms (effectively instant). Status changes are announced via `aria-live` regardless of motion.

Named motions: `holdSelectTl` (ring `transform: scale(.85 -> 1)` + `opacity`), `lineupRevealTl` (card `clip-path: inset(0 100% 0 0) -> inset(0)`, staggered), `sheetOpenTl` (`transform: translateY(100%) -> 0`), `beaconPulseTl` (rays `opacity` 0.4 -> 1, `--beacon-pulse-count` times). Use `will-change` only on the element during its own animation.

## 6. Board SVG tokens

The board is an inline `<svg>` with `viewBox` derived from the bounding box of the route's `placements[].x/y` plus padding (the contract has no board extents; see needs-contract-change in `wireframes/explore.md`). Coordinate orientation (y up or down) is not specified by the contract; the renderer assumes "y increases upward" and must flip once; flagged as a contract question.

Sizes are CSS px at 1x zoom; the renderer divides by the viewBox scale so glyphs stay the same on-screen size when zoomed, and uses `vector-effect: non-scaling-stroke`.

```css
:root {
  --hold-radius: 12px;           /* visible glyph radius */
  --hold-hit-radius: 22px;       /* transparent hit circle = 44px target */
  --hold-stroke: 2.5px;
  --hold-start-ring-gap: 4px;    /* START: second concentric ring */
  --hold-foot-size: 18px;        /* FOOT: square side (smaller than hand diameter, hatched) */
  --hold-finish-size: 26px;      /* FINISH: diamond diagonal */
  --hold-label-size: 12px;       /* role label text, matches --text-xs floor */
  --hold-selected-ring: 3px;     /* outer ring, ink */
  --hold-selected-gap: 3px;
  --hold-recommended-ray: 6px;   /* length of the four accent ticks at N/E/S/W */
  --hold-recommended-stroke: 2.5px;
  --hold-unknown-dash: 2 3;      /* insufficient-data / unknown: dotted outline */
  --focus-ring-width: 3px;       /* ink ring */
  --focus-ring-gap: 2px;         /* surface-colored gap inside ring */
  --focus-ring-offset: 2px;
  --board-min-height: 18rem;
  --board-aspect: 4 / 5;         /* mobile default; use available width, never a fixed height */
  --board-zoom-min: 1;  --board-zoom-step: 0.5;
}
```

Hold glyph grammar (identical on every screen; shape + pattern + text, color is a redundant fourth channel):

| Role / state | Shape | Pattern / fill | Text channel | Color token (redundant) |
|---|---|---|---|---|
| START | Circle with a second concentric ring | Solid fill, ring gap shows board face | Label "START" (always visible) | `--color-hold-start` |
| HAND | Circle | Solid fill | "HAND" (on focus/hover/pin, or labels-on) | `--color-hold-hand` |
| FOOT | Rounded square, smaller than hand | Diagonal hatch fill (pattern `foot-hatch`) | "FOOT" (as above) | `--color-hold-foot` |
| FINISH | Diamond | Solid fill with a flat bar on top ("flag") | Label "FINISH" (always visible) | `--color-hold-finish` |
| Selected (pinned) | Any glyph plus outer ring and a check badge at upper right | 3px ring with 3px gap | "Selected" in the hold callout and `aria-pressed="true"` | `--color-focus` ink |
| Recommended route | Four short rays at N/E/S/W around each hold of the route, plus a "RECOMMENDED" stamp on the board corner | Solid rays | Stamp text, route card badge | `--color-accent` |
| Insufficient data (route-level) | All glyphs keep their shape but outline becomes dotted and fill drops to hatch; "?" badge at board corner | Dotted outline `--hold-unknown-dash`, light hatch | Stamp "NOT ENOUGH DATA" plus caption with the reason | `--color-hold-neutral` |
| Keyboard focus | Two-tone ring around the glyph (ink ring, surface gap) | 3px ring + 2px gap | Roving focus announced by accessible name | `--color-focus` |

Accessible name for a hold: `"<Role> hold <hold_id>, <relative position>"`, for example "Start hold h1, lower left". Relative position (left/centre/right, low/mid/high) is derived client-side from `x`/`y` within the route's bounding box. States are appended: ", selected", ", part of recommended route". The `hold_id` is a contract string and may be technical; it is read last-but-one so users can still tell holds apart.

## 7. Layout and component tokens (shared pieces)

- App shell: `header` (brand, board/angle chip, synthetic badge), `nav` (bottom tab bar at < 1024px, left rail at >= 1024px), `main`, optional `aside` (hold/decision details at >= 1024px, a bottom sheet below), `footer` (data note). Landmark detail per screen lives in the wireframe files.
- Banner pattern: `--color-warning-bg` + text label + icon with text alternative. Never color alone.
- Button hierarchy: primary = ink fill; secondary = ink outline; destructive = error outline, confirm in dialog; a recommendation action ("Why this route") uses a text link with accent underline, not a filled accent button.
- Stamps: uppercase 12px, `--radius-stamp`, 1.5px border; used for `SYNTHETIC DEMO BOARD`, `RECOMMENDED`, `SIMPLIFIED RECOMMENDATION`, `RETRACTED`, `DRAFT ON THIS DEVICE`.
- Skip links: "Skip to main content" first in tab order on every screen; "Skip board" inside board screens.
- Focus-visible: every interactive element shows the two-tone ring (`--focus-ring-*`) on `:focus-visible`; never `outline: none` without replacement; ring is never clipped by `overflow: hidden` (use outline-offset or inset ring on scroll containers).
- Touch: hit area >= `--size-hit-min` except hold glyphs which meet it through `--hold-hit-radius`; where holds are denser than 44px at 1x zoom, zoom buttons ("Zoom in", "Zoom out", "Reset") and the list view are the equivalent path. Pinch-only zoom is never the only path (WCAG 2.5.1).
- Texture: contour-line SVG pattern behind the board only, at `--texture-contour-opacity`, `aria-hidden`, not animated.

## 8. Design-review checklist (rules/web/design-quality.md applied to these docs)

This is the BC-0008 test: each item is marked against the documents in `docs/ux/` (design intent, not a built UI). "N/A" is not used.

### Anti-template banned patterns

| Item | Mark | Justification |
|---|---|---|
| Default card grid, uniform spacing, no hierarchy | PASS | Explore is a board-plus-ranked-list with one featured route sheet; spacing rhythm tokens differ per zone (`--space-board-pad`, `--space-section`) |
| Stock hero with centered headline, gradient blob, generic CTA | PASS | No hero; first screen is a sign-in/demo choice on a topo sheet with the board preview as the visual |
| Unmodified library defaults | PASS | All controls specified with own tokens (ink actions, stamps, two-tone focus); no component-library theme assumed |
| Flat layout without layering/depth/motion | PASS | Stacked-paper elevation, board over page, bottom sheet overlap, purposeful motion list in section 5 |
| Uniform radius/spacing/shadow everywhere | PASS | Stamp 2px / input 6px / sheet 16px; four elevation levels with distinct roles |
| Safe gray-on-white with one decorative accent | PASS | Warm paper and ink; the single accent is semantic (recommendations only), not decorative |
| Dashboard-by-numbers (sidebar + cards + charts) | PASS | Observatory is a stub with a plain status list; nav rail is navigation only; no chart grid specified |
| Default font stacks without reason | PASS | Fraunces plus IBM Plex Sans with roles and fallbacks justified in section 3 |

### Required qualities (at least four; this design claims eight)

| # | Quality | Mark | Justification |
|---|---|---|---|
| 1 | Hierarchy through scale contrast | PASS | `--text-grade` 36-56px numerals vs 12px stamps; route name in display face vs 16px UI text |
| 2 | Intentional spacing rhythm | PASS | Tight in components (`--space-1..3`), generous between zones (`--space-section`) |
| 3 | Depth / layering | PASS | Hard-offset sheets, board over page, hold callout overlapping board edge |
| 4 | Typography with character and pairing | PASS | Serif display + humanist UI sans with tabular numerals, explicit role table |
| 5 | Semantic color | PASS | Accent = recommendation only; ochre = degraded/stale; brick = failure; roles by shape first |
| 6 | Designed hover/focus/active | PASS | Two-tone focus ring, selected ring + check, press = `transform: translate(1px,1px)` on hard shadow |
| 7 | Grid-breaking / editorial composition | PASS | Board bleeds past the content column on mobile; the route sheet overlaps it; aside rail at 1024 |
| 8 | Texture / atmosphere | PASS | Contour-line pattern behind the board, paper tones |
| 9 | Motion that clarifies flow | PASS | Lineup wipe, sheet slide, selection ring; pulse capped at `--beacon-pulse-count`; reduced-motion defined |
| 10 | Data viz as part of design system | PASS | Board SVG is the data visualization: tokenized glyph grammar, states, focus (section 6) |

### Component checklist

| Item | Mark | Justification |
|---|---|---|
| Avoids default Tailwind/shadcn look | PASS | Tokens are bespoke; no utility-default palette; implementation may use any tool provided it consumes these tokens |
| Intentional hover/focus/active states | PASS | Specified per control family in section 7 and per screen in each wireframe's keyboard section |
| Hierarchy rather than uniform emphasis | PASS | One primary ink button per view; accent appears at most on the recommended items |
| Believable in a real product screenshot | PASS (docs only) | Wireframes use real contract field names and copy; visual confirmation requires Playwright screenshots at 320/768/1024/1440 in BC-0114+ |
| Both themes intentional | PASS | Separate dark palette ("Lamp-lit crag"), re-derived hold colors and contrast, not an inversion |

Not verifiable at documentation stage (carried to BC-0114/0119 as acceptance): rendered visual regression, axe results, real screen-reader pass, Lighthouse CWV.

## 9. Constraints from the global rules, applied

- Tokens are CSS custom properties; no repeated hardcoded palette values in components.
- Animation properties limited to transform/opacity/clip-path; `will-change` scoped.
- Semantic HTML first: landmarks, headings, `button`, `fieldset`/`legend`, `table` for the hold list; no div soup.
- Naming: components PascalCase, hooks `use*`, CSS kebab-case, timelines camelCase with intent (`lineupRevealTl`).
- Perf: two font families, WOFF2 subsets, no webfont for the board labels (they use `--font-ui`), no image hero; CSS budget < 50 kb (app page), app JS < 150 kb gz (06 section 11).
- No health, risk or physical-outcome wording appears in any token name or copy (PRD non-goals); this was checked by grep, see the BC-0008 handoff.
