# Murine independent-review correction report

## Scope, revision and readiness

Original implementation base: `e1e9525b555da58bbd1fdb63c7d80bffc2ad8b24`. Reviewed tip / correction base: `8a67f078b40a7fc916c38da1348f3fda0799d6b8`. This is the single authorized correction pass for the coordinator's nine Important findings. `git log 8a67f078..HEAD` supplies exact correction commits. No other editor, nested agent or additional reviewer was used. No push, merge, main integration, branch rename, device operation, heavy Gradle build or global installation was performed by this implementer during correction.

The prior current-process-only internal policy and never-loaded-layout exemption are withdrawn. Corrected source and regression coverage are present, but the final tip is **not declared publication-ready**: the coordinator owns its clean incremental app/test compilation and remaining Android runtime checks. Host boundary tests are not substituted for actual ModelWriter, binder or Android service execution.

## Per-finding disposition

### 1. Restored backup matched old uninstall tombstones

Verified in BackupHelper's snapshot/staged-restore path: the identity table was copied and noBackup journal survived. Staged databases now pass through `WorkspaceAvailability.prepareRestoredDatabase` before any live database replacement. The production `WorkspaceLayoutStore` rotates the workspace incarnation and durably fences restored ordinary rows as explicit new user intent. Identity-cache epoch invalidation and adapter restore-epoch reload prevent retaining the old cached identity/associations. No unrelated backup scrub/deletion policy was changed.

Executed host RED→GREEN: an actual SQLite file snapshot taken before uninstall was copied back after journaled uninstall/reinstall. The old flow returned REMOVE for the restored installed target; the corrected production restore transaction returns AVAILABLE with the same favorites ID and saved label. This uses real SQLite JDBC and the production identity/layout code, with only Android SQL API and intent-component parsing at the test boundary. BackupHelper's archive transport and full application restart remain unexecuted at the final tip.

### 2. Rejected insertion still published ghost success and consumed queue

Verified that ModelWriter ignored insert=-1 and AddWorkspaceItemsTask bound success unconditionally. `WorkspaceInsertCommit` now gates publication on database success. ModelWriter's model-thread insertion reports its result; the regular optimistic-owner path rolls back through all callbacks, surfaces localized failure feedback, and reloads. Other callbacks are notified only after successful persistence/model insertion. AddWorkspaceItemsTask binds only committed items and reports one completion result. ItemInstallQueue acknowledges only the committed in-flight snapshot; failed requests remain durably queued for foreground/later-add retry, and requests appended during a flush are not lost.

Executed host RED→GREEN at the production publication boundary: a failed insertion initially published (`expected 0, got 1`); it now neither publishes nor acknowledges, and successful retry publishes once. Real ModelWriter/ModelDbController/SQLite/AddWorkspaceItemsTask regression coverage was added in `ShortcutInsertionIntegrationTest`, plus a Soong AddWorkspaceItemsTask regression. These consumer-level Android tests have not yet executed at the final tip; the host publication test is not claimed as proof of their integration.

### 3. Scheduled/deferred binds could resurrect removed items

Verified copied lists and deferred RunnableLists in BaseLauncherBinder/ModelCallbacks. An atomic bind generation is invalidated on authoritative removal receipt. Both normal and inflated/chunked UI deliveries, deferred pages and the old loader-queue resume check that generation when they actually execute; they perform no database or package-service query on UI. LauncherModel restarts the affected load transaction so valid remaining items and completion signals are rebound rather than leaving an invalidated partial transaction.

Executed host RED→GREEN with blocked immediate and deferred executors: both previously delivered stale items after invalidation (`expected 0, got 2`); neither now delivers, while a new-generation delivery works. A real BaseLauncherBinder copied-list/deferred-page/blocked-main regression is added as `ShortcutBindIntegrationTest` and remains pending final-tip Android execution.

### 4. Unavailable low-resolution folder child lost its cache

Verified PackageUpdatedTask's prior cache invalidation and IconCache's actual asynchronous high-resolution request. Protected ordinary items now hydrate their saved normal appearance before binding. The background task checks the current unavailable state at execution and hydrates favorites instead of asking the missing APK/cache for a replacement. Recovery uses a separate candidate snapshot to bypass that unavailable guard only after exact resolution and preserves the previous snapshot if lookup fails. Archived/restore/deep-shortcut paths are excluded from this ordinary-item guard.

Coordinator-provided actual Android GREEN at the observed APK snapshot: `unavailableLowResFolderChildSurvivesEvictionAndRealAsyncHighResRequest` passed on API28, including saved-label and real colored-bitmap assertions after cache eviction and asynchronous delivery. This proves that tested snapshot/scenario, not all final-tip folder/UI scenarios.

### 5. Removal authority missed inactive layouts and migration copies

Verified only the active database was snapshotted before the pending flag was cleared. All existing grid database files plus the active file now contribute exact profile/component/item/incarnation snapshots before a receipt is consumed. Confirmed rows are removed idempotently from source layouts before either grid-migration path can copy/remap them. An unreadable layout retains ordered package authority rather than consuming it or blocking readable grids; an undurable removal snapshot cannot be migrated past. Existing installation-generation fences preserve new-install rows. Explicit restored layouts receive new-incarnation fences instead of inheriting old removal authority.

Executed real SQLite RED→GREEN: never-loaded inactive rows previously remained, and unreadable inactive grids initially caused a missing-table failure. Current tests cover two actual database files, another profile that survives, a new-install row that survives, an actual SQLite grid-copy with changed IDs, and a partial/unreadable layout that is reconciled after becoming readable. The complete Android GridSizeMigrationLogic runtime is not represented by that SQL-copy test and remains a coordinator validation item.

### 6. Healthy known-internal policy changed after restart

Verified and removed the unauthorized `mCurrentInternalTargets` requirement. A reopened healthy journal with known internal evidence and no recovery condition again returns NORMAL for the ordinary missing-target path. A separate fsynced uncertainty latch identifies a failed/interrupted write so an older valid file cannot falsely appear current. Uncertain historical internal/removal data is downgraded before trust is rebuilt; positive external relocation metadata also remains preservation evidence.

Executed RED→GREEN: reopened healthy known-internal history initially returned PRESERVE instead of NORMAL. Tests now verify NORMAL after healthy restart, preservation after a failed unavailable write/restart and unchanged retry, and return to normal policy after positive resolution and healthy persistence. No blanket internal-history exemption remains.

### 7. Journal health could never recover

Verified the permanent false health latch. Fresh successful snapshots now restore health; failed writes are retried even when the availability inputs have not changed. Corrupt/unreadable historical authority is not promoted merely because writing resumes, while new authoritative events can be journaled and applied in the same process. The uncertainty marker is cleared only after successful durable publication.

Executed RED→GREEN: fresh confirmed removal after corrupt storage initially remained suppressed; transient-failure/unchanged retry also initially left uncertainty latched. Tests now exercise fresh true uninstall after corruption and after transient write failure without restarting, including persisted pending receipt and exact-row removal. Total filesystem failure and final-tip Android fault-injection remain runtime validation limits, not grounds for deletion.

### 8. Helper-thread event used a handle ID as profile serial

Verified UserCache's positive handle-ID fallback before initialization and during refresh. Durable ingress, exact-item matching and queue comparisons now use the public thread-safe UserManager stable serial source, never the cache fallback. Invalid/nonexistent profiles are not journaled under a guessed identity.

The Android regression injects cached ID10 versus system serial301, then an old cached serial301 versus changed system serial401, and reopens the actual AtomicFile to check profile-scoped events. The observed API28 run failed in Mockito initialization before reaching this feature; it did not establish a feature result. Test-runtime wiring has been corrected as described below, and this final-tip test awaits coordinator rerun.

### 9. Suspension/quiet/lock state was not rebuilt for unavailable items

Verified the unavailable loader's fresh WorkspaceItemInfo lacked LauncherActivityInfo-derived flags. Independently observable suspension (including metadata available without a launcher activity), quiet mode and profile lock are now populated when the unavailable item is created and during recovery. Other publisher/admin-related state conveyed by existing flags is not erased by storage recovery; only NOT_AVAILABLE is removed after positive target resolution. No nonexistent separate "admin bit" is invented.

The Android regression now constructs an actual LoaderCursor from a saved row, with no pre-populated suspension/quiet/lock snapshot, then exercises exact recovery with independently supplied ApplicationInfo/UserManager observations. Its previous version failed in Mockito initialization before feature execution on API28. The corrected runtime dependency and final test require coordinator execution; no Android GREEN is claimed for this finding yet.

## Executed checks in this correction pass

`python tests/host/run_shortcut_tests.py` passes: 42 original journal assertions, 11 review journal assertions, 14 real SQLite integration assertions, and nine publication/deferred-delivery boundary assertions (76 total). The runtime prints counts separately so policy, SQLite integration and execution-boundary proof are not conflated. The SQL adapter delegates to real cached SQLite JDBC; it does not implement launcher policy or simulate SQL decisions. `SQLITE_JDBC_JAR` can select that dependency explicitly; otherwise the runner uses its existing Gradle cache, without downloading it. Temporary classes/SQLite fixtures remain under `C:/Users/33632/AppData/Local/Temp/opencode/murine-shortcut-tests-*`.

The production journal, AtomicFile adapter, SQLite identity/layout store and publication/bind guards also compile with JDK21 against the real installed API35 android.jar. `git diff --check` passes. These are focused checks, not final full-app Java/Kotlin/Dagger compilation or API29 runtime evidence.

## Coordinator Android evidence and its limits

The coordinator's online `assembleAospWithoutQuickstepDebug assembleAospWithoutQuickstepDebugAndroidTest` exited 0 in 683 seconds. AGP9.4.1 and normal SDK37.2/build-tools37.0.0 installation succeeded; old offline-cache observations are superseded and no source/toolchain downgrade was necessary. Log: `C:/Users/33632/AppData/Local/Temp/opencode/launcher-reliability-builds/murine-apks.log`. Compilation overlapped editing, so the compiler-observed source snapshot is not assumed identical to the final corrected tip.

Observed app SHA256: `63b7d8364a30d135b5efa998e0587115cb38f37efd361b2612c5742a65bc6f87`. Observed test SHA256: `786a916a00dcaa416861f91b2eef74498102f29967b7aad91100360f157ce6d7`, package `com.android.launcher3.tests`. These are evidence identifiers, not final release artifacts.

The coordinator's API28 run on `agent-launcher-shortcuts-v2-6ff0dd05` (now stopped) executed six tests: three passed (additive SQLite identity, actual AtomicFile reopen/reinstall, actual unavailable folder async icon request), three failed. Evidence: `C:/Users/33632/AppData/Local/Temp/opencode/launcher-reliability-runtime/agent-launcher-shortcuts-v2-6ff0dd05/murine-instrumentation.log` and adjacent `logcat.log`. This is API28 evidence, not API29 or physical Lenovo evidence.

The appearance-write fixture set BitmapInfo to null even though ItemInfoWithIcon initializes it to the non-null LOW_RES_INFO sentinel and getMatchingLookupFlag dereferences it. No null path was introduced by the correction. The fixture now uses LOW_RES_INFO, keeps its saved-label/blob/placement/disabled-bit assertions, and still checks empty-title protection. Its corrected Android result remains pending.

Two other tests failed with AbstractMethodError for MockMaker.isTypeMockable before feature execution. Source dependencies paired Mockito5.20 with the inherited Dexmaker1.2 Mockito1.x plugin. Only androidTest wiring is changed: same-version `org.mockito:mockito-android` now supplies the Android MockMaker and core together; the old explicit Dexmaker test dependencies are removed. Production dependencies are not broadly upgraded, and tests are not disabled. API35 signatures confirm the mocked Android service classes/methods are non-final. Successful final runtime creation/stubbing still requires the coordinator rerun.

## Pending final validation

- Coordinator normal incremental app + Android-test APK compilation at the exact final commit, including same-version Mockito Android test dependency resolution.
- Rerun all eight currently added Android instrumentation tests, including new actual insertion and binder races. The two inherited Soong model regressions (WorkspaceItemProcessor and AddWorkspaceItemsTask) also remain unexecuted here.
- Actual install-queue failure/retry UI behavior, full BackupHelper archive/application restart, full Android grid migration, process-kill timing and all independent profile/admin/customization combinations.
- Effective final APK manifest/minSdk inspection and API29/physical Lenovo storage validation. `aospWithoutQuickstepDebug` still declares minSdk26; withQuickstep still declares33. Version remains 1.3.0 / 10300 for coordinator integration/version bump.

No final all-green Android suite, final functioning API29 APK, publication readiness or resolved physical-storage ambiguity is claimed. A wholly missed stopped-process uninstall of an external/unknown package remains intrinsically ambiguous; elapsed time, another volume or negative lookup still cannot authorize its deletion.
