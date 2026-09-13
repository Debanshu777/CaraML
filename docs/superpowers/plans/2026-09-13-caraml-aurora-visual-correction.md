# CaraML Aurora Visual Correction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Correct the first Aurora implementation so CaraML has a visible seed-derived atmosphere, compact information hierarchy, and calm navigation motion matching the supplied Android screenshots and drawer recording.

**Architecture:** Correct the visual system at its shared ownership points first: backdrop colors, pane compositing, shapes, and focal surfaces. Then simplify navigation motion, rebuild the compact Model Hub composition, and normalize Model Details presentation without changing ViewModel, domain, route, download, or recommendation contracts. Each slice begins with a real Compose or policy regression that fails against the current UI.

**Tech Stack:** Kotlin 2.4.0, Compose Multiplatform 1.11.1, Material 3 1.10.0-alpha05, Navigation3 1.1.1, Compose UI Test, Kotlin Test, Android Debug Bridge.

**Spec:** `docs/superpowers/specs/2026-09-13-caraml-aurora-design.md`

## Global Constraints

- Preserve Android, iOS, and Desktop JVM source compatibility; keep shared UI in `commonMain`.
- Do not change ViewModel, repository, database, download, recommendation, storage, or native-runner contracts.
- Do not change the route set or the meaning of Chat, Image, Video, Models, Settings, and Details destinations.
- Add no third-party animation, blur, navigation, font, or design-system dependency.
- Keep exactly one 16dp compact page gutter; children inside `ResponsiveContentPane` must not add another page gutter.
- Derive gradient and translucent surfaces only from the active Material `ColorScheme`; verify contrast after compositing.
- Keep one ambient gradient across the shell and at most one focal gradient treatment per screen.
- Keep standard panes borderless; use borders only for focused controls, selections, and necessary separators.
- Keep 8dp compact shapes, 12dp controls, 16dp panes, 28dp modals, and capsules only for tags, badges, and circular icon controls.
- Keep 48dp minimum interactive targets while allowing painted non-interactive status labels to remain 28–32dp high.
- Peer routes use opacity only; Details uses a fixed 24dp horizontal axis; the compact drawer overlays stationary content.
- Reduced motion removes translation, scale, pulse, stagger, and shape morphing.
- Preserve one scroll owner per Model Hub tab and the existing Details scroll-owner guarantees.
- Use the supplied video and screenshots as visual regression inputs, then repeat the same flows on the attached Android phone in light and dark themes.
- Update only root `README.md` and `composeApp/README.md` Recent Changes.

## File Structure

- `core/theme/AuroraColors.kt` owns semantic gradient endpoints and composited surface alpha.
- `core/ui/components/AuroraBackdrop.kt` owns the static full-window atmospheric brush.
- `core/ui/components/CaraMLPane.kt` owns borderless translucent pane presentation.
- `core/ui/components/AuroraFocalSurface.kt` owns the single reusable focal gradient container.
- `core/drawer/AnimatedDrawerScaffold.kt` owns modal drawer overlay motion and gestures.
- `core/drawer/AppDrawerShell.kt` serializes drawer dismissal before peer destination replacement.
- `core/navigation/AppNavigation.kt` owns peer versus hierarchical transition descriptors.
- `features/modelhub/presentation/search/SearchScreen.kt` owns compact tab/list composition and page gutters.
- `features/modelhub/presentation/search/components/ModelHubOverview.kt` owns the focal device/profile band.
- `features/modelhub/presentation/search/components/SortFilterChips.kt` owns the compact kind/order/filter controls and filter sheet.
- `features/modelhub/presentation/search/components/SearchBar.kt` owns the single search affordance.
- `features/modelhub/presentation/search/components/ModelResultCard.kt` owns compact result hierarchy.
- `core/rating/ui/SuitabilityChip.kt` owns the compact painted status treatment and accessible target.
- `features/modelhub/presentation/details/components/ModelDetailContent.kt` owns overview and metadata hierarchy.

---

### Task 1: Visible Aurora atmosphere and restrained surfaces

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/theme/AuroraColors.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/ui/components/AuroraBackdrop.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/ui/components/CaraMLPane.kt`
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/ui/components/AuroraFocalSurface.kt`
- Modify: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/theme/AuroraColorsTest.kt`
- Modify: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/ui/components/AuroraComponentsUiTest.kt`

**Interfaces:**
- Consumes: `MaterialTheme.colorScheme`, `MaterialTheme.auroraColors`, `AuroraSurfaceLevel`, and existing shape/spacing tokens.
- Produces: `AuroraColors.backdropStops`, `AuroraSurfaceLevel.containerAlpha`, borderless `CaraMLPane`, and `AuroraFocalSurface(modifier, content)`.

- [ ] **Step 1: Write failing semantic and rendered-surface regressions**

Add literal expectations that dark Aurora endpoints use `primary`/`tertiary`, that Pane/Recessed/Floating alpha bands are `0.86f/0.78f/0.94f`, and a real Compose pixel test whose left and right samples remain measurably non-uniform through a standard Pane.

```kotlin
@Test
fun darkAuroraUsesVisibleSeedRolesAndApprovedSurfaceAlpha() {
    val scheme = darkColorScheme(primary = Color.Red, tertiary = Color.Blue)
    val aurora = scheme.toAuroraColors(isDark = true)

    assertEquals(Color.Red.copy(alpha = 0.28f), aurora.primaryGlow)
    assertEquals(Color.Blue.copy(alpha = 0.20f), aurora.tertiaryGlow)
    assertEquals(0.78f, AuroraSurfaceLevel.Recessed.containerAlpha)
    assertEquals(0.86f, AuroraSurfaceLevel.Pane.containerAlpha)
    assertEquals(0.94f, AuroraSurfaceLevel.Floating.containerAlpha)
}

@Test
fun paneKeepsBackdropAtmosphereVisibleWithoutDefaultOutline() = runComposeUiTest {
    // Render CaraMLTheme -> AuroraBackdrop -> CaraMLPane at 320x180.
    // Assert two interior Pane pixels differ by a visible hand-checked delta and
    // an edge pixel is not the old outlineVariant boundary color.
}
```

- [ ] **Step 2: Run focused tests and verify RED**

Run:

```bash
./gradlew :composeApp:jvmTest --tests 'com.debanshu777.caraml.core.theme.AuroraColorsTest' --tests 'com.debanshu777.caraml.core.ui.components.AuroraComponentsUiTest'
```

Expected: tests fail because the current dark endpoints disappear into container colors and every Pane paints an opaque outlined surface.

- [ ] **Step 3: Implement semantic compositing and the focal surface**

```kotlin
enum class AuroraSurfaceLevel(val containerAlpha: Float) {
    Canvas(0f), Recessed(0.78f), Pane(0.86f), Floating(0.94f)
}

@Composable
fun AuroraFocalSurface(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = MaterialTheme.auroraColors
    Box(
        modifier.background(
            Brush.linearGradient(listOf(colors.primaryGlow, colors.tertiaryGlow)),
            MaterialTheme.shapes.large,
        ),
        content = content,
    )
}
```

`CaraMLPane` composites `level.containerColor(colorScheme).copy(alpha = level.containerAlpha)` and defaults to no border. `AuroraBackdrop` uses broad diagonal/radial anchors remembered from semantic colors and stays static.

- [ ] **Step 4: Re-run focused tests, refactor only after GREEN, and commit**

Run the Task 1 focused command again, then:

```bash
git add composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/theme/AuroraColors.kt composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/ui/components/AuroraBackdrop.kt composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/ui/components/CaraMLPane.kt composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/ui/components/AuroraFocalSurface.kt composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/theme/AuroraColorsTest.kt composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/ui/components/AuroraComponentsUiTest.kt
git commit -m "fix(ui): reveal Aurora surface atmosphere"
```

---

### Task 2: Calm drawer and route motion

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/drawer/AnimatedDrawerScaffold.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/drawer/AppDrawerShell.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/navigation/AppNavigation.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/ui/motion/AuroraMotionPolicy.kt`
- Modify: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/drawer/AdaptiveNavigationUiTest.kt`
- Modify: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/navigation/NavigationTransitionPolicyTest.kt`
- Modify: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/ui/motion/AuroraMotionPolicyTest.kt`

**Interfaces:**
- Consumes: `CustomDrawerState`, `DrawerController`, `AuroraMotionPolicy`, current Navigation3 back stack.
- Produces: overlay-only `AnimatedDrawerScaffold`, `DrawerController.closeThen(action)`, and fixed transition descriptors with peer axis `None` and hierarchical offset `24dp` converted once through density.

- [ ] **Step 1: Write the drawer geometry and route descriptor regressions**

```kotlin
@Test
fun openingDrawerDoesNotMoveResizeOrRoundRouteContent() = runComposeUiTest {
    // Capture tagged content bounds at Closed, advance the controllable clock
    // through Opened, and assert identical bounds throughout; assert drawer is
    // displayed over content and remains dismissible.
}

@Test
fun fullMotionUsesFadeOnlyForPeersAndFixedAxisForDetails() {
    val motion = auroraMotionPolicy(1f)
    val peer = navigationTransitionDescriptor(Peer, Forward, motion, detailOffsetPx = 24)
    val detail = navigationTransitionDescriptor(Hierarchical, Forward, motion, detailOffsetPx = 24)

    assertEquals(NavigationTransitionAxis.None, peer.axis)
    assertEquals(180, peer.enterDurationMillis)
    assertEquals(24, detail.enterOffset(containerSize = 1_000))
    assertEquals(240, detail.enterDurationMillis)
}
```

Add a real `AppDrawerShell` test that clicks Models while the drawer is open and proves route content is not spatially transitioning until the drawer panel has left composition.

- [ ] **Step 2: Run focused tests and verify RED**

```bash
./gradlew :composeApp:jvmTest --tests 'com.debanshu777.caraml.core.drawer.AdaptiveNavigationUiTest' --tests 'com.debanshu777.caraml.core.navigation.NavigationTransitionPolicyTest' --tests 'com.debanshu777.caraml.core.ui.motion.AuroraMotionPolicyTest'
```

Expected: bounds change because content scales/translates/rounds; peer axis is Vertical; Details offset is 10% of container width; destination replacement overlaps drawer closing.

- [ ] **Step 3: Implement overlay drawer and serialized navigation**

Remove content `offset`, `scale`, `shadow`, and animated corner modifiers. Animate only drawer panel X and scrim alpha with a 240ms emphasized tween. Keep the route Box at fixed bounds. In `AppDrawerShell`, close the drawer first and apply the pending destination only after its close transition completes; persistent navigation applies immediately.

```kotlin
private fun spatialOffset(containerSize: Int): Int = when (axis) {
    NavigationTransitionAxis.None -> 0
    NavigationTransitionAxis.Horizontal -> detailOffsetPx
}
```

Peer routes use `fadeIn(tween(180)) togetherWith fadeOut(tween(180))`; Details uses 24dp, 240ms enter and 190ms exit. Reduced motion remains at most 100ms opacity only.

- [ ] **Step 4: Re-run focused tests and commit**

```bash
git add composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/drawer/AnimatedDrawerScaffold.kt composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/drawer/AppDrawerShell.kt composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/navigation/AppNavigation.kt composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/ui/motion/AuroraMotionPolicy.kt composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/drawer/AdaptiveNavigationUiTest.kt composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/navigation/NavigationTransitionPolicyTest.kt composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/ui/motion/AuroraMotionPolicyTest.kt
git commit -m "fix(navigation): calm Aurora transitions"
```

---

### Task 3: Compact Model Hub composition

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search/SearchScreen.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search/components/ModelHubOverview.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search/components/SortFilterChips.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search/components/SearchBar.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search/components/ModelResultCard.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/rating/ui/SuitabilityChip.kt`
- Modify: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search/ModelHubAuroraUiTest.kt`
- Modify: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search/ModelHubControlsUiTest.kt`
- Modify: `composeApp/src/jvmTest/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search/SearchScreenLayoutRegressionTest.kt`

**Interfaces:**
- Consumes: existing `ModelHubBrowseMode`, `ModelOrdering`, `ModelSort`, `ParameterRange`, storage/profile UI state, and callbacks.
- Produces: one-row `ModelKindSelector`, visible `OrderingControl`, one `Filters` action/sheet, single-affordance `SearchBar`, compact `ModelResultCard`, and `activeFilterCount` derived only from current selections.

- [ ] **Step 1: Write compact-phone layout regressions against real components**

At a 420dp-wide real `SearchTabContent`/controls composition, assert:

```kotlin
@Test
fun compactHubUsesOneGutterAndKeepsResultsInTheInitialViewport() = runComposeUiTest {
    // Assert tab/control/search/card left edges share the single 16dp page edge.
    // Assert LLM/Image/Video share one visual row.
    // Assert ordering and Filters share one row and Min/Max are absent until Filters opens.
    // Assert first result title is displayed in a 915dp-tall phone viewport.
}

@Test
fun emptySearchShowsOneSearchGlyphAndTypedSearchShowsClearPlusSubmit() = runComposeUiTest {
    // Empty: exactly one search icon/action. Non-empty: clear and submit actions.
}

@Test
fun passiveSuitabilityStatusPaintsCompactButKeepsReadableSemantics() = runComposeUiTest {
    // Painted label height <= 32dp; surrounding actionable card remains >=48dp.
}
```

- [ ] **Step 2: Run focused tests and verify RED**

```bash
./gradlew :composeApp:jvmTest --tests 'com.debanshu777.caraml.features.modelhub.presentation.search.ModelHubAuroraUiTest' --tests 'com.debanshu777.caraml.features.modelhub.presentation.search.ModelHubControlsUiTest' --tests 'com.debanshu777.caraml.features.modelhub.presentation.search.SearchScreenLayoutRegressionTest'
```

Expected: current child-level 16dp padding doubles the gutter; controls wrap through multiple rows; empty search paints two search glyphs; status and cards are oversized.

- [ ] **Step 3: Implement the compact hierarchy without changing callbacks**

- Remove page-level horizontal padding from `ModelHubOverview`, `SortFilterChips`, `SearchBar` call sites, search summaries, and list-card call sites inside `ResponsiveContentPane`.
- Render Search/Downloaded in one 48dp, 8dp-radius tab surface with opacity plus at most 1dp selected movement.
- Wrap `ModelHubOverview` in `AuroraFocalSurface`; keep device details collapsed and render profile as a compact tonal row.
- Keep LLM/Image/Video in one horizontally scrollable row. Keep ordering visible. Replace visible sort/min/max chips with one Filters button carrying an active-count badge and the existing modal choices.
- Empty SearchBar uses its leading icon only; the trailing submit action appears after input is non-empty. IME Search continues to submit.
- Separate owner caption from the two-line title, reduce card padding to 16dp, paint passive status at 28–32dp, and keep its state description.

- [ ] **Step 4: Re-run focused tests plus recommendation semantics and commit**

```bash
./gradlew :composeApp:jvmTest --tests 'com.debanshu777.caraml.features.modelhub.presentation.search.*' --tests 'com.debanshu777.caraml.core.rating.ui.RecommendationComponentsUiTest'
git add composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/rating/ui/SuitabilityChip.kt composeApp/src/commonTest/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search composeApp/src/jvmTest/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search/SearchScreenLayoutRegressionTest.kt
git commit -m "fix(modelhub): compact the browse experience"
```

---

### Task 4: Human-readable Model Details hierarchy

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/details/components/ModelDetailContent.kt`
- Modify: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/features/modelhub/presentation/details/ModelDetailsAuroraUiTest.kt`

**Interfaces:**
- Consumes: unchanged `ModelDetailResponse`, file/install/recommendation states, and callbacks.
- Produces: `splitRepositoryId(id): RepositoryHeading`, `formatHubTimestamp(value): String`, `visibleModelTags(tags): List<String>`, saveable Show all/less disclosure, and focal Overview presentation.

- [ ] **Step 1: Write pure presentation and real Compose regressions**

```kotlin
@Test
fun repositoryHeadingSeparatesOwnerFromNameWithoutRepeatingEither() {
    assertEquals(
        RepositoryHeading("GnLOLot", "MiniCPM5-1B-Claude-Opus-Fable5-Thinking-GGUF"),
        splitRepositoryId("GnLOLot/MiniCPM5-1B-Claude-Opus-Fable5-Thinking-GGUF"),
    )
}

@Test
fun metadataFormatsDatesAndFiltersStructuredDuplicateTags() {
    assertEquals("3 Jul 2026", formatHubTimestamp("2026-07-03T09:24:41.000Z"))
    assertEquals(
        listOf("gguf", "llama.cpp", "quantized", "coding"),
        visibleModelTags(listOf("gguf", "llama.cpp", "quantized", "coding", "base_model:org/model", "text-generation"), pipelineTag = "text-generation"),
    )
}
```

Add a 420dp/200% font real `ModelDetailContent` test asserting owner eyebrow and title are distinct, the long Base model value begins below its label and shares the start edge, raw ISO/namespaced tags are absent, and Show all reveals additional approved tags.

- [ ] **Step 2: Run Details tests and verify RED**

```bash
./gradlew :composeApp:jvmTest --tests 'com.debanshu777.caraml.features.modelhub.presentation.details.ModelDetailsAuroraUiTest'
```

Expected: owner is repeated inside the headline, long values are trailing-aligned, raw ISO strings render, and all raw tags appear as pills.

- [ ] **Step 3: Implement the corrected Details presentation**

Use `AuroraFocalSurface` for Overview. Split only on the first `/`, fall back safely for blank or ownerless IDs, and use `titleLarge` for the model name. Render long metadata with `Column` label/value rows; format valid ISO dates using the existing multiplatform date/time facilities and fall back to the source string when parsing fails. Remove tags that duplicate library/pipeline/model facts or contain structured `:` prefixes, show the first four, and use a saveable text button for Show all/less.

- [ ] **Step 4: Re-run Details and recommendation tests, then commit**

```bash
./gradlew :composeApp:jvmTest --tests 'com.debanshu777.caraml.features.modelhub.presentation.details.ModelDetailsAuroraUiTest' --tests 'com.debanshu777.caraml.core.rating.ui.RecommendationComponentsUiTest'
git add composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/details/components/ModelDetailContent.kt composeApp/src/commonTest/kotlin/com/debanshu777/caraml/features/modelhub/presentation/details/ModelDetailsAuroraUiTest.kt
git commit -m "fix(details): clarify model information hierarchy"
```

---

### Task 5: Consistency sweep, documentation, and physical-device proof

**Files:**
- Modify only if a regression proves necessary: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/chat/presentation/components/ChatInputBar.kt`
- Modify only if a regression proves necessary: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/settings/presentation/SettingsScreen.kt`
- Modify: `README.md`
- Modify: `composeApp/README.md`

**Interfaces:**
- Consumes: corrected shared Pane/focal/backdrop primitives from Tasks 1–4.
- Produces: consistent Chat/Settings rendering, concise Recent Changes, full automated evidence, and Android screenshots/recording for the supplied flows.

- [ ] **Step 1: Run the focused cross-feature visual contracts**

```bash
./gradlew :composeApp:jvmTest --tests 'com.debanshu777.caraml.features.chat.presentation.ChatAuroraUiTest' --tests 'com.debanshu777.caraml.features.chat.presentation.ChatBackdropLayeringUiTest' --tests 'com.debanshu777.caraml.features.settings.presentation.SettingsAuroraUiTest' --tests 'com.debanshu777.caraml.core.ui.components.AuroraComponentsUiTest'
```

If any test fails, add one regression naming the user-visible break before the smallest production correction; do not add a second focal gradient to either screen.

- [ ] **Step 2: Update relevant Recent Changes**

Replace stale Aurora bullets with concise notes that the compact UI now uses visible semantic gradients, translucent borderless panes, compact Model Hub/Details hierarchy, and overlay/fade navigation motion.

- [ ] **Step 3: Run all automated and platform build gates**

```bash
./gradlew :composeApp:jvmTest
./gradlew verifyProject
./gradlew :androidApp:assembleDebug
git diff --check
```

Also run a signing-disabled iOS simulator-SDK compile when Xcode is available. Report platform build gaps separately from source failures.

- [ ] **Step 4: Reproduce and inspect the attached Android flows**

Install the fresh debug APK on the attached device. In dark and light themes capture:

1. Models initial viewport with device band, compact controls, search, and first result.
2. Models results list with three cards visible at normal phone height when content permits.
3. The supplied long-name Model Details screen with corrected overview and metadata.
4. Drawer open, drawer destination selection, peer transition, Details push, and Details pop.

Record the navigation sequence and inspect frames at closed, half-open, open, destination selection, and settled states. Acceptance requires stationary route bounds under the drawer, no simultaneous whole-screen translations, no clipped content, one 16dp page gutter, readable system bars, and a visibly non-uniform Aurora atmosphere.

- [ ] **Step 5: Commit documentation and any proven final correction**

```bash
git add README.md composeApp/README.md
git commit -m "docs(ui): document corrected Aurora experience"
```

Final reporting distinguishes automated PASS, Android visual PASS, compile-only platforms, and unverified device classes. Include absolute paths to the fresh screenshots and recording.
