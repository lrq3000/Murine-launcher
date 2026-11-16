/*
 * Copyright (C) 2026 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */
package com.android.launcher3.model;

import java.io.IOException;
import java.util.List;

/** Runs real policy/codec behavior without Android, JUnit, network or Gradle dependencies. */
public final class ShortcutAvailabilityJournalTest {
    private static int sCount;
    private static final class MemoryDisk implements ShortcutAvailabilityJournal.Persistence {
        byte[] bytes;
        boolean fail;
        public byte[] read() throws IOException { return bytes; }
        public void write(byte[] value) throws IOException {
            if (fail) throw new IOException("interrupted atomic write");
            bytes = value.clone();
        }
    }

    private static ShortcutAvailabilityJournal.Target target(long profile, String component) {
        return new ShortcutAvailabilityJournal.Target("workspace-generation-A", 42, profile,
                "example.app", component);
    }

    private static void expect(Object expected, Object actual) {
        sCount++;
        if (!expected.equals(actual)) throw new AssertionError("expected " + expected + ", got " + actual);
    }

    public static void main(String[] args) {
        MemoryDisk disk = new MemoryDisk();
        ShortcutAvailabilityJournal journal = new ShortcutAvailabilityJournal(disk);
        var a = target(7, "example.app/.Main");
        var b = target(7, "example.app/.Second");
        // A negative lookup after boot, after any mounted volume, or after arbitrary time has no extra authority.
        expect(ShortcutAvailabilityJournal.Decision.PRESERVE, journal.evaluate(a, false, null, null, null));
        expect(ShortcutAvailabilityJournal.Decision.PRESERVE,
                new ShortcutAvailabilityJournal(disk).evaluate(a, false, null, null, null));
        expect(ShortcutAvailabilityJournal.Decision.AVAILABLE,
                journal.evaluate(a, true, true, "adopted-install-uuid", null));
        expect(ShortcutAvailabilityJournal.Decision.PRESERVE, journal.evaluate(b, false, null, null, null));
        expect(ShortcutAvailabilityJournal.Decision.AVAILABLE,
                journal.evaluate(a, true, true, "adopted-install-uuid", null));
        expect(ShortcutAvailabilityJournal.Decision.PRESERVE, journal.evaluate(b, false, null, null, null));
        expect("adopted-install-uuid", new ShortcutAvailabilityJournal(disk).installationUuid(7, "example.app"));
        expect("", new ShortcutAvailabilityJournal(disk).publicVolumeUuid(7, "example.app"));

        MemoryDisk internalDisk = new MemoryDisk();
        var internal = new ShortcutAvailabilityJournal(internalDisk);
        internal.evaluate(a, true, false, "internal", null);
        expect(ShortcutAvailabilityJournal.Decision.NORMAL, internal.evaluate(a, false, null, null, null));
        internal.unavailable(7, "example.app");
        expect(ShortcutAvailabilityJournal.Decision.PRESERVE,
                new ShortcutAvailabilityJournal(internalDisk).evaluate(a, false, null, null, null));
        internal.evaluate(a, true, false, "internal", null);
        expect(ShortcutAvailabilityJournal.Decision.NORMAL, internal.evaluate(a, false, null, null, null));
        expect(ShortcutAvailabilityJournal.Decision.PRESERVE, internal.evaluate(b, false, null, null, null));

        // Confirmed removal is durable, profile/component/database scoped, and survives a rapid re-add.
        expect(true, journal.removed(7, "example.app", List.of(a, b)));
        journal.added(7, "example.app");
        var restart = new ShortcutAvailabilityJournal(disk);
        expect(ShortcutAvailabilityJournal.Decision.REMOVE, restart.evaluate(a, true, true, "new-install", null));
        expect(ShortcutAvailabilityJournal.Decision.PRESERVE,
                restart.evaluate(target(8, "example.app/.Main"), false, null, null, null));
        var newRow = new ShortcutAvailabilityJournal.Target("workspace-generation-A", 43, 7,
                "example.app", "example.app/.Main");
        expect(ShortcutAvailabilityJournal.Decision.AVAILABLE, restart.evaluate(newRow, true, false, "internal", null));
        var newDb = new ShortcutAvailabilityJournal.Target("workspace-generation-B", 42, 7,
                "example.app", "example.app/.Main");
        expect(ShortcutAvailabilityJournal.Decision.AVAILABLE, restart.evaluate(newDb, true, false, "internal", null));

        // Receipt while loading is durable before the model can obtain a row snapshot.
        restart.packageRemoved(19, "pending.app");
        restart.added(19, "pending.app");
        var pendingRestart = new ShortcutAvailabilityJournal(disk);
        expect(true, pendingRestart.hasPendingRemoval(19, "pending.app"));
        var pendingRow = new ShortcutAvailabilityJournal.Target("workspace-generation-A", 71, 19,
                "pending.app", "pending.app/.Main");
        expect(true, pendingRestart.removed(19, "pending.app", List.of(pendingRow)));
        expect(false, new ShortcutAvailabilityJournal(disk).hasPendingRemoval(19, "pending.app"));
        expect(ShortcutAvailabilityJournal.Decision.REMOVE,
                new ShortcutAvailabilityJournal(disk).evaluate(pendingRow, true, false, "internal", null));

        // Failed storage must never turn missing targets or stale removal metadata into a purge.
        disk.fail = true;
        expect(false, restart.removed(8, "example.app", List.of(target(8, "example.app/.Main"))));
        expect(ShortcutAvailabilityJournal.Decision.PRESERVE,
                restart.evaluate(target(8, "example.app/.Main"), false, null, null, null));
        internalDisk.bytes = new byte[] {1, 2, 3};
        expect(ShortcutAvailabilityJournal.Decision.PRESERVE,
                new ShortcutAvailabilityJournal(internalDisk).evaluate(a, false, null, null, null));
        System.out.println("PASS " + sCount + " journal assertions");
    }
}
