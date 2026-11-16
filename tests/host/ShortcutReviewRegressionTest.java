/* Copyright (C) 2026 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0: http://www.apache.org/licenses/LICENSE-2.0 */
package com.android.launcher3.model;

import java.io.IOException;
import java.util.Collections;

/** Review regressions exercise the production journal and its durable-storage boundary. */
public final class ShortcutReviewRegressionTest {
    private static int checks;
    static final class Disk implements ShortcutAvailabilityJournal.Persistence {
        byte[] bytes;
        boolean fail;
        boolean uncertain;
        public byte[] read() { return bytes; }
        public void write(byte[] value) throws IOException {
            if (fail) throw new IOException("transient write failure");
            bytes = value.clone();
        }
        public boolean isUncertain() { return uncertain; }
        public void markUncertain() { uncertain = true; }
        public void clearUncertain() { uncertain = false; }
    }
    private static final ShortcutAvailabilityJournal.Target ROW = new ShortcutAvailabilityJournal.Target("db", 42, 301, "pkg", "pkg/.Main");
    private static void check(Object expected, Object actual) {
        checks++;
        if (!expected.equals(actual)) throw new AssertionError("expected " + expected + ", got " + actual);
    }
    public static void main(String[] args) {
        if (args.length == 0 || args[0].equals("6")) knownInternalRestartAndFailedRecovery();
        if (args.length == 0 || args[0].equals("7")) freshRemovalAfterCorruptOrFailedStore();
        System.out.println("PASS " + checks + " review journal assertions (findings " + (args.length == 0 ? "6,7" : args[0]) + ")");
    }
    private static void knownInternalRestartAndFailedRecovery() {
        Disk disk = new Disk();
        var journal = new ShortcutAvailabilityJournal(disk);
        journal.evaluate(ROW, true, false, "internal", null);
        check(ShortcutAvailabilityJournal.Decision.NORMAL, new ShortcutAvailabilityJournal(disk).evaluate(ROW, false, null, null, null));
        disk.fail = true;
        journal.unavailable(301, "pkg");
        disk.fail = false;
        var reopened = new ShortcutAvailabilityJournal(disk);
        check(ShortcutAvailabilityJournal.Decision.PRESERVE, reopened.evaluate(ROW, false, null, null, null));
        check(ShortcutAvailabilityJournal.Decision.PRESERVE, reopened.evaluate(ROW, false, null, null, null));
        reopened.evaluate(ROW, true, false, "internal", null);
        check(ShortcutAvailabilityJournal.Decision.NORMAL, new ShortcutAvailabilityJournal(disk).evaluate(ROW, false, null, null, null));
    }
    private static void freshRemovalAfterCorruptOrFailedStore() {
        Disk disk = new Disk();
        disk.bytes = new byte[] {1, 2, 3};
        var journal = new ShortcutAvailabilityJournal(disk);
        journal.packageRemoved(301, "pkg");
        check(true, journal.hasPendingRemoval(301, "pkg"));
        check(true, journal.removed(301, "pkg", Collections.singletonList(ROW)));
        check(ShortcutAvailabilityJournal.Decision.REMOVE, journal.evaluate(ROW, false, null, null, null));

        Disk transientDisk = new Disk();
        var transientJournal = new ShortcutAvailabilityJournal(transientDisk);
        transientJournal.evaluate(ROW, true, false, "internal", null);
        transientDisk.fail = true;
        transientJournal.unavailable(301, "pkg");
        transientDisk.fail = false;
        transientJournal.evaluate(ROW, false, null, null, null);
        check(false, transientDisk.uncertain); // An unchanged availability retry still retries a previously failed write.
        transientJournal.packageRemoved(301, "pkg");
        check(true, transientJournal.hasPendingRemoval(301, "pkg"));
        check(true, transientJournal.removed(301, "pkg", Collections.singletonList(ROW)));
        check(ShortcutAvailabilityJournal.Decision.REMOVE, transientJournal.evaluate(ROW, true, false, "internal", null));
    }
}
