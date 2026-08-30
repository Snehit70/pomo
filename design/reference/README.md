# Design reference

Visual reference for Pomo's design language. Decided in
[#42](https://github.com/Snehit70/pomo/issues/42), under the wayfinder map
[#41](https://github.com/Snehit70/pomo/issues/41).

**Build against these images.** Do not re-derive the look from `docs/`, from
`CONTEXT.md`, or from the current `Color.kt` / `Type.kt` — all of those describe
the old cool-slate-and-red identity and are stale.

## The images

| File | What it is |
| --- | --- |
| `timer-accepted.png` | The accepted Timer look — palette, type voice, and heat. This is the target. |
| `timer-states-and-icons.png` | Refined layout with the bottom nav, an icon sheet, and the four phase states (idle / focus / final minute / break). The more complete reference of the two. |
| `palette-midnight-glow.png` | A candidate *second* palette (deep navy `#001F3F` → cyan `#00BFFF`) for a theme switcher. **Not decided** — see [#56](https://github.com/Snehit70/pomo/issues/56). It has no heat, so it forces the question of whether the metaphor is *heat* or the more general *intensity*. |

## What is binding

- **Metaphor: thermal.** The screen accumulates heat as a session burns down —
  pale and cool at idle, warm while running, molten orange in the final minute,
  cooling through the break. Heat replaces the progress bar's job.
- **Palette.** Warm sunflower `#FFF59D` → `#FFA000`, deep amber-brown ink.
  Dark variant is molten amber on near-black — amber as *emitter*, not field.
- **Accent ramp.** Temperature carries everything continuous (progress, urgency,
  phase, intensity). One hot red carries exactly one binary fact: **LIVE**.
  Nothing else is ever red.
- **Type.** One superfamily, two widths — condensed cut for digits and hero
  numbers (tabular figures required), normal cut for labels and body. Inter and
  JetBrains Mono are both dropped.
- **Phase is heat, not a label.** Focus is hot. Break sits at the cool end and
  warms as it ends. Break is still clearly *on*.
- **The millisecond digits** stay a vertical column beside MM:SS.

## What is NOT binding

These are mockup artifacts, not decisions:

- **The icons** in `timer-states-and-icons.png` are stock Material (the flame,
  trash, and pencil are off-the-shelf). The real icon system is
  [#48](https://github.com/Snehit70/pomo/issues/48).
- **Exact type sizes and the final typeface.** Archivo + Archivo Condensed is the
  leading candidate, not a decision — see
  [#47](https://github.com/Snehit70/pomo/issues/47).
- **Exact token values, spacing, and elevation** — see
  [#46](https://github.com/Snehit70/pomo/issues/46).
- **Layout and information architecture of the Timer** — see
  [#53](https://github.com/Snehit70/pomo/issues/53).
- **BREAK as rendered in the state sheet is wrong.** It reads as paler than IDLE,
  making a break look like the app is switched off. Break must stay clearly on.
