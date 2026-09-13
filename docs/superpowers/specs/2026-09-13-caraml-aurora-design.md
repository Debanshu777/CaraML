# CaraML Aurora Visual System Design

## Goal

Upgrade CaraML from a functional Material 3 interface into a cohesive, modern, cross-platform product experience without changing inference, download, recommendation, storage, or navigation behavior.

The redesign keeps user-selected dynamic colors as a core product feature. It derives atmospheric gradients and accent treatments from the active Material color scheme, applies one consistent spacing and surface hierarchy across the app, and uses motion to explain state changes. The result should feel calm while idle, expressive while generating, and efficient when browsing dense model information.

This revision incorporates physical-device feedback from the first Aurora implementation. The original implementation was internally consistent but visually missed the intended system: opaque bordered panes hid the ambient gradient, oversized containers reduced information density, and compound drawer/content transforms made navigation feel theatrical. The corrected system below supersedes those implementation choices while preserving the original product and ownership boundaries.

## Design references

CaraML Aurora adapts principles from several references instead of visually cloning one product:

- **Arc:** seed-colored atmosphere, soft pane geometry, sidebar-first composition, and gradients used as environmental color rather than decorative blobs.
- **Linear:** precise dark surfaces, restrained borders, clear luminance hierarchy, dense but legible utility layouts, and disciplined accent use.
- **OpenAI:** output-first conversation layout, quiet chrome, generous reading rhythm, and subtle fade/translate transitions.
- **Claude:** warm and human empty states, approachable copy, and visual calm. CaraML does not adopt a serif product typeface.
- **ElevenLabs:** premium generative-progress treatment, light-touch surface elevation, and focused feedback during long-running AI work.
- **Apple:** two presentation gears in one system: expressive showcase states when content is sparse and compact utility states when information is dense.
- **Perplexity:** readable long-form answers, restrained metadata, and a strict hierarchy between primary content and supporting facts.
- **Material 3 Expressive:** semantic color roles, platform typography, shape roles, adaptive layouts, accessible components, and spring-based interaction motion.

These sources are aesthetic references, not asset libraries. CaraML will not copy proprietary logos, illustrations, fonts, trademarks, or product-specific component arrangements.

## Product principles

1. **The model output is the hero.** Chrome becomes quieter as a conversation fills with content.
2. **Color creates atmosphere and describes state.** One coherent gradient field establishes the active theme; restrained focal treatments identify the primary action or selected context without decorating every card.
3. **Motion explains change.** Animation connects a user action to its result, communicates progress, or preserves spatial context.
4. **One system, two densities.** Empty states may be spacious and expressive; model lists and settings remain compact and scannable.
5. **Platform-native readability.** Use the platform sans-serif through Material typography. Do not bundle a brand font solely for visual novelty.
6. **Dynamic themes remain trustworthy.** Every foreground/background pair uses Material color roles and is checked in light and dark themes.
7. **Native inference stays protected.** Visual effects must not add material CPU, GPU, memory, or recomposition pressure during generation.

## Scope

This design covers:

- Theme-level gradient, surface, typography, shape, spacing, and motion tokens.
- The compact drawer and persistent navigation treatments for wider windows.
- Shared app bars, section headings, cards/panes, pills, empty states, and progress treatments.
- Chat, image generation, video generation, Model Hub search/downloaded tabs, model details, Settings, dialogs, and sheets.
- Responsive behavior across Android, iOS, and Desktop JVM.
- Reduced-motion, contrast, text scaling, keyboard, pointer, and touch behavior.
- Focused UI regression coverage and cross-platform compile gates.

## Non-goals

- Changing ViewModel, repository, database, download, recommendation, or native-runner contracts.
- Changing the route set or the meaning of Chat, Image, Video, Models, and Settings destinations.
- Adding a third-party animation, blur, navigation, or design-system dependency.
- Applying backdrop blur throughout the app. Frosted depth is simulated with translucent tonal surfaces so rendering remains portable.
- Adding perpetual animated backgrounds while the app is idle.
- Replacing Material 3 components when styling and composition can produce the intended result.
- Redesigning launch icons or introducing copied brand artwork.

## Visual identity

### Dynamic Aurora atmosphere

The active `ThemePreferences` remains the single input to `CaraMLTheme`. `materialKolor` continues to create the light or dark `ColorScheme`; Aurora tokens are derived after that scheme exists.

The ambient backdrop uses a layered Compose brush that must remain visibly present after content is composed:

1. `surface` is the full-window base.
2. In light themes, a broad wash of `primaryContainer` enters from the top-leading corner and a smaller `tertiaryContainer` wash enters from the opposing edge.
3. In dark themes, the washes use low-alpha `primary` and `tertiary` roles so the color does not disappear into dark container tones.
4. A gentle diagonal interpolation connects the anchors; isolated decorative blobs are prohibited.
5. Standard panes composite semantic Material surface roles over the backdrop instead of fully covering it. Foreground contrast is verified against the final composited color, never assumed from the uncomposited token.

The gradient is visible through the navigation shell and normal panes. Each screen may additionally use one focal gradient treatment: the empty-state/composer halo in Chat, the device summary in Model Hub, the overview header in Details, or the appearance preview in Settings. Dense rows, ordinary cards, dialogs, and secondary controls use translucent tonal surfaces rather than independent gradients.

Gradient-filled text and arbitrary white text over gradients are prohibited. A primary action may use a seed-derived gradient only when its foreground passes contrast against every endpoint and the midpoint in all supported palettes and themes. Otherwise the gradient forms a clearly visible outer halo around a solid semantic Material button. Destructive actions never use gradients.

### Surface hierarchy

CaraML uses four visual levels mapped to existing Material roles:

| Level | Material role | Use |
|---|---|---|
| Canvas | `surface` | App background and long-form assistant output |
| Recessed | `surfaceContainerLow` composited at approximately 72–82% | Navigation and low-priority grouped regions |
| Pane | `surfaceContainer` composited at approximately 82–90% | Composer, model cards, settings groups, and loading states |
| Floating | `surfaceContainerHigh` composited at approximately 92–96% | Menus, sheets, dialogs, selected or interactive overlays |

`surfaceContainerHighest` is reserved for pressed, selected, or high-attention transient states. Tonal translucency is the primary depth cue. A single soft, long-throw shadow style is used only on content that physically overlaps another pane, such as the open compact drawer, modal sheet, or floating composer.

Borders are rare. `outlineVariant` may separate adjacent rows or protect a control boundary, while `outline` is reserved for focused fields and important selections. Standard panes do not all receive an outline. Dark mode uses slightly lighter tonal steps instead of heavy black shadows.

### Typography

The app continues to use the platform Material sans-serif. Typography becomes more intentional through a fixed role map:

| Content | Material role | Treatment |
|---|---|---|
| Empty-state statement | `headlineMedium` | Medium weight, tight visual grouping |
| Screen title | `titleLarge` | Semibold, no oversized app-bar text |
| Section heading | `titleMedium` | Semibold |
| Model/card title | `titleMedium` | Semibold, maximum two lines; repository owner is a separate caption |
| Conversation body | `bodyLarge` | Relaxed line height for reading |
| Dense settings/model metadata | `bodyMedium` | Standard weight |
| Buttons and chips | `labelLarge` | Semibold |
| Measurements and generation stats | `labelMedium` | Tabular numerals where supported |

Large text relies on size and whitespace rather than weights above semibold. Supporting copy uses `onSurfaceVariant`; disabled or tertiary text must still satisfy accessibility contrast and must not be created by arbitrary alpha reductions.

### Shape and spacing

The existing 4dp spacing grid remains canonical. The token scale is `2, 4, 8, 12, 16, 24, 32, 48, 64dp`. Feature code consumes named spacing values; new bare spacing literals are limited to component-specific optical adjustments documented beside the use.

Shape roles are:

| Role | Radius | Use |
|---|---:|---|
| Micro | 4dp | Small status badges and progress tracks |
| Compact | 8dp | Tabs, chips, status labels, and compact selection indicators |
| Control | 12dp | Inputs, buttons, compact rows, and small cards |
| Pane | 16dp | Standard cards, focal panels, composer, and message bubbles |
| Modal | 28dp | Dialogs and bottom sheets |
| Full | Capsule | True tags, small badges, and circular icon controls only |

Compact windows use exactly one 16dp horizontal content margin. Children inside `ResponsiveContentPane` do not add a second page gutter. Medium and expanded windows use one 24dp margin. Standard pane padding is 16dp on compact windows and 20–24dp only where content density allows it. Large desktop content may use 32dp internal pane padding, but readable content widths remain constrained.

## Motion system

### Motion character

Motion is calm, responsive, and slightly elastic. Component interactions use Material 3 Expressive springs. Screen-level transitions use deterministic emphasized easing so navigation remains predictable across platforms.

No animation exists merely to keep the screen moving. Idle gradients are static. Continuous motion is allowed only for indeterminate work, active generation, or a directly manipulated control.

### Motion categories

| Category | Behavior | Timing |
|---|---|---|
| Press and selection | Shape, scale, or tonal response with low bounce | Expressive spring; settle without overshoot that changes layout |
| Top-level destination change | Opacity blend with no whole-screen translation or scale | 180–200ms |
| Forward detail navigation | Fade plus a fixed 24dp horizontal shared-axis movement | 220–250ms enter, 180–200ms exit |
| Back navigation | Reverse the same fixed detail movement | 220–250ms enter, 180–200ms exit |
| Drawer | Drawer panel slides over stationary content while the scrim fades | 220–280ms emphasized easing |
| Sheet/dialog | Fade scrim and decelerating vertical entrance | 250ms enter, 200ms exit |
| Expand/collapse | Animate content size and chevron rotation | 220ms |
| List/filter update | Animate only inserted, removed, and repositioned rows | 180–260ms |
| Determinate progress | Animate toward reported value without inventing progress | 180ms |
| Active generation | One lightweight alpha/gradient pulse on the status or composer halo | 1,600ms reversible cycle |

The existing full-width linear slide between every route is replaced because it overstates transitions between peer destinations. Chat, Models, and Settings are peers and use the top-level crossfade. Model Details is hierarchical and uses the horizontal shared axis.

### Feature motion

- Opening the compact drawer slides a modal navigation surface above stationary content. The underlying route keeps identical bounds, scale, and corner geometry throughout the transition. The drawer and route transition never compete for spatial movement.
- Switching peer destinations uses a short opacity blend. Tabs use at most 1dp of translation plus opacity, matching the Arc reference; neither interaction scales a full pane.
- Switching Chat, Image, and Video modes uses `AnimatedContent` so the empty-state message, prompt hint, mode pill, and relevant controls transform together.
- The composer changes tonal level and receives a subtle theme-gradient halo on focus or while generation is active. It does not continuously float or bounce.
- A newly submitted user message enters with a short fade and 8dp upward translation. Assistant tokens are not animated individually.
- Streaming text remains direct and allocation-conscious. A small status pulse indicates an active stream; individual tokens and the text cursor are not animated.
- Thinking disclosure, generation statistics, download details, recommendation explanations, and settings descriptions use coordinated expand/collapse motion.
- Model list filtering animates item placement and removal without replaying entrance animation for the whole list.
- Download and generation progress use backend-reported progress. Indeterminate states use the standard Material indicator plus one local atmospheric pulse.
- Success and error state changes use icon/color/content transitions; they do not shake the screen or display celebratory particles.

### Reduced motion

When the platform motion-duration scale is zero or reduced-motion behavior is exposed by the target, CaraML disables translation, scale, pulsing, stagger, and shape morphing. State changes become immediate or use a brief opacity-only transition no longer than 100ms. Functionality, progress text, and state announcements remain unchanged.

Animation tests use a controllable clock. No test relies on wall-clock sleeping.

## Adaptive app shell

Navigation adapts by window width while preserving the same destination model and controllers:

| Width | Navigation treatment | Content behavior |
|---|---|---|
| Compact, below 600dp | Gesture-enabled modal drawer | One content pane, 16dp margins |
| Medium, 600–839dp | Persistent compact rail | One content pane, 24dp margins |
| Expanded, 840dp and above | Persistent 240dp sidebar | Content centered within destination-specific maximum width |

The expanded sidebar shows the CaraML name, generation destinations, Models, and Settings. The selected destination uses a softly tinted pill and a narrow seed-derived gradient accent. Unselected destinations remain tonal and quiet.

Content maximum widths are 840dp for chat reading content, 1,040dp for Model Hub browsing, and 760dp for Settings. Model Details uses a list-detail arrangement at expanded widths, with metadata/actions in a supporting pane. No interactive content spans a fold or hinge.

Keyboard, safe-area, status-bar, navigation-bar, and display-cutout insets remain owned by the appropriate scaffold. The redesign must not double-apply IME padding.

## Shared presentation components

The redesign adds a small `core/ui` presentation vocabulary rather than a parallel component framework:

- `AuroraBackdrop`: remembers and draws the seed-derived ambient brush.
- `CaraMLPane`: composites an approved translucent surface level, optional rare boundary, shape role, and optional overlap elevation.
- `AuroraFocalSurface`: provides the single approved per-screen gradient field with a contrast-safe semantic content layer.
- `AuroraPrimaryAction`: provides a contrast-verified gradient action or a gradient halo around a semantic Material action.
- `CaraMLTopBar`: standardizes menu/back affordance, title metrics, scroll treatment, and optional trailing action.
- `CaraMLSectionHeader`: standard title, supporting copy, and optional action alignment.
- `CaraMLStatusPill`: semantic status icon, label, and container role.
- `CaraMLEmptyState`: expressive but compact icon/mark, statement, support copy, and primary action slot.
- `GenerationActivity`: shared active/indeterminate generation treatment for chat, image, and video modes.
- `ResponsiveContentPane`: applies window-aware margins and destination maximum width.

Feature-specific model cards, message bubbles, settings controls, and recommendation panels remain feature-owned. Shared components may not depend on feature ViewModels or domain models.

## Screen designs

### Chat, image, and video

The initial state is the most expressive surface. A centered `CaraMLEmptyState` presents the current mode, one concise line of guidance, and the floating composer. The Aurora wash is visible behind it. Once messages exist, the hero recedes and the conversation becomes an uncluttered reading surface.

Assistant output sits directly on the canvas with a constrained reading width. User messages retain a compact container aligned to the trailing edge. Thinking and generation metadata are secondary disclosures, not competing cards.

The composer uses the Pane radius, a Pane surface, a subtle boundary, and a Floating surface when focused. Model selection, context status, attachable mode indicators, send, and stop controls share one baseline and meet minimum touch targets. The send/stop transformation is animated as one control.

Image and video generation use the same conversational structure. Active work displays phase, reported progress, elapsed time, and a lightweight generation pulse. Generated media enters with a fade and size reveal after decoding, never before bytes are available.

### Model Hub

The Models screen retains Search and Downloaded as peer tabs but renders them as a compact 48dp tab treatment, not a feature-radius segmented hero. The app bar and tabs remain pinned; each tab owns exactly one vertical scroll container.

Device capacity and recommendation profile become one gradient-washed focal band with a translucent semantic inner surface. Device details remain collapsed initially, and the profile action reads as a compact row rather than a second large filled panel.

Model-kind controls occupy one compact row. Ordering stays immediately visible, while sort and parameter ranges move behind one clearly labeled Filters action whose badge or summary exposes active selections. Opening that action uses the existing modal sheet behavior and does not change filter semantics. Controls scroll horizontally rather than creating three or more stacked chip rows. Search shows one search affordance and one conditional clear affordance, never duplicate search icons.

Model cards use a compact list-card hierarchy: owner caption, two-line model title, one compact fit/status label, and one muted metadata line. Standard compact cards use 12–16dp internal padding and no redundant outer page gutter. A non-interactive status is visually 28–32dp high; an interactive status retains a 48dp semantic and touch target without inflating its painted container. Recommended models receive a seed-derived edge tint and tonal emphasis, not a full gradient fill. Downloading cards transform in place to show truthful progress and cancellation controls.

Downloaded models use the same card grammar as search results. Readiness, missing components, and selected-model status use `CaraMLStatusPill` so the same state looks identical across Search, Downloaded, Details, and Chat.

### Model Details

Compact windows show a single scrolling detail view with a normal top app bar and a stable bottom action region when an install action is available. Expanded windows use a list-detail/supporting-pane composition: description and files in the primary pane, device fit and install summary in the supporting pane.

The overview is the screen's one focal gradient treatment. Repository owner is a small eyebrow; the model name uses `titleLarge` and wraps naturally without repeating the owner. Download and like metrics remain secondary.

Metadata is grouped into meaningful sections rather than a flat wall of chips. Common short values may use a label/value row; long values such as base-model identifiers stack below their label and remain start-aligned. ISO timestamps are formatted for people. Structured or duplicate namespaced tags are not rendered as raw pills. A compact initial tag set is followed by an explicit Show all/Show less disclosure when needed. File variants remain explicit, and recommendation evidence remains readable before the user initiates a download or model load.

### Settings

Settings uses a 760dp centered content pane and grouped sections with clear headings. Appearance is first and includes a small live Aurora preview. Recommendation profile, inference defaults, memory/quality controls, and acceleration settings each use one Pane container with consistent internal row spacing.

Controls remain native Material components. Explanations sit below their corresponding control and use the supporting text role. Advanced technical descriptions longer than two body lines use an explicit Show details disclosure; current values and safety implications remain visible while the disclosure is closed.

### Dialogs, sheets, errors, and empty states

Dialogs and sheets share Modal shape, Floating surface, consistent title/body spacing, and one primary action hierarchy. Destructive actions remain semantically explicit and never use a celebratory gradient.

Errors state what failed and offer one concrete recovery action when available. Empty states explain why the area is empty and what the user can do next. Loading states use stable layout placeholders so content does not jump when data arrives.

## Accessibility and interaction requirements

- Normal text maintains at least 4.5:1 contrast; large text and meaningful boundaries maintain at least 3:1.
- Gradients are decorative and never the sole carrier of selection, progress, success, warning, or error state.
- Touch targets are at least 48dp. Desktop pointer targets retain visible hover and focus states without shrinking touch targets on mobile.
- Icon-only controls have localized content descriptions. Decorative icons remain excluded from accessibility traversal.
- Dynamic type must not clip at 200% font scale. Rows may grow vertically; labels may wrap rather than truncate critical meaning.
- Focus order follows visual order. Drawer, dialog, and sheet focus stays within the active modal surface until dismissal.
- Keyboard users can activate every navigation and model action, dismiss overlays, and identify the focused element.
- Progress updates expose meaningful text and avoid announcing every token or tiny percentage change.
- Color-blind users receive icon and text reinforcement for every semantic color.

## Performance constraints

- No third-party visual dependency is added.
- No live backdrop blur is required for the design to work.
- Brushes, paths, and animation specifications are remembered rather than recreated on each streaming update.
- Streaming token updates do not trigger animation objects per token.
- At most one continuous visual animation runs for the active generation surface.
- Lazy lists animate only affected items and do not stagger an entire refreshed result set.
- The ambient backdrop is static while idle and must not invalidate frames continuously.
- Expensive generated-media decoding remains off the UI thread and is not coupled to transition timing.

## Architecture and ownership

`CaraMLTheme` remains the only app theme entry point. It provides Material color, typography, shapes, motion, spacing, and new Aurora presentation tokens through composition locals.

`AppDrawerShell` remains responsible for destination selection and generation-mode routing. Its presentation may adapt by width, but it does not gain navigation business logic.

`NavigationHost` keeps the existing back stack and route entries. It selects peer versus hierarchical transition semantics without changing routes.

Shared presentation primitives live under `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/ui/`. Feature-specific UI stays in the existing `features/chat`, `features/modelhub`, and `features/settings` presentation packages. No shared primitive receives a ViewModel.

Existing UI state remains authoritative. Animation observes state; it never delays, predicts, or fabricates a business-state transition.

## Validation strategy

Implementation follows test-driven development for behavior and testable presentation policies.

Automated coverage will verify:

- Aurora gradient tokens derive only from semantic `ColorScheme` roles.
- Gradient atmosphere remains visibly non-uniform after panes are composed in light and dark schemes.
- Surface and shape role mappings remain stable in light and dark schemes, with standard panes borderless by default.
- Window-width policy selects drawer, rail, and sidebar at the specified boundaries.
- Opening and closing the compact drawer leaves content bounds and scale unchanged.
- Peer routes are opacity-only; hierarchical routes use the fixed 24dp shared axis.
- Reduced-motion policy disables translation, scale, pulse, and stagger.
- Shared components preserve accessible labels, touch-target sizing, and state semantics.
- Chat, Model Hub, Details, and Settings retain their existing actions and state routing.
- Existing Model Hub scroll-owner and layout regressions remain covered.
- At a representative compact phone viewport, Model Hub does not double its 16dp page gutter, filter controls do not create three stacked rows, and a result or loading state enters the initial scroll viewport.
- Model Details separates owner from model name, formats timestamps, start-aligns long metadata, and hides raw duplicate namespaced tags from the initial hierarchy.

Verification gates are:

1. Focused theme, adaptive-layout, motion-policy, and component tests.
2. Existing `SearchScreenLayoutRegressionTest` and relevant chat/model/settings tests.
3. `./gradlew :composeApp:jvmTest`.
4. `./gradlew verifyProject` as the preferred repository gate.
5. Android debug assembly when the local Android SDK is available.
6. Desktop visual review at compact, medium, expanded, light, dark, and reduced-motion configurations.
7. Android visual review at phone and tablet widths.
8. iOS compilation and visual review when the macOS/Xcode target environment is available; lack of this environment is reported separately rather than treated as passing.

Visual review checks typography hierarchy, spacing rhythm, contrast, focus, insets, scroll ownership, animation continuity, progress truthfulness, and idle rendering cost.

The supplied compact-phone screenshots and drawer recording are regression references. Final Android review repeats the same flows in light and dark themes and records the corrected drawer open/close, Model Hub initial/result states, and long-name Details state. A compile-only gate is never reported as visual approval.

## Documentation

Implementation updates the root and `composeApp` README Recent Changes sections with concise bullets describing the visual system, adaptive shell, and motion behavior. Other module READMEs remain unchanged because native engines, runners, and Hugging Face networking are outside this design.

## Acceptance criteria

- All primary destinations visibly share one CaraML Aurora system in light and dark themes.
- The active seed color influences atmosphere without reducing content contrast.
- The ambient gradient remains clearly visible through the shell and pane hierarchy, and each screen uses at most one approved focal gradient treatment.
- Ordinary panes do not form a wall of opaque bordered cards.
- Chat becomes quieter as content accumulates, while empty and generating states remain expressive.
- Model discovery and Settings present dense information with consistent hierarchy and spacing.
- Model Hub uses one page gutter, compact controls, one advanced-filter affordance, and compact list cards on phones.
- Model Details does not repeat repository ownership, right-align long identifiers, expose raw ISO timestamps, or dump every raw tag into the initial view.
- Compact, medium, and expanded widths use the specified navigation treatment and readable content limits.
- Drawer content overlays a stationary route; peer routes crossfade; hierarchical transitions use only a shallow fixed translation.
- Active generation and download states communicate truthful progress without excessive continuous motion.
- Reduced-motion mode remains fully usable and removes spatial/continuous effects.
- Existing product behavior and ownership boundaries are preserved.
- Focused tests and available platform build gates pass, with unavailable platform validation reported explicitly.
