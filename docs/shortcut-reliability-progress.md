# Murine shortcut reliability ledger

Plan: `C:/git/android-launchers-shortcuts-fix/docs/superpowers/plans/2026-11-16-shortcut-reliability.md`; specification: adjacent `docs/brainstorm/android-launchers-shortcuts-fix_HANDOFF_STANDALONE_EN.md` in the coordinator workspace.

## Scope and baseline

Work is confined to `.worktrees/shortcut-reliability`, branch `fix/preserve-unavailable-shortcuts`, base `e1e9525b555da58bbd1fdb63c7d80bffc2ad8b24`. Historical reference `10ea49884acbb1972b050904e226d7b29a2124bd` is not the implementation base. Current main is version 1.3.0 (10300); coordinator owns integration, patch version bump, publication and independent review.

## Concrete sequence

1. Reproduce loader deletion in the existing model harness where possible; add tests before production edits. Establish durable policy tests using the actual production Java policy and codec, with only persistence and package observations injected at the boundaries.
2. Implement a versioned atomic journal, stable profile/package observations, exact database/item/component keys, and durable confirmed-removal tombstones. Use an additive identity table without changing the favorites schema/version or invoking migration fallback.
3. Integrate loading, postboot, model-discard, package-update/removal and foreground paths. Preserve unknown/external/recovering items; retain normal known-internal behavior outside recovery; never replace a protected component or infer an uninstall from a negative query.
4. Protect cached labels/icons, folder children and customizations; keep independent disabled bits; use existing helpful unavailable tap feedback plus revalidation; prevent recovery auto-add.
5. Run bounded host checks and document canonical Android build/model-test blockers; commit coherent milestones and provide a complete validation report for coordinator review.

## Preflight and rulings

Pre-flight: loader, callbacks and package updates share the journal; appearance persistence consumes the loader's unavailable flag; foreground revalidation consumes the same exact-target policy. No other repository is a dependency.

Ruling: execute inline with this committed ledger instead of the skill's shell-specific temporary workspace scripts — the shared plan is a coordinator plan, not a generated per-repository brief; permanent test/report artifacts must be committed and no nested agents are authorized — cost if wrong: manual rather than automated task bookkeeping.

Ruling: retain newer upstream main rather than reset to v1.2.3 — explicitly required; current `aospWithoutQuickstep` still declares minSdk 26 and Quickstep 33 — cost if wrong: runtime/API compatibility remains unproven until an APK and device run exist.

## Investigation and verification

Confirmed source paths: `WorkspaceItemProcessor` deletes missing packages after boot and substitutes missing activities; `SdCardAvailableReceiver` synthesizes OP_REMOVE; `PackageUpdatedTask` clears NOT_AVAILABLE on every ADD/UPDATE and deletes inferred package/components; `LauncherModel.enqueueModelUpdateTask` discards updates while unloaded; `LoaderCursor` normally excludes application icon blobs; `WorkspaceItemInfo.onAddToDatabase` can overwrite appearance during a failed lookup. `ModelTaskController` passes deletion predicates through to database and UI. Session commits/install sessions feed `ItemInstallQueue`; package availability does not need to enqueue items.

Canonical baseline command (JDK21, cached Gradle9.7.1, SDK configured only in process environment): `gradle --offline --console=plain --quiet --max-workers=1 testAospWithoutQuickstepDebugUnitTest --tests com.android.launcher3.model.WorkspaceItemProcessorTest` failed before compilation because Android Gradle Plugin 9.4.1 is not cached. Existing `tests/multivalentTests` additionally uses platform-only test APIs and is not wired into Gradle; `androidTest` source sets are commented out. This is an environment/harness blocker, not a witnessed model RED. No device/emulator was accessed and no dependency download was attempted.

## Progress

- Source investigation complete. Policy/codec test initially could not compile (missing implementation), then witnessed executable RED with a compileable no-policy skeleton: `expected PRESERVE, got NORMAL`. Production policy implemented; `python tests/host/run_shortcut_tests.py` GREEN, 18 assertions. Covers unknown migration, restart, exact components, external UUID with unresolved public mapping, known internal behavior, durable unavailability, profile/database/row-scoped removal, rapid re-add, corrupt metadata and write interruption. These are real policy/codec checks, not proof of Android UI or favorites integration.
- Callback ingress/pending-removal test witnessed RED (`expected true, got false`), then GREEN; internal-app recovery exit regression witnessed RED (`expected NORMAL, got PRESERVE`), then GREEN. Current host suite: 24 assertions. Added real Android AtomicFile/SQLite identity tests in `tests/availabilityAndroidTest/src`, explicitly wired into `androidTest`; they remain unexecuted because AGP is unavailable and devices are prohibited. Added a processor regression to the existing platform-only suite; it also remains unexecuted.
- Loader/update/removal/foreground integrations implemented. Package callbacks record on UI_HELPER_EXECUTOR while loading remains on MODEL_EXECUTOR. Durable pending uninstall receipt is converted to exact-row tombstones before task discard and before final bind. Temporary unavailability is never reclassified as OP_REMOVE. Boot/media hints are lifecycle-owned revalidation triggers. Known internal targets return to ordinary policy after recovery; previously unseen components remain provisional.
- `javac` against the installed real Android API35 jar compiled the journal, Android AtomicFile boundary, and additive SQLite identity helper successfully. This is a narrow compile check, not application compilation or API29 runtime validation.

Ruling: add an idempotent identity table without changing the favorites schema or SQLite user_version — avoids the existing destructive migration fallback; reset the identity only with an already-requested empty-workspace reset — cost if wrong: database replacement outside the helper lifecycle could retain a stale identity until reopen; instrumentation coverage is provided but not executed here.

Ruling: register package callbacks on the existing UI_HELPER executor and journal there, with a shared lock around ingress and final loader reconciliation/bind scheduling — otherwise callbacks are not received while the model executor is busy loading — cost if wrong: off-UI durable writes add callback latency; model/database mutation remains model-thread-only.

Ruling: removal ordering uses a durable exact-row snapshot rather than expiring a package tombstone on ADD — uninstall/reinstall must still remove the previous installation's original rows while new row IDs remain independent — cost if wrong: explicit tombstones require lifecycle pruning and a stable database generation.
