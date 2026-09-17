# CaraML Prism Workbench Overhaul Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace CaraML's card-heavy Aurora UI with the approved Prism Workbench system across the adaptive shell, Model Hub, Model Details, Create, and Settings while preserving all domain and ViewModel behavior.

**Architecture:** Keep `CaraMLTheme`, the existing routes, ViewModels, state types, and motion-policy boundary. Replace the presentation grammar with a small shared workbench vocabulary, expose three true shell destinations, and migrate each feature in sequence. Model Hub is the proof surface; subsequent features may consume only the shared contracts proven there.

**Tech Stack:** Kotlin 2.4, Compose Multiplatform 1.11, Material 3 Expressive, Navigation3, Koin, Compose JVM UI tests, Android device verification.

**Spec:** `docs/superpowers/specs/2026-09-17-caraml-prism-workbench-design.md`

## Global Constraints

- Existing inference, recommendation, admission, download, storage, settings persistence, route, and ViewModel contracts remain authoritative.
- The shell exposes exactly three destinations: Create (`AppScreen.Home`), Models (`AppScreen.Search`), and Settings (`AppScreen.Settings`). Text, Image, and Video remain generation modes inside Create.
- Navigation layout is bottom bar below `600dp`, 80dp rail from `600dp` through `1199dp`, and 256dp sidebar at `1200dp` and above.
- Peer routes use opacity only; Details uses a fixed 16dp horizontal offset. Reduced motion uses no spatial movement.
- At most one ambient gradient and one contextual command/live-state gradient are visible per screen. Ordinary cards, buttons, chips, status marks, dialogs, and metadata rows are never gradient-filled.
- Compact page gutter is 16dp; medium/expanded gutter is 24dp. No nested page gutter.
- All interactive targets are at least 48×48dp; body text reaches 4.5:1 contrast and meaningful icons/boundaries reach 3:1.
- The full compact flow remains reachable at 200% font scale and 420×280dp landscape.
- There is exactly one continuous animation for active indeterminate work and no idle infinite animation.
- Do not add a third-party UI, blur, animation, font, or screenshot dependency.
- Every production behavior/layout change follows RED → GREEN with a real Compose or unit test. No wall-clock sleeps and no whole-screen golden equality.
- The parent worktree `/Users/debanshud/Documents/Personal/Flash` contains unrelated dirty download/native work. Never stage, rewrite, commit, or merge those changes from this worktree.
- Update only `README.md` and `composeApp/README.md` Recent Changes for this presentation-only overhaul.

---

### Task 1: Prism foundation and shared workbench vocabulary

**Files:**

- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/theme/AuroraColors.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/theme/AppShapes.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/theme/AppTypography.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/ui/components/AuroraBackdrop.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/ui/components/CaraMLPane.kt`
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/ui/components/CommandSurface.kt`
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/ui/components/SignalRail.kt`
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/ui/components/TechnicalListRow.kt`
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/ui/components/StatusMark.kt`
- Create: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/ui/components/PrismWorkbenchComponentsUiTest.kt`
- Modify: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/theme/AuroraColorsTest.kt`
- Modify: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/ui/components/AuroraComponentsUiTest.kt`

**Interfaces:**

- Produces:

```kotlin
enum class SignalTone { Accent, Positive, Warning, Error, Neutral }

@Composable
fun CommandSurface(
    focused: Boolean,
    active: Boolean,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable BoxScope.() -> Unit,
)

@Composable
fun SignalRail(
    tone: SignalTone,
    modifier: Modifier = Modifier,
)

@Composable
fun TechnicalListRow(
    title: String,
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
    metadata: String? = null,
    contentDescription: String? = null,
    selected: Boolean = false,
    signalTone: SignalTone? = null,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    status: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
)

@Composable
fun StatusMark(
    label: String,
    contentDescription: String,
    tone: SignalTone,
    icon: ImageVector,
    modifier: Modifier = Modifier,
)
```

- Preserves: `AuroraSurfaceLevel`, `MaterialTheme.auroraColors`, `LocalAuroraMotionPolicy`, and all existing component call sites until their owning feature migrates.

- [ ] **Step 1: Write failing token and component tests**

Add tests that prove:

```kotlin
@Test fun commandSurfaceShowsSeedDerivedFocusAtBothEdgesWithoutFillingItsContent()
@Test fun technicalListRowUsesDividerGrammarAndKeepsACompactHeight()
@Test fun selectedTechnicalRowHasOneSignalRailAndNonColorSelectionSemantics()
@Test fun statusMarkPaintsCompactlyButInteractiveParentRetainsFortyEightDpTarget()
@Test fun backdropHasOneBroadNonUniformFieldAndReadableDarkAndLightContent()
```

Use fixed `Density(1f)`, 360dp hosts, interior pixel probes away from text, and existing contrast helpers. The compact row fixture contains a one-line title and metadata and asserts height `<= 112f`.

- [ ] **Step 2: Run focused tests and record expected RED**

```bash
./gradlew :composeApp:jvmTest --tests '*PrismWorkbenchComponentsUiTest' --tests '*AuroraColorsTest' --tests '*AuroraComponentsUiTest'
```

Expected: unresolved workbench component APIs and the old pane/backdrop pixel contracts fail.

- [ ] **Step 3: Implement semantic color, type, and shape roles**

Keep dynamic Material color generation. Add only derived presentation roles needed by the interfaces above. Make the default pane borderless, use 12dp control and 18dp meaningful-panel shapes, and add a monospace technical text style:

```kotlin
val AppTechnicalLabel = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Medium,
    fontSize = 12.sp,
    lineHeight = 17.sp,
)
```

The backdrop remains static and remembered. It draws one top-leading primary wash and one smaller opposing tertiary wash; it does not animate while idle.

- [ ] **Step 4: Implement the four shared components**

`CommandSurface` uses a neutral semantic surface plus a 1dp focus boundary and a low-alpha outer gradient layer only when `focused || active`. `TechnicalListRow` uses padding and an aligned divider instead of a card outline. `SignalRail` is 3dp wide by default. `StatusMark` always includes icon, text, and `stateDescription`.

- [ ] **Step 5: Run focused and regression component suites**

```bash
./gradlew :composeApp:jvmTest --tests '*PrismWorkbenchComponentsUiTest' --tests '*AuroraColorsTest' --tests '*AuroraComponentsUiTest' --tests '*RecommendationComponentsUiTest'
```

Expected: all selected suites pass.

- [ ] **Step 6: Review the diff for raw colors and unowned gradients**

```bash
rg -n 'Color\(|horizontalGradient|verticalGradient|radialGradient|sweepGradient' composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core
git diff --check
```

Expected: raw colors are limited to existing color construction/tests; new gradient calls exist only in the backdrop and `CommandSurface`.

- [ ] **Step 7: Commit the approved design contract and foundation**

```bash
git add docs/superpowers/specs/2026-09-17-caraml-prism-workbench-design.md docs/superpowers/plans/2026-09-17-caraml-prism-workbench-overhaul.md composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core
git commit -m "feat(ui): establish Prism workbench foundation"
```

### Task 2: Three-destination adaptive shell and truthful navigation motion

**Files:**

- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/ui/layout/AdaptiveLayoutPolicy.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/drawer/AppDrawerShell.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/drawer/AdaptiveNavigation.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/drawer/DrawerItemView.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/ui/components/CaraMLTopBar.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/navigation/AppNavigation.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/chat/presentation/components/ModelSelectorTopBar.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search/SearchScreen.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/settings/presentation/SettingsScreen.kt`
- Delete if no references remain: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/drawer/AnimatedDrawerScaffold.kt`
- Delete if no references remain: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/drawer/CustomDrawer.kt`
- Delete if no references remain: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/drawer/DrawerController.kt`
- Modify: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/ui/layout/AdaptiveLayoutPolicyTest.kt`
- Rewrite: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/drawer/AdaptiveNavigationUiTest.kt`
- Modify: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/navigation/NavigationTransitionPolicyTest.kt`

**Interfaces:**

- `AppNavigationLayout` becomes `BottomBar`, `Rail`, and `Sidebar`.
- `AppDrawerShell` retains its public name and `backStack`/`content` signature as a compatibility shell for `App.kt`.
- `LocalGenerationModeController` remains available to Create.
- `LocalAppWindowWidth` and `LocalAppNavigationLayout` remain available to feature layout code.
- `CaraMLPrimaryTopBar` no longer requires or exposes a menu action on primary routes.
- Task 2 changes only the primary top-bar invocation in the three feature files above; Tasks 4, 6, and 7 own their later content recomposition.

- [ ] **Step 1: Replace old drawer expectations with failing shell contracts**

Cover exact boundaries and semantics:

```kotlin
@Test fun width599UsesBottomBarWithExactlyCreateModelsAndSettings()
@Test fun width600UsesRailAndWidth1199StillUsesRail()
@Test fun width1200UsesContextualSidebar()
@Test fun generationModeChangeDoesNotChangeSelectedCreateDestination()
@Test fun selectingModelsUpdatesVisibleRouteAndSelectionAtomically()
@Test fun peerDestinationTransitionNeverMovesTheRouteBounds()
@Test fun bottomBarAndRailRespectSafeDrawingInsetsAtTwoHundredPercentFontScale()
```

The first test asserts that Chat, Image, and Video are absent from shell destination semantics. The generation mode test changes `LocalGenerationModeController` through Text/Image/Video and keeps exactly one `Create, selected` node.

- [ ] **Step 2: Run shell tests and verify RED**

```bash
./gradlew :composeApp:jvmTest --tests '*AdaptiveLayoutPolicyTest' --tests '*AdaptiveNavigationUiTest' --tests '*NavigationTransitionPolicyTest'
```

Expected: the old `ModalDrawer`/840dp-sidebar policy and five shell items violate the new assertions.

- [ ] **Step 3: Implement the new breakpoints and three destination mapping**

Map Create to Home without resetting `GenerationModeController.mode`; Models to Search; Settings to Settings. Keep `Snapshot.withMutableSnapshot` for atomic stack replacement. Do not convert generation modes into routes.

- [ ] **Step 4: Implement compact bar, rail, and sidebar**

Compact bar is stationary and uses `WindowInsets.safeDrawing.only(Bottom)`. Rail/sidebar use vertical safe drawing insets and a scroll owner at 200% font scale. Selection uses a neutral tonal background plus `SignalRail`; no navigation item uses a gradient fill.

- [ ] **Step 5: Update route motion**

Set peer enter/exit to opacity-only 180ms. Set detail offset to 16dp, enter 220ms, exit 180ms. Reduced-motion descriptor has `axis = None` and duration no longer than 90ms.

- [ ] **Step 6: Run shell and existing feature top-bar tests**

```bash
./gradlew :composeApp:jvmTest --tests '*AdaptiveLayoutPolicyTest' --tests '*AdaptiveNavigationUiTest' --tests '*NavigationTransitionPolicyTest' --tests '*ChatAuroraUiTest' --tests '*ModelHubAuroraUiTest' --tests '*SettingsAuroraUiTest'
```

Expected: all selected suites pass after obsolete menu assertions are updated to assert no menu action.

- [ ] **Step 7: Remove unused drawer implementation only after reference scan**

```bash
rg -n 'AnimatedDrawerScaffold|CustomDrawerState|DrawerController|LocalDrawerController' composeApp/src
```

Delete files only when the scan shows no production or valid test dependency. Otherwise keep the smallest compatibility shim and record it in the task report.

- [ ] **Step 8: Commit the shell**

```bash
git add composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core
git commit -m "feat(navigation): introduce adaptive Prism workspace shell"
```

### Task 3: Model Hub registry components

**Files:**

- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search/components/SearchBar.kt`
- Replace: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search/components/ModelResultCard.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search/components/SearchModelListItem.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search/components/ModelListItem.kt`
- Replace: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search/components/ModelHubOverview.kt`
- Replace: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search/components/SortFilterChips.kt`
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search/components/ModelHubHeader.kt`
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search/components/ModelHubContextStrip.kt`
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search/components/ModelHubToolbar.kt`
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search/components/ModelHubStateView.kt`
- Modify: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search/ModelHubAuroraUiTest.kt`
- Modify: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search/ModelHubControlsUiTest.kt`
- Create: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search/ModelHubRegistryUiTest.kt`

**Interfaces:**

- Preserve every existing callback and model type consumed by `SearchScreen`.
- `ModelResultCard` may remain as a source-compatible wrapper but must render `TechnicalListRow` grammar.
- Use stable tags: `model-command`, `model-context`, `model-toolbar`, `model-summary`, `model-results`, and `model-row:<repositoryId>`.

- [ ] **Step 1: Add failing component hierarchy and density tests**

```kotlin
@Test fun searchCommandHasOneSearchAffordanceAndConditionalClearAction()
@Test fun contextStripIsShorterAndVisuallyQuieterThanTheSearchCommand()
@Test fun browseToolbarFitsOneHorizontalBandAt360dp()
@Test fun oneLineModelRowIsAtMost148DpAndHasNoCardOutline()
@Test fun longModelNameKeepsOwnerTitleStatusAndMetadataReadable()
@Test fun recommendedRowUsesOneSignalRailRatherThanGradientFill()
@Test fun needsInformationRemainsAnExplicitNonColorState()
```

For the toolbar, assert all controls share one vertical band after horizontal scrolling rather than appearing on stacked Y positions. For the row, sample neutral interior regions and assert no independent rounded-card boundary at the page edge.

- [ ] **Step 2: Run Model Hub component suites and verify RED**

```bash
./gradlew :composeApp:jvmTest --tests '*ModelHubRegistryUiTest' --tests '*ModelHubAuroraUiTest' --tests '*ModelHubControlsUiTest' --tests '*RecommendationComponentsUiTest'
```

Expected: old stacked controls and card hierarchy fail geometry/pixel assertions.

- [ ] **Step 3: Implement the command and context components**

Search uses `CommandSurface`, one leading search icon, and a trailing clear action only for non-blank input. Device storage and recommendation profile become a compact secondary strip; preserve the exact profile content description and callback.

- [ ] **Step 4: Implement a one-band toolbar and existing filter sheet**

Keep Text/Image/Video, sort, min/max parameter, and filter semantics. Visible chrome is one horizontally scrollable band with model modes, Sort, and Filters; detailed ranges remain in the existing sheet. Expose active filter count in Filters semantics and text.

- [ ] **Step 5: Implement registry rows**

Split repository ID into owner and model title once. Use monospace only for owner/format/size/quantization. Keep recommendation, download, local selection, and click callbacks unchanged. Provide exact state descriptions.

- [ ] **Step 6: Run component suites at 1x and 200% font scale**

```bash
./gradlew :composeApp:jvmTest --tests '*ModelHubRegistryUiTest' --tests '*ModelHubAuroraUiTest' --tests '*ModelHubControlsUiTest' --tests '*RecommendationComponentsUiTest'
```

- [ ] **Step 7: Commit registry components**

```bash
git add composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search/components composeApp/src/commonTest/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search
git commit -m "feat(modelhub): build dense Prism registry components"
```

### Task 4: Model Hub screen integration and proof checkpoint

**Files:**

- Refactor: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search/SearchScreen.kt`
- Modify: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search/ModelHubRefreshUiTest.kt`
- Modify: `composeApp/src/jvmTest/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search/SearchScreenLayoutRegressionTest.kt`
- Create: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search/ModelHubWorkbenchScreenUiTest.kt`
- Evidence: ignored `.superpowers/sdd/2026-09-17-caraml-prism-workbench-overhaul/task-4-device/`

**Interfaces:**

- `SearchScreen` public parameters and callbacks remain byte-for-byte compatible.
- Search and Library each retain exactly one vertical scroll owner.
- Screen order on compact is header → tabs → command → context → toolbar → summary → results/state.
- At `>=840dp`, supporting context may become a 280–320dp column while results remain at least 480dp.

- [ ] **Step 1: Add failing whole-screen order and state tests**

```kotlin
@Test fun compactModelsPlacesCommandBeforeContextAndResultsInFirstViewport()
@Test fun discoverTabHasExactlyOneVerticalScrollOwner()
@Test fun libraryTabHasExactlyOneVerticalScrollOwner()
@Test fun filteredEmptyStateShowsCountFilterSummaryAndReset()
@Test fun loadingEmptyErrorAndContentHaveDistinctSemantics()
@Test fun width840UsesSupportingContextWithoutShrinkingResultsBelow480Dp()
@Test fun compactTwoHundredPercentTextKeepsResetAndFirstResultReachable()
```

The first viewport test uses a 360×800dp host and asserts `model-command` is displayed and either `model-summary` or the first result begins before the viewport bottom after normal insets.

- [ ] **Step 2: Run whole-screen tests and verify RED**

```bash
./gradlew :composeApp:jvmTest --tests '*ModelHubWorkbenchScreenUiTest' --tests '*ModelHubRefreshUiTest' --tests '*SearchScreenLayoutRegressionTest'
```

- [ ] **Step 3: Recompose `SearchScreen` without changing state collection**

Keep ViewModel collection, dialogs, refresh, selection, and callbacks in place. Extract only presentation sections. Use one `LazyColumn` per tab on compact; use one results scroll owner plus non-scrolling supporting context at expanded width.

- [ ] **Step 4: Implement explicit result-state summaries**

Use existing query, filter, loading, error, and result values. Do not synthesize incompatibility. When active filters produce zero results, render Reset filters with the existing filter reset callbacks. Preserve refresh-retention behavior.

- [ ] **Step 5: Run all Model Hub and recommendation suites**

```bash
./gradlew :composeApp:jvmTest --tests '*ModelHub*' --tests '*SearchScreenLayoutRegressionTest' --tests '*RecommendationComponentsUiTest' --tests '*ModelViewModelRecommendationTest'
```

- [ ] **Step 6: Build and inspect the Model Hub proof on a connected phone**

```bash
./gradlew :androidApp:assembleDebug
adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
adb shell am start -n com.debanshu777.caraml/.MainActivity
adb exec-out screencap -p > .superpowers/sdd/2026-09-17-caraml-prism-workbench-overhaul/task-4-device/models-dark.png
adb shell screenrecord --time-limit 15 /sdcard/caraml-modelhub.mp4
adb pull /sdcard/caraml-modelhub.mp4 .superpowers/sdd/2026-09-17-caraml-prism-workbench-overhaul/task-4-device/modelhub-navigation.mp4
```

Inspect actual frames for: one dominant search command, one-band controls, results visible without excessive preamble, three-destination shell, no stale mode/destination state, no route scale/translation, and readable long titles. If no device is connected, record the checkpoint as unverified and do not claim a device pass.

- [ ] **Step 7: Commit Model Hub integration**

```bash
git add composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search/SearchScreen.kt composeApp/src/commonTest/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search composeApp/src/jvmTest/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search
git commit -m "feat(modelhub): compose Prism discovery workspace"
```

### Task 5: Model Details artifact workspace

**Files:**

- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/details/DetailsScreen.kt`
- Refactor: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/details/components/ModelDetailContent.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/details/components/GgufFileListItem.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/details/components/InstallBundleCard.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/details/components/VariantPickerRow.kt`
- Modify: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/features/modelhub/presentation/details/ModelDetailsAuroraUiTest.kt`
- Create: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/features/modelhub/presentation/details/ModelDetailsWorkbenchUiTest.kt`

**Interfaces:**

- Preserve `DetailsScreen`, `ModelDetailContent`, exact-artifact callbacks, global interaction lock, progress, and install state parameters.
- Preserve `modelDetailLayout`, `modelDetailsUseSupportingPane`, `splitRepositoryId`, `formatHubTimestamp`, and `visibleModelTags` behavior unless a failing presentation test requires only layout output to change.
- Use tags: `detail-overview`, `detail-metadata`, `detail-files`, `detail-support`, `detail-action`.

- [ ] **Step 1: Write failing hierarchy and artifact-decision tests**

```kotlin
@Test fun compactDetailsUsesSectionsAndDividersInsteadOfStackedOutlinedCards()
@Test fun longRepositoryNameWrapsBelowOwnerWithoutRepeatingOwner()
@Test fun metadataShowsFourPrimaryRowsBeforeSaveableShowAll()
@Test fun selectedArtifactUsesSignalRailAndExactDownloadAction()
@Test fun compactShortLandscapeKeepsBottomActionReachableAtTwoHundredPercentText()
@Test fun expandedDetailsHasOneScrollOwnerAndA320To360DpSupportPane()
```

Retain and run every existing exact-artifact semantics/callback test. The new selected-artifact fixture includes two shards in one descriptor and expects exactly one active signal/state.

- [ ] **Step 2: Run Details tests and verify RED**

```bash
./gradlew :composeApp:jvmTest --tests '*ModelDetailsAuroraUiTest' --tests '*ModelDetailsWorkbenchUiTest' --tests '*RecommendationComponentsUiTest'
```

- [ ] **Step 3: Recompose overview and metadata**

Use the screen's one contextual gradient in the overview only as a low-alpha field behind semantic content. Metadata becomes aligned divider rows with a saveable Show all/less state. Cap raw tags and retain the existing date formatting.

- [ ] **Step 4: Recompose artifact and install surfaces**

Render files as technical rows. Selected/recommended artifact uses `SignalRail`; progress and exact action semantics remain unchanged. Keep the action footer stable on compact and one scroll owner on expanded.

- [ ] **Step 5: Run Details, recommendation, and exact-download suites**

```bash
./gradlew :composeApp:jvmTest --tests '*ModelDetails*' --tests '*RecommendationComponentsUiTest' --tests '*DownloadManager*'
```

- [ ] **Step 6: Commit Details**

```bash
git add composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/details composeApp/src/commonTest/kotlin/com/debanshu777/caraml/features/modelhub/presentation/details
git commit -m "feat(details): build artifact-first Prism workspace"
```

### Task 6: Create workspace, composer, and AI state trace

**Files:**

- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/chat/presentation/ChatScreen.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/chat/presentation/components/ModelSelectorTopBar.kt`
- Refactor: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/chat/presentation/components/ChatInputBar.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/chat/presentation/components/ChatMessageList.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/chat/presentation/components/MessageBubble.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/chat/presentation/components/GenerationActivity.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/chat/presentation/components/GenerationStatsBar.kt`
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/chat/presentation/components/GenerationModeSwitcher.kt`
- Modify: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/features/chat/presentation/ChatAuroraUiTest.kt`
- Modify: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/features/chat/presentation/ChatBackdropLayeringUiTest.kt`
- Create: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/features/chat/presentation/CreateWorkbenchUiTest.kt`

**Interfaces:**

- `ChatScreen`, `ChatScreenContent`, `ChatInputBar`, send/stop/model/download callbacks, `ChatUiState`, and `StreamingState` remain behavior-compatible.
- `GenerationModeSwitcher(mode, onModeSelected)` reads/writes the existing mode boundary; it does not navigate.
- `GenerationActivity` may delegate visual phase rows to `StateTrace` but cannot invent phases absent from state.

- [ ] **Step 1: Add failing Create hierarchy and mode tests**

```kotlin
@Test fun createShellSelectionStaysSelectedAcrossTextImageAndVideoModes()
@Test fun modeSwitcherInvokesExactModeWithoutNavigating()
@Test fun emptyCreateHasOneStatementAndOneCommandSurfaceWithoutOuterCard()
@Test fun assistantOutputRendersDirectlyOnCanvasWhileUserMessageRemainsTonal()
@Test fun focusedComposerShowsOneGradientLensAndRestingComposerShowsNone()
@Test fun sendStopRemainsOneStableFortyEightDpAction()
@Test fun stateTraceDistinguishesWaitingRunningCompleteAndErrorWithoutInventedPhases()
@Test fun reducedMotionModeChangeUsesNoSpatialOffset()
```

- [ ] **Step 2: Run Chat/Create suites and verify RED**

```bash
./gradlew :composeApp:jvmTest --tests '*ChatAuroraUiTest' --tests '*ChatBackdropLayeringUiTest' --tests '*CreateWorkbenchUiTest'
```

- [ ] **Step 3: Introduce the local mode switcher and quiet header**

Remove the hamburger dependency. Keep model selection contextual. On compact/rail layouts show Text/Image/Video within Create; expanded sidebar may also mirror the same mode state without creating a second source of truth.

- [ ] **Step 4: Recompose empty, conversation, and message surfaces**

Remove the outer empty-state card. Keep assistant output on canvas and user output on one compact tonal surface. Preserve list keys, scrolling, model-required actions, missing-component actions, and exact callbacks.

- [ ] **Step 5: Rebuild the composer with `CommandSurface`**

Resting composer is neutral. Focused or generating composer uses the allowed command lens. Keep one send/stop node through transition and existing IME/inset ownership.

- [ ] **Step 6: Rebuild generation activity as transparent state**

Use icon, label, optional progress, and semantics. Unknown progress gets the standard indeterminate indicator and no second pulse. Determinate progress may use one policy-aware local pulse. Reduced motion is static.

- [ ] **Step 7: Run Chat/Create and navigation suites**

```bash
./gradlew :composeApp:jvmTest --tests '*Chat*UiTest' --tests '*CreateWorkbenchUiTest' --tests '*AdaptiveNavigationUiTest' --tests '*NavigationTransitionPolicyTest'
```

- [ ] **Step 8: Commit Create**

```bash
git add composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/chat/presentation composeApp/src/commonTest/kotlin/com/debanshu777/caraml/features/chat/presentation
git commit -m "feat(chat): create focused Prism generation workspace"
```

### Task 7: Settings configuration list

**Files:**

- Refactor: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/settings/presentation/SettingsScreen.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/settings/presentation/AppearanceSection.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/settings/presentation/RecommendationProfileSection.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/settings/presentation/SettingFilterChip.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/settings/presentation/ExpandableSettingDescription.kt`
- Modify: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/features/settings/presentation/SettingsAuroraUiTest.kt`
- Create: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/features/settings/presentation/SettingsWorkbenchUiTest.kt`

**Interfaces:**

- Preserve all `SettingsViewModel` state, callback, enabled, error, calibration, seed, GPU, and KV cache contracts.
- Appearance preview owns the only contextual gradient in Settings.
- Use one vertical scroll owner and maximum width 760dp.

- [ ] **Step 1: Add failing settings-list tests**

```kotlin
@Test fun settingsUsesSectionLabelsAndDividerRowsInsteadOfOutlinedPanePerGroup()
@Test fun appearancePreviewIsTheOnlyContextualGradient()
@Test fun paletteRecommendationKvGpuAndSeedSelectionsHaveNonColorIndicators()
@Test fun everyTouchedControlKeepsFortyEightDpTarget()
@Test fun descriptionsRemainSaveableAndFadeOnlyUnderReducedMotion()
@Test fun settingsRemainReachableAtTwoHundredPercentTextInShortLandscape()
```

- [ ] **Step 2: Run Settings suites and verify RED**

```bash
./gradlew :composeApp:jvmTest --tests '*SettingsAuroraUiTest' --tests '*SettingsWorkbenchUiTest' --tests '*RecommendationComponentsUiTest'
```

- [ ] **Step 3: Recompose groups as configuration sections**

Use section labels, rows, and aligned dividers. Keep meaningful nested transactional groups only where one background is necessary. Do not wrap every section in `CaraMLPane`.

- [ ] **Step 4: Restrict gradient and preserve selection semantics**

Appearance preview uses the ambient field. All other controls use semantic tonal selection plus icon/check/radio/toggle semantics. Preserve exact callbacks.

- [ ] **Step 5: Run Settings and recommendation suites**

```bash
./gradlew :composeApp:jvmTest --tests '*SettingsAuroraUiTest' --tests '*SettingsWorkbenchUiTest' --tests '*RecommendationComponentsUiTest'
```

- [ ] **Step 6: Commit Settings**

```bash
git add composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/settings/presentation composeApp/src/commonTest/kotlin/com/debanshu777/caraml/features/settings/presentation
git commit -m "feat(settings): adopt Prism configuration workspace"
```

### Task 8: Cross-app verification, device evidence, and documentation

**Files:**

- Modify: `README.md`
- Modify: `composeApp/README.md`
- Evidence: ignored `.superpowers/sdd/2026-09-17-caraml-prism-workbench-overhaul/task-8-evidence/`
- Report: ignored `.superpowers/sdd/2026-09-17-caraml-prism-workbench-overhaul/task-8-report.md`

**Interfaces:**

- Consumes all prior tasks.
- Produces no new behavior; only fixes verification failures within the owning presentation layer and documents the completed overhaul.

- [ ] **Step 1: Run focused cross-app UI suites**

```bash
./gradlew :composeApp:jvmTest --tests '*PrismWorkbenchComponentsUiTest' --tests '*AdaptiveNavigationUiTest' --tests '*NavigationTransitionPolicyTest' --tests '*ModelHub*UiTest' --tests '*SearchScreenLayoutRegressionTest' --tests '*ModelDetails*UiTest' --tests '*CreateWorkbenchUiTest' --tests '*Chat*UiTest' --tests '*Settings*UiTest' --tests '*RecommendationComponentsUiTest'
```

- [ ] **Step 2: Run the complete JVM and project gates**

```bash
./gradlew :composeApp:jvmTest
./gradlew verifyProject
./gradlew :androidApp:assembleDebug
git diff --check
```

Record exact totals, failures, warnings, and unsupported tasks separately.

- [ ] **Step 3: Scan design-system invariants**

```bash
rg -n 'horizontalGradient|verticalGradient|radialGradient|sweepGradient' composeApp/src/commonMain/kotlin/com/debanshu777/caraml
rg -n 'rememberInfiniteTransition|infiniteRepeatable' composeApp/src/commonMain/kotlin/com/debanshu777/caraml
rg -n 'Color\(' composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features
```

Expected: gradient ownership matches the spec; any infinite animation is active-work-only and policy-gated; no new feature raw colors exist.

- [ ] **Step 4: Install and capture a Pixel light/dark matrix**

Capture Create Text/Image/Video, Models initial/results/empty when reachable, long-name Details, Settings, bottom navigation, and Details push/pop. Record at least one navigation video and one detail video. Inspect every artifact, not just successful capture commands.

- [ ] **Step 5: Probe expanded and iOS compile paths**

Run Desktop and capture only if a visible display is available. Run an iOS simulator-SDK compile without claiming visual coverage when no simulator exists. Keep compile PASS and visual PASS as separate statuses.

- [ ] **Step 6: Update Recent Changes**

Add concise bullets describing the three-destination adaptive shell, registry-style Model Hub, artifact-first Details, focused Create composer/state trace, Settings list, and verification boundary. Do not update unrelated module READMEs.

- [ ] **Step 7: Run final hygiene and commit**

```bash
git status --short
git diff --check
git add README.md composeApp/README.md
git commit -m "docs(ui): document Prism workbench overhaul"
```

- [ ] **Step 8: Request final whole-branch review**

Review the entire range from `5000969` to `HEAD` against the design spec, with special attention to gradient ownership, shell truthfulness, callback preservation, one-scroll-owner rules, exact artifact actions, reduced motion, and 200% text reachability. Fix every Critical/Important issue through the plan's review loop before integration.
