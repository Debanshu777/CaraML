# Shared native adapter runtime

Internal Kotlin support for the llama and diffusion plugins. It has no native
libraries, runner dependency, application state or global DI dependency. Its JVM
resolver loads host-packaged libraries from `caraml.native.lib.dir` when set,
otherwise Compose application resources, otherwise the JVM library search path.

Both adapters acquire `NativeRuntimeGate` before preflight or load. A loaded
session retains the lease through teardown. This deliberately allows only one
native adapter session across both engines, because the current native backends
share GGML initialization, logging and backend registry state. The core's per-engine
capacity limit alone cannot protect those cross-engine globals.

The guard covers one linked Kotlin runtime. Legacy direct runner calls and multiple
JVM classloaders are not coordinated; coexistence with other native SDKs still needs
native ownership coordination, isolated binary/export identities and restricted
backend discovery. Those guarantees are not supplied by this Kotlin gate.

## Recent Changes

- Added JVM native-library resolution for explicit `caraml.native.lib.dir` overrides and Compose application resources, with an authoritative directory (no silent fallback when it is broken). Unit tests cover precedence and installed-resource resolution.

- Documented native ownership and coexistence limits directly, without relying on a separate plan.

- Standardized runtime ownership declarations and tests on `com.debanshu777.caraml.sdk.runtime`.

- Added the shared lease and a competing-adapter/repeated-close regression test.
