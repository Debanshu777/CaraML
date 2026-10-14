# Shared native adapter runtime

Internal Kotlin support for the llama and diffusion plugins. It has no native
libraries, runner dependency, application state or global DI dependency.

Both adapters acquire `NativeRuntimeGate` before preflight or load. A loaded
session retains the lease through teardown. This deliberately allows only one
native adapter session across both engines, because the current native backends
share GGML initialization, logging and backend registry state. The core's per-engine
capacity limit alone cannot protect those cross-engine globals.

The guard covers one linked Kotlin runtime. Legacy direct runner calls and multiple
JVM classloaders are not coordinated; coexistence with other native SDKs still needs
the lower-level binary and runtime work in the architecture plan.

## Recent Changes

- Added the shared lease and a competing-adapter/repeated-close regression test.
