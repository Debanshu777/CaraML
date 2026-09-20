# CaraML Baseline UI Overhaul Design

## Status

Approved in conversation on 2026-09-21. This document is the production design contract for translating the approved `Current baseline` browser visualization into the complete Compose Multiplatform application.

## Goal

Rebuild CaraML's presentation layer as one coherent local-AI workbench across Create, active conversations, Models, Model Details, Settings, navigation, overlays, and system states. The result must preserve existing product behavior while replacing the remaining mixed Prism/Aurora presentation with the approved baseline's calm sidebar-first shell, full-canvas creation experience, selective atmospheric gradient, dense technical hierarchy, and causal motion.

## Scope Boundary

This is a presentation-only overhaul.

The following remain authoritative and behaviorally unchanged:

- inference and generation flows;
- model loading, recommendation, compatibility, and device-fit decisions;
- downloads, durable download controls, progress, retries, cancellation, and artifact identity;
- storage, preferences, settings values, persistence, and validation;
- route identities, back-stack behavior, callbacks, and navigation destinations;
- ViewModel and domain ownership of business state and terminal actions;
- security, privacy, and local-only execution behavior.

Presentation may regroup, reorder, collapse, or restyle information when the same capability remains discoverable and semantically equivalent. It must not invent supported states, compatibility claims, progress, actions, or domain decisions.

## Source of Truth

The visual source is Variation 00, `Current baseline`, in the approved `navigation-lab.html` after the following accepted iterations:

- Create is an edge-to-edge workspace, not a large card.
- Empty Text mode is quiet and contains no recent-session or `Continue working` distraction.
- Active conversation removes persistent navigation and page headers.
- Active conversation keeps the production composer rather than the prototype text field.
- Assistant content is unboxed and does not use a yellow response rail.
- Reasoning and performance metadata use quiet typography without green decorative dots or a redundant `Local` label.
- The slow Neon Reactor-inspired gradient entrance is retained only as a one-shot focal treatment.
- Noise and grain are visible enough to create material depth but never cover ordinary rows, controls, or navigation.

The HTML is a behavioral and visual reference, not production code. Compose implementation must use existing domain state, accessibility semantics, insets, and platform APIs.

## Product Character

CaraML should feel like a private technical workspace rather than a generic mobile dashboard.

The visual hierarchy is:

1. the user's current work;
2. the current command surface;
3. model/runtime state needed to understand that work;
4. navigation and configuration chrome.

The interface uses matte navigation, calm content surfaces, compact technical rows, direct typography, and one atmospheric focal region where appropriate. Rounded containers are used only for real grouping, interaction, or overlap. They are not the default method of separating content.

## Information Architecture

The shell has exactly three top-level destinations:

1. **Create** — Text, Image, and Video generation modes;
2. **Models** — Discover, Downloads, and Library contexts;
3. **Settings** — Appearance, recommendation, runtime, and inference configuration.

Model Details remains a hierarchical child of Models. Downloads is contextual navigation inside Models, not a fourth top-level destination. Text, Image, and Video are modes inside Create, not app destinations.

## Adaptive Navigation Shell

### Compact: below 600dp

- No bottom navigation.
- A 48dp menu action opens a modal left sidebar.
- The panel is `min(320dp, viewport width - 40dp)` and owns safe top, start, and bottom insets once.
- The route canvas remains fixed while the panel moves over it.
- A neutral scrim communicates depth and dismisses the panel.
- Back and Escape close the panel before affecting the route stack.
- Create modes and Models contexts appear inside the panel under their selected destination.

### Medium: 600–839dp

- An 80dp persistent icon rail exposes Create, Models, and Settings.
- Labels are available through accessibility semantics and tooltips.
- Create modes remain visible in the Create workspace because the rail does not provide labeled contextual navigation.
- The route canvas begins after the rail and never slides during selection.

### Expanded: 840dp and above

- A 256dp persistent contextual sidebar is visible.
- It contains the CaraML identity, top-level destinations, selected-route context, and Settings pinned to the bottom.
- Create context exposes Text, Image, and Video.
- Models context exposes Discover, Downloads, and Library.
- Settings context exposes its major sections when that provides useful orientation.
- The sidebar stays matte; selection uses a narrow signal rail, weight, and a restrained tonal surface rather than a large saturated pill.

### Shared shell behavior

- The selected destination and visible route change atomically.
- Tapping the current destination never creates duplicate back-stack entries.
- Details keeps Models selected and presents Back rather than Menu.
- Resizing from modal to persistent navigation closes stale modal state.
- Navigation never owns the route's dominant gradient.
- Content widths are measured after navigation and safe insets, then capped and centered by the shared responsive pane.

## Visual Foundation

### Color and surfaces

- `surface` is the route canvas.
- `surfaceContainerLow` is persistent navigation and recessed context.
- `surfaceContainer` is used for command surfaces and meaningful grouped controls.
- `surfaceContainerHigh` is reserved for overlays, focused controls, and selected transactional rows.
- `primary` marks the active destination, selected mode, focused command, and primary action.
- `secondary` and `tertiary` support semantic state and the focal atmosphere; they do not create arbitrary decoration.
- `error` is reserved for destructive or error meaning.
- Dividers use `outlineVariant` with restrained opacity and align to content rather than the viewport edge.

### Gradient and grain contract

At most one strong atmospheric focal treatment is visible on a route. A faint app-level field may sit beneath it, but it must not compete.

Allowed strong uses:

- the empty Create canvas;
- an active image/video generation field;
- the Model Details overview when it materially clarifies the selected artifact;
- the Settings appearance preview.

Models discovery, ordinary lists, navigation, sheets, dialogs, chips, status marks, buttons, and settings rows remain matte.

The focal field uses three seed-derived anchors with a static cached grain texture and a quiet vignette. Grain is clipped to the focal owner. It does not animate. Dark mode may be more chromatic; light mode uses lower saturation and opacity. Foreground contrast is measured against the composited result.

The Create atmosphere enters once over approximately 900ms using opacity and slow anchor revelation. It does not loop, drift while idle, or restart on unrelated recomposition. Reduced motion shows the settled field immediately or with a maximum 90ms opacity transition.

### Typography

- Screen title: 28sp/34sp, semibold.
- Expanded Details title: 32sp/38sp, semibold.
- Compact Details title: 24sp/30sp, semibold.
- Section title: 16sp/22sp, semibold.
- Model/result title: 17sp/22sp, medium or semibold, maximum two lines.
- Reading body: 16sp/24sp.
- Dense metadata: 14sp/20sp.
- Technical identifier: 12sp/17sp, monospace medium.
- Action and control label: 14sp/20sp, medium.

Monospace is limited to filenames, formats, quantization, sizes, speeds, token counts, identifiers, and similar machine data. Long model names wrap naturally without shrinking below their semantic role.

### Shape

- 8dp: status marks and compact badges.
- 12dp: buttons, inputs, selectable controls, and selected technical rows.
- 18dp: command surfaces and sparse focal content.
- 24dp: sheets and dialogs.
- Capsules: true tags, compact navigation selection, and circular controls only.

Ordinary result rows and configuration sections do not become cards.

### Spacing

The 4dp grid is canonical.

- compact page gutter: 16dp;
- medium and expanded page gutter: 24dp;
- related label/value spacing: 4dp;
- compact inline group: 8dp;
- row element spacing: 12dp;
- standard internal padding and related-group spacing: 16dp;
- section spacing: 24dp;
- new screen-level region: 32dp.

Nested page gutters are prohibited.

## Shared Presentation Architecture

The overhaul keeps `CaraMLTheme`, dynamic seed color, motion-policy ownership, and current responsive layout boundaries. It evolves shared presentation components rather than duplicating route-specific approximations.

The shared vocabulary is:

- **App shell** — adaptive sidebar/rail/modal ownership, selected destination, contextual navigation, and focus-mode coordination.
- **Responsive workspace** — route width cap, gutters, safe insets, and optional supporting pane.
- **Quiet header** — title or hierarchy context with Back/Menu only when required.
- **Command surface** — prompt, model search, and high-value transactional input.
- **Technical row** — dense identity, metadata, status, selection, and trailing action without a card wrapper.
- **Signal rail** — narrow selected/live/recommended marker.
- **Status mark** — icon plus label and non-color semantic state.
- **Section header** — compact title, supporting state, and optional action.
- **Focal atmosphere** — shared seed-derived gradient, grain, contrast ownership, and one-shot entrance.

Shared components accept presentation data and callbacks. They do not access feature ViewModels or infer business actions.

## Create

### Empty Text workspace

- The route canvas itself is the focal surface; there is no enclosing card.
- The screen contains one mode selector, one concise statement, one supporting sentence, and the production composer/command surface.
- The initial content is vertically balanced without forcing exact viewport centering when the keyboard or large text reduces space.
- Model readiness appears as concise metadata near the composer, without `Local only` or decorative status noise.
- Recent sessions and `Continue working` are absent until a real product-owned session history exists.

### Empty Image and Video workspaces

- They share the same full-canvas structure and navigation semantics as Text.
- Copy and supporting controls are mode-specific.
- Required model/components, unavailable capability, confirmation, error, and retry states remain explicit and actionable.
- Media controls may form one meaningful control group but may not become a dashboard of nested cards.

### Active Text conversation

- After the first message, persistent navigation and Create header disappear.
- The conversation reclaims the full route canvas.
- Exactly one 48dp menu action opens the existing modal sidebar.
- The assistant response is unboxed reading content.
- User messages use a compact neutral tonal surface.
- Reasoning is a quiet disclosure with a 48dp target and no decorative green indicator.
- Generation statistics are subordinate technical text and omit redundant `Local` copy.
- The current production composer remains the command surface and retains model selection, send/stop behavior, IME handling, and accessibility semantics.
- New content remains visible above measured composer/statistics insets without manual scrolling.

### Active Image and Video generation

- The generated or generating media owns the focal region.
- Progress communicates preparing, generating, and finalizing only when supported by actual state.
- Unknown progress uses one indeterminate animation; determinate progress may use one policy-gated continuous signal.
- Controls stay matte and stable below or beside the focal output.

## Models

### Workspace hierarchy

1. quiet route header;
2. Discover, Downloads, and Library context;
3. dominant search command when Discover is active;
4. compact device/recommendation context;
5. one horizontal browse toolbar;
6. result summary and Reset when applicable;
7. dense results or a specific state view.

Search and the beginning of results must be visible in the first normal compact viewport. Storage and recommendation context are secondary and collapsible.

### Registry rows

- Rows sit directly on the canvas with aligned separators.
- Owner/provenance, model name, format, task, parameters, size, and recommendation are arranged as compact text rather than pills.
- Titles use at most two lines.
- Recommended, selected, and active rows may use a signal rail and low tonal emphasis, not a gradient fill.
- Ready, needs setup, unsupported, downloading, paused, failed, and retryable states use explicit StatusMark semantics.
- Nested artifact actions keep exact callback identity and global interaction locking.

### States

Loading, initial empty, query empty, filtered empty, offline/error, and Library empty are distinct. Filtered empty offers Reset. Error offers Retry only when the callback exists. `NEEDS_INFORMATION` never claims incompatibility.

### Expanded layout

At effective content width 840dp or greater, Models may use a 280–320dp supporting context column while preserving at least 480dp for results. The list remains the primary visual surface and retains one page scroll owner.

## Model Details

- Details uses Back and keeps Models selected in the shell.
- The repository owner is secondary context; the model name is the dominant hierarchy.
- The overview is integrated into the route rather than placed in a generic bordered card.
- Downloads, likes, task, format, and concise description remain visible without producing a metadata wall.
- Metadata uses divider-based key/value rows. The first four non-blank useful values appear before a saveable Show all/less disclosure.
- Tags are capped and secondary. Raw `key:value` tags do not dominate.
- Artifact and file rows are the primary decision surface and use technical typography.
- Exact selected artifact identity controls the signal rail, progress, state, and durable actions.
- Compact layouts expose one stable primary action region without duplicating the selected row action.
- Expanded layouts use one scrolling two-column workspace: primary overview/files plus a 320–360dp device-fit/install pane.
- There is exactly one page-level vertical scroll owner.

The focal atmosphere, when present, is limited to the overview and uses explicit foreground colors with tested contrast.

## Settings

- Settings is a continuous configuration document, not a stack of independent cards.
- Sections are separated by spacing and dividers.
- Theme, seed, palette, recommendation profile, priority, KV cache, runtime, and generation settings preserve their current callbacks and persistence.
- Exclusive choices expose selectable-group and radio semantics with one action node per option.
- The selected state has a visible non-color indicator.
- Controls have at least 48dp interactive targets.
- Descriptions disclose in place with brief opacity motion and remain saveable where appropriate.
- The appearance preview is the only strong gradient/grain treatment in Settings.
- The route has one vertical scroll owner and remains usable at 420x280dp and 200% font scale.

## Sheets, Dialogs, and Overlays

- Sheets and dialogs use a 24dp shape, matte tonal surface, clear title, one primary action, and an explicit dismiss path.
- Modal navigation, model selection, filters, recommendation calibration, risk confirmation, and download confirmation trap focus and restore it to their trigger.
- Scrims communicate modality without obscuring contrast.
- Overlay scroll owners remain separate from the underlying page and do not introduce nested page scrolling.
- Destructive actions use semantic error color and never a gradient.

## Motion

Motion is brief, causal, interruptible, and tied to state change.

- Modal sidebar open: panel translation 180ms ease-out; scrim fade 90ms.
- Modal sidebar close: 160–180ms ease-in; selection feedback and destination update begin immediately rather than waiting for dismissal.
- Rail/sidebar selection: signal rail and tonal state 180ms.
- Peer route transition: opacity only, 180ms.
- Details push: opacity plus 16dp horizontal offset, 220ms.
- Details pop: opacity plus reversed 16dp offset, 180ms.
- Disclosure: 90ms reduced/compact fade or 180ms standard reveal without spring overshoot.
- Create atmosphere: one-shot approximately 900ms entrance, then static.

There is no idle gradient motion, route scaling, content translation during drawer movement, or simultaneous independent continuous animation within one component. Reduced motion removes spatial movement and settles within 90ms.

## Accessibility and Input

- Every interactive target is at least 48dp in both dimensions.
- Selection, progress, loading, error, and completion have non-color semantics.
- Ordinary navigation rows do not publish false selection state; selectable groups do.
- Headers, rows, controls, and overlays remain usable at 200% font scale.
- Long model names wrap without clipping or overlapping actions.
- Keyboard traversal, Enter/Space activation, Escape dismissal, and focus restoration work on Desktop.
- Modal focus stays inside the active overlay.
- Screen reader order follows visual order on compact and expanded layouts.
- Light and dark foreground contrast meets WCAG AA for text and appropriate non-text contrast for icons and controls.

## State and Error Handling

Each route must render the domain state it receives without creating presentation-only ambiguity.

- Empty states explain the missing prerequisite and expose the existing recovery action.
- Loading states preserve spatial stability where possible.
- Recoverable errors expose Retry only when supported.
- Destructive actions remain explicit and confirm when the existing behavior requires it.
- Unknown progress never becomes fake determinate progress.
- Unsupported and needs-information remain distinct.
- Download state is matched by exact artifact identity and does not leak between variants or shards.

## Implementation Boundaries

The work is delivered in independently testable presentation slices:

1. shared baseline tokens, atmosphere, shape, typography, and command surfaces;
2. adaptive shell and contextual navigation;
3. Create empty/media states and production composer alignment;
4. distraction-free active conversation and focus-mode integration;
5. Models Discover/Downloads/Library workspace;
6. Model Details and durable artifact actions;
7. Settings document layout and appearance preview;
8. overlays, motion, accessibility, full verification, documentation, and device review.

The existing uncommitted Focus Mode implementation is the starting implementation for slices 3 and 4. It must be reconciled with this contract rather than discarded or described as the complete overhaul.

## Verification Contract

Every slice follows production-path red/green testing. Tests assert behavior, rendered geometry, semantics, and motion rather than source strings or test-only replicas.

Required coverage includes:

- widths 360, 599, 600, 839, 840, 1199, and 1200dp;
- portrait and short-landscape layouts;
- 100% and 200% font scale;
- light and dark appearance;
- reduced and standard motion;
- safe top, start, end, bottom, and IME insets;
- empty, loading, populated, filtered-empty, error, downloading, paused, retryable, generating, and completed states;
- stationary route bounds during modal sidebar motion;
- exact callbacks and artifact identities;
- single page scroll-owner invariants;
- rendered contrast on composited gradient/grain surfaces;
- keyboard and accessibility semantics for overlays and exclusive choices.

Final gates are:

1. focused Compose suites for each slice;
2. full `:composeApp:jvmTest`;
3. repository `verifyProject`, including native tests;
4. `:androidApp:assembleDebug`;
5. signing-independent iOS simulator compilation;
6. Android physical-device install and visual interaction matrix when an unlocked device and required local models are available;
7. Desktop and iOS runtime visuals reported separately and never inferred from compilation.

Visual review compares production captures against the baseline's hierarchy and behavior rather than requiring pixel identity with HTML. Any unexercised device state is reported as unverified.

## Completion Criteria

The overhaul is complete only when:

- all routes visibly belong to one baseline system;
- no route retains the rejected stacked-card dashboard silhouette;
- Create owns the atmospheric full canvas and Focus Mode;
- Models and Settings remain primarily matte and information-led;
- Details is artifact-first and preserves exact durable download behavior;
- compact navigation uses a modal sidebar, never bottom navigation;
- gradients and grain remain selective and static after entrance;
- motion preserves spatial trust and reduced-motion behavior;
- accessibility, large text, insets, and interaction targets pass their production tests;
- full repository gates pass;
- device evidence and any remaining runtime gaps are reported precisely.
