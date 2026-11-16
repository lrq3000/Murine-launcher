# Murine final Android runtime validation

## Result and exact source provenance

The full added Android suite passed on LDPlayer API28 at clean committed source `669054fdd09edfd3cf2e780272706ec157e115b7`: eight tests executed, eight passed, zero failed, zero skipped. This clears the requested eight-test runtime gate; it does not establish API29, physical Lenovo, complete backup/migration or publication readiness. This report is a subsequent documentation-only change, not a different tested application/test source snapshot.

The coordinator commits remain in history unchanged: `eee37b482a6f61882d5c96f0ad9ce99f7e4ee082` bumps version to 1.3.1 / 10301; `dbb8587f1862273249ff7cb2e282ee00427f2f9d` fixes the actually observed missing `java.util.ArrayList` import in ItemInstallQueue. The coordinator reported clean app/test assembly and `testAospWithoutQuickstepDebugUnitTest` exit0 at dbb8587f in 175 seconds. The earlier failed build at eee37b48 is genuine RED evidence, not an offline/SDK blocker. Coordinator log: `C:/Users/33632/AppData/Local/Temp/opencode/launcher-reliability-builds/murine-final-import-fixed.log`.

## Actual runtime RED and minimal correction

The first full run at dbb8587f on this workstream's newly created independent emulator executed all eight tests: seven passed, one failed, zero skipped. The failure was `ShortcutCallbackIntegrationTest.missingActivityRebuildsSuspensionQuietAndLockWithoutErasingOtherDisabledBits`: `BaseIconCache.assertWorkerThread` threw `IllegalStateException: Cache accessed on wrong thread null` from the test's direct LoaderCursor call on the instrumentation thread. The existing Mockito Android wiring worked; the real writer/binder/serial/cache regressions already passed in this run.

Commit 669054fd changes only the test's loader/recovery section to execute on `Executors.MODEL_EXECUTOR`, matching the production loader and real icon-cache contract, and records the observed RED. No production condition, assertion, test count or real icon-cache operation was removed or replaced to obtain GREEN. The final run reached and passed the saved-row suspension/quiet/lock, other-disabled-bit retention and exact-recovery assertions.

Initial RED evidence and logcat remain at `C:/Users/33632/AppData/Local/Temp/opencode/launcher-reliability-runtime/murine-worker-final-dbb8587/`. The initial app hash was the same as the final app hash below; the initial test hash was `a2137e9ce0f13c74d8b2c651cedf6a55be66c75c3f10b4a55cffb4cf79ee8af2`.

## Build and host verification

Within `C:/git/android-launchers-shortcuts-fix/Murine-launcher/.worktrees/shortcut-reliability`, with `JAVA_HOME=C:/Program Files/Java/jdk-21` and `ANDROID_HOME=ANDROID_SDK_ROOT=C:/Users/33632/AppData/Local/Android/Sdk`, the normal incremental command was:

```text
C:/Users/33632/.gradle/wrapper/dists/gradle-9.7.1-bin/1w1c7tv4s851m17nbqdsro2tv/gradle-9.7.1/bin/gradle.bat --console=plain --quiet --max-workers=1 assembleAospWithoutQuickstepDebug assembleAospWithoutQuickstepDebugAndroidTest testAospWithoutQuickstepDebugUnitTest
```

It exited0 after the actual test-source correction in 9.3 seconds, then again at clean committed 669054fd in 6.1 seconds with HEAD identical before and after. Logs: `C:/Users/33632/AppData/Local/Temp/opencode/launcher-reliability-builds/murine-model-thread-fix.log` and `murine-final-669054f.log` in the same directory. No clean/force/verbose flag, source downgrade, global installation or simultaneous build in this worktree was used. The JVM task completed successfully, but no JUnit XML was generated under the worktree; this is not claimed as execution of the inherited Soong model regressions.

`python tests/host/run_shortcut_tests.py` was rerun on 669054fd and passed 76 assertions: 42 original journal, 11 review journal, 14 actual SQLite integration and nine delivery-boundary assertions. Output is retained as `host-tests.log` in the final evidence directory. Full implementation-range `git diff --check e1e9525b555da58bbd1fdb63c7d80bffc2ad8b24..HEAD` also passed. The real app/test assembly supersedes prior focused-javac-only compilation limits for this source snapshot.

## Installed artifacts and effective SDK

Artifact source snapshot: `669054fdd09edfd3cf2e780272706ec157e115b7`; both source HEAD checks around instrumentation match it. The app bytes are unchanged from dbb8587f because the only subsequent source correction is in the instrumentation test.

- App: `C:/git/android-launchers-shortcuts-fix/Murine-launcher/.worktrees/shortcut-reliability/build/outputs/apk/aospWithoutQuickstep/debug/shortcut-reliability-aosp-withoutQuickstep-debug.apk`.
- App SHA256: `5faf9d7c495183ee430b45a39ff161cf9e871f6da60a3902d4337761fc0babbc`.
- Test: `C:/git/android-launchers-shortcuts-fix/Murine-launcher/.worktrees/shortcut-reliability/build/outputs/apk/androidTest/aospWithoutQuickstep/debug/shortcut-reliability-aosp-withoutQuickstep-debug-androidTest.apk`.
- Test SHA256: `c85f659c8ae653b0b44a913a9052a92bdebf0c5ab84dc2d0147e1171be3f9d22`.

`aapt dump badging` and installed-package dumps independently confirm application `app.murinelauncher.debug`, versionName1.3.1, versionCode10301, minSdk26, targetSdk37. Test package is `com.android.launcher3.tests`, minSdk26/targetSdk37. Both installs returned Success. The guest reports API28, not API29. The tested artifacts are debug APKs, not signed publication artifacts. Complete badging, install and dumpsys outputs are preserved.

## Exact instrumentation command and all eight passing tests

```text
C:/Users/33632/AppData/Local/Android/Sdk/platform-tools/adb.exe -s emulator-5564 shell am instrument -w -r com.android.launcher3.tests/androidx.test.runner.AndroidJUnitRunner
```

No class filter, ignored-test option or assertion suppression was used in either full run. The final raw output contains eight `INSTRUMENTATION_STATUS_CODE: 0` test completions and no failure/skip completion codes:

- `ShortcutAvailabilityStorageTest.unavailablePositionWritePreservesLastValidAppearanceAndDisabledBits`
- `ShortcutAvailabilityStorageTest.databaseIdentityIsAdditiveAndChangesWithNewWorkspace`
- `ShortcutAvailabilityStorageTest.atomicRemovalSurvivesProcessStyleReopenAndReinstall`
- `ShortcutBindIntegrationTest.authoritativeRemovalInvalidatesAlreadyScheduledAndDeferredPages`
- `ShortcutCallbackIntegrationTest.missingActivityRebuildsSuspensionQuietAndLockWithoutErasingOtherDisabledBits`
- `ShortcutCallbackIntegrationTest.removalBeforeUserCacheInitializationUsesStableSystemSerial`
- `ShortcutCallbackIntegrationTest.unavailableLowResFolderChildSurvivesEvictionAndRealAsyncHighResRequest`
- `ShortcutInsertionIntegrationTest.rejectedFenceDoesNotBindOrAddGhostAndRetryCommitsBeforeSuccess`

The exact successful raw-output tail is:

```text
INSTRUMENTATION_RESULT: stream=

Time: 4,608

OK (8 tests)


INSTRUMENTATION_CODE: -1
```

`INSTRUMENTATION_CODE: -1` is the runner's normal final success result here; it is not a test failure code. The shell command exit code was0. Test completion statuses and `OK (8 tests)`, rather than shell exit alone, establish success.

## Isolation, memory admission and verified shutdown

Exactly one new native instance was created, not cloned or borrowed: `agent-murine-shortcuts-e773138cd7fc`, observed index5 at `C:/LDPlayer/LDPlayer9`. Native add returned5 yet the exact new name/index appeared in list2; creation was accepted from observed identity, not assumed next-index/exit-code semantics. Its observed native serial was `emulator-5564`. The record was written before native add/launch under `C:/Users/33632/AppData/Local/agent-emulators/ldplayer/allocation.lock` with `allocation-owner.json`, after rereading all claims/list2 and measuring available RAM. The first admission measured27,211,264KiB and the restart26,984,936KiB, each with zero other pending reservations and above the4,194,304KiB floor.

Only the new stopped index5 was configured: native memory4096/cpu2/root0; after verifying exact playerName, only its `basicSettings.adbDebug=1` and `basicSettings.rootMode=false` were set/confirmed before first boot. No global config, root enablement, physical-device mutation, ADB-server reset, existing-index mutation, push, merge, rename, deletion or additional agent/reviewer was used. Index4 and every other existing target remained untouched.

Initial generation `0b0e65c8-3004-4db1-a445-c25f5e966c63` was verified stopped before rebuilding. The retained disk was restarted under fresh generation `c64b3020-fb54-4397-912d-0ba7042234d5`, invalidating old shutdown authorization. Each launch held the shared allocation lock through bounded readiness: exact native name/index/process mapping, matching native/ADB serial, ADB device state, boot_completed1, pm path android and API28.

After all runtime consumers finished and instrumentation/logcat/provenance were saved, current-generation shutdown_ready/evidence_saved were recorded. Under an exclusive cleanup claim, the original player PID45296 / CreationDate `2026-11-16T03:58:32.989009+01:00` and VM PID42228 / CreationDate `2026-11-16T03:58:35.90008+01:00` were persisted before native quit of index5. Verification confirmed both original process identities exited, `isrunning` returned stop and list2 showed index5 stopped with PIDs-1/-1. Only then were ownership and the zero runtime reservation released. The final inventory showed every listed index0–5 stopped; no other instance needed or received shutdown. The physical WiFi target remained connected with its original transport46.

Final shared record: `C:/Users/33632/AppData/Local/agent-emulators/ldplayer/agent-murine-shortcuts-e773138cd7fc.json`, state `stopped-released`, owner null, shutdown_verified true, reservedKiB0. The exact original process identities and prior generation are retained there. All guest disks and logs remain intact; no storage deletion is authorized or performed.

## Evidence directory and remaining limits

Final evidence directory: `C:/Users/33632/AppData/Local/Temp/opencode/launcher-reliability-runtime/murine-worker-final-669054f/`. Important files: `instrumentation.log` (complete eight-test output), `logcat.log`, `provenance.json`, `result.json`, `app-badging.txt`, `test-badging.txt`, `app-installed.txt`, `test-installed.txt`, `app-install.txt`, `test-install.txt`, `host-tests.log`, `instance.json` and the generation-specific instance record. Generated host classes/SQLite fixtures remain under `C:/Users/33632/AppData/Local/Temp/opencode/murine-shortcut-tests-*`; the local lifecycle/test-driver scripts and pointer remain in the same approved opencode temporary directory for manual inspection, not cleanup.

Still unverified: inherited Soong WorkspaceItemProcessor/AddWorkspaceItemsTask regressions; complete BackupHelper archive transport/application restart; full Android grid-migration engine rather than the executed real-SQLite migration boundary; actual install-queue/UI failure/retry flows beyond the executed real writer/task regression; exhaustive crash-timing/profile/admin/customization combinations; API29 and physical Lenovo/removable-storage behavior. Wholly missed stopped-process uninstall of an external/unknown package remains intrinsically ambiguous. None of these is converted into deletion authority or a claim of publication readiness merely because this eight-test API28 gate passed.
