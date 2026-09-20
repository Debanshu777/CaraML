# CaraML Baseline UI Overhaul Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace CaraML's remaining mixed Prism/Aurora presentation with the approved baseline UI across the complete app while preserving all domain behavior.

**Architecture:** Evolve the existing shared theme, responsive workspace, navigation shell, and presentation components, then migrate each route through production-path Compose tests. Domain and ViewModel contracts remain unchanged; feature screens consume the shared baseline vocabulary and keep exact callbacks, state, artifact identity, and persistence behavior.

**Tech Stack:** Kotlin 2.4.0, Compose Multiplatform 1.11.1, Material 3, Navigation3, Koin, Compose UI tests, Gradle/JDK 21.

**Spec:** `docs/superpowers/specs/2026-09-21-caraml-baseline-ui-overhaul-design.md`

## Global Constraints

- Preserve inference, recommendation, download, storage, settings, navigation, and ViewModel behavior.
- Keep exactly three top-level destinations: Create, Models, Settings.
- Use modal sidebar below 600dp, 80dp rail from 600–839dp, and 256dp sidebar at 840dp and above; never add bottom navigation.
- Keep at most one strong gradient/grain focal treatment per route; navigation, ordinary rows, controls, dialogs, and sheets stay matte.
- Create's atmosphere enters once in approximately 900ms, then remains static; reduced motion settles within 90ms with no spatial movement.
- All interactive targets are at least 48dp; all screens remain usable at 200% font scale and with safe drawing/IME insets.
- Use production-path RED → GREEN tests. Do not accept source-grep or test-only replica assertions as behavioral proof.
- Preserve the user's existing uncommitted Focus Mode changes; reconcile them in Tasks 2–4 and stage only task-owned files at each commit.
- Run Gradle serially with `--no-daemon -Pkotlin.incremental=false` for focused UI gates when shared caches are at risk.
- Do not download a large model automatically for device verification.

---

### Task 1: Baseline visual foundation and focal atmosphere

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/theme/AuroraColors.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/theme/AppTypography.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/theme/AppShapes.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/ui/components/AuroraBackdrop.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/ui/components/AuroraFocalSurface.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/ui/components/CommandSurface.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/ui/components/CaraMLSectionHeader.kt`
- Modify: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/theme/AuroraColorsTest.kt`
- Modify: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/ui/components/AuroraBackdropUiTest.kt`
- Modify: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/ui/components/PrismWorkbenchComponentsUiTest.kt`
- Modify: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/ui/motion/AuroraMotionPolicyTest.kt`

**Interfaces:**
- Consumes: existing `MaterialTheme.auroraColors`, `LocalAuroraMotionPolicy`, deterministic grain brush, and Material theme tokens.
- Produces: `FocalEntrance`, the extended `AuroraFocalSurface`, and final baseline typography/shape/color tokens used by every later task.

- [ ] **Step 1: Write failing token and rendered-surface tests**

Add tests that assert exact typography/shape roles, a matte command surface at rest, deterministic static grain, explicit dark/light foreground contrast, and no rendered motion after the focal entrance settles. Define the production-facing entrance contract in the test:

```kotlin
enum class FocalEntrance { None, OneShot }

AuroraFocalSurface(
    entrance = FocalEntrance.OneShot,
    modifier = Modifier.testTag("baseline-focal"),
) { Text("Private workspace", color = MaterialTheme.colorScheme.onSurface) }
```

Use `mainClock.autoAdvance = false` to prove the field changes during the first 900ms, remains pixel-stable afterward, and has no spatial change when reduced motion is active.

- [ ] **Step 2: Run the focused RED gate**

Run:

```bash
./gradlew --no-daemon -Pkotlin.incremental=false :composeApp:jvmTest \
  --tests '*AuroraColorsTest' \
  --tests '*AuroraBackdropUiTest' \
  --tests '*PrismWorkbenchComponentsUiTest' \
  --tests '*AuroraMotionPolicyTest'
```

Expected: behavioral failures for the missing entrance contract and any baseline token mismatches; existing static-grain tests continue compiling.

- [ ] **Step 3: Implement the shared baseline foundation**

Add `FocalEntrance` as an optional tail parameter so current call sites remain source-compatible:

```kotlin
enum class FocalEntrance { None, OneShot }

@Composable
fun AuroraFocalSurface(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.large,
    entrance: FocalEntrance = FocalEntrance.None,
    content: @Composable BoxScope.() -> Unit,
)
```

Drive only gradient opacity/anchor revelation with `Animatable`; remember the settled state by surface instance; bypass spatial animation under reduced motion. Keep the existing cached 64px deterministic grain tile and draw it after the three seed-derived anchors and vignette. Keep `CommandSurface` matte at rest and use only a focus/live edge halo.

- [ ] **Step 4: Run GREEN and inspect rendered evidence**

Run the Step 2 command. Capture dark and light focal surfaces at start, midpoint, settled, and reduced-motion settled states. Confirm ordinary command and technical surfaces have no gradient fill.

- [ ] **Step 5: Commit Task 1**

```bash
git add composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/theme \
  composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/ui/components \
  composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/theme \
  composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/ui/components \
  composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/ui/motion/AuroraMotionPolicyTest.kt
git diff --cached --check
git commit -m "feat(ui): establish baseline visual foundation"
```

---

### Task 2: Sidebar-first shell and contextual navigation

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/drawer/AdaptiveNavigation.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/drawer/AppDrawerShell.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/drawer/DrawerController.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/drawer/DrawerItemView.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/ui/layout/AdaptiveLayoutPolicy.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/ui/layout/ResponsiveContentPane.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/ui/components/CaraMLTopBar.kt`
- Modify: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/drawer/AdaptiveNavigationUiTest.kt`
- Modify: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/ui/layout/ShellGutterContractUiTest.kt`
- Modify: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/ui/components/CaraMLTopBarUiTest.kt`

**Interfaces:**
- Consumes: `AppNavigationLayout`, `DrawerController`, `GenerationModeController`, `FocusModeController`, and shared baseline tokens.
- Produces: atomic modal/rail/sidebar navigation, route-specific contextual items, stable route bounds, and menu/back ownership for feature screens.

- [ ] **Step 1: Write shell behavior tests before changing production**

Cover 599/600/839/840/1199/1200dp and assert:

```kotlin
assertEquals(AppNavigationLayout.ModalSidebar, adaptiveLayoutPolicy(599.dp, kind).navigation)
assertEquals(AppNavigationLayout.Rail, adaptiveLayoutPolicy(600.dp, kind).navigation)
assertEquals(AppNavigationLayout.Rail, adaptiveLayoutPolicy(839.dp, kind).navigation)
assertEquals(AppNavigationLayout.Sidebar, adaptiveLayoutPolicy(840.dp, kind).navigation)
```

Add production-shell tests for stationary content bounds during modal open/close; immediate route/selection agreement; Back/Escape/scrim dismissal; resize cleanup; Create modes; Models Discover/Downloads/Library contexts; Settings pinned to the bottom; and Details keeping Models selected while exposing Back.

- [ ] **Step 2: Verify RED against the current delayed selection flow**

Run:

```bash
./gradlew --no-daemon -Pkotlin.incremental=false :composeApp:jvmTest \
  --tests '*AdaptiveNavigationUiTest' \
  --tests '*ShellGutterContractUiTest' \
  --tests '*CaraMLTopBarUiTest'
```

Expected: the atomic-selection test fails because `closeThen` delays the route callback for the full panel duration; contextual Models navigation tests fail until the shell owns those items.

- [ ] **Step 3: Implement atomic contextual navigation**

Remove `selectionDelayMillis` and the delayed `closeThen` coroutine. Invoke the route/mode callback and `controller.close()` in the same event:

```kotlin
val selectAndClose: ((DrawerItem) -> Unit) -> (DrawerItem) -> Unit = { action ->
    { item ->
        action(item)
        controller.close()
    }
}
```

Keep panel translation at 180ms and scrim at 90ms, but never translate route content. Add route-context identity to the shell without introducing new domain routes. Keep Settings outside the scrolling primary/context region.

- [ ] **Step 4: Reconcile the existing uncommitted shell Focus Mode changes**

Retain `FocusModeController`, `LocalFocusModeController`, and the `currentScreen == AppScreen.Home` guard. Ensure focus mode switches only Home to modal navigation and cannot affect Search, Details, or Settings during Navigation3 transition overlap.

- [ ] **Step 5: Run shell GREEN and compatibility gates**

Run the Step 2 command plus:

```bash
./gradlew --no-daemon -Pkotlin.incremental=false :composeApp:jvmTest \
  --tests '*NavigationTransitionPolicyTest' \
  --tests '*ModelHubWorkbenchScreenUiTest' \
  --tests '*SettingsWorkbenchUiTest'
```

- [ ] **Step 6: Commit Task 2**

Stage only shell, shared layout/top-bar, and their tests. Confirm the Chat/README dirty files remain unstaged.

```bash
git diff --cached --check
git commit -m "feat(navigation): align the baseline sidebar shell"
```

---

### Task 3: Full-canvas Create empty and media states

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/chat/presentation/ChatScreen.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/chat/presentation/components/ModelSelectorTopBar.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/chat/presentation/components/GenerationModeSwitcher.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/chat/presentation/components/StateScreens.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/chat/presentation/components/ChatInputBar.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/chat/presentation/components/GenerationActivity.kt`
- Modify: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/features/chat/presentation/CreateWorkbenchUiTest.kt`
- Modify: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/features/chat/presentation/ChatBackdropLayeringUiTest.kt`
- Modify: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/features/chat/presentation/ChatFinalAuditUiTest.kt`

**Interfaces:**
- Consumes: `ChatUiState`, `GenerationModeController`, `AuroraFocalSurface`, `ChatInputBar`, and shell menu/layout locals.
- Produces: baseline full-canvas empty Text/Image/Video states and preserved recovery/generation actions.

- [ ] **Step 1: Write failing full-canvas and state-matrix tests**

At 420x800dp and 900x720dp, assert the empty workspace focal region reaches the responsive canvas edges and is not nested in `CaraMLPane`. Assert one mode selector, one statement, one explanation, the real composer/command action, and concise model readiness without `Continue working`, `Recent local sessions`, or `Local only`.

At 420x280dp and 200% font scale, cover NoModels, NoModelsForMode, ModelLoading, ModelError, MissingComponents, ConfirmRisk, AcceptAlternative, and RetryQuarantined. Require one scroll owner and exact callbacks.

- [ ] **Step 2: Verify RED**

```bash
./gradlew --no-daemon -Pkotlin.incremental=false :composeApp:jvmTest \
  --tests '*CreateWorkbenchUiTest' \
  --tests '*ChatBackdropLayeringUiTest' \
  --tests '*ChatFinalAuditUiTest'
```

Expected: geometry/copy/focal ownership failures against the existing Prism Create arrangement; state callback tests must continue compiling.

- [ ] **Step 3: Build the baseline empty workspace**

Use `AuroraFocalSurface(entrance = FocalEntrance.OneShot)` as the route canvas, not a child card. Keep the actual `ChatInputBar` path. Render mode controls in the top chrome for modal/sidebar layouts and in-screen where the rail lacks contextual labels. Keep all non-Ready states inside the existing shared scrollable viewport.

- [ ] **Step 4: Align Image/Video generation states**

Use the same canvas hierarchy, but let actual media/progress own the focal region. Keep only one indeterminate animation for unknown progress and one policy-gated determinate signal. Do not add media-specific domain logic.

- [ ] **Step 5: Run GREEN and inspect phone/desktop captures**

Run the Step 2 command. Capture empty Text/Image/Video at compact and expanded widths in dark/light themes, plus reduced-motion settled frames.

- [ ] **Step 6: Commit Task 3**

```bash
git diff --cached --check
git commit -m "feat(chat): build the baseline creation canvas"
```

---

### Task 4: Distraction-free active conversation

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/chat/presentation/ChatScreen.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/chat/presentation/components/ChatMessageList.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/chat/presentation/components/MessageBubble.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/chat/presentation/components/GenerationStatsBar.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/chat/presentation/components/ChatContextIndicator.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/chat/presentation/components/ChatInputBar.kt`
- Modify: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/features/chat/presentation/CreateWorkbenchUiTest.kt`
- Modify: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/features/chat/presentation/ChatAuroraUiTest.kt`
- Modify: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/features/chat/presentation/ChatFinalAuditUiTest.kt`

**Interfaces:**
- Consumes: Task 2 focus controller/shell behavior and Task 3 Create canvas/composer.
- Produces: production Focus Mode, unboxed assistant content, compact user messages, quiet reasoning/stats, and exact composer reachability.

- [ ] **Step 1: Strengthen the existing Focus Mode regression before changing visuals**

Keep the current production-shell test and add assertions that active Text conversation has no persistent CaraML/Create chrome, exactly one 48dp `Open navigation menu` action, an unboxed assistant region, no yellow response rail, no green decorative reasoning/model indicators, no `Local` statistic, and the unchanged production composer/model selector.

Assert switching the real back stack to Search while the outgoing Chat composition remains mounted restores Sidebar immediately.

- [ ] **Step 2: Verify RED for the remaining baseline message visuals**

```bash
./gradlew --no-daemon -Pkotlin.incremental=false :composeApp:jvmTest \
  --tests '*CreateWorkbenchUiTest' \
  --tests '*ChatAuroraUiTest' \
  --tests '*ChatFinalAuditUiTest'
```

Expected: current Focus Mode geometry passes where already implemented; remaining message/reasoning/stat visual assertions fail until the baseline content treatment lands.

- [ ] **Step 3: Finish production Focus Mode and message hierarchy**

Retain the existing shell-scoped nullable controller so standalone previews/tests do not require a provider. Activate only for non-empty Ready Text conversations. Keep the menu inside the Ready content box, use existing safe top ownership, and use horizontal+bottom composer insets without double-applying top insets.

Render assistant Markdown directly on the canvas. Keep user messages as compact tonal surfaces. Make reasoning a 48dp disclosure using typography and chevron state only. Use opaque `onSurfaceVariant` for statistics and omit the redundant `Local` label.

- [ ] **Step 4: Prove composer occlusion and auto-scroll behavior**

At 420x280dp and 200% font scale, expand the composer to four lines and show statistics. Without manually scrolling, assert the terminal assistant content remains above measured bottom padding and send/stop remains reachable.

- [ ] **Step 5: Run GREEN and commit Task 4**

Run the Step 2 command plus `*AdaptiveNavigationUiTest`. Stage the reconciled Chat files and the pre-existing README bullets only if their wording accurately describes the now-complete Focus Mode slice.

```bash
git diff --cached --check
git commit -m "feat(chat): complete distraction-free conversation mode"
```

---

### Task 5: Models discovery, downloads, and library workspace

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search/SearchScreen.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search/components/ModelHubHeader.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search/components/SearchBar.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search/components/ModelHubContextStrip.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search/components/ModelHubToolbar.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search/components/ModelResultCard.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search/components/ModelHubStateView.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/downloaded/components/LocalModelListItem.kt`
- Modify: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search/ModelHubWorkbenchScreenUiTest.kt`
- Modify: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search/ModelHubRegistryUiTest.kt`
- Modify: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search/ModelHubControlsUiTest.kt`
- Modify: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search/ModelHubRefreshUiTest.kt`
- Modify: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/features/modelhub/presentation/downloaded/components/LocalModelListItemPrismUiTest.kt`

**Interfaces:**
- Consumes: existing `SearchTabContent`, `DownloadedTabContent`, staged filter callbacks, `TechnicalListRow`, `StatusMark`, and shell Models context.
- Produces: matte baseline registry with dominant search, compact context/filter controls, exact result states, and dense local rows.

- [ ] **Step 1: Write failing whole-screen hierarchy tests**

At 420x800dp, assert search and the first result begin within the first viewport after normal insets. Assert one horizontal toolbar, one result summary, no stacked card wrappers, and no route-level strong gradient. At 900dp, assert an optional 280–320dp context column leaves at least 480dp for results.

Add Discover/Downloads/Library contextual-selection tests through the actual shell.

- [ ] **Step 2: Write registry/state behavior tests**

Assert two-line titles, aligned separators, owner/provenance visibility, technical metadata, signal rail only for recommended/selected/live rows, explicit ready/setup/unsupported/downloading/paused/retryable semantics, exact nested callbacks, and one page scroll owner. Cover initial empty, query empty, filtered empty with Reset, offline/error with Retry, and Library empty.

- [ ] **Step 3: Verify RED**

```bash
./gradlew --no-daemon -Pkotlin.incremental=false :composeApp:jvmTest \
  --tests '*ModelHub*UiTest' \
  --tests '*ModelHubRefreshUiTest' \
  --tests '*LocalModelListItemPrismUiTest' \
  --tests '*RecommendationComponentsUiTest'
```

- [ ] **Step 4: Implement the baseline Models workspace**

Keep current request/filter/ViewModel flows. Reorder presentation into header, contexts, search command, compact device strip, one toolbar, summary, and registry. Remove residual decorative panes and full-row alpha. Keep interactive controls matte and use shared StatusMark/SignalRail semantics.

- [ ] **Step 5: Run GREEN, capture populated and empty states, and commit**

Run the Step 3 command plus `*ModelViewModelRecommendationTest`. Inspect compact/expanded dark/light captures. Commit only Model Hub production/tests.

```bash
git commit -m "feat(modelhub): adopt the baseline model workspace"
```

---

### Task 6: Artifact-first Model Details

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/details/DetailsScreen.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/details/components/ModelDetailContent.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/details/components/GgufFileListItem.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/details/components/VariantPickerRow.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/details/components/InstallBundleCard.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/details/DownloadForLaterConfirmationDialog.kt`
- Modify: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/features/modelhub/presentation/details/ModelDetailsAuroraUiTest.kt`
- Modify: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/features/modelhub/presentation/details/ModelDetailsWorkbenchUiTest.kt`
- Create: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/features/modelhub/presentation/details/ModelDetailsRouteUiTest.kt`

**Interfaces:**
- Consumes: exact `DownloadArtifactIdentity`, durable download batches/actions, `TechnicalListRow`, shared focal atmosphere, and Details route callbacks.
- Produces: integrated overview, metadata disclosure, artifact-first rows, stable compact actions, and one-scroll expanded layout.

- [ ] **Step 1: Write failing hierarchy and geometry tests**

Assert Back + Models selected, owner once, model title once, no generic `Model details` duplication, divider metadata with four non-blank primary rows, saveable Show all/less, capped tags, and no generic bordered overview card. At 839/840 effective content width, assert compact/expanded title role and one/two-column layout.

- [ ] **Step 2: Write durable action regressions before presentation changes**

Use multi-file/sharded and diffusion fixtures to assert exact selected artifact state; global interaction lock; filename-scoped Pause/Resume/Retry/Cancel; selected diffusion batch over unrelated earlier batches; stable compact footer; and exact callback payloads. These tests must pass or fail only on presentation wiring, never on invented progress.

- [ ] **Step 3: Verify RED**

```bash
./gradlew --no-daemon -Pkotlin.incremental=false :composeApp:jvmTest \
  --tests '*ModelDetailsAuroraUiTest' \
  --tests '*ModelDetailsWorkbenchUiTest' \
  --tests '*ModelDetailsRouteUiTest' \
  --tests '*RecommendationComponentsUiTest' \
  --tests '*ModelViewModelRecommendationTest'
```

- [ ] **Step 4: Implement the baseline Details workspace**

Integrate the owner/title/description hierarchy into the route canvas. Limit focal atmosphere to Overview. Keep explicit foreground colors. Use divider metadata and technical artifact rows. Preserve the compact fixed primary action and the expanded 320–360dp supporting pane with exactly one vertical scroll owner.

- [ ] **Step 5: Run GREEN and owning download gates**

Run the Step 3 command and:

```bash
./gradlew --no-daemon -Pkotlin.incremental=false \
  :huggingFaceManager:jvmTest --tests '*DownloadManager*' --tests '*DownloadCoordinator*'
```

- [ ] **Step 6: Commit Task 6**

```bash
git diff --cached --check
git commit -m "feat(details): adopt the baseline artifact workspace"
```

---

### Task 7: Continuous baseline Settings document

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/settings/presentation/SettingsScreen.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/settings/presentation/AppearanceSection.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/settings/presentation/RecommendationProfileSection.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/settings/presentation/SettingFilterChip.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/settings/presentation/ExpandableSettingDescription.kt`
- Modify: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/features/settings/presentation/SettingsWorkbenchUiTest.kt`
- Modify: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/features/settings/presentation/SettingsAuroraUiTest.kt`

**Interfaces:**
- Consumes: existing Settings callbacks/state, baseline typography/dividers, `AuroraFocalSurface`, and selectable radio semantics.
- Produces: one-scroll continuous Settings document with a single atmospheric appearance preview.

- [ ] **Step 1: Write failing document-layout tests**

Assert a 760dp maximum content width, one vertical scroll owner, divider-separated sections, no stacked `CaraMLPane` cards, and exactly one strong focal gradient tagged to the appearance preview.

- [ ] **Step 2: Preserve and strengthen interaction semantics**

Assert six exclusive selectable groups where applicable; `Role.RadioButton`; selected true/false; exactly one OnClick node per option; decorative selection marks; 48dp targets; exact callback updates; and saveable disclosure state.

At 420x280dp and 200% font scale, assert preview text remains inside its safe content area and the terminal GPU/runtime action is reachable.

- [ ] **Step 3: Verify RED**

```bash
./gradlew --no-daemon -Pkotlin.incremental=false :composeApp:jvmTest \
  --tests '*SettingsWorkbenchUiTest' \
  --tests '*SettingsAuroraUiTest' \
  --tests '*RecommendationComponentsUiTest'
```

- [ ] **Step 4: Implement the continuous Settings document**

Replace residual card wrappers with section headers, rows, dividers, and spacing. Keep `AuroraFocalSurface` only in `AuroraThemePreview`. Keep descriptions and callbacks unchanged. Do not move setting ownership into shared UI.

- [ ] **Step 5: Run GREEN and commit Task 7**

Run the Step 3 command, inspect dark/light and 200%-font captures, then commit only Settings production/tests.

```bash
git commit -m "feat(settings): adopt the baseline settings document"
```

---

### Task 8: Overlays, cross-app accessibility, verification, and documentation

**Files:**
- Modify as required: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/chat/presentation/components/ChatModelPickerSheet.kt`
- Modify as required: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search/components/RecommendationProfileDialog.kt`
- Modify as required: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/details/DownloadForLaterConfirmationDialog.kt`
- Modify: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/ui/components/AuroraComponentsUiTest.kt`
- Modify: `README.md`
- Modify: `composeApp/README.md`
- Create: `.superpowers/sdd/2026-09-21-caraml-baseline-ui-overhaul/final-report.md` (ignored evidence report)

**Interfaces:**
- Consumes: all prior task output and current feature behavior.
- Produces: consistent overlays, final accessibility/motion proof, repository documentation, and explicit platform verification status.

- [ ] **Step 1: Add cross-app overlay and accessibility regressions**

Assert 24dp sheet/dialog shapes, matte surfaces, one primary action, explicit dismiss, focus containment/restoration, safe insets, 48dp targets, and no gradient ownership. Add cross-route 200%-font tests and ensure ordinary navigation rows omit false Selected semantics.

- [ ] **Step 2: Run the focused cross-app gate**

```bash
./gradlew --no-daemon -Pkotlin.incremental=false :composeApp:jvmTest \
  --tests '*AuroraComponentsUiTest' \
  --tests '*AdaptiveNavigationUiTest' \
  --tests '*CreateWorkbenchUiTest' \
  --tests '*Chat*UiTest' \
  --tests '*ModelHub*UiTest' \
  --tests '*ModelDetails*UiTest' \
  --tests '*Settings*UiTest' \
  --tests '*RecommendationComponentsUiTest'
```

- [ ] **Step 3: Run static ownership and security checks**

Confirm only approved focal owners construct/render strong gradients, grain is static, there are no idle infinite transitions, no new raw color literals outside theme/test fixtures, no build/dependency changes, no submodule pointer changes, no secret-like assignments, and `git diff --check` passes.

- [ ] **Step 4: Run full repository gates**

```bash
./gradlew --no-daemon :composeApp:jvmTest
./gradlew --no-daemon verifyProject
./gradlew --no-daemon :androidApp:assembleDebug
./gradlew --no-daemon :composeApp:compileKotlinIosSimulatorArm64
```

Record exact test/suite counts, native CTest counts, warnings, and any environment-only failures separately.

- [ ] **Step 5: Perform Android physical-device review when available**

Use the connected unlocked device without bypassing a secure keyguard. Install the fresh APK, launch it, and capture compact dark/light states for Create empty, modal sidebar, Models populated/empty where data permits, Details where data permits, Settings, and an active conversation only when a local model is already available. Record unreachable states as unverified; do not download a large model automatically.

- [ ] **Step 6: Probe Desktop and iOS honestly**

Compile and launch Desktop only when a visible display is accessible. Treat compile/launch-without-visible-window as unverified visual evidence. Treat the iOS simulator compile as compile-only unless an available simulator can actually render the app.

- [ ] **Step 7: Update documentation and write the final report**

Replace stale Recent Changes bullets in root and Compose READMEs with concise baseline-overhaul entries. Write the ignored report with per-platform PASS/BLOCKED/UNVERIFIED status, screenshots, test counts, and remaining gaps.

- [ ] **Step 8: Final review and commit**

Request an independent diff review. Fix only reproduced findings with their own RED/GREEN cycle. Re-run affected and full gates after production changes. Stage explicit paths, inspect the staged name list, and commit:

```bash
git diff --cached --check
git commit -m "docs(ui): document the baseline overhaul"
```

## Final Stop Condition

Stop only when the spec's completion criteria are met, full gates pass, the branch contains no unintended or unstaged production changes, and device/runtime gaps are reported rather than inferred. Do not broaden the work into domain redesign, new navigation destinations, session-history storage, model downloads for testing, or dependency upgrades.
