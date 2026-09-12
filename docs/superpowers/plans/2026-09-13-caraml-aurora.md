# CaraML Aurora Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Apply the approved CaraML Aurora visual system consistently across the Compose Multiplatform app while preserving all inference, download, recommendation, storage, and navigation behavior.

**Architecture:** Extend the existing `CaraMLTheme` with semantic Aurora color, surface, spacing, shape, motion, and adaptive-layout policies. Build a small feature-agnostic `core/ui` presentation vocabulary, then migrate the app shell and each feature slice onto it without giving shared UI access to ViewModels or domain models. UI state stays authoritative; animations only render state transitions and never delay or invent business state.

**Tech Stack:** Kotlin 2.4.0, Compose Multiplatform 1.11.1, Material 3 1.10.0-alpha05, Material 3 Expressive, materialKolor 4.1.1, Navigation3 1.1.1, Compose UI Test, Kotlin Test.

**Spec:** `docs/superpowers/specs/2026-09-13-caraml-aurora-design.md`

## Global Constraints

- Preserve Android, iOS, and Desktop JVM source compatibility; keep shared UI in `commonMain`.
- Do not change ViewModel, repository, database, download, recommendation, storage, or native-runner contracts.
- Do not change the route set or the meaning of Chat, Image, Video, Models, Settings, and Details destinations.
- Add no third-party animation, blur, navigation, font, or design-system dependency.
- Keep `CaraMLTheme` as the only app theme entry point and keep `ThemePreferences` as its single persisted input.
- Derive Aurora colors from Material `ColorScheme` roles; do not use copied brand colors or arbitrary foregrounds over gradients.
- Keep gradients decorative and restricted to the app shell, empty-state hero, active composer halo, and selected recommendation treatment.
- Keep the ambient gradient static while idle and run at most one continuous visual animation for active generation.
- Preserve truthful backend-reported download and generation progress; never animate fabricated progress.
- Disable translation, scale, pulsing, stagger, and shape morphing when the motion-duration scale is zero.
- Maintain 48dp minimum interactive targets, 4.5:1 normal-text contrast, 3:1 large-text/boundary contrast, keyboard focus, and non-color state semantics.
- Keep one vertical scroll owner per Model Hub tab and do not double-apply IME or safe-area insets.
- Update only root `README.md` and `composeApp/README.md` Recent Changes for this UI-only project.

## File Structure

The implementation adds these focused units:

- `core/theme/AuroraColors.kt` — semantic gradient and surface colors derived from `ColorScheme`.
- `core/ui/layout/AdaptiveLayoutPolicy.kt` — deterministic width-to-navigation/content policy.
- `core/ui/motion/AuroraMotionPolicy.kt` — full/reduced motion decisions and shared durations.
- `core/ui/components/AuroraBackdrop.kt` — static ambient background brush.
- `core/ui/components/CaraMLPane.kt` — approved tonal surface levels.
- `core/ui/components/CaraMLTopBar.kt` — standardized menu/back/none top bar.
- `core/ui/components/CaraMLSectionHeader.kt` — title, support text, and action alignment.
- `core/ui/components/CaraMLStatusPill.kt` — accessible semantic status treatment.
- `core/ui/components/CaraMLEmptyState.kt` — expressive sparse-state composition.
- `core/ui/layout/ResponsiveContentPane.kt` — width-aware margins and maximum content width.
- `core/drawer/AdaptiveNavigation.kt` — modal drawer, compact rail, and expanded sidebar presentation.
- `features/chat/presentation/components/GenerationActivity.kt` — the single active-generation pulse/status surface.
- `features/modelhub/presentation/search/components/ModelHubOverview.kt` — compact storage/device/profile overview extracted from the oversized screen file.
- `features/modelhub/presentation/search/components/ModelResultCard.kt` — shared search/browse result card grammar.
- `features/settings/presentation/ExpandableSettingDescription.kt` — accessible disclosure for long technical explanations.

Existing feature files remain responsible for feature-specific state and actions.

---

### Task 1: Theme, adaptive-layout, and reduced-motion policies

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/theme/AuroraColors.kt`
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/ui/layout/AdaptiveLayoutPolicy.kt`
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/ui/motion/AuroraMotionPolicy.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/theme/AppSpacing.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/theme/AppShapes.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/theme/AppTypography.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/theme/CaraMLTheme.kt`
- Test: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/theme/AuroraColorsTest.kt`
- Test: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/ui/layout/AdaptiveLayoutPolicyTest.kt`
- Test: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/ui/motion/AuroraMotionPolicyTest.kt`

**Interfaces:**
- Consumes: `MaterialTheme.colorScheme`, `ThemePreferences`, and the platform `LocalMotionDurationScale`.
- Produces: `AuroraColors`, `AuroraSurfaceLevel`, `MaterialTheme.auroraColors`, `AppNavigationLayout`, `AppContentKind`, `AdaptiveLayoutPolicy`, `adaptiveLayoutPolicy(width, contentKind)`, `AuroraMotionPolicy`, `auroraMotionPolicy(durationScale)`, and `LocalAuroraMotionPolicy`.

- [ ] **Step 1: Write failing semantic-color tests**

```kotlin
class AuroraColorsTest {
    @Test
    fun auroraColorsComeFromSemanticSchemeRoles() {
        val scheme = lightColorScheme(
            surface = Color(0xFF101010),
            primaryContainer = Color(0xFF223344),
            tertiaryContainer = Color(0xFF556677),
            outlineVariant = Color(0xFF8899AA),
        )

        val colors = scheme.toAuroraColors()

        assertEquals(scheme.surface, colors.canvas)
        assertEquals(scheme.primaryContainer.copy(alpha = 0.34f), colors.primaryGlow)
        assertEquals(scheme.tertiaryContainer.copy(alpha = 0.22f), colors.tertiaryGlow)
        assertEquals(scheme.outlineVariant.copy(alpha = 0.72f), colors.paneBorder)
    }

    @Test
    fun everySurfaceLevelMapsToOneMaterialRole() {
        val scheme = lightColorScheme()

        assertEquals(scheme.surface, AuroraSurfaceLevel.Canvas.containerColor(scheme))
        assertEquals(scheme.surfaceContainerLow, AuroraSurfaceLevel.Recessed.containerColor(scheme))
        assertEquals(scheme.surfaceContainer, AuroraSurfaceLevel.Pane.containerColor(scheme))
        assertEquals(scheme.surfaceContainerHigh, AuroraSurfaceLevel.Floating.containerColor(scheme))
    }
}
```

- [ ] **Step 2: Run the color tests and verify the missing API failure**

Run:

```bash
./gradlew :composeApp:jvmTest --tests 'com.debanshu777.caraml.core.theme.AuroraColorsTest'
```

Expected: compilation fails because `toAuroraColors`, `AuroraColors`, and `AuroraSurfaceLevel` do not exist.

- [ ] **Step 3: Implement the semantic Aurora colors**

```kotlin
@Immutable
data class AuroraColors(
    val canvas: Color,
    val primaryGlow: Color,
    val tertiaryGlow: Color,
    val activeHalo: List<Color>,
    val paneBorder: Color,
)

enum class AuroraSurfaceLevel {
    Canvas,
    Recessed,
    Pane,
    Floating;

    fun containerColor(scheme: ColorScheme): Color = when (this) {
        Canvas -> scheme.surface
        Recessed -> scheme.surfaceContainerLow
        Pane -> scheme.surfaceContainer
        Floating -> scheme.surfaceContainerHigh
    }
}

internal fun ColorScheme.toAuroraColors(): AuroraColors = AuroraColors(
    canvas = surface,
    primaryGlow = primaryContainer.copy(alpha = 0.34f),
    tertiaryGlow = tertiaryContainer.copy(alpha = 0.22f),
    activeHalo = listOf(primary.copy(alpha = 0.32f), tertiary.copy(alpha = 0.24f)),
    paneBorder = outlineVariant.copy(alpha = 0.72f),
)

internal val LocalAuroraColors = staticCompositionLocalOf<AuroraColors?> { null }

val MaterialTheme.auroraColors: AuroraColors
    @Composable @ReadOnlyComposable
    get() = LocalAuroraColors.current ?: colorScheme.toAuroraColors()
```

- [ ] **Step 4: Write failing width-boundary and reduced-motion tests**

```kotlin
class AdaptiveLayoutPolicyTest {
    @Test
    fun navigationChangesAtMaterialWidthBoundaries() {
        assertEquals(AppNavigationLayout.ModalDrawer, adaptiveLayoutPolicy(599.dp, AppContentKind.Chat).navigation)
        assertEquals(AppNavigationLayout.Rail, adaptiveLayoutPolicy(600.dp, AppContentKind.Chat).navigation)
        assertEquals(AppNavigationLayout.Rail, adaptiveLayoutPolicy(839.dp, AppContentKind.Chat).navigation)
        assertEquals(AppNavigationLayout.Sidebar, adaptiveLayoutPolicy(840.dp, AppContentKind.Chat).navigation)
    }

    @Test
    fun destinationWidthsMatchTheApprovedSpec() {
        assertEquals(840.dp, adaptiveLayoutPolicy(1200.dp, AppContentKind.Chat).maxContentWidth)
        assertEquals(1040.dp, adaptiveLayoutPolicy(1200.dp, AppContentKind.ModelHub).maxContentWidth)
        assertEquals(760.dp, adaptiveLayoutPolicy(1200.dp, AppContentKind.Settings).maxContentWidth)
    }
}

class AuroraMotionPolicyTest {
    @Test
    fun zeroDurationScaleDisablesEverySpatialOrContinuousEffect() {
        val policy = auroraMotionPolicy(durationScale = 0f)

        assertFalse(policy.spatialTransitionsEnabled)
        assertFalse(policy.pulseEnabled)
        assertFalse(policy.shapeMorphEnabled)
        assertEquals(100, policy.opacityDurationMillis)
    }

    @Test
    fun positiveDurationScaleUsesApprovedTimings() {
        val policy = auroraMotionPolicy(durationScale = 1f)

        assertTrue(policy.spatialTransitionsEnabled)
        assertTrue(policy.pulseEnabled)
        assertEquals(220, policy.peerTransitionMillis)
        assertEquals(300, policy.detailEnterMillis)
        assertEquals(200, policy.exitMillis)
        assertEquals(1600, policy.generationPulseMillis)
    }
}
```

- [ ] **Step 5: Run the policy tests and verify they fail for missing production types**

Run:

```bash
./gradlew :composeApp:jvmTest --tests 'com.debanshu777.caraml.core.ui.layout.AdaptiveLayoutPolicyTest' --tests 'com.debanshu777.caraml.core.ui.motion.AuroraMotionPolicyTest'
```

Expected: compilation fails because the adaptive and motion policy types do not exist.

- [ ] **Step 6: Implement the policies and wire the theme**

```kotlin
enum class AppNavigationLayout { ModalDrawer, Rail, Sidebar }
enum class AppContentKind { Chat, ModelHub, Settings, Details }

@Immutable
data class AdaptiveLayoutPolicy(
    val navigation: AppNavigationLayout,
    val horizontalMargin: Dp,
    val maxContentWidth: Dp,
)

fun adaptiveLayoutPolicy(width: Dp, contentKind: AppContentKind): AdaptiveLayoutPolicy {
    val navigation = when {
        width < 600.dp -> AppNavigationLayout.ModalDrawer
        width < 840.dp -> AppNavigationLayout.Rail
        else -> AppNavigationLayout.Sidebar
    }
    val margin = if (width < 600.dp) 16.dp else 24.dp
    val maxWidth = when (contentKind) {
        AppContentKind.Chat -> 840.dp
        AppContentKind.ModelHub -> 1040.dp
        AppContentKind.Settings -> 760.dp
        AppContentKind.Details -> 1040.dp
    }
    return AdaptiveLayoutPolicy(navigation, margin, maxWidth)
}

@Immutable
data class AuroraMotionPolicy(
    val spatialTransitionsEnabled: Boolean,
    val pulseEnabled: Boolean,
    val shapeMorphEnabled: Boolean,
    val opacityDurationMillis: Int,
    val peerTransitionMillis: Int,
    val detailEnterMillis: Int,
    val exitMillis: Int,
    val generationPulseMillis: Int,
)

fun auroraMotionPolicy(durationScale: Float): AuroraMotionPolicy {
    val reduced = durationScale <= 0f
    return AuroraMotionPolicy(
        spatialTransitionsEnabled = !reduced,
        pulseEnabled = !reduced,
        shapeMorphEnabled = !reduced,
        opacityDurationMillis = if (reduced) 100 else 220,
        peerTransitionMillis = 220,
        detailEnterMillis = 300,
        exitMillis = 200,
        generationPulseMillis = 1600,
    )
}

val LocalAuroraMotionPolicy = staticCompositionLocalOf {
    auroraMotionPolicy(durationScale = 1f)
}
```

Update the existing tokens exactly as follows:

```kotlin
data class Spacing(
    val xxs: Dp = 2.dp,
    val xs: Dp = 4.dp,
    val s: Dp = 8.dp,
    val m: Dp = 12.dp,
    val l: Dp = 16.dp,
    val xl: Dp = 24.dp,
    val xxl: Dp = 32.dp,
    val xxxl: Dp = 48.dp,
    val huge: Dp = 64.dp,
)

val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)
```

In `CaraMLTheme`, derive colors from the active scheme and provide both new locals while retaining `MaterialExpressiveTheme` and `AppMotionScheme`:

```kotlin
val scheme = MaterialTheme.colorScheme
val auroraColors = remember(scheme) { scheme.toAuroraColors() }
val motionPolicy = auroraMotionPolicy(LocalMotionDurationScale.current.scaleFactor)
CompositionLocalProvider(
    LocalSpacing provides Spacing(),
    LocalAuroraColors provides auroraColors,
    LocalAuroraMotionPolicy provides motionPolicy,
) {
    content()
}
```

Update `AppTypography` so `titleLarge`, `titleMedium`, and `labelLarge` are semibold, `bodyLarge` has a 24sp line height, and `bodyMedium` keeps its 20sp line height. Set `AppNumericLabel.fontFeatureSettings = "tnum"` so generation measurements and counters use tabular numerals through the common Compose `TextStyle` API.

- [ ] **Step 7: Run focused tests and the module compile gate**

Run:

```bash
./gradlew :composeApp:jvmTest --tests 'com.debanshu777.caraml.core.theme.AuroraColorsTest' --tests 'com.debanshu777.caraml.core.ui.layout.AdaptiveLayoutPolicyTest' --tests 'com.debanshu777.caraml.core.ui.motion.AuroraMotionPolicyTest'
./gradlew :composeApp:compileKotlinJvm
```

Expected: all focused tests pass and the JVM common UI compiles.

- [ ] **Step 8: Commit the policy foundation**

```bash
git add composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/theme composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/ui/layout composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/ui/motion composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/theme composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/ui
git commit -m "feat(ui): add Aurora design policies"
```

---

### Task 2: Shared Aurora presentation components

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/ui/components/AuroraBackdrop.kt`
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/ui/components/CaraMLPane.kt`
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/ui/components/CaraMLTopBar.kt`
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/ui/components/CaraMLSectionHeader.kt`
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/ui/components/CaraMLStatusPill.kt`
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/ui/components/CaraMLEmptyState.kt`
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/ui/layout/ResponsiveContentPane.kt`
- Test: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/ui/components/AuroraComponentsUiTest.kt`

**Interfaces:**
- Consumes: `MaterialTheme.auroraColors`, `AuroraSurfaceLevel`, `AdaptiveLayoutPolicy`, and existing Material 3 components.
- Produces: `AuroraBackdrop`, `CaraMLPane`, `CaraMLTopBar`, `CaraMLSectionHeader`, `CaraMLStatusPill`, `CaraMLEmptyState`, and `ResponsiveContentPane`.

- [ ] **Step 1: Write failing accessibility and interaction tests**

```kotlin
@file:OptIn(ExperimentalTestApi::class)
class AuroraComponentsUiTest {
    @Test
    fun emptyStateExposesItsActionAndInvokesIt() = runComposeUiTest {
        var clicks = 0
        setContent {
            MaterialTheme {
                CaraMLEmptyState(
                    icon = Icons.Default.AutoAwesome,
                    title = "Think locally. Stay private.",
                    supportingText = "Your prompt and model stay on this device.",
                    actionLabel = "Browse models",
                    onAction = { clicks += 1 },
                )
            }
        }

        onNodeWithText("Think locally. Stay private.").assertIsDisplayed()
        onNodeWithText("Browse models").performClick()
        runOnIdle { assertEquals(1, clicks) }
    }

    @Test
    fun statusPillMeaningDoesNotDependOnColor() = runComposeUiTest {
        setContent {
            MaterialTheme {
                CaraMLStatusPill(
                    label = "Ready",
                    contentDescription = "Model ready for chat",
                    tone = StatusTone.Success,
                    icon = Icons.Default.CheckCircle,
                )
            }
        }

        onNodeWithContentDescription("Model ready for chat")
            .assertIsDisplayed()
            .assertTextEquals("Ready")
    }
}
```

- [ ] **Step 2: Run the UI tests and verify the missing component failure**

Run:

```bash
./gradlew :composeApp:jvmTest --tests 'com.debanshu777.caraml.core.ui.components.AuroraComponentsUiTest'
```

Expected: compilation fails because the shared Aurora components do not exist.

- [ ] **Step 3: Implement the backdrop, pane, and responsive container**

```kotlin
@Composable
fun AuroraBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = MaterialTheme.auroraColors
    Box(
        modifier = modifier
            .fillMaxSize()
            .drawWithCache {
                val primaryWash = Brush.radialGradient(
                    colors = listOf(colors.primaryGlow, Color.Transparent),
                    center = Offset.Zero,
                    radius = size.maxDimension * 0.85f,
                )
                val tertiaryWash = Brush.radialGradient(
                    colors = listOf(colors.tertiaryGlow, Color.Transparent),
                    center = Offset(size.width, size.height),
                    radius = size.maxDimension * 0.65f,
                )
                onDrawBehind {
                    drawRect(color = colors.canvas)
                    drawRect(brush = primaryWash)
                    drawRect(brush = tertiaryWash)
                }
            },
        content = content,
    )
}

@Composable
fun CaraMLPane(
    modifier: Modifier = Modifier,
    level: AuroraSurfaceLevel = AuroraSurfaceLevel.Pane,
    shape: Shape = MaterialTheme.shapes.medium,
    showBorder: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = level.containerColor(MaterialTheme.colorScheme),
        border = if (showBorder) BorderStroke(1.dp, MaterialTheme.auroraColors.paneBorder) else null,
        shadowElevation = if (level == AuroraSurfaceLevel.Floating) 3.dp else 0.dp,
    ) {
        Column(content = content)
    }
}

@Composable
fun ResponsiveContentPane(
    kind: AppContentKind,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val policy = adaptiveLayoutPolicy(maxWidth, kind)
        Box(
            Modifier.fillMaxSize()
                .widthIn(max = policy.maxContentWidth)
                .padding(horizontal = policy.horizontalMargin),
            content = content,
        )
    }
}
```

`drawWithCache` recreates the finite brush only when the measured size or semantic colors change, so the idle backdrop does not create per-frame allocations or invalid infinite coordinates.

- [ ] **Step 4: Implement the top bar, section header, status pill, and empty state**

```kotlin
enum class TopBarNavigation { None, Menu, Back }
enum class StatusTone { Neutral, Accent, Success, Warning, Error }

@Composable
fun CaraMLTopBar(
    title: String,
    navigation: TopBarNavigation,
    onNavigationClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
)

@Composable
fun CaraMLSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    action: @Composable (() -> Unit)? = null,
)

@Composable
fun CaraMLStatusPill(
    label: String,
    contentDescription: String,
    tone: StatusTone,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
)

@Composable
fun CaraMLEmptyState(
    icon: ImageVector,
    title: String,
    supportingText: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
)
```

Implement `CaraMLTopBar` with Material `TopAppBar`; Menu uses `Icons.Default.Menu`, Back uses `Icons.AutoMirrored.Filled.ArrowBack`, and None emits no navigation button. Reject an action label without an action callback using `require((actionLabel == null) == (onAction == null))` in `CaraMLEmptyState`. Map every `StatusTone` to a semantic container/content pair and always set the supplied content description on the merged pill semantics.

- [ ] **Step 5: Run the shared component tests and full common UI tests**

Run:

```bash
./gradlew :composeApp:jvmTest --tests 'com.debanshu777.caraml.core.ui.components.AuroraComponentsUiTest'
./gradlew :composeApp:jvmTest
```

Expected: shared component tests and the existing JVM suite pass.

- [ ] **Step 6: Commit the shared presentation layer**

```bash
git add composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/ui composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/ui
git commit -m "feat(ui): add shared Aurora components"
```

---

### Task 3: Adaptive navigation shell and spatial transitions

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/drawer/AdaptiveNavigation.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/App.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/drawer/AppDrawerShell.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/drawer/AnimatedDrawerScaffold.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/drawer/CustomDrawer.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/drawer/DrawerItemView.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/navigation/AppNavigation.kt`
- Test: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/drawer/AdaptiveNavigationUiTest.kt`
- Test: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/navigation/NavigationTransitionPolicyTest.kt`

**Interfaces:**
- Consumes: `adaptiveLayoutPolicy`, `AuroraBackdrop`, `CaraMLPane`, `LocalAuroraMotionPolicy`, existing `DrawerController`, `GenerationModeController`, `NavBackStack`, and `AppScreen`.
- Produces: `AdaptiveNavigation`, `AppNavigationPanel`, `NavigationTransitionFamily`, and `navigationTransitionFamily(target)`.

- [ ] **Step 1: Write failing navigation presentation and transition tests**

```kotlin
class NavigationTransitionPolicyTest {
    @Test
    fun primaryDestinationsUsePeerMotionAndDetailsUseHierarchicalMotion() {
        assertEquals(NavigationTransitionFamily.Peer, navigationTransitionFamily(AppScreen.Home))
        assertEquals(NavigationTransitionFamily.Peer, navigationTransitionFamily(AppScreen.Search))
        assertEquals(NavigationTransitionFamily.Peer, navigationTransitionFamily(AppScreen.Settings))
        assertEquals(
            NavigationTransitionFamily.Hierarchical,
            navigationTransitionFamily(AppScreen.Details("org/model", ModelHubBrowseMode.LanguageModels)),
        )
    }
}

@file:OptIn(ExperimentalTestApi::class)
class AdaptiveNavigationUiTest {
    @Test
    fun expandedPanelShowsBrandAndDestinationLabels() = runComposeUiTest {
        setContent {
            MaterialTheme {
                AppNavigationPanel(
                    items = listOf(DrawerItem("chat", "Chat", Icons.Default.ChatBubbleOutline)),
                    selectedItemId = "chat",
                    compact = false,
                    onItemClick = {},
                )
            }
        }

        onNodeWithText("CaraML").assertIsDisplayed()
        onNodeWithText("Chat").assertIsDisplayed()
        onNodeWithContentDescription("Chat, selected").assertIsDisplayed()
    }
}
```

- [ ] **Step 2: Run the navigation tests and verify missing API failures**

Run:

```bash
./gradlew :composeApp:jvmTest --tests 'com.debanshu777.caraml.core.drawer.AdaptiveNavigationUiTest' --tests 'com.debanshu777.caraml.core.navigation.NavigationTransitionPolicyTest'
```

Expected: compilation fails because the adaptive panel and transition policy do not exist.

- [ ] **Step 3: Implement the shared navigation panel and adaptive shell**

```kotlin
@Composable
fun AppNavigationPanel(
    items: List<DrawerItem>,
    selectedItemId: String?,
    compact: Boolean,
    onItemClick: (DrawerItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    CaraMLPane(
        modifier = modifier.fillMaxHeight(),
        level = AuroraSurfaceLevel.Recessed,
        shape = RectangleShape,
        showBorder = false,
    ) {
        Text("CaraML", style = MaterialTheme.typography.titleLarge)
        items.forEach { item ->
            DrawerItemView(
                item = item,
                selected = item.id == selectedItemId,
                showLabel = !compact,
                onClick = { onItemClick(item) },
            )
        }
    }
}
```

Refactor `AppDrawerShell` so its existing destination callback is built once and passed to all three presentations. Use `BoxWithConstraints` and `adaptiveLayoutPolicy(maxWidth, AppContentKind.Chat).navigation` to select:

```kotlin
when (navigation) {
    AppNavigationLayout.ModalDrawer -> AnimatedDrawerScaffold(
        modifier = modifier,
        drawerState = controller.drawerState,
        onDrawerStateChange = controller::setState,
        gestureEnabled = gestureEnabled,
        drawerContent = {
            AppNavigationPanel(
                items = items,
                selectedItemId = selectedItemId,
                compact = false,
                onItemClick = onItemClick,
                modifier = Modifier.fillMaxWidth(0.80f),
            )
        },
        content = content,
    )
    AppNavigationLayout.Rail -> Row {
        AppNavigationPanel(
            items = items,
            selectedItemId = selectedItemId,
            compact = true,
            onItemClick = onItemClick,
            modifier = Modifier.width(80.dp),
        )
        Box(Modifier.weight(1f)) { content() }
    }
    AppNavigationLayout.Sidebar -> Row {
        AppNavigationPanel(
            items = items,
            selectedItemId = selectedItemId,
            compact = false,
            onItemClick = onItemClick,
            modifier = Modifier.width(240.dp),
        )
        Box(Modifier.weight(1f)) { content() }
    }
}
```

`DrawerItemView` gains `showLabel: Boolean = true`, keeps a 48dp minimum target, and uses the merged content description `"${item.title}, selected"` or `item.title`. Replace `CustomDrawer`'s fractional inner width with `fillMaxWidth()` because `AnimatedDrawerScaffold` owns the modal width.

- [ ] **Step 4: Replace fixed drawer tweens with one coordinated transition**

Use one `updateTransition(targetState = isOpened)` for offset, scale, scrim alpha, and corner size. Use `spring(dampingRatio = 0.82f, stiffness = 500f)` when spatial motion is enabled. Under reduced motion, keep scale at `1f`, corner size at `0.dp`, and change only opacity within the policy's 100ms limit. Preserve the existing edge-swipe threshold and gesture-enable rules.

Wrap the app content in `AuroraBackdrop` from `App.kt`; set destination scaffolds to transparent containers so the backdrop remains visible only through approved gaps and hero regions.

- [ ] **Step 5: Implement peer versus hierarchical navigation motion**

```kotlin
internal enum class NavigationTransitionFamily { Peer, Hierarchical }

internal fun navigationTransitionFamily(target: NavKey?): NavigationTransitionFamily =
    if (target is AppScreen.Details) NavigationTransitionFamily.Hierarchical
    else NavigationTransitionFamily.Peer
```

In `NavigationHost`, use a 220ms fade plus 4–8dp vertical continuity for peer destinations. Use fade plus 10%-width horizontal shared-axis motion for Details, with 300ms enter and 200ms exit; reverse it for pop. When `spatialTransitionsEnabled` is false, use the policy's opacity-only duration and no slide or scale.

- [ ] **Step 6: Run focused and regression tests**

Run:

```bash
./gradlew :composeApp:jvmTest --tests 'com.debanshu777.caraml.core.drawer.AdaptiveNavigationUiTest' --tests 'com.debanshu777.caraml.core.navigation.NavigationTransitionPolicyTest'
./gradlew :composeApp:jvmTest
```

Expected: navigation tests pass and existing navigation/model-selection behavior remains green.

- [ ] **Step 7: Commit the adaptive shell**

```bash
git add composeApp/src/commonMain/kotlin/com/debanshu777/caraml/App.kt composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/drawer composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/navigation/AppNavigation.kt composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/drawer composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/navigation
git commit -m "feat(ui): add adaptive Aurora navigation"
```

---

### Task 4: Conversation, composer, and generation experience

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/chat/presentation/components/GenerationActivity.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/chat/presentation/ChatScreen.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/chat/presentation/components/ChatInputBar.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/chat/presentation/components/ChatMessageList.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/chat/presentation/components/MessageBubble.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/chat/presentation/components/GenerationStatsBar.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/chat/presentation/components/ModelSelectorTopBar.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/chat/presentation/components/ChatModelPickerSheet.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/chat/presentation/components/StateScreens.kt`
- Test: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/features/chat/presentation/ChatAuroraUiTest.kt`

**Interfaces:**
- Consumes: shared Aurora components, `ChatUiState`, `StreamingState`, existing callbacks, and existing model entities.
- Produces: `ChatEmptyStateCopy`, `emptyStateCopy(mode)`, and `GenerationActivity`; public `ChatScreen`, `ChatInputBar`, and message APIs retain their existing callback contracts.

- [ ] **Step 1: Write failing empty-state and composer interaction tests**

```kotlin
class ChatAuroraUiTest {
    @Test
    fun everyGenerationModeHasSpecificHumanCopy() {
        assertEquals("Think locally. Stay private.", emptyStateCopy(GenerationMode.Text).title)
        assertEquals("Create without the cloud.", emptyStateCopy(GenerationMode.Image).title)
        assertEquals("Set ideas in motion.", emptyStateCopy(GenerationMode.Video).title)
    }

    @Test
    fun composerTransformsSendIntoStopWithoutChangingCallbacks() = runComposeUiTest {
        var sent = ""
        var cancelled = 0
        var generating by mutableStateOf(false)
        setContent {
            MaterialTheme {
                ChatInputBar(
                    generationMode = GenerationMode.Text,
                    isGenerating = generating,
                    selectedModel = null,
                    topModels = persistentListOf(),
                    onSelectModel = {},
                    onDownloadModelClick = {},
                    onSendMessage = { sent = it },
                    onCancelGeneration = { cancelled += 1 },
                )
            }
        }

        onNode(hasSetTextAction()).performTextInput("Hello")
        onNodeWithContentDescription("Send message").performClick()
        runOnIdle { assertEquals("Hello", sent); generating = true }
        onNodeWithContentDescription("Stop generation").performClick()
        runOnIdle { assertEquals(1, cancelled) }
    }
}
```

- [ ] **Step 2: Run the chat UI tests and verify the copy API failure**

Run:

```bash
./gradlew :composeApp:jvmTest --tests 'com.debanshu777.caraml.features.chat.presentation.ChatAuroraUiTest'
```

Expected: compilation fails because `ChatEmptyStateCopy` and `emptyStateCopy` do not exist; after adding only those types, the interaction test exposes the existing composer semantics gap.

- [ ] **Step 3: Implement the empty-state copy and sparse-state composition**

```kotlin
@Immutable
data class ChatEmptyStateCopy(val title: String, val supportingText: String)

internal fun emptyStateCopy(mode: GenerationMode): ChatEmptyStateCopy = when (mode) {
    GenerationMode.Text -> ChatEmptyStateCopy(
        "Think locally. Stay private.",
        "Ask anything — your prompt and model stay on this device.",
    )
    GenerationMode.Image -> ChatEmptyStateCopy(
        "Create without the cloud.",
        "Describe a scene and generate it entirely on this device.",
    )
    GenerationMode.Video -> ChatEmptyStateCopy(
        "Set ideas in motion.",
        "Describe a short sequence for local video generation.",
    )
}
```

In `ChatScreenContent`, place chat content inside `ResponsiveContentPane(AppContentKind.Chat)`. When a Ready state has no messages, show a mode-specific `CaraMLEmptyState` without a duplicate action; the composer remains the bottom action. Replace the separate NoModels, NoCompatibleModels, loading, and error layouts with `CaraMLEmptyState` or `CaraMLPane` while keeping every existing action callback and error message.

- [ ] **Step 4: Modernize the composer and generation activity**

`ChatInputBar` keeps its public parameters and transient local `inputText`. Add focus tracking with `onFocusChanged`; draw a remembered two-color Aurora halo only when focused or generating. Keep the input surface at Feature shape, use Pane at rest and Floating while focused, and provide exact `Send message` and `Stop generation` descriptions.

```kotlin
@Composable
fun GenerationActivity(
    label: String,
    modifier: Modifier = Modifier,
    progress: Float? = null,
) {
    val motion = LocalAuroraMotionPolicy.current
    val alpha = if (motion.pulseEnabled) rememberGenerationPulse(motion.generationPulseMillis) else 1f
    CaraMLPane(modifier.alpha(alpha), AuroraSurfaceLevel.Pane) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        if (progress != null) LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) })
        else LinearProgressIndicator()
    }
}
```

Use `GenerationActivity` for image/video preparation and sampling. Keep reported step, total, requested steps, and elapsed time text unchanged in meaning. Ensure only this component owns a continuous pulse during active work.

- [ ] **Step 5: Refine message and stats hierarchy without animating tokens**

Give assistant output `bodyLarge` reading typography on Canvas, constrain it to the responsive chat width, and retain user output in a Pane-shaped trailing bubble. Use `animateItem()` for new messages and a fade plus 8dp translation only when spatial motion is enabled. Do not animate `streamingText` changes.

Replace `GenerationStatsBar`'s two equal elevated cards with a single low-emphasis row using `AppNumericLabel` and `CaraMLStatusPill`. Update `ModelSelectorTopBar` to delegate to `CaraMLTopBar` without changing its public signature.

Style `ChatModelPickerSheet` with Modal shape and the Floating surface role. Keep its existing state, dismissal behavior, selection callback, sheet-state ownership, and focus trapping unchanged; add no custom sheet animation on top of Material's transition.

- [ ] **Step 6: Run focused chat tests and existing chat behavior tests**

Run:

```bash
./gradlew :composeApp:jvmTest --tests 'com.debanshu777.caraml.features.chat.presentation.ChatAuroraUiTest' --tests 'com.debanshu777.caraml.features.chat.presentation.ModelLoadRouterTest' --tests 'com.debanshu777.caraml.features.chat.presentation.PendingLoadActionGateTest'
```

Expected: Aurora UI interactions pass and load-routing behavior remains unchanged.

- [ ] **Step 7: Commit the conversation redesign**

```bash
git add composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/chat/presentation composeApp/src/commonTest/kotlin/com/debanshu777/caraml/features/chat/presentation
git commit -m "feat(chat): apply Aurora conversation design"
```

---

### Task 5: Model Hub overview, filters, and consistent model cards

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search/components/ModelHubOverview.kt`
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search/components/ModelResultCard.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search/SearchScreen.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search/components/SearchBar.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search/components/SortFilterChips.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search/components/ModelListItem.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search/components/SearchModelListItem.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search/components/RecommendationProfileDialog.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/downloaded/components/LocalModelListItem.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/rating/ui/SuitabilityInfoSheet.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/rating/ui/RecommendationDetailsSheet.kt`
- Test: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search/ModelHubAuroraUiTest.kt`
- Test: `composeApp/src/jvmTest/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search/SearchScreenLayoutRegressionTest.kt`

**Interfaces:**
- Consumes: `StorageInfoUiState`, `DeviceHints`, `RecommendationProfile`, `RecommendedModelUiState`, existing list/search DTOs, and shared Aurora components.
- Produces: `ModelHubOverview`, `ModelResultCard`, and a consistent card/status hierarchy while retaining all existing feature callbacks.

- [ ] **Step 1: Write failing card and overview tests**

```kotlin
class ModelHubAuroraUiTest {
    @Test
    fun modelResultCardKeepsTitleStatusMetadataAndActionOrder() = runComposeUiTest {
        var opened = 0
        setContent {
            MaterialTheme {
                ModelResultCard(
                    title = "org/tiny-model",
                    author = "org",
                    metadata = "Text generation · 1.2 GB",
                    status = { Text("Recommended") },
                    onClick = { opened += 1 },
                )
            }
        }

        onNodeWithText("org/tiny-model").assertIsDisplayed()
        onNodeWithText("Recommended").assertIsDisplayed()
        onNodeWithContentDescription("Open model org/tiny-model").performClick()
        runOnIdle { assertEquals(1, opened) }
    }

    @Test
    fun overviewProfileActionRetainsItsAccessibleSelectionSummary() = runComposeUiTest {
        setContent {
            MaterialTheme {
                ModelHubOverview(
                    storageInfo = StorageInfoUiState(),
                    profile = RecommendationProfile(),
                    onOpenProfile = {},
                )
            }
        }

        onNodeWithContentDescription(
            "Recommendation profile. Selected risk: Balanced. Selected priority: Balanced. Open profile controls.",
        ).assertIsDisplayed()
    }
}
```

- [ ] **Step 2: Run the Model Hub tests and verify missing component failures**

Run:

```bash
./gradlew :composeApp:jvmTest --tests 'com.debanshu777.caraml.features.modelhub.presentation.search.ModelHubAuroraUiTest'
```

Expected: compilation fails because `ModelResultCard` and `ModelHubOverview` do not exist.

- [ ] **Step 3: Extract and implement the compact overview**

Move `StorageInfoBar`, `DeviceInfoSection`, `DeviceInfoRow`, `RecommendationProfileAction`, and `formatStorageBytes` out of `SearchScreen.kt` into `ModelHubOverview.kt`. Keep the current profile content description exactly. Present storage, device, and profile in one `CaraMLPane` overview band; keep device details expandable and use the shared 220ms expand/collapse motion. Progress animation moves toward the truthful `usedFraction` over 180ms.

```kotlin
@Composable
fun ModelHubOverview(
    storageInfo: StorageInfoUiState,
    profile: RecommendationProfile?,
    onOpenProfile: (() -> Unit)?,
    modifier: Modifier = Modifier,
)
```

Validate `profile` and `onOpenProfile` as a pair before emitting the profile action. Omit storage content when `totalDeviceBytes <= 0L` and omit device content when `deviceHints == null`, matching current behavior.

- [ ] **Step 4: Implement and adopt the shared result card**

```kotlin
@Composable
fun ModelResultCard(
    title: String,
    author: String?,
    metadata: String,
    status: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    trailing: @Composable RowScope.() -> Unit = {},
)
```

Use `CaraMLPane` at Pane level, Feature shape only when highlighted, a seed-derived edge tint for highlighted recommendations, and the content description `Open model $title`. Migrate `ModelListItem` and `SearchModelListItem` to this shell while preserving their DTO mapping, recommendation status, selected-variant text, click behavior, and null-model early return.

Migrate `LocalModelListItem` to the same title/metadata/status rhythm without replacing its combined-click selection semantics. Replace private `ReadinessPill` with `CaraMLStatusPill`; unsupported, partial, ready, and selected states keep text/icon reinforcement.

- [ ] **Step 5: Update the Models screen composition and filter motion**

Use `CaraMLTopBar`, `ResponsiveContentPane(AppContentKind.ModelHub)`, and transparent scaffold containers. Replace `PrimaryTabRow` with a contained pill/segmented peer-tab treatment that keeps Search and Downloaded above each tab's single `LazyColumn`.

Keep `SearchBar` and `SortFilterChips` APIs unchanged. Apply Control shape, consistent 48dp targets, remembered tonal transitions, and `animateItem()` only to inserted, removed, or repositioned model rows. Do not replay entrance motion for the complete result list after refresh.

Update `SearchScreenLayoutRegressionTest` to assert the new pinned peer-tab symbol and retain these invariants:

```kotlin
assertEquals(1, Regex("\\bLazyColumn\\(").findAll(searchTab).count())
assertFalse(searchHeader.contains("RecommendationProfileSection("))
assertTrue(screen.indexOf("ModelHubTabRow(") < screen.indexOf("SearchTabContent("))
```

Apply Modal shape and the Floating surface role to the sort/filter sheet, recommendation profile dialog, calibration dialog, suitability sheet, recommendation-details sheet, and the local search error dialog. Preserve their existing dismiss, retry, save, calibration, and download callbacks. Rely on Material's modal transition so the app never stacks a second custom entrance animation over a sheet or dialog.

- [ ] **Step 6: Run Model Hub controls, layout, and recommendation regressions**

Run:

```bash
./gradlew :composeApp:jvmTest --tests 'com.debanshu777.caraml.features.modelhub.presentation.search.ModelHubAuroraUiTest' --tests 'com.debanshu777.caraml.features.modelhub.presentation.search.ModelHubControlsUiTest' --tests 'com.debanshu777.caraml.features.modelhub.presentation.search.SearchScreenLayoutRegressionTest' --tests 'com.debanshu777.caraml.core.rating.ui.RecommendationComponentsUiTest'
```

Expected: card/overview behavior, compact-width reachability, one-scroll-owner layout, and recommendation semantics all pass.

- [ ] **Step 7: Commit the Model Hub redesign**

```bash
git add composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/downloaded/components/LocalModelListItem.kt composeApp/src/commonTest/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search composeApp/src/jvmTest/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search/SearchScreenLayoutRegressionTest.kt
git commit -m "feat(modelhub): apply Aurora browsing design"
```

---

### Task 6: Responsive model details and truthful download states

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/details/DetailsScreen.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/details/components/ModelDetailContent.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/details/components/GgufFileListItem.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/details/components/InstallBundleCard.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/details/DownloadForLaterConfirmationDialog.kt`
- Test: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/features/modelhub/presentation/details/ModelDetailsAuroraUiTest.kt`
- Test: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/rating/ui/RecommendationComponentsUiTest.kt`

**Interfaces:**
- Consumes: `AppContentKind.Details`, existing `ModelViewModel` state, current model-detail DTOs, file selection/download callbacks, and recommendation state.
- Produces: an internal `ModelDetailLayout` supporting compact and expanded arrangements; public `DetailsScreen` and `ModelDetailContent` behavior remains compatible.

- [ ] **Step 1: Write a failing responsive layout policy and download-action test**

```kotlin
class ModelDetailsAuroraUiTest {
    @Test
    fun detailsUseSupportingPaneOnlyAtExpandedWidth() {
        assertEquals(false, modelDetailsUseSupportingPane(839.dp))
        assertEquals(true, modelDetailsUseSupportingPane(840.dp))
    }

    @Test
    fun fileDownloadStillInvokesTheExactPath() = runComposeUiTest {
        var requested = ""
        setContent {
            MaterialTheme {
                GgufFileListItem(
                    filename = "weights/model-q4.gguf",
                    sizeBytes = 1_073_741_824L,
                    isDownloaded = false,
                    progress = null,
                    isDownloading = false,
                    onDownloadClick = { requested = "weights/model-q4.gguf" },
                )
            }
        }

        onNodeWithContentDescription("Download weights/model-q4.gguf").performClick()
        runOnIdle { assertEquals("weights/model-q4.gguf", requested) }
    }
}
```

Change the icon's content description from `Download` to `Download $filename`; keep the callback parameterless so the existing details owner remains responsible for associating the row with its exact file path.

- [ ] **Step 2: Run the details tests and verify the missing policy/semantics failure**

Run:

```bash
./gradlew :composeApp:jvmTest --tests 'com.debanshu777.caraml.features.modelhub.presentation.details.ModelDetailsAuroraUiTest'
```

Expected: `modelDetailsUseSupportingPane` is unresolved; after adding the policy only, the download action test identifies any missing content description.

- [ ] **Step 3: Implement the responsive details composition**

```kotlin
internal fun modelDetailsUseSupportingPane(width: Dp): Boolean = width >= 840.dp
```

Use `CaraMLTopBar` with Back navigation and `ResponsiveContentPane(AppContentKind.Details)`. In `ModelDetailContent`, use `BoxWithConstraints`: compact widths retain one scroll owner and a stable bottom install action region; expanded widths use a Row with a weighted primary description/files pane and a 320–360dp supporting recommendation/install pane.

Keep model description, author, tags, recommendation evidence, selected variant, GGUF/weight file list, Smart Install, individual download, and Download for later callbacks unchanged.

- [ ] **Step 4: Apply the Aurora hierarchy to detail sections and progress**

Group overview, metadata, file variants, recommendation, and install summary with `CaraMLSectionHeader` and `CaraMLPane`. Replace flat chip walls with wrapping semantic status pills. `GgufFileListItem` and `InstallBundleCard` animate only backend-reported progress over 180ms; indeterminate work uses Material progress plus one local status transition, not a second infinite background effect.

Update `DownloadForLaterConfirmationDialog` to use Modal shape and Floating surface while retaining the exact explicit `Download anyway` confirmation action.

- [ ] **Step 5: Run details and safety regressions**

Run:

```bash
./gradlew :composeApp:jvmTest --tests 'com.debanshu777.caraml.features.modelhub.presentation.details.ModelDetailsAuroraUiTest' --tests 'com.debanshu777.caraml.core.rating.ui.RecommendationComponentsUiTest'
```

Expected: responsive details policy, exact download callback, recommendation details, and explicit download confirmation pass.

- [ ] **Step 6: Commit the model-details redesign**

```bash
git add composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/details composeApp/src/commonTest/kotlin/com/debanshu777/caraml/features/modelhub/presentation/details composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/rating/ui/RecommendationComponentsUiTest.kt
git commit -m "feat(modelhub): modernize model details"
```

---

### Task 7: Grouped Settings and live Aurora appearance preview

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/settings/presentation/ExpandableSettingDescription.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/settings/presentation/SettingsScreen.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/settings/presentation/AppearanceSection.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/settings/presentation/RecommendationProfileSection.kt`
- Test: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/features/settings/presentation/SettingsAuroraUiTest.kt`

**Interfaces:**
- Consumes: `ThemeViewModel`, `SettingsViewModel`, current update callbacks, `ResponsiveContentPane`, `CaraMLPane`, and `MaterialTheme.auroraColors`.
- Produces: `AuroraThemePreview` and `ExpandableSettingDescription`; all persisted settings behavior remains unchanged.

- [ ] **Step 1: Write failing appearance-preview and disclosure tests**

```kotlin
class SettingsAuroraUiTest {
    @Test
    fun appearancePreviewIsIdentifiableWithoutDependingOnColor() = runComposeUiTest {
        setContent { MaterialTheme { AuroraThemePreview() } }
        onNodeWithContentDescription("Current Aurora theme preview").assertIsDisplayed()
    }

    @Test
    fun longDescriptionExpandsAndCollapsesAccessibly() = runComposeUiTest {
        setContent {
            MaterialTheme {
                ExpandableSettingDescription(
                    summary = "Balanced memory and quality.",
                    details = "Uses Q8 keys and values for most devices.",
                )
            }
        }

        onNodeWithText("Show details").performClick()
        onNodeWithText("Uses Q8 keys and values for most devices.").assertIsDisplayed()
        onNodeWithText("Hide details").performClick()
        onNodeWithText("Uses Q8 keys and values for most devices.").assertDoesNotExist()
    }
}
```

- [ ] **Step 2: Run the Settings UI tests and verify missing component failures**

Run:

```bash
./gradlew :composeApp:jvmTest --tests 'com.debanshu777.caraml.features.settings.presentation.SettingsAuroraUiTest'
```

Expected: compilation fails because `AuroraThemePreview` and `ExpandableSettingDescription` do not exist.

- [ ] **Step 3: Implement the preview and technical-description disclosure**

```kotlin
@Composable
internal fun AuroraThemePreview(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.auroraColors
    Box(
        modifier
            .fillMaxWidth()
            .height(88.dp)
            .clip(MaterialTheme.shapes.large)
            .drawWithCache {
                val primaryWash = Brush.radialGradient(
                    colors = listOf(colors.primaryGlow, Color.Transparent),
                    center = Offset.Zero,
                    radius = size.maxDimension,
                )
                val tertiaryWash = Brush.radialGradient(
                    colors = listOf(colors.tertiaryGlow, Color.Transparent),
                    center = Offset(size.width, size.height),
                    radius = size.maxDimension * 0.7f,
                )
                onDrawBehind {
                    drawRect(colors.canvas)
                    drawRect(primaryWash)
                    drawRect(tertiaryWash)
                }
            }
            .semantics { contentDescription = "Current Aurora theme preview" },
    )
}

@Composable
fun ExpandableSettingDescription(
    summary: String,
    details: String,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(modifier) {
        Text(summary, style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "Hide details" else "Show details")
        }
        AnimatedVisibility(expanded) {
            Text(details, style = MaterialTheme.typography.bodySmall)
        }
    }
}
```

Honor `LocalAuroraMotionPolicy`: reduced motion emits details immediately without size or translation motion.

- [ ] **Step 4: Recompose Settings into consistent groups**

Use `CaraMLTopBar`, `ResponsiveContentPane(AppContentKind.Settings)`, and one scroll owner. Group Appearance, Recommendation profile/calibration, System prompt, Temperature, KV cache quality, and GPU acceleration into labeled `CaraMLPane` sections. Keep every existing ViewModel callback and current enabled/error state.

Place `AuroraThemePreview` above theme mode, seed, and palette controls. Apply 48dp targets to swatches, chips, slider interaction, calibration, and switches. Use `ExpandableSettingDescription` only for technical explanations longer than two body lines; keep current values and safety implications outside the collapsed details.

- [ ] **Step 5: Run Settings and recommendation UI tests**

Run:

```bash
./gradlew :composeApp:jvmTest --tests 'com.debanshu777.caraml.features.settings.presentation.SettingsAuroraUiTest' --tests 'com.debanshu777.caraml.core.rating.ui.RecommendationComponentsUiTest'
```

Expected: preview/disclosure behavior and recommendation setting semantics pass.

- [ ] **Step 6: Commit the Settings redesign**

```bash
git add composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/settings/presentation composeApp/src/commonTest/kotlin/com/debanshu777/caraml/features/settings/presentation
git commit -m "feat(settings): apply Aurora settings design"
```

---

### Task 8: Cross-app accessibility, animation, and visual regression pass

**Files:**
- Modify: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/ui/components/AuroraComponentsUiTest.kt`
- Modify: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/features/chat/presentation/ChatAuroraUiTest.kt`
- Modify: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search/ModelHubAuroraUiTest.kt`
- Modify: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/features/modelhub/presentation/details/ModelDetailsAuroraUiTest.kt`
- Modify: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/features/settings/presentation/SettingsAuroraUiTest.kt`

**Interfaces:**
- Consumes: completed Aurora theme, shared components, adaptive shell, and feature screens.
- Produces: regression proof for touch targets, reduced motion, semantics, large text, and deterministic animation clocks.

- [ ] **Step 1: Add reduced-motion and touch-target assertions**

Add `reducedMotionDisclosureReachesFinalStateWithinOpacityBudget` to `SettingsAuroraUiTest`. It explicitly provides the zero-scale policy and uses the controllable Compose clock:

```kotlin
@Test
fun reducedMotionDisclosureReachesFinalStateWithinOpacityBudget() = runComposeUiTest {
    mainClock.autoAdvance = false
    setContent {
        CompositionLocalProvider(
            LocalAuroraMotionPolicy provides auroraMotionPolicy(0f),
        ) {
            MaterialTheme {
                ExpandableSettingDescription(
                    summary = "Balanced memory and quality.",
                    details = "Details",
                )
            }
        }
    }

    onNodeWithText("Show details").performClick()
    mainClock.advanceTimeBy(100)
    onNodeWithText("Details").assertIsDisplayed()
}
```

Add minimum-size assertions for the shared empty-state action in `AuroraComponentsUiTest`, the composer send/stop controls in `ChatAuroraUiTest`, the model-card action in `ModelHubAuroraUiTest`, the file-download action in `ModelDetailsAuroraUiTest`, and the disclosure action in `SettingsAuroraUiTest`. Each assertion uses the component's existing label or content description:

```kotlin
onNodeWithContentDescription("Send message")
    .assertWidthIsAtLeast(48.dp)
    .assertHeightIsAtLeast(48.dp)
```

- [ ] **Step 2: Run the new tests and identify any remaining presentation defect**

Run:

```bash
./gradlew :composeApp:jvmTest --tests 'com.debanshu777.caraml.core.ui.components.AuroraComponentsUiTest' --tests 'com.debanshu777.caraml.features.chat.presentation.ChatAuroraUiTest' --tests 'com.debanshu777.caraml.features.modelhub.presentation.search.ModelHubAuroraUiTest' --tests 'com.debanshu777.caraml.features.modelhub.presentation.details.ModelDetailsAuroraUiTest' --tests 'com.debanshu777.caraml.features.settings.presentation.SettingsAuroraUiTest'
```

Expected: all already-compliant targets pass. Any undersized target or reduced-motion defect fails with the exact component assertion; record that component before editing it.

- [ ] **Step 3: Correct only the components named by the failing assertions**

Use `Modifier.minimumInteractiveComponentSize()` or `heightIn(min = 48.dp)` on the failing control. Route animation specs through `LocalAuroraMotionPolicy` and replace spatial animation with opacity-only transition when `spatialTransitionsEnabled` is false. Do not change domain callbacks or screen state to satisfy presentation tests.

- [ ] **Step 4: Verify large-text and non-color semantics**

Add this test-only wrapper in each affected UI test file and render the representative component at 200% font scale:

```kotlin
@Composable
private fun AtTwoHundredPercentFontScale(content: @Composable () -> Unit) {
    val current = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(current.density, fontScale = 2f),
        content = content,
    )
}
```

In `AuroraComponentsUiTest`, assert the empty-state title and action remain displayed. In `ChatAuroraUiTest`, assert the composer input and send/stop action remain displayed. In the Model Hub and Details suites, place the representative card/section inside a `verticalScroll(rememberScrollState())` host and call `performScrollTo()` before asserting its title, progress label, and recovery/download action. In `SettingsAuroraUiTest`, do the same for the disclosure summary and action.

Add parameterized semantic assertions for `Ready`, `Partial`, `Unsupported`, `Recommended`, `Risky`, `Downloading`, `Generating`, `Success`, and `Error`; each rendered treatment must expose its visible label plus an icon content description or merged state description, so none depends on color alone.

- [ ] **Step 5: Run the complete Compose JVM test suite**

Run:

```bash
./gradlew :composeApp:jvmTest
```

Expected: all Compose JVM tests pass with no wall-clock sleeps in animation tests.

- [ ] **Step 6: Commit the cross-app regression pass**

```bash
git add composeApp/src/commonTest composeApp/src/jvmTest
git commit -m "test(ui): cover Aurora accessibility and motion"
```

---

### Task 9: Documentation and full verification

**Files:**
- Modify: `README.md` under `## Recent Changes`
- Modify: `composeApp/README.md` under `## Recent Changes`

**Interfaces:**
- Consumes: the completed implementation and the repository validation commands.
- Produces: accurate documentation plus fresh verification evidence; no native-module README changes.

- [ ] **Step 1: Update only the relevant Recent Changes sections**

Add concise bullets describing the shipped behavior:

```markdown
- Added the CaraML Aurora visual system with seed-derived atmosphere, semantic tonal surfaces, consistent typography/spacing, and reduced-motion-aware interactions
- Added adaptive drawer/rail/sidebar navigation and responsive Chat, Model Hub, Details, and Settings layouts without changing feature journeys
- Added focused generation, download, list, disclosure, and route transitions while keeping backend progress authoritative and idle rendering static
```

Use one or two bullets in each README if combining them reads better; remove a stale UI bullet only when the new statement supersedes it.

- [ ] **Step 2: Run formatting and source hygiene checks**

Run:

```bash
git diff --check
rg -n 'Color\(0x' composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/ui composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features
rg -n 'infiniteRepeatable|rememberInfiniteTransition' composeApp/src/commonMain/kotlin/com/debanshu777/caraml
```

Expected: `git diff --check` exits cleanly. Review every raw color hit and keep only pre-existing seed/semantic constants. Confirm the only new infinite transition belongs to `GenerationActivity` or replace unintended loops.

- [ ] **Step 3: Run the preferred repository gate**

Run:

```bash
./gradlew verifyProject
```

Expected: the complete preferred JVM/CI verification gate passes.

- [ ] **Step 4: Run the Android debug assembly gate**

Run:

```bash
./gradlew :composeApp:assembleDebug
```

Expected: Android debug assembly passes when the local SDK and platform dependencies are available. If the environment is missing an SDK component, report that separately and do not call the Android gate passing.

- [ ] **Step 5: Perform the visual matrix review**

Run the Desktop app and inspect these configurations:

```bash
./gradlew :composeApp:run
```

Review compact, medium, and expanded widths in both light and dark themes. Exercise Chat, Image, Video, Models Search, Downloaded, Model Details, Settings, modal drawer, rail/sidebar selection, dialog/sheet focus, active generation, determinate download, error, and empty states. Repeat the navigation and disclosure checks with the platform animation scale disabled. Confirm no clipping at 200% font scale and no second scroll owner in either Model Hub tab.

Run Android phone and tablet visual checks when an emulator or device is available. Run iOS compilation and visual checks when Xcode targets are available. Report unavailable platform reviews as unverified, not passed.

- [ ] **Step 6: Review the final diff against every acceptance criterion**

Read `docs/superpowers/specs/2026-09-13-caraml-aurora-design.md` and check each acceptance bullet against a code path, test, or visual observation. Remove unrelated refactors. Confirm shared components have no ViewModel or feature-domain dependency and animations observe rather than own UI state.

- [ ] **Step 7: Commit documentation after verification**

```bash
git add README.md composeApp/README.md
git commit -m "docs: document CaraML Aurora redesign"
```

- [ ] **Step 8: Capture final evidence**

Run:

```bash
git status --short
git log --oneline -10
```

Expected: the worktree is clean and the Aurora implementation appears as small, reviewable commits. Report exact test/build commands and platform visual checks that actually ran.
