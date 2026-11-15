---
description: Contribute to CaraML with portable SDK tests, JVM verification, Android and iOS checks, package conventions, and focused pull requests.
---

# Contributing and testing

Documentation, reproducible bug reports, accessibility improvements, and focused fixes are welcome. Read [CONTRIBUTING.md](https://github.com/Debanshu777/CaraML/blob/main/CONTRIBUTING.md) and the [license](licensing.md) before contributing.

## Test without native engines

For portable SDK changes, start with a clone without native submodules:

```bash
./gradlew -PsdkOnly=true verifySdk
```

This configures `core-api`, `core`, `storage`, `model-discovery`, and `native-runtime`; it runs their JVM tests, common metadata compilation, and dependency boundary checks. JDK 21 and dependency downloads are still needed. It does not test inference plugins or the app.

## Verify the application

After the [full setup](getting-started.md):

```bash
./gradlew verifyProject
```

This is the preferred local and CI JVM gate. If personal Git URL rewrites cause the native HTTPS origin check to fail, apply the [checkout-local remote fix](troubleshooting.md#native-submodule-https-allowlist). It includes portable SDK verification, plugin/app JVM tests, desktop launcher compilation, native pin checks, and selected native tests, including all four llama hardening/history/stream-finalization/memory checks. It is not a substitute for Android assembly, iOS compilation, packaged-app checks, or real-model runs.

| Change | Additional relevant check |
| --- | --- |
| Android UI or packaging | `./gradlew :app:androidApp:assembleDebug` and a device/emulator journey |
| iOS bridge or shared Kotlin | `./gradlew :app:composeApp:compileKotlinIosArm64 :app:composeApp:compileKotlinIosSimulatorArm64` on macOS |
| Desktop packaging | `./gradlew :app:desktopApp:nativeRuntimeInstalledImageSmoke` |
| Native engine fixtures | `CARAML_NATIVE_PARITY=true ./gradlew :plugin-llama:jvmTest :plugin-diffusion:jvmTest` |
| Docs or public assets | `python -m mkdocs build --strict` and `python tools/docs/check_site.py` after [docs setup](maintaining-docs.md) |

Use the narrowest meaningful checks while iterating, then the relevant project gate before a code PR. Native parity checks may download model fixtures. Avoid starting with `allTests`: it includes platform tasks with additional host requirements.

The app JVM suite covers persisted settings edits made before UI collection, deletion
of an off-picker chat selection, a superseded detail lookup, and failed download
cancellation followed by a retry. It also covers interrupted pause and resume,
file-list retry, unpublished media rollback, and Llama ownership after a failed
unload. The diffusion JVM suite covers preflight gate ownership, progress ending
before publication, and direct session close retry after a failed native release.

## Model Hub migration checks

`ModelMonogramFrame`, `ModelMonogram` and `ModelAuthorAvatar` accept a square `size: Dp` with a 36 dp default. Callers can pass `size = 48.dp`, including a value derived from Compose state; ordinary parent layout constraints still apply. Avatar artwork fills the frame, while initial typography and the 12 dp corner radius keep their existing sizes. For this component, run `./gradlew :app:composeApp:jvmTest --tests '*ModelAuthorAvatarUiTest*'` to check default-frame parity, SVG decoding, and loading/error fallbacks.

The discovery tests exercise `/models-json` request mapping, bounded envelopes, mixed gating, unknown parameter counts, avatar URLs and query-bound continuation. App tests cover paging retries, deduplication, query changes, server totals and recommendation enrichment from sparse search rows. Use `:model-discovery:jvmTest` and focused `:app:composeApp:jvmTest` filters while iterating, then `verifyProject`. Avatar rendering also needs Android, iOS and desktop checks, including SVG and network failure fallback; JVM assertions alone do not establish device rendering.

The Model Hub checks exercise Downloads back-stack and selection behavior, applied
filter counts, recent history, and local variant aggregation. For Android gesture
changes, verify on a touch device that a downward pull at the top focuses the
field and opens the keyboard, then open Downloads and return to the same search.

## Find the right code

- Shared app features: `app/composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/`.
- App DI, navigation, settings, and downloads: the sibling `core/` package.
- Portable contracts and orchestration: root `core-api/` and `core/` modules.
- Model discovery: `model-discovery/`; provider-specific Hub code lives under `sdk.discovery.huggingface`.
- Inference: `plugin-llama/`, `plugin-onnx/`, and `plugin-diffusion/`.
- Native builds and pinned upstream source preparation: `nativeEngine/`.

See the [architecture](architecture.md) for dependency boundaries. Preserve persisted identifiers and keep JNI symbols, cinterop definitions, and consumer keep rules synchronized when bridge packages move.

## Keep changes reviewable

Describe the user-visible problem, final behavior, and checks actually run. Add screenshots or a short recording for UI changes. Report device, OS, model revision/quantization, backend, and workload when making performance claims.

Do not commit downloaded models, tokens, local SDK paths, APKs, native build outputs, or benchmark traces.

## Keep documentation current

Every change to code, tests, build configuration, dependencies, or developer tooling must include documentation updates in the same change set. Update the relevant pages in `docs/`, the root README and its `Recent Changes`, and the affected module READMEs. Revise explanations, examples, commands, defaults, support claims, and limitations to match the final implementation; a changelog bullet alone does not replace an outdated guide.

For internal refactors and test-only changes, describe the developer impact and verification here and in the affected module README. Keep unrelated module READMEs unchanged and replace stale `Recent Changes` entries with concise current ones. Record measured evidence for performance claims; use the version catalog as the dependency source of truth.

Coding agents follow the same requirement through [AGENTS.md](https://github.com/Debanshu777/CaraML/blob/main/AGENTS.md#documentation-and-readme-update-policy); `CLAUDE.md` imports that shared policy. Agents must review the final diff for documentation impact, make the edits before handing off, and report which docs and checks they updated. Documentation cannot be deferred to a later session or waived for a code change. The PR template records the same expectations for contributors.

Run the [strict docs build and generated-site checks](maintaining-docs.md#validate-before-publishing) after documentation edits, and review rendered pages after layout or media changes. These checks verify links and site metadata; matching the prose to code still requires reviewing the implementation.

Model search and Downloads both render `ModelRow` from a presentation-only `ModelRowUiState`. Pure `discoveryModelRow` and `downloadedModelRow` mappers translate catalog and local model data into metrics, status tones, avatar and interaction capabilities; callbacks stay at the call site. Keep rendering, accessibility, selection, repair and recommendation interactions in the shared renderer, with layout handled by `ModelHubCard` and `ModelMetric`. `ModelRowMappersTest` covers sparse metadata, zero counts, unsupported files and repair suppression during selection; `LocalModelListItemPrismUiTest` exercises open, long-press, selection and repair callbacks against this renderer. The existing `ModelHubProductionBranchUiTest`, `ModelHubVisualEvidenceTest` and `ModelAuthorAvatarUiTest` cover production branches, normal/large-text layouts and avatars.

Open `ModelHubCard.kt` in the IDE preview panel to inspect light, dark and 320 dp / 200% text layouts. The previews use local fixtures for catalog metrics, Downloads repair and selected rows, so they require no network or model downloads.

Model Hub scroll content releases the responsive pane’s trailing gutter through `LocalContentEndInset`. Row content and list controls retain that inset; shared row separators use the full pane width. Safe-area insets and the desktop maximum content width still apply.

### Model search hierarchy verification

Shared `BrandIconButton` uses icon-specific tokens for a 1dp contour, 2dp depth, and inset face inside the stable 48dp target. Navigation, Downloads, Filters, and search-clear use the same component. `BrandButton` keeps its existing size and depth tokens. Both sizes share the solid ink shadow and theme-aware rim: ink in light mode, the shared outline variant in dark mode. Dark button shadows are black; light-mode shadows retain brand ink. Toolbar glyphs retain 48dp touch targets, with smaller search/filter glyphs and long-press or hover tooltips. The active-filter dot keeps the exact group count in semantics and the tooltip. Recent history uses four compact cards and a separate results heading. Validate with `:app:androidApp:assembleDebug` and `:app:composeApp:jvmTest`, then inspect the physical-device capture in the demo guide.

### Dark surface hierarchy

`withBrandSurfaces` defines a monotonic dark elevation scale from the page background through low cards, panels, and floating surfaces. `AuroraColors` reduces dark ambient wash intensity so it does not compete with raised containers. Shared button faces use the high container role, borders use the outline variant, and depth stays dark. Search uses the shared surface role. Device panels use neutral raised containers in dark mode, with subtle borders and contrast-safe action icons. Brand illustrations and selected pastel tabs retain their intentional accent colours. Theme tests verify increasing surface luminance and readable primary/secondary text across every elevation, alongside the existing accent-seed contrast checks.

## Compact details verification

The details UI retains exact-artifact downloads, shard/bundle dispatch, durable transfer controls, and phone/desktop/200% text layouts. Variants, Model card, and Technical details use one horizontal pager inside a single vertical scroller. At large text sizes, tab labels wrap onto additional lines and metadata values stack below their labels. Tests cover back navigation and the scroll-aware model title, three-tab selection, swiping, grouped technical data, fully expanded excerpts across every tab, the model-name-only URL span, edge-to-edge section dividers, filename-aligned file dividers, and fit explanations. `ModelDetailsTransitionUiTest` checks shared identity during loading/navigation and reduced motion. Diffusion keeps a pinned install action on compact Variants pages and an inline action at wide widths.

`ResponsiveContentPane` exposes start/end content insets for the Details body so section dividers reach both pane edges while the header, text tab row, and content labels retain adaptive gutters. Variant row content keeps its trailing inset independently of its divider. Details uses the default `FrostedPageScaffold` glass and Aurora wash, matching search. Details uses the shared header fade without an override and keeps a 12dp content gap. `ModelOverviewSection` reports its measured title bottom; `DetailsScreen` compares it with the fixed chrome edge to reveal `ModelDetailsHeader`. The compact name reserves one line even while hidden, preventing layout jumps at large font sizes. Hidden header text has no accessibility semantics; the visible heading exposes the full repository identity. Scrolling content passes behind the fixed header. Soft effects still controls the shared opaque fallback. `ModelTechnicalDetails` groups supplied metadata and scales metrics/rows to available width and font size; its section headings expose heading semantics. Links use Compose `LinkAnnotation.Url`; the dotted underline follows only the visible model-name span, including wrapping. Destinations come from validated repository IDs. `ModelCardTest` and `ModelViewModelRecommendationTest` continue to cover publisher-prose extraction and stale/delayed responses independently of file loading. UI tests explicitly target the vertical scroll ancestor when bringing content inside the horizontal pager into view.

Model row and overview titles use one annotated `Text` for `owner/model`: `onSurfaceVariant` colors the owner prefix, which uses the `bodyLarge` font size and normal weight; the model name keeps `itemTitle` semibold styling and `onSurface` color. UI assertions should match the complete title; the navigation transition check uses `sample/Compact-Chat`.

Run `./gradlew :model-discovery:jvmTest`, focused `:app:composeApp:jvmTest --tests '*ModelDetails*' --tests '*ModelCard*' --tests '*ModelViewModelRecommendationTest*'`, and `./gradlew verifyProject`. For visual changes, also build `:app:androidApp:assembleDebug` and inspect the [actual device captures](demo.md#model-details-captures). The full reader uses the catalog-managed Markdown renderer and Coil3 adapter described below; no manual platform setup is required.

### Full model-card rendering

`getModelCard` returns a `ModelCardDocument` with repository ID, immutable revision, full Markdown body (YAML removed), and a separate bounded excerpt. The 256 KiB response bound and strict UTF-8/redirect/revision checks are unchanged. The ViewModel exposes Loading, Ready, Missing, and Failed states, cancels README work and clears its state when opening a different model/browse mode or explicitly refreshing, and supports a separate retry. File discovery does not wait for the README.

The Markdown renderer and Coil3 adapter share the version pinned in `gradle/libs.versions.toml`. `ModelViewModel` runs HTML normalization and `parseMarkdown` on a background dispatcher once per fetched document, then retains the prepared renderer state with the document in `ModelCardUiState.Ready`. `ModelCardSection` consumes this state directly with AppTheme typography, native links, and tables whose cells wrap rather than truncate. Pager-page disposal does not discard the parsed document. `ModelCardLinks` resolves relative files/images to the document revision and limits destinations to HTTP(S). HTML image/layout normalization touches parser-identified HTML spans, preserving fenced/inline code. The full reader is composed when its pager page is shown; it shares the details vertical scroller. The overview excerpt remains present on every tab; selecting Model card never collapses it or shifts the tabs. Reading text is capped at 720dp. Images have compact loading/failure feedback and a source action; placeholder sizing uses available width and intrinsic aspect ratio, including initially empty image-only paragraphs. The normalizer also handles bracketed absolute destinations that GFM parses as autolinks. Arbitrary HTML widgets and math remain outside the native renderer.

`ModelCardDocumentTest` covers URL resolution and HTML/code preservation; discovery `ModelCardTest` checks full document retention and request bounds. `ModelDetailsModelCardUiTest` checks full article/table rendering, link dispatch, and tab-local retry/absence. `ModelDetailsImageUiTest` uses a local HTTP server for deterministic successful and failed image requests. UI checks include keyboard tab activation, table links, and all three tabs visible at 200% text. Existing route tests cover stale responses and downloads. Markdown dependency changes also affect chat, so include chat rendering tests when validating updates.

The 15 November 2026 reader verification passed 58 discovery tests and 120 focused app tests (details, model-card handling, recommendation state, shared components, and chat rendering), plus Android debug assembly. Pixel 9 checks used live model data; JVM fixtures covered light/dark, 320dp at 200% text, desktop width, tables, image success/failure, and keyboard interaction. The narrow tab check inspects glyph bounds and ellipsis because Compose can reuse a paragraph with a wider cached layout constraint after a width change.

The overview/header follow-up passed 61 focused details, search-layout, and shared-scaffold tests plus Android debug assembly. `ModelDetailsRouteUiTest` also checks that switching to Model card preserves the overview/tab bounds, scrolling reveals the header name, scrolling back hides it, and the header height stays fixed at normal and 200% text sizes.

### Sticky model-detail tabs

`ModelDetailTabsState` shares the pager and vertical scroll position between the inline row and the scaffold’s sticky-header slot. The measured overview height determines where the row pins. While pinned, its original location contains an equal-height spacer, so only one tab group is exposed. `FrostedPageScaffold` draws the sticky slot outside the scrolling haze source without changing content insets; the shared chrome fade moves below the combined navigation/tab region. Short sections reserve enough viewport height to keep their first content below the row. Tab changes from a pinned position reset to the beginning of the selected section. Horizontal swipes reset only after a different page settles, so a reversed/cancelled drag preserves the reading position. The same movable row retains its state across locations; an explicit focused-tab snapshot restores keyboard focus after reattachment, without substituting the selected tab or stealing focus during touch scrolling. Automatic bring-into-view is suppressed during that restoration frame, so it does not undo the user’s vertical scroll.

The sticky-tab follow-up passed the 61 focused details, search-layout, and shared-scaffold tests plus Android debug assembly. The production-route regression checks pinning immediately below the header, fixed bounds during deep README scrolling, single-instance tab semantics, switching to short sections, returning to the overview, cancelled drags, current-tab position preservation, keyboard focus through pin/unpin, and Enter activation at normal and 200% text sizes.

### Details request and README retention

`DetailsScreen` calls `ensureDetail` on entry. The ViewModel keys its active request by repository and browse mode and reuses pending, successful, and failed state for matching entries. `loadDetail` remains the explicit retry/refresh path; README retry only replaces the card request. No global or persistent cache is introduced. Returning from search resumes detail recommendations independently of metadata/files/README reuse. Re-entry also refreshes local installation evidence without repeating those remote requests; a completed historical transfer alone does not prove the files are still installed. Clearing a browse session clears its ownership flag; a late browse response may update cached search rows but cannot replace or append to a newer details assessment. The pager and vertical scroll use saveable state, while prepared Markdown belongs to the retained ViewModel rather than the pager composition. `AdaptiveNavigation` moves one remembered content subtree between its mutually exclusive layouts, with a stable saved-state provider above the branch and an updated content callback. Keeping only the provider key is insufficient: nested saveable registrations also need a stable composition identity.

Regression tests count metadata, tree, and README requests through in-flight re-entry, loaded re-entry, and explicit refresh; verify a different browse mode starts a new request; and check that failures wait for retry. The production-route test wraps details in `AppDrawerShell` and the actual navigation display/decorator. It disposes and recreates the composition with saved registry values at different widths, crosses compact/sidebar/rail breakpoints during live resizing, checks tab/scroll restoration, then switches across all pages while asserting the same parsed document and unchanged request count. Skiko’s `StateRestorationTester` serialization hook is unimplemented, so this test exercises new saveable registries directly; Android rotation is a separate device check.

The final retention verification passed 132 focused JVM tests (details, model-card normalization, ViewModel/recommendation state, search layout, shared scaffold, and adaptive navigation) and Android debug assembly. The pending-browse regression covers both failure and late success after details regains ownership. A physical Pixel 9 check on 15 November 2026 verified that Model card remains selected through portrait/landscape changes and that an idle portrait round trip restores identical README and pinned-tab bounds. Variants, Technical details, and Model card remained usable afterward. The shell-wired regression reproduces the original compact-to-sidebar state loss before the fix and verifies both recreation and live resizing afterward; request counts and parsed-document reuse are asserted in the automated tests.

## Review fix regressions

The download storage boundary runs validated manifest and bundle reads on IO, including recovery. The production `DownloadManagerJvmTest` checks that filesystem reads leave the calling thread. ViewModel tests await the published detail-completion state before flushing virtual-time work, so real IO does not race their assertions; pending README and cancellation gates remain independent. Display-only reads use publication-time checksums plus current contained path/size checks; same-size content changes still require full validation to detect. Pending transaction recovery can still verify payloads before returning committed metadata. Count badges avoid routine rehashing, ignore usage-count changes and row reordering, and read a repository once per catalog snapshot. `DownloadedVariantCountsTest` checks usage, installation, and removal transitions. These are bounded-work changes, not measured latency claims.

`DownloadDatabaseMigrationTest` opens actual version 1 and 2 SQLite schemas through the production Room builder, checks preserved task IDs, evidence and byte checkpoints across repeated opens, and verifies unknown versions fail without deleting the queue. Historical task-ID fixtures also check that a fresh immutable download can start without changing quarantined records or checkpoints. Mutable legacy tasks with unsafe paths or missing evidence are retained as terminal secure-path failures; they are not silently resumed or rewritten. Records that fail current identity validation are excluded from the active queue. Completed historical rows remain stored without being promoted to valid current downloads. Installed-catalog tests cover same-repository ONNX companions, shared references, and rejection of an unbound companion.

Model Hub has one detail-tab label source and one `results` state for list/search data, loading and errors. Unused suitability widgets and the old component checker were removed. Historical status helpers and the workload factory remain test fixtures rather than shipped UI; the obsolete filter fixtures were retired with their old contracts. The fully wrapped linked model title is tested directly instead of expecting a removed expansion control. Model-card normalization applies descending edits with common Kotlin `StringBuilder.setRange`; iOS compilation catches use of JVM-only string-builder APIs, while existing document tests preserve link/image conversion and untouched code spans.

Run `./gradlew verifyProject` for the combined JVM/native gate, then `./gradlew :app:desktopApp:nativeRuntimeInstalledImageSmoke` for packaging changes. The packaging smoke copies the distributable jars/resources to a separate directory, clears native library search paths, checks secure artifact IO, and initializes/tears down the selected llama/diffusion bridges. It does not load a model or establish Windows/Linux release compatibility.

The desktop CI matrix runs this behavioral regression under the configured JVM on macOS, Linux, and Windows. In particular, Windows must exercise native initialization with the host JVM's loaded C++ runtime; successful MSI creation alone cannot catch mutex initialization crashes. Desktop MSVC builds apply `_DISABLE_CONSTEXPR_MUTEX_CONSTRUCTOR` to runners and upstream dependencies. Smoke phase messages identify the active filesystem/engine operation, and the failure-only **Report native JVM crashes** step preserves fatal JVM reports in the job log. See [Windows runtime diagnosis](troubleshooting.md#windows-native-runtime-crashes-in-msvcp140dll).

### Second review regressions and cleanup

The ONNX fixtures include a decoder with an empty logits row. JVM and Apple simulator tests require a clean failure and successful reload of a valid model. Java tensor-ownership tests also check cleanup after input preparation fails and preservation of borrowed cache tensors. Native bridge deduplication keeps JNI class names and methods unchanged; only Android/JVM loading differs. Run JVM tests, Android assembly and the installed-image native smoke after changing shared bridge sources.

Discovery tests model the Hub response contract: immutable revision metadata is present only when requested. Client-lifetime tests cover owned facade shutdown, failed construction and borrowed-host survival. The compatibility API uses bounded manual JSON decoding; unused content negotiation and explicit standard-library declarations were removed.

History tests cover compact legacy migration, concurrent visits during migration, preservation of unrelated preferences, off-caller JSON work, recovery of unreadable history and file loading while history persistence is blocked. Removed toolbar/context/summary/navigation helpers had no production callers; remaining UI tests exercise the current filter panel, device display, queue rows and production screens. Keep the shared storage formatter used by Device and Downloads.

App JVM tests resolve the selected `artifact_fs` output via the producer’s `desktopArtifactFs` configuration. Validate non-default profiles with `./gradlew :app:composeApp:jvmTest -PcaramlDesktopBackend=cpu`; this check must not rely on a default-profile build. The installed `AppDatabase` development reset policy is intentionally unchanged; this work does not establish catalog upgrade compatibility for published releases.

Download regressions cover the additive version 3→4 cleanup-marker migration, process restart after worker cancellation, cleanup failure/retry, completed-publication retention, and more than 64 terminal failures alongside active work. Command dispatch loads exact persisted identity rather than relying on the visible queue. Cancellation and re-enqueueing share lifecycle coordination so an old recovery pass cannot stop or delete a newer attempt. Publication hashing runs outside that command lock; publication locks protect the files and a full persisted observation/version check rejects stale completion after a command. Removal tests use real Room and filesystem state for partial/legacy rows, damaged selected files, shared references and contained file-only cleanup.

Download commands persist intent under the lifecycle mutex, then await platform readiness outside it and recheck current intent under the lock before scheduling. The startup regressions deliberately run commands before restoration, then reverse that order, and check cancellation while waiting; accepted commands remain durable, and newer same-batch pause/cancel intent prevents stale dispatch after startup. The iOS scheduler keeps its readiness guard for direct callers. These tests model the restoration barrier and do not claim a physical-device reproduction.

Retained-detail regressions remove installation evidence between visits, including during initial hydration, and assert that download actions recover without another metadata, file-tree, or README request. Delayed local reads cannot overwrite a newer detail route or local projection, and re-entry preserves the selected diffusion variant. Reinstalling the same immutable batch refreshes completion evidence again. Explicit enqueue reactivates completed work and reuses published files when still valid; startup recovery and insert-only calls do not reactivate it. Model-card service tests cover `---` and `...` front-matter terminators, absent front matter, BOM/CRLF variants, and unterminated metadata.

Cleanup consolidates identical persisted-snapshot validators, removes unused `LocalModelDao` operations, and tests removal through `removeSelectedIfMatches` instead of the obsolete Ready-only helper. Legacy Model Hub fixtures pass layout content explicitly; production no longer carries composition locals solely for those fixtures. These internal cleanups do not change the database schema or screen design.

Verification on 16 November 2026 after the startup, retained-details, and front-matter fixes passed `verifyProject` with 1,679 JVM tests (1,494 in the app), including 13 added regression tests and expanded existing coverage. Android debug assembly, shared iOS arm64/simulator compilation, and the macOS installed-image smoke for `artifact_fs`, llama, and diffusion passed. Strict MkDocs, generated-site checks, and `git diff --check` passed. Gradle reused unchanged outputs where applicable. These checks do not establish physical-device inference performance or Windows/Linux packaging compatibility.

Earlier verification also passed the ONNX Apple simulator suite and a separate `DownloadManagerJvmTest` run with `-PcaramlDesktopBackend=cpu -PcaramlNativeEngines=llama --info`, confirming `macos/profiles/llama-cpu` in the test JVM. Those two checks were not repeated for the startup/details/front-matter follow-up.
