# storage

Optional Kotlin Multiplatform implementations of the neutral catalog, task, and artifact storage contracts. Consumers can omit this module and provide their own implementations through `:core-api`.

The module provides an in-memory reference implementation and an injectable Okio file-backed artifact store. The file store uses opaque hashed names, bounded staged writes, SHA-256 and size validation, atomic final publication, generation-fenced writes, immutable committed content, and leases that prevent removal while open. It returns a `LocalFile` location suitable for local inference engines when supplied the host's system filesystem. Filesystem work defaults to `Dispatchers.IO` and can use an injected dispatcher for host control and testing.

The file store's maps and locks are scoped to one instance. Its root must be a dedicated directory exclusively controlled by the host; external mutation can invalidate file and lease guarantees. It rejects simultaneous staged writers for an artifact within that instance, but it does not coordinate with another instance/process and does not recover orphaned staging files after restart. If cancellation races with a commit after the atomic move, the artifact may be committed without returning a lease; callers can reacquire it by reference. It must not be presented as process-safe or as durable download recovery. Database task persistence/migration, crash reconciliation, backup/restore, platform import, and external consumer verification remain release gates in `evalutaion.md`. A task store and artifact store supplied independently cannot guarantee an atomic cross-store commit; callers requiring production recovery must provide a coordinated implementation or a stronger transaction contract.

## Recent Changes

- Standardized storage implementation and tests on `com.debanshu777.caraml.sdk.storage`.

- Closed stages can be aborted while owner/generation fencing prevents stale handles from deleting resumed work.

- Added in-memory neutral catalog/task/artifact stores plus an injectable Okio file artifact store with bounded writes, hashed confined names, digest checks, atomic publication, and leased local-file locations.
- Added FakeFileSystem tests for immutable publication, digest rejection, ID path confinement, leases, generation fencing, and orphan-stage rejection.
