# Final fix L report — multiplatform GGUF source closure

Date: 2026-09-21
Branch: `codex/v2-installed-model-loading`
Base: `2bf648d`

## Outcome

`GgufMetadataInspector` now imports Okio's multiplatform `use` extension for its
`BufferedSource`. This preserves the existing bounded parsing and guaranteed
source closure behavior while compiling on Kotlin/Native. No parser behavior,
limits, accepted metadata, or error mapping changed.

## Root cause and TDD evidence

The RED iOS simulator compile failed with:

```text
GgufMetadataInspector.kt:20:51 Cannot infer type for type parameter 'R'.
GgufMetadataInspector.kt:20:51 ... receiver type mismatch:
fun <T : AutoCloseable?, R> T.use(block: (T) -> R): R
GgufMetadataInspector.kt:73:9 Null cannot be a value of a non-null type ...
```

Okio 3.9's `BufferedSource` implements `okio.Closeable`; it does not satisfy
Kotlin/Native's `AutoCloseable.use`. The source imported `okio.buffer` but not
Okio's public multiplatform `okio.use`, so the compiler considered only the
wrong receiver contract. The line 73 nullable inference error was downstream
from that unresolved generic call. Okio's own native `FileSystem.read`
implementation uses the same `okio.use` extension.

The GREEN change is one import:

```kotlin
import okio.use
```

The same narrowed iOS compiler command then completed successfully in 59s.

## Verification

Narrowed iOS compiler proof:

```text
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer \
  ./gradlew :composeApp:compileKotlinIosSimulatorArm64 \
  -x :nativeEngine:buildLlamaRunnerCMakeIosSimulatorArm64 \
  -x :nativeEngine:compileLlamaRunnerCMakeIosSimulatorArm64 \
  -x :nativeEngine:mergeLlamaRunnerStaticIosSimulatorArm64 \
  --no-daemon --no-parallel -Pkotlin.incremental=false
  BUILD SUCCESSFUL in 59s
```

Focused parser and repair regressions:

```text
GgufMetadataInspectorTest              8 / 8
InstalledModelEvidenceRepairerTest    14 / 14
```

Full iOS simulator compiler, including native static-library build and merge:

```text
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer \
  ./gradlew :composeApp:compileKotlinIosSimulatorArm64 \
  --no-daemon --no-parallel -Pkotlin.incremental=false
  BUILD SUCCESSFUL in 8m 28s
```

Android common-source compiler:

```text
./gradlew :composeApp:compileAndroidMain \
  --no-daemon --no-parallel -Pkotlin.incremental=false
  BUILD SUCCESSFUL in 40s
```

Repository gate:

```text
./gradlew verifyProject --no-daemon --no-parallel -Pkotlin.incremental=false
  BUILD SUCCESSFUL in 53s
  composeApp JVM                 1,050 tests, 0 failures, 0 errors
  huggingFaceManager JVM           113 tests, 0 failures, 0 errors
  runner JVM                        29 tests, 0 failures, 0 errors
  diffusionRunner JVM               21 tests, 0 failures, 0 errors
  native artifact/diffusion           6 tests, 0 failures
```

## Security and scope review

- The file remains bounded to a 4 MiB header scan and existing count/string
  limits.
- Malformed or unreadable local metadata still fails closed as `null`.
- Source closure remains exception-safe through the standard Okio extension.
- No paths, payloads, or errors are logged.
- No dependency, API, persistence, download, or inference behavior changed.

## Limitations

This fix proves compilation and existing parser/repair behavior. It does not
claim physical iOS-device execution.
