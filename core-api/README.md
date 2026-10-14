# CaraML core API

`core-api` contains the portable contracts shared by inference, discovery, and storage modules. It has no dependency on CaraML UI, Koin, Room, DataStore, HTTP clients, runner modules, or native inference libraries. Coroutine Flow is part of the public contract and is therefore an API dependency.

Most extension interfaces are marked `ExperimentalCaraMLApi`. They are an initial SDK contract and may change before a stable release. A consumer implementing providers, artifact stores, task stores, or engine plugins must explicitly opt in.

## Recent Changes

- Artifact transfers use a scoped `withTransfer` callback so response bodies cannot outlive their transport scope. `UnreleasedEngineLoad` carries uncertain native ownership to core.

- Added provider-neutral model/artifact identities, artifact access and staged storage contracts, fenced task records, discovery provider/transfer contracts, and typed engine capability/session contracts.
- Added an experimental marker for extension interfaces, reasoning/content deltas and terminal events, explicit text resynchronization and published-media events, resource estimates, and media-output sink contracts.

## Current limits

- The artifact/task contracts express generation fencing, but two independently supplied stores cannot provide one atomic cross-store publication transaction without a shared transaction implementation.
- The API surface is an alpha implementation slice; persistence conformance and binary compatibility have not been established.
