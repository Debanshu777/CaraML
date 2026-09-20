# SDD ledger — plan: docs/superpowers/plans/2026-09-20-v2-installed-model-loading.md

Workspace: `/Users/debanshud/Documents/Personal/Flash/.worktrees/v2-installed-model-loading`
Branch: `codex/v2-installed-model-loading`
Spec: `docs/superpowers/specs/2026-09-20-v2-installed-model-loading-design.md`
Start commit: `85f4a0f87e8d5f95b516023b850893d7231beaf7`

## Pre-flight consistency scan

| Tasks | Producer / consumer or self-check | Finding |
|---|---|---|
| 1 | Codec tests versus codec contract | Consistent: complete and enrichment envelopes, strict digest and size validation. |
| 2 | Download migration tests versus persistence contract | Consistent after plan self-review: v1 rows synthesize non-null enrichment evidence. |
| 3 | Finalizer tests versus transactional Ready publication | Consistent: invalid evidence blocks; validated enrichment evidence may publish artifact readiness. |
| 4 | Model Hub tests versus download behavior | Consistent: Needs information remains downloadable and produces enrichment evidence. |
| 5 | Repair tests versus exact metadata behavior | Consistent: exact identity match, idempotent persistence, no weight rewrite. |
| 6 | Resolver tests versus current-state V2 resolution | Conflict: fixed AUTO KV/backend defaults do not fully reflect current `AppSettings.kvQuantPreset` and `useGpu` required by the spec. See ruling below. |
| 7 | No-legacy tests versus runtime contract | Consistent: exact request is the only loader input and transient handoff is removed. |
| 8 | Verification commands versus acceptance | Consistent: focused tests, project gate, preserved-data install, online repair, offline restart. |
| 1 -> 2 | `EncodedModelEvidence` into durable batch | Consistent: Task 2 consumes the exact Task 1 type. |
| 1 -> 3 | Strict decode before catalog publication | Consistent: Task 3 rejects malformed or mismatched Task 1 envelopes. |
| 1 -> 4 | Codec used by `DownloadEvidenceFactory` | Consistent: Task 4 emits complete or enrichment state only. |
| 1 -> 5 | Codec used by repair | Consistent: repaired descriptors replace incomplete evidence using the same schema. |
| 2 -> 3 | `DownloadBatchSnapshot.evidence` into finalizer | Consistent: snapshot evidence is non-null, including migrated v1 rows. |
| 2 -> 4 | `DownloadBatchRequest.evidence` at enqueue sites | Consistent: Task 4 updates every durable enqueue and fixture. |
| 3 -> 5 | `InstalledModelEvidenceRepository` into repairer | Consistent: Task 5 reads and atomically replaces the repository record. |
| 3 -> 6 | Evidence repository and `AppModule.kt` integration | Consistent: Task 6 wires Task 3 storage and Task 5 repair. |
| 4 -> 5 | Downloaded exact identities into remote repair lookup | Consistent if remote object IDs are normalized by existing canonical identity rules, never filename-only matching. |
| 5 -> 6 | `EvidenceRepairResult` into resolver | Consistent: network, rejection, and ready states map without inference fallback. |
| 6 -> 7 | `InstalledModelLoadRequestResolver` into Chat | Consistent: Task 7 consumes only `Ready.request` and maps all other states before inference. |
| 6 -> 7 | Shared `AppModule.kt` edits | Consistent: Task 6 registers resolver; Task 7 changes ViewModel constructor wiring without removing resolver bindings. |
| 7 -> 8 | Exact-only runtime into device acceptance | Consistent: Task 8 proves repair then offline exact loading on the connected device. |

Ruling: Task 6 must derive KV-cache constraints from `AppSettings.kvQuantPreset` and must honor `AppSettings.useGpu`; the plan's fixed AUTO/all-types wording applies only to AUTO with GPU enabled. The spec requires current settings to participate in every fresh assessment. If wrong, the migration could ignore an explicit user runtime preference or reject plans that the old loader honored.

Ruling: Task 2 may synthesize migrated v1 enrichment evidence only from exact persisted artifact identity. If the legacy artifact has no remote object ID, the row is corrupt/unrestorable; the migration must not invent identity or weaken the strict evidence codec. If wrong, two distinct remote artifacts with the same repository/path could be silently conflated.

Setup: isolated worktree created at `codex/v2-installed-model-loading`; exact pinned top-level and nested native submodules hydrated from the main checkout because upstream no longer advertises one pinned commit.
Baseline: `./gradlew verifyProject --no-daemon` passed at `85f4a0f` (41 actionable tasks; native and JVM tests included).
Task 1: minor (deferred): payload UTF-8 size measurement allocates a second byte array before applying the 262,144-byte cap; final review should decide whether a portable non-allocating count is warranted.
Task 1: fix round 1/5 (3 addressed, 0 open — collection bounds before set conversion; full control and URI-scheme rejection; adversarial strict-decoding tests; commits b5029e5..873ee8e).
Task 1: complete (commits 85f4a0f..873ee8e, review clean).
Task 2: fix round 1/5 (1 addressed, 0 open — artifact primary keys are now batch-scoped; distinct evidence batches coexist while migrated IDs remain authoritative; commit a57e63a).
Task 2: complete (commits 873ee8e..a57e63a, review clean).
Task 3: fix round 1/5 (2 addressed, 0 open — canonical remote identity and restored-snapshot owner topology now fail closed; commit c6fd9c1).
Task 3: complete (commits a57e63a..c6fd9c1, review clean).
Task 4: fix round 1/5 (1 addressed, 0 open — exact artifact validation now precedes descriptor uncertainty; absent/stale descriptors enqueue enrichment; commit ff73f45).
Task 4: minor (deferred): dormant direct `downloadSetupComponents` path is now rejected by the current-detail gate; no production caller exists, and future wiring must validate exact component metadata rather than GGUF detail rows.
Task 4: complete (commits c6fd9c1..ff73f45, review clean for durable scope).
Task 5: complete (commits ff73f45..b950db6, review clean).
Task 6: fix round 1/5 (1 addressed, 0 open — only RECOMMENDED/USABLE/RISKY categories can construct a request; commit 022dba9).
Task 6: complete (commits b950db6..022dba9, review clean).
Task 7: fix round 1/5 (4 addressed, 0 original open — pre-assessment CPU-only compatibility, exact CPU flags, deterministic fake, and direct config/lifecycle tests; commit 3e8ee55).
Task 7: fix round 2/5 (1 addressed, 0 open — structured predecessor join preserves the transitive A-to-B-to-C native-operation barrier; commit 5fe9a6e).
Task 7: complete (commits 022dba9..5fe9a6e, review clean).
Task 8: fix round 1/5 (2 addressed, 1 open — parser is full-table/fail-closed; original settings now expose an exact policy-approved CPU alternative; device interaction transcript still uses placeholders and omits bounded outputs).
Task 8: fix round 2/5 (1 report gap substantially addressed, 1 new product issue open — automatic/restored selection can terminate on native Invalid while explicit reselection resolves a safe CPU alternative; Settings transcript also incomplete).
Task 8: fix round 3/5 (2 addressed, 0 open — typed same-model fresh retry with race coverage; literal phased device/Settings/Library/connectivity proof; commits 5f711f2..d4b235f).
Task 8: minor report consistency correction complete (commit 57a0280).
Task 8: complete (commits 5fe9a6e..57a0280, review clean; 1,010 JVM + native 5/5; preserved-data online/offline Pixel acceptance passed).
Final review: changes requested (1 Critical, 3 Important, 2 Minor).
Final fix A: complete — production resolution now consumes the authoritative validated owner bundle without re-appending Room-linked external entries; image/video multi-repository regressions and the full repository gate pass.
Final fix B: pending — exact repair queries current Hugging Face HEAD instead of installed immutable revisions.
Final fix C: complete — bounded owner coordination linearizes manifest/catalog/evidence publication; repair releases the owner lock for remote I/O, then exact-baseline checks and Room CAS prevent stale evidence overwrite.
Final fix D: pending — quarantine retry escapes Chat model-load serialization.
Final fix E: pending — remove dormant legacy sidecar resolver/request implementation to structurally enforce the no-legacy contract.
Final fix F: pending — cap UTF-8 payload size before allocating encoded bytes.
Final fix B review round 1/5: 0 addressed, 2 Important + 1 Minor open — pinned config must handle one trusted same-Hub redirect; deterministic malformed/oversized metadata must reject rather than retry; document public overload/NotFound compatibility.
Final fix B review round 2/5: 2 addressed, 1 Important + 1 Minor open — trusted redirect and deterministic classification are sound, but redirect-enabled config leaked into normal browse; README example uses nonexistent interface constructor.
Final fix B: complete (commits 13f0ec3..1bf9979, review clean; 1,035 JVM + native 5/5).
Final fix C review round 1/5: 0 addressed, 3 Important + 1 Minor open — exact-case repair flight identity; cancellation swallowed by batch runner and production manifest adapters; dormant direct Ready publication bypass.
Final fix C review round 2/5: 3 addressed, 1 Important + 1 Minor open — queued isPublished still swallows cancellation; nullable download coordinator retains direct bundle/Ready fallback.
Final fix C review round 3/5: 2 addressed, 0 open — queued publication-check cancellation propagates with zero mutation; durable coordinator is mandatory and all direct Ready fallbacks are removed (commit 5ca4318).
Final fix C: complete (commits b6ed188..5ca4318, review clean; 1,049 JVM + native 5/5).
Final fix D: in progress — serialize explicit quarantined-model permission and exact retry under the selected model load job.
Final fix C review round 3/5: 2 addressed, 0 open — queued manifest preflight now preserves cancellation without mutation; ModelViewModel requires the durable coordinator and retains no direct bundle/Ready fallback.
Task 7: complete (V2-only installed loading; entity-only loaders, rollout routing, and transient navigation requests removed; focused and full JVM gates passed).
Final fix D review round 1/5: 1 addressed, 0 open — one ownership mutex now linearizes successor claim, every runner release/unload/load entry, and terminal Success/Error/Admission state commits; claim precedes but never encloses the NonCancellable predecessor join; deterministic native-boundary and stale terminal-result regressions pass.
Final fix D: complete (commits `5331a0c` + review follow-up `4ee8547`; 34 focused tests; 1,056 JVM + native 5/5).
Final fix D review round 2/5: 1 addressed, 0 open — mode/inventory/no-model and ViewModel-clear teardown now claim the same runner owner, preserve the predecessor barrier, complete one guarded full teardown without stale UI writes, and allow same-model reselection after a null transition (commit `c3e59c9`; 36 focused tests; 1,058 JVM + native 5/5).
Final fix D review round 3/5: 1 addressed, 0 open — incompatible public selection and defensive model-load admission now validate mode compatibility before any selection mutation or owner cancellation, so a pending required teardown cannot be orphaned (commit `7f938e0`; 37 focused tests; 1,059 JVM + native 5/5).
Final fix D: complete (commits 5331a0c..7f938e0, review clean after three follow-ups; serialized retry, native ownership, teardown, and compatibility ordering proven).
Final fix E: in progress — delete legacy local-content sidecar resolution and request construction; immutable Hub manifest only.
Final fix E: complete (commits 98481ff..40dc449, production review clean; exact Hub-manifest-only resolver, 1,063 JVM + native 5/5).
Final fix F: in progress — preflight UTF-8 payload size without allocating attacker-sized encoded bytes.
Final fix F: complete (commits 3924b6e..d71f28c, review clean; 1,071 JVM + native 5/5).
Whole-branch review 2: changes requested — 6 Important, 1 Minor; fixes G-K required before merge.
Final fix E review round 1/5: 1 Minor addressed, 0 open — the production-source structural regression now forbids the removed strict alias plus every named sidecar helper/model/constant, with comments excluded and generic names scoped to the artifact resolver (commit `4cde022`; 17 focused tests; 1,059 JVM + native 5/5).
Final fix E: complete (commits `98481ff` + review follow-up `4cde022`; immutable Hub manifest resolution only; review clean).
Final fix E review round 2/5: 1 Minor addressed, 0 open — removed non-Kotlin-aware comment preprocessing and replaced broad tokens with resolver-scoped syntax patterns; permanent and real-source mutations prove URL/raw-string/nested-comment prose stays green while legacy declarations/calls fail (commit `5acf02c`; 18 focused tests; 1,060 JVM + native 5/5).
Final fix E: complete (commits `98481ff`, `4cde022`, and `5acf02c`; immutable Hub manifest resolution only; review round 2 clean).
Final fix E review round 3/5: 1 Minor addressed, 0 open — resolver-only structural enforcement now uses a Kotlin-aware lexical sanitizer across strings/templates/raw strings/chars/comments/nested blocks/EOF and guards every remaining deleted helper/constant; real-code mutation RED and 19-test focused GREEN recorded (commit `cfffb42`; 1,061 JVM + native 5/5).
Final fix E: complete (commits `98481ff`, `4cde022`, `5acf02c`, and `cfffb42`; immutable Hub manifest resolution only; final permitted hardening round clean).
Final fix E review round 4/5: 1 Important addressed, 0 open — the resolver-scoped Kotlin lexer now exposes executable normal/raw string interpolation (balanced nested templates, braces, strings, chars, line/nested block comments, shorthand identifiers, and fail-closed EOF) while preserving prose isolation; exact normal/raw sidecar literals and a real template mutation are covered (commit `40dc449`; 21 focused tests; 1,063 JVM + native 5/5).
Final fix E: complete (commits `98481ff`, `4cde022`, `5acf02c`, `cfffb42`, and `40dc449`; immutable Hub manifest resolution only; review round 4 clean).
Final fix F review round 1/5: 1 Minor addressed, 0 open — ASCII and multibyte over-limit prefixes followed by a malformed surrogate now prove immediate capped-scan exit; continue-scanning mutation RED, 23 codec tests, 107 impacted tests, and 1,071 JVM + native 5/5 pass; production unchanged.
Final fix G: complete (commit `dddb979`; assessed CPU/Metal/Vulkan/CUDA runtime assignment reaches native load and exact placement admission).
Final fix G review round 1/5: 2 Important addressed, 0 open — exact configured role/path ordinals reject omitted, extra, duplicate, and swapped components; a production-path seam proves `sd_ctx_params_t.backend` assignment for all four stable ABI values; auto-fit Vulkan safety follows the selected component backend rather than ambient devices (39 focused tests; 1,085 JVM + native 5/5).
