# Final fix K report — cancellation-safe repair single-flight

Date: 2026-09-20
Branch: `codex/v2-installed-model-loading`
Base: `3f8b6ec9cbfdcb4e434384fd572ccef393ab2cf0`
Implementation commit: the Fix K commit containing this report

## Outcome

Cancelling the caller that currently leads an installed-evidence repair no longer publishes that caller's `CancellationException` as the shared result. The coordinator publishes a private typed `LeaderAborted` outcome, completes and exposes it from a non-cancellable cleanup section, and lets one live waiter become the successor leader. Every other waiter from that aborted generation follows the exact same successor flight, even if the successor finishes before those waiters resume.

The retry is intentionally bounded to one follower re-election per caller. If the elected successor also aborts, a remaining live follower receives a typed non-cancellation exhaustion failure rather than spinning or inheriting another caller's cancellation. `InstalledModelEvidenceRepairer` maps that internal ordinary failure through its existing fail-closed invalid-metadata path. The leader's own cancellation and a follower's own cancellation still propagate locally and unchanged.

No loader, artifact-identity, metadata-source, publication-lock, database, or legacy path was added or changed.

## Coordination contract

- Exact validated owner ID plus operation remains the flight key. Case-distinct owners cannot share a result.
- The lowercase owner ID remains only the selector for the fixed 64-stripe coordinator; no unbounded keyed registry was introduced.
- Success and ordinary failure retain their existing single-flight sharing semantics.
- An aborted flight retains only a bounded successor link. This link prevents late waiters from starting duplicate fast successor work after the first successor has already completed.
- Stripe selection and successor linking occur under the existing stripe mutex. Publication/root lock ordering is unchanged because the coalescer still invokes the same repair block outside the stripe mutex.
- `NonCancellable` is used only to publish a terminal flight outcome and release coordinator state; application repair work never runs non-cancellably.
- If every caller cancels, no successor work starts. A later caller can safely recover the abandoned flight.

## TDD evidence

Initial focused command:

```text
./gradlew :composeApp:jvmTest \
  --tests '*InstalledModelPublicationCoordinatorTest' \
  --tests '*InstalledModelEvidenceRepairerTest' \
  --no-daemon
```

RED: 20 tests ran and 3 failed for the intended missing behavior:

- the cancelled repair leader's `JobCancellationException` reached eight live repair followers;
- the generic cancelled-leader fan-out failed the same way;
- repeated leader abort had no bounded non-cancellation terminal outcome.

The first minimal retry implementation produced a second useful RED: 20 tests ran and 2 failed because eight late waiters performed nine total blocks, while the repeated-abort case performed three instead of two. That proved a loop alone did not preserve single-flight when a successor completed before all prior waiters reselected.

GREEN: linking the aborted generation to one successor produced 20/20 passing tests with zero skipped/failures/errors:

- `InstalledModelEvidenceRepairerTest`: 14/14;
- `InstalledModelPublicationCoordinatorTest`: 6/6.

Coverage includes one and eight live followers, exactly one successor metadata lookup and evidence write, all callers cancelled, one follower cancelled locally, success coalesced once, ordinary failure shared once by identity, a second leader abort bounded at two total executions, different-owner concurrency, and case-distinct owner results.

## Repository verification

```text
git diff --check
./gradlew verifyProject --no-daemon
```

Both passed. `verifyProject` completed in 45 seconds with 41 actionable tasks (14 executed, 27 up to date):

- JVM: 1,143/1,143 tests, zero skipped/failures/errors;
- artifact-root native CTest: 1/1;
- diffusion native CTests: 5/5.

The unchanged build emitted its existing expect/actual beta, missing `ccache`, generic CPU architecture, and vendored native Git-metadata warnings.

## Documentation

The root and `composeApp` Recent Changes bullets now state that a cancelled repair leader yields a typed abort, caller cancellation remains local, and all live followers share one bounded successor. Other module READMEs were intentionally unchanged because their contracts were not modified.

## Security and failure behavior

- Model IDs and operation keys retain their existing validation and length/control-character bounds before any coordination state is selected.
- The implementation adds no external input, URL, path, payload, logging, telemetry, or persisted data surface.
- Cancellation details are not exposed to another caller or the UI.
- Repeated abort fails closed after a fixed per-caller budget; it cannot create an unbounded retry loop or unbounded map.
- Ordinary failures continue to be shared without duplicate remote requests or database writes.

## Verification boundary

No physical-device run was added for Fix K. The change is common coroutine coordination with no platform-specific branch, and its cancellation/order guarantees are covered deterministically with the common JVM coroutine scheduler plus the repository-wide JVM/native gate.
