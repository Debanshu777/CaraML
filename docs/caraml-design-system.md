# CaraML Prism Design System

This is the mandatory visual and interaction contract for CaraML on Android, iOS, and Desktop.
New UI should extend these roles instead of inventing screen-local colors, type scales, spacing,
corner radii, or motion.

## Principles

1. **The work is the focal point.** Navigation and chrome stay matte and quiet. Rich gradient and
   grain belong only to one meaningful focal region on a screen.
2. **Hierarchy comes from type and spacing before containers.** Do not wrap every group in a card.
   Use a surface only when it communicates elevation, interaction, or a distinct task boundary.
3. **Technical information stays legible.** Repository identity, artifact paths, statistics, and
   download state use the shared dense/technical roles and never inherit an unknown content color.
4. **Compact means reflow, not clipping.** Required context and actions wrap or stack. They must not
   be hidden in remembered horizontal scroll positions.
5. **Color is never the only signal.** Selection, state, progress, and errors also have text,
   semantics, icons, or a signal rail.
6. **Reveal detail in the order a decision is made.** A compact screen shows identity, current
   state, and the next useful action first. Device diagnostics, tags, timestamps, and exhaustive
   metadata are disclosed on demand.

## Sources of truth

| Concern | Code source | Usage |
|---|---|---|
| Color and surfaces | `CaraMLTheme`, `AuroraColors`, `AuroraSurfaceLevel` | `MaterialTheme.colorScheme`, `MaterialTheme.auroraColors` |
| Typography | `AppTypography`, `AppPrismTypography` | `MaterialTheme.typography.prism` |
| Shape | `AppShapes`, `AppPrismShapes` | `MaterialTheme.prismShapes` |
| Spacing | `Spacing` | `LocalSpacing.current` |
| Interaction geometry | `AppPrismMetrics` | 48dp minimum target, responsive gutters |
| Motion | `AuroraMotionPolicy` | `LocalAuroraMotionPolicy.current` |
| Responsive layout | `AdaptiveLayoutPolicy`, `ResponsiveContentPane` | content-kind width cap and gutter |

Feature code must not introduce raw colors, a new `RoundedCornerShape`, a new `TextStyle`, or an
arbitrary page gutter. If a missing semantic role is genuine, add it to the shared system with tests
and document its intended use here.

## Color and surfaces

- The persisted seed is the exact app primary. Material palette generation may derive secondary,
  tertiary, and surface roles, but it must not replace the selected accent with a different hue.
- `surface` is the route canvas; `surfaceContainerLow` is recessed context/navigation;
  `surfaceContainer` is a command or pane; `surfaceContainerHigh` is selected/floating emphasis.
- Text and icons on transparent route content must set `onSurface` or `onSurfaceVariant`
  explicitly. Never rely on an inherited `LocalContentColor` outside a Material container.
- Standard text requires at least 4.5:1 contrast; large text and meaningful icons require at least
  3:1. Disabled content may be quieter but must retain its non-color state semantics.
- The global Aurora backdrop is faint atmosphere. A screen may add at most one strong focal
  gradient/grain surface. Navigation, list rows, filter controls, sheets, and dialogs stay matte.

## Typography roles

| Role | Token | Size / line | Weight | Use |
|---|---|---:|---|---|
| Screen title | `prism.screenTitle` | 28 / 34sp | Semibold | Create, Models, Settings, Artifact |
| Section title | `prism.sectionTitle` | 16 / 22sp | Semibold | Metadata, Device fit, Runtime |
| Model title | `prism.modelTitle` | 17 / 22sp | Medium | Registry and library identity |
| Dense metadata | `prism.denseMetadata` | 14 / 20sp | Normal | Context, summaries, secondary facts |
| Technical label | `prism.technicalLabel` | 12 / 17sp | Mono Medium | Paths, engines, stats, machine values |
| Detail title | `detailTitleCompact/Expanded` | 24 / 30sp; 32 / 38sp | Semibold | Artifact focal identity |

Use ordinary Material body/label roles for prose and standard controls. Do not use a large title
role for counts, filter summaries, or metadata. Long model names may wrap to two lines; technical
metadata truncates only after the identity has received its full compact width.

## Shape roles

| Role | Radius | Use |
|---|---:|---|
| `prismShapes.status` | 8dp | Status marks, small badges, passive state |
| `prismShapes.control` | 12dp | Inputs, buttons, tabs, selectable controls |
| `prismShapes.command` | 18dp | Composer and search command surfaces |
| `prismShapes.pane` | 18dp | A meaningful grouped task, not ordinary list content |
| `prismShapes.focal` | 24dp | Sparse hero/preview content only |
| `prismShapes.modal` | 24dp | Dialogs and sheets |

Capsules are reserved for compact navigation selection or true tags. Compact browse results and
download artifacts use one rounded `pane` surface when that boundary makes the whole item easier to
scan or tap. Dense desktop tables may remain flat with dividers. Do not mix square and rounded
containers in one list, and do not nest rounded surfaces unless the inner surface is independently
interactive.

## Spacing and layout

- The 4dp grid is canonical. Use `LocalSpacing`: 4 related label/value, 8 compact inline group,
  12 row elements, 16 standard internal padding, 24 section spacing, 32 new screen region.
- Compact page gutter is 16dp. Rail/sidebar layouts use 24dp. Do not add a second feature gutter
  inside `ResponsiveContentPane`.
- Every interactive target is at least 48dp in both dimensions.
- Below 480dp, technical-row status/actions stack below identity and metadata. They never reserve a
  trailing column that squeezes the model name.
- Required controls and context never use horizontal scrolling. Reflow into complete bands or a
  `FlowRow`. Horizontal scrolling is allowed only for optional, browseable media with a visible cue.
- Each route owns one primary vertical scroll container. Sheets and overlays own their own scroll
  only while presented.

## Information hierarchy and disclosure

- The first compact viewport has three jobs only: identify the page, expose its primary command,
  and begin the primary results or decision content. Supporting diagnostics must not push the first
  useful result below the fold.
- Model discovery orders content as command, collapsed device profile, browse controls, result
  count, then results. Storage, CPU/GPU, and recommendation calibration expand from one remembered
  `Device profile` disclosure.
- Artifact details order content as identity, device-fit evidence, file/install decision, then a
  collapsed `Technical details` disclosure. Library, pipeline, license, timestamps, tags, and
  architecture never compete with the download decision by default.
- Long identity text wraps before secondary status or actions. At compact widths, metadata and
  actions move to their own bands; they never claim a fixed trailing column that clips the name.
- Use one disclosure level at a time. A collapsed summary states what is inside and its current
  value or item count. The expanded region remains in the page's single scroll owner.
- For data-heavy routes, permanent chrome should be minimal. A route may scroll a large intro or
  utility strip away and retain a compact title/tab or command affordance, but must not hide the
  current destination, Back/Menu, or the primary action. Collapsing behavior uses the shared motion
  policy and becomes an immediate state change under reduced motion.

## Shared component grammar

- `CaraMLTopBar`: one screen title, one 48dp Menu or Back action, transparent over the canvas.
- `CommandSurface`: search/composer only; command radius and a single focus treatment.
- `TechnicalListRow`: owner, identity, wrapping metadata, then status/action at compact widths. It
  supplies row content and semantics; its owner decides whether the surrounding collection uses a
  rounded compact pane or a dense desktop divider.
- `StatusMark` / `CaraMLStatusPill`: status radius, concise label, icon or state description; never a
  large decorative capsule.
- `ModelHubContextStrip`: compact layouts show one rounded `Device profile` summary and disclose
  Storage, Device, and Profile vertically. Expanded layouts may show the facts side-by-side.
- `ModelHubToolbar`: Text/Image/Video form one complete selection band; Sort/Filters form a second
  compact band. All selected controls use the exact seed accent and a visible check/state.
- Empty states are content-sized, centered within the available work region, and use semantic icon
  and text colors.

## Motion

- Navigation feedback and route updates begin together; never wait for a panel to close first.
- Peer routes use a stationary 180ms opacity transition. Details uses a 16dp shared-axis offset.
- Disclosure is 180ms standard or no more than 90ms under reduced motion.
- Create atmosphere may enter once over about 900ms, then becomes static. No idle looping gradient,
  grain, scale, pulse, or shimmer.

## Preview and verification contract

- Maintain production-component Compose previews for compact Models (empty and populated),
  Artifact Details, Settings, and Create/Chat states. Preview fixtures must not call the network,
  storage, or native engines.
- Reuse the same state shapes in JVM Compose UI tests at 360–412dp, light/dark, and 200% font scale.
- Any visual change must run the affected preview-backed UI suites plus `:composeApp:jvmTest`.
- Before release, install the rebuilt APK without clearing app data and inspect the affected states on
  a real device. Capture empty, populated, and detail states when data makes them reachable.

## Review checklist

- Does the screen use semantic color, type, shape, and spacing roles?
- Is there at most one strong gradient focal region?
- Are list rows flat unless a surface communicates real hierarchy or interaction?
- Does the first compact viewport prioritize the next decision instead of diagnostics?
- Are secondary facts behind a labeled, remembered disclosure rather than removed or clipped?
- Are all controls visible without remembered horizontal scroll?
- Do compact rows preserve identity before status and actions?
- Are dark/light contrast, 200% text, and 48dp targets covered?
- Are callbacks, scroll ownership, and reduced-motion behavior unchanged?
- Is a production-component preview present or updated for the changed state?
