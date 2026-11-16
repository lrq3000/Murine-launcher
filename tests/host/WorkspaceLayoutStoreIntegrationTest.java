/* Copyright (C) 2026 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0: http://www.apache.org/licenses/LICENSE-2.0 */
package com.android.launcher3.model;

import android.database.sqlite.SQLiteDatabase;
import java.nio.file.*;
import java.util.*;

/** Real file/SQLite backup restore, inactive layout and migration-copy regressions with the production reconciler. */
public final class WorkspaceLayoutStoreIntegrationTest {
    static int checks;
    static final WorkspaceLayoutStore.ComponentReader READER = intent -> new WorkspaceLayoutStore.Component("pkg", intent);
    static void seed(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE favorites (_id INTEGER PRIMARY KEY, profileId INTEGER, intent TEXT, itemType INTEGER, restored INTEGER, title TEXT)");
        db.execSQL("INSERT INTO favorites VALUES (42,301,'pkg/.Main',0,0,'Saved label')");
    }
    static void check(Object want, Object got) { checks++; if (!want.equals(got)) throw new AssertionError("expected " + want + ", got " + got); }
    static long count(SQLiteDatabase db) { try (var c = db.rawQuery("SELECT COUNT(*) FROM favorites", null)) { c.moveToFirst(); return c.getLong(0); } }
    public static void main(String[] args) throws Exception {
        Path dir = Paths.get(args[0]);
        if (args.length < 2 || args[1].equals("1")) restoreIsNewIncarnation(dir);
        if (args.length < 2 || args[1].equals("5")) reconcileAllBeforeMigration(dir);
        if (args.length < 2 || args[1].equals("5")) unreadableInactiveLayoutKeepsAuthority(dir);
        System.out.println("PASS " + checks + " SQLite integration assertions (findings " + (args.length < 2 ? "1,5" : args[1]) + ")");
    }
    static void restoreIsNewIncarnation(Path dir) throws Exception {
        Path live = dir.resolve("restore-live.db"), backup = dir.resolve("before-uninstall.db");
        String identity;
        try (var db = SQLiteDatabase.openDatabase(live.toString(), null, 0)) { seed(db); identity = WorkspaceDatabaseIdentity.get(db); }
        Files.copy(live, backup);
        var disk = new ShortcutReviewRegressionTest.Disk();
        var journal = new ShortcutAvailabilityJournal(disk);
        var old = new ShortcutAvailabilityJournal.Target("restore-live.db:" + identity, 42, 301, "pkg", "pkg/.Main");
        journal.removed(301, "pkg", Collections.singletonList(old));
        journal.added(301, "pkg");
        Files.copy(backup, live, StandardCopyOption.REPLACE_EXISTING);
        WorkspaceLayoutStore.prepareRestoredDatabase(live.toFile(), journal, READER);
        try (var db = SQLiteDatabase.openDatabase(live.toString(), null, 0)) {
            var restored = new ShortcutAvailabilityJournal.Target("restore-live.db:" + WorkspaceDatabaseIdentity.get(db), 42, 301, "pkg", "pkg/.Main");
            check(ShortcutAvailabilityJournal.Decision.AVAILABLE, journal.evaluate(restored, true, false, "internal", null));
            check(1L, count(db));
            try (var row = db.rawQuery("SELECT title FROM favorites WHERE _id=42", null)) { row.moveToFirst(); check("Saved label", row.getString(0)); }
        }
    }
    static void reconcileAllBeforeMigration(Path dir) {
        try (var active = SQLiteDatabase.openDatabase(dir.resolve("active.db").toString(), null, 0);
             var inactive = SQLiteDatabase.openDatabase(dir.resolve("never-loaded.db").toString(), null, 0);
             var migrated = SQLiteDatabase.openDatabase(dir.resolve("migrated.db").toString(), null, 0)) {
            seed(active); seed(inactive);
            active.execSQL("INSERT INTO favorites VALUES (43,302,'pkg/.Main',0,0,'Other profile')");
            var journal = new ShortcutAvailabilityJournal(new ShortcutReviewRegressionTest.Disk());
            journal.packageRemoved(301, "pkg");
            journal.added(301, "pkg");
            journal.created(new ShortcutAvailabilityJournal.Target("never-loaded.db:" + WorkspaceDatabaseIdentity.get(inactive), 45, 301, "pkg", "pkg/.Main"));
            inactive.execSQL("INSERT INTO favorites VALUES (45,301,'pkg/.Main',0,0,'New installation')");
            WorkspaceLayoutStore.reconcile(Arrays.asList(new WorkspaceLayoutStore.Layout("active.db", active), new WorkspaceLayoutStore.Layout("never-loaded.db", inactive)), journal, READER);
            check(1L, count(active)); check(1L, count(inactive));
            try (var row = active.rawQuery("SELECT _id FROM favorites", null)) { row.moveToFirst(); check(43L, row.getLong(0)); }
            try (var row = inactive.rawQuery("SELECT _id FROM favorites", null)) { row.moveToFirst(); check(45L, row.getLong(0)); }
            migrated.execSQL("CREATE TABLE favorites AS SELECT 0 AS _id, 0 AS profileId, '' AS intent, 0 AS itemType, 0 AS restored, '' AS title WHERE 0");
            migrated.execSQL("ATTACH DATABASE ? AS old", new Object[] {dir.resolve("never-loaded.db").toString()});
            migrated.execSQL("INSERT INTO favorites SELECT _id+100,profileId,intent,itemType,restored,title FROM old.favorites");
            check(1L, count(migrated));
            try (var row = migrated.rawQuery("SELECT _id FROM favorites", null)) { row.moveToFirst(); check(145L, row.getLong(0)); }
            check(false, journal.hasPendingRemoval(301, "pkg"));
        }
    }
    static void unreadableInactiveLayoutKeepsAuthority(Path dir) {
        try (var active = SQLiteDatabase.openDatabase(dir.resolve("partial-active.db").toString(), null, 0);
             var inactive = SQLiteDatabase.openDatabase(dir.resolve("partial-inactive.db").toString(), null, 0)) {
            seed(active);
            var journal = new ShortcutAvailabilityJournal(new ShortcutReviewRegressionTest.Disk());
            journal.packageRemoved(301, "pkg");
            var layouts = Arrays.asList(new WorkspaceLayoutStore.Layout("partial-active.db", active), new WorkspaceLayoutStore.Layout("partial-inactive.db", inactive));
            WorkspaceLayoutStore.reconcile(layouts, journal, READER);
            check(0L, count(active)); check(true, journal.hasPendingRemoval(301, "pkg"));
            seed(inactive); // The previously unreadable layout becomes readable with its original row.
            WorkspaceLayoutStore.reconcile(layouts, journal, READER);
            check(0L, count(inactive)); check(false, journal.hasPendingRemoval(301, "pkg"));
        }
    }
}
