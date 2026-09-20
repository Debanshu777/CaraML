# Final fix K report — cancellation-safe repair single-flight

Date: 2026-09-20
Branch: `codex/v2-installed-model-loading`
Base: `3f8b6ec9cbfdcb4e434384fd572ccef393ab2cf0`
Review follow-up base: `68d24013a0fb6370325e2f34fba28f682e95dbe9`
Implementation commit: the Fix K commit containing this report

## Outcome

Cancelling the caller that currently leads an installed-evidence repair no longer publishes that caller's `CancellationException` as the shared result. The coordinator publishes a typed `LeaderAborted` outcome, completes and exposes it from a non-cancellable cleanup section, and lets one live waiter become the successor leader. Every other waiter from that generation observes the same successor terminal outcome, even if the successor finishes before those waiters resume.

The retry budget belongs to one shared generation, not to each caller. The generation owns one root outcome and at most one successor terminal outcome. If the elected successor also aborts, that terminal publishes `RetryExhausted`, the stripe drops its active reference, and every current or delayed follower receives the typed non-cancellation `RepairFlightRetryExhaustedException`. A follower that arrives during the successor cannot create a third leader. A later independent invocation after cleanup starts a fresh root generation. `InstalledModelEvidenceRepairer` maps exhaustion through its existing fail-closed invalid-metadata path, while each leader's own cancellation and each follower's own cancellation still propagate locally and unchanged.

No loader, artifact-identity, metadata-source, publication-lock, database, or legacy path was added or changed.

## Coordination contract

- Exact validated owner ID plus operation remains the flight key. Case-distinct owners cannot share a result.
- The lowercase owner ID remains only the selector for the fixed 64-stripe coordinator; no unbounded keyed registry was introduced.
- Success and ordinary failure retain their existing single-flight sharing semantics.
- Each active generation retains one root deferred and optionally one successor deferred: two outcomes maximum, no linked list, map, or rolling per-caller budget.
- Stripe selection and generation phase changes occur under the existing stripe mutex. Publication/root lock ordering is unchanged because the coalescer still invokes the same repair block outside the stripe mutex.
- `NonCancellable` is used only to publish a terminal flight outcome and release coordinator state; application repair work never runs non-cancellably.
- Root abort keeps one bounded recoverable generation only while it is the stripe's active item. Successor success, failure, or cancellation clears it; a bounded internal snapshot verifies zero coordinator-retained generations/outcomes after terminal exhaustion.
- If every caller cancels before a successor is elected, no successor work starts. A later caller can safely claim that generation's single successor.

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

The review follow-up added a follower only after the successor was active, cancelled that successor, kept one early root waiter deliberately queued, and then made a fresh invocation after cleanup. It failed against the first Fix K commit because the rolling follower started a third execution. A separate compile RED required the bounded coordination snapshot, and another compile RED required the exact typed exhaustion result rather than a generic `IllegalStateException` assertion.

GREEN: the shared generation state machine produced 21/21 passing tests with zero skipped/failures/errors:

- `InstalledModelEvidenceRepairerTest`: 14/14;
- `InstalledModelPublicationCoordinatorTest`: 7/7.

Coverage includes one and eight live followers, exactly one successor metadata lookup and evidence write, all callers cancelled, one follower cancelled locally, success coalesced once, ordinary failure shared once by identity, rolling followers joining the successor, a delayed root waiter observing the same exhaustion, coordinator retention dropping from one generation/two outcomes to zero, a fresh post-cleanup root, different-owner concurrency, and case-distinct owner results.

## Repository verification

```text
git diff --check
./gradlew verifyProject --no-daemon
```

Both passed. `verifyProject` completed in 51 seconds with 41 actionable tasks (15 executed, 26 up to date):

- JVM: 1,144/1,144 tests, zero skipped/failures/errors;
- artifact-root native CTest: 1/1;
- diffusion native CTests: 5/5.

The unchanged build emitted its existing expect/actual beta, missing `ccache`, generic CPU architecture, and vendored native Git-metadata warnings.

## Documentation

The root and `composeApp` Recent Changes bullets now state that cancellation is bounded by one shared generation, a successor cancellation is terminal for all followers, and each caller's cancellation remains local. Other module READMEs were intentionally unchanged because their contracts were not modified.

## Security and failure behavior

- Model IDs and operation keys retain their existing validation and length/control-character bounds before any coordination state is selected.
- The implementation adds no external input, URL, path, payload, logging, telemetry, or persisted data surface.
- Cancellation details are not exposed to another caller or the UI.
- Repeated abort fails closed from shared generation state after exactly one successor; rolling arrivals cannot renew the budget, and the coordinator retains no unbounded chain or map.
- Ordinary failures continue to be shared without duplicate remote requests or database writes.

## Verification boundary

No physical-device run was added for Fix K. The change is common coroutine coordination with no platform-specific branch, and its cancellation/order guarantees are covered deterministically with the common JVM coroutine scheduler plus the repository-wide JVM/native gate.
