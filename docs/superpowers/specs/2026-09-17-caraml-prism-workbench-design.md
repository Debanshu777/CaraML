# CaraML Prism Workbench Design

## Status

Approved for implementation on 2026-09-17 after a reference reset and review of the supplied Pixel recording.

## Goal

Replace the current card-heavy Aurora presentation with a coherent, product-specific workspace for local AI. The new system must make the primary task obvious, expose technical state without visual noise, use gradients as environmental light rather than decoration, and keep navigation spatially stable.

The redesign is presentation-only. Existing inference, recommendation, download, storage, settings, route, and ViewModel contracts remain authoritative.

## Why the current design failed

The rejected UI changed palette, radii, and opacity while preserving a generic dashboard skeleton: large title, segmented control, stacked chips, rounded cards, and hamburger drawer. This created five concrete problems visible in the supplied device recording:

1. Navigation and generation mode were conflated. A drawer item could highlight Image while the closing route still showed Assistant content.
2. Too many filled surfaces competed with search, so the Model Hub had no dominant interaction.
3. Compact screens paid a large vertical tax before reaching results.
4. Nearly every content group became a rounded card, making technical hierarchy depend on container count rather than information structure.
5. Motion described durations but did not preserve a trustworthy frame of reference.

The new design changes the information architecture and component grammar, not just styling.

## Reference ownership

Each source has one job so the result is not a visual collage:

- **Arc:** owns contextual navigation, user-controlled atmosphere, and stationary spatial framing.
- **Linear:** owns restrained chrome, information hierarchy, dividers, and dense work surfaces.
- **Raycast:** owns the dominant command surface and contextual secondary actions.
- **Hugging Face:** owns model provenance, technical metadata, and compact registry rows.
- **Cursor:** owns transparent AI activity and phase communication.
- **Material 3 Expressive:** owns touch ergonomics, semantic color, adaptive layout, and emphasis of one primary action.
- **Apple motion guidance:** owns brief, causal, interruptible transitions and reduced-motion behavior.

CaraML does not copy proprietary assets, fonts, logos, or product-specific screen arrangements.

## Product identity: Prism Workbench

Prism Workbench is a quiet technical canvas with three signature elements:

1. **Ambient field:** one broad seed-derived gradient lights the shell and sparse creation states. It is static while idle.
2. **Command lens:** the current task—prompt, model search, or artifact choice—receives the strongest local emphasis and an optional gradient edge glow.
3. **Signal rail:** a narrow seed-derived line marks active navigation, selected artifacts, recommended models, or live generation. It replaces large accent-filled containers.

Everything else is neutral, structured by typography, spacing, dividers, and tonal depth.

## Non-negotiable principles

1. Every screen has one dominant interaction.
2. Navigation orients; it never becomes the visual hero.
3. A filled container must indicate a real grouping, interaction, or overlap—not merely separate adjacent text.
4. Gradients belong to atmosphere, command focus, and live creation state. They do not fill ordinary cards, chips, status labels, or destructive actions.
5. Model and runtime state must be explicit: loading, empty, filtered-empty, offline/error, unsupported, ready, downloading, and generating cannot share one ambiguous treatment.
6. The route canvas stays stationary during shell interaction. Peer routes crossfade; only hierarchical details move shallowly.
7. Compact and expanded layouts share semantics and order. Adaptation changes placement, not meaning.
8. UI work must not alter domain decisions or inspect domain progress to invent actions.

## Visual foundation

### Color

Dynamic seed color remains user-controlled through `CaraMLTheme` and `materialKolor`.

- `surface` is the application canvas.
- `surfaceContainerLow` is navigation and recessed context.
- `surfaceContainer` is reserved for command surfaces and meaningful groups.
- `surfaceContainerHigh` is reserved for floating overlays, selected transactional rows, and focused input.
- `primary` marks selection and primary actions.
- `secondary` supports non-primary semantic state.
- `tertiary` is used only as the second endpoint of environmental gradients.
- `error` is used only for error/destructive semantics.
- Standard separators use `outlineVariant` at 35–55% effective alpha.

The ambient field uses a top-leading `primary` or `primaryContainer` wash and a smaller opposing `tertiary` wash. Dark themes target a visible but subdued color-distance change; light themes must remain quieter. The final composited foreground/background pairs must meet contrast requirements.

### Gradient contract

At most two gradient roles may be visible on one screen:

1. The app-level ambient field.
2. One contextual command/live-state treatment.

Allowed contextual uses:

- focused or active prompt composer outline/halo;
- focused Model search command outline/halo;
- selected artifact signal rail;
- live image/video generation field;
- appearance preview in Settings.

Disallowed uses:

- ordinary buttons, cards, tabs, chips, status pills, dialogs, metadata rows, or list backgrounds;
- gradient text;
- moving idle gradients;
- multiple competing focal gradients.

### Typography

Use the platform sans family for all readable interface copy and monospace only for identifiers or machine data.

| Role | Treatment |
|---|---|
| Screen title | 28sp/34sp, semibold, tight grouping |
| Detail title | 24sp/30sp compact; 32sp/38sp expanded, semibold |
| Section title | 16sp/22sp, semibold |
| Model result title | 17sp/22sp, medium or semibold, maximum two lines |
| Reading body | 16sp/24sp |
| Dense metadata | 14sp/20sp |
| Identifier/format/size | 12–13sp monospace, 17–18sp line height |
| Label/action | 14sp/20sp, medium |

Avoid using bold weight as the only hierarchy. Tertiary information uses color and placement, not text smaller than 12sp.

### Shape

- 8dp: badges, status marks, compact indicators.
- 12dp: inputs, buttons, selectable controls, technical rows when a selected background is needed.
- 18dp: command surfaces, transactional panels, sparse empty-state focal regions.
- 24dp: sheets and dialogs.
- Capsule: only navigation selection, true tags, and circular/icon controls.

Ordinary result and settings rows are not cards. They use the canvas, spacing, and separators.

### Spacing

The 4dp grid remains canonical. Compact page gutter is 16dp; medium and expanded page gutter is 24dp. The common vertical rhythm is:

- 4dp between tightly related label/value content;
- 8dp inside compact inline groups;
- 12dp between row elements;
- 16dp standard component padding and between related groups;
- 24dp between sections;
- 32dp before a new screen-level region.

Nested page gutters are prohibited.

## Adaptive shell and information architecture

The shell exposes three true destinations:

1. **Create** (`AppScreen.Home`)
2. **Models** (`AppScreen.Search`)
3. **Settings** (`AppScreen.Settings`)

Text, Image, and Video remain generation modes inside Create. They are not separate destinations and therefore cannot contradict the selected route.

### Breakpoints

| Window width | Navigation | Content |
|---|---|---|
| `< 600dp` | bottom destination bar | one pane, 16dp gutter |
| `600–1199dp` | 80dp navigation rail | one or adaptive split pane, 24dp gutter |
| `>= 1200dp` | 256dp contextual sidebar | centered workspace, 24dp gutter |

The compact bar contains exactly Create, Models, and Settings. It respects bottom safe drawing insets, provides 48dp minimum targets, and does not resize or translate the route during selection.

The expanded sidebar shows the CaraML wordmark, the three destinations, and contextual subnavigation only for the selected destination. When Create is active, Text/Image/Video modes appear as secondary items inside the sidebar; the same mode selector remains available in the Create screen on compact and medium layouts.

Primary screens no longer show a hamburger button. Hierarchical Details keeps a back action.

### Navigation motion

- Compact destination selection: signal/label transition, 160ms; route crossfade, 180ms.
- Rail/sidebar selection: tonal selection and 3dp signal rail, 180ms.
- Peer route canvas: opacity only, 180ms; no translation or scale.
- Details push/pop: opacity plus fixed 16dp horizontal offset, 220ms enter and 180ms exit.
- Reduced motion: immediate state or opacity only, maximum 90ms; no spatial movement.

Navigation state changes atomically: the selected destination and visible route must agree at every settled frame. Generation mode changes never mutate the selected destination.

## Shared presentation vocabulary

The overhaul may replace current Aurora components but keeps the theme provider and motion-policy boundary.

- `WorkspacePage`: owns screen background, content width, gutters, and optional top command region.
- `WorkspaceHeader`: compact title, optional supporting state, and trailing actions without a hamburger dependency.
- `CommandSurface`: one high-emphasis input/action region with focus-aware signal treatment.
- `WorkbenchPanel`: a meaningful tonal group; no border by default.
- `TechnicalListRow`: dense row with optional leading mark, primary/secondary/mono slots, trailing state/action, and separator.
- `SignalRail`: a narrow gradient or semantic solid marker for selected/live/recommended state.
- `StateTrace`: compact waiting/running/complete/error phase presentation.
- `StatusMark`: non-color semantic icon and concise label; painted height may be compact while the interactive target remains 48dp.
- `InlineSectionHeader`: title, count/supporting copy, and optional contextual action.

Shared components accept presentation values and callbacks only. They cannot depend on a feature ViewModel or domain model.

## Model Hub: first proof surface

Model Hub defines the new system before it spreads to other screens.

### Compact order

1. Workspace header: `Models` and an optional total/result summary.
2. Underline tabs: `Discover` and `Library`; no filled segmented hero.
3. Command surface: one search affordance, one conditional clear action.
4. Device context strip: storage and recommendation profile in one compact secondary row.
5. Browse toolbar: Text/Image/Video modes, Sort, and Filters. It scrolls horizontally if needed and does not create multiple stacked chip rows.
6. Result-state line: result count, active query/filter summary, and Reset when applicable.
7. Results or explicit loading/empty/error state.

Search and at least the beginning of results must be reachable in the first compact viewport after normal system insets. The context strip is secondary and collapsible; it may not appear as the primary CTA.

### Results

Results use a registry list, not independent rounded cards.

- A row has 14–16dp vertical and 0–4dp horizontal internal padding inside the page grid.
- Owner/provenance appears as a small label or monospace eyebrow.
- Model title uses a maximum of two lines.
- Format, task, downloads, likes, parameter count, and license form one or two compact metadata lines.
- Recommendation state is a concise trailing or wrapped `StatusMark` with icon and state description.
- Recommended/live rows receive a 3dp signal rail and subtle tonal selection, never a full gradient fill.
- Row separators align with the text column, not the page edge when a leading mark is present.
- Compact row height should normally remain at or below 148dp at 1x font scale. At 200% font scale, content may grow and must remain scrollable and actionable.

Loading shows stable skeleton rows or a list-local indicator. Empty results distinguish:

- no query and no available results;
- query returned zero results;
- active filters removed all results;
- failed/offline fetch.

Filtered empty state includes Reset filters. Error state includes retry when that callback exists. Copy never implies incompatibility when the domain state is `NEEDS_INFORMATION`.

### Expanded layout

At 840dp and above, Model Hub may place device/filter context in a 280–320dp supporting column while results retain at least 480dp. At narrower widths the order above remains single-column. Maximum workspace width is 1180dp.

## Model Details

Details is an artifact decision workspace, not a stack of metadata cards.

### Compact

- Back header uses the repository owner as secondary context; avoid a large generic `Model details` title.
- The top focal region contains owner, naturally wrapped model name, task/format, downloads/likes, and concise description.
- Metadata is a divider-based key/value section. Show the four most useful values first and use a saveable Show all/less action.
- Tags are capped and secondary; raw `key:value` tags do not dominate the page.
- Artifact/file rows are the primary decision list. Filename and quantization/size use the technical type voice.
- The selected artifact uses the signal rail and tonal selection.
- When an install/download action exists, its concise summary and action remain reachable in a stable bottom region; the full explanation remains in the scroll owner.

### Expanded

At an effective content width of 840dp or more, the screen uses one scrolling two-column workspace: overview/description/files in the primary column and device fit/install summary in a 320–360dp supporting column. There is one vertical scroll owner. Maximum workspace width is 1180dp.

Exact-artifact download identity, lock behavior, progress, recommendation meaning, and callback payloads remain unchanged.

## Create: chat, image, and video

Create is one destination with a local Text/Image/Video mode selector.

- Empty state has one clear statement, one concise explanation, and the command surface. Avoid a decorative card around the entire state.
- Conversation/output owns the canvas. Assistant content is unboxed; user content may use one compact tonal surface.
- The model selector is contextual chrome, not a full screen title.
- The composer is the command lens. It uses a neutral 18dp surface at rest and a visible seed-derived edge/halo only while focused or generating.
- Send/stop remains one stable 48dp action.
- Runtime phases use `StateTrace`: preparing, loading, tokenizing, generating, saving, complete/error as available from existing state. Do not invent missing phases.
- Generation stats and reasoning remain disclosures below the output hierarchy.
- Live text is not animated token-by-token.

Mode changes use a 160–180ms fade plus at most 8dp local vertical movement. The shell and route do not move.

## Settings

Settings is a calm configuration list.

- Use section labels and divider-based rows rather than one rounded card per group.
- Appearance may use the one allowed contextual gradient preview.
- Theme, palette, recommendation, GPU, KV cache, seed, and advanced controls retain their current callbacks and enabled/error behavior.
- Selected values always expose a non-color indicator and semantics.
- Explanations expand inline and remain saveable; motion follows the shared disclosure policy.
- Maximum content width remains 760dp.

## Accessibility and input

- Interactive targets are at least 48×48dp.
- Body text contrast is at least 4.5:1; icons and meaningful non-text boundaries are at least 3:1.
- Selection, progress, and error are never conveyed by color alone.
- Screen reader labels describe exact actions, including exact artifact filenames.
- The complete compact flow remains reachable at 200% font scale and 420×280dp landscape without clipped terminal actions.
- Keyboard focus uses an explicit semantic outline and predictable order on Desktop/iPad keyboard contexts.
- Pointer hover may add tonal emphasis but may not be the only affordance.

## Motion and performance

- No idle infinite animation.
- Exactly one continuous animation may be visible for active indeterminate generation/download work.
- Determinate progress animates toward reported values over 180ms and never invents progress.
- List placement may animate for user-triggered filtering but the entire list does not replay entrance animation.
- Every animation is interruptible and reads `LocalAuroraMotionPolicy` (or its behavior-compatible successor).
- Backdrop brushes and token objects are remembered; streaming output must not recreate expensive brushes or animate whole-screen state.

## Behavioral boundaries

The redesign must not:

- change `AppScreen` route meaning or add navigation destinations;
- alter recommendation, admission, storage, download, inference, or settings persistence decisions;
- rename public ViewModel state or callback contracts;
- infer a download action from progress rather than rendering the established action/state;
- add a third-party UI, blur, animation, or screenshot dependency;
- copy proprietary assets or bundle an ornamental font;
- discard or absorb the unrelated dirty download/native changes in the parent worktree.

## Verification contract

### Automated

1. Write every new behavioral/layout contract as a failing JVM Compose test before production code.
2. Test widths at 360, 599, 600, 839, 840, 1199, and 1200dp where relevant.
3. Test key compact flows at 200% font scale.
4. Use semantics and bounds for layout/actions, tolerant interior pixel sampling for atmosphere/contrast, and manual clocks for motion.
5. Do not use exact whole-screen bitmap equality.
6. Required final gates:
   - focused theme/shell/feature suites;
   - `./gradlew :composeApp:jvmTest`;
   - `./gradlew verifyProject`;
   - `./gradlew :androidApp:assembleDebug`;
   - `git diff --check`.

### Device evidence

Use the supplied Pixel recording as the negative baseline. On a connected phone, capture and inspect:

- Create in Text, Image, and Video modes;
- Models initial, populated, loading, filtered-empty, and error states when reachable;
- long-name Details with metadata and artifact selection;
- Settings in dark and light themes;
- compact destination changes and Details push/pop;
- focused composer/search and active progress.

Record navigation at start, midpoint, and settled frames. The route must not scale or expose stale destination/mode selection. Capture expanded/Desktop evidence when a visible display is available; otherwise report it as unverified rather than inferred.

## Migration order

1. Foundation and shared primitives.
2. Three-destination adaptive shell and motion.
3. Model Hub proof surface and device checkpoint.
4. Model Details.
5. Create/Chat.
6. Settings.
7. Cross-app visual/device sweep and documentation.

The system spreads only after the Model Hub proves the hierarchy, density, gradient ownership, and motion language.
