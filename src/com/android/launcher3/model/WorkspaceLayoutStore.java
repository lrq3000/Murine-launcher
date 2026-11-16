/* Copyright (C) 2026 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0: http://www.apache.org/licenses/LICENSE-2.0 */
package com.android.launcher3.model;

import android.database.sqlite.SQLiteDatabase;
import java.io.File;
import java.util.List;
import java.util.ArrayList;

/** SQLite reconciliation shared by active/inactive grids, migration preparation and explicit local restore. */
public final class WorkspaceLayoutStore {
    public record Component(String packageName, String name) { }
    public interface ComponentReader { Component read(String intent); }
    public record Layout(String filename, SQLiteDatabase database) { }
    private record Row(ShortcutAvailabilityJournal.Target target, String intent) { }
    private static List<Row> rows(Layout layout, ComponentReader reader) {
        String identity = layout.filename + ":" + WorkspaceDatabaseIdentity.get(layout.database);
        List<Row> rows = new ArrayList<>();
        try (var cursor = layout.database.rawQuery("SELECT _id,profileId,intent FROM favorites WHERE itemType=0 AND restored=0", null)) {
            while (cursor.moveToNext()) {
                String intent = cursor.getString(2);
                Component component = reader.read(intent);
                if (component != null) rows.add(new Row(new ShortcutAvailabilityJournal.Target(identity,
                        cursor.getLong(0), cursor.getLong(1), component.packageName, component.name), intent));
            }
        }
        return rows;
    }
    public static boolean reconcile(List<Layout> layouts, ShortcutAvailabilityJournal journal, ComponentReader reader) {
        return reconcile(layouts, journal, reader, true);
    }
    public static boolean reconcile(List<Layout> layouts, ShortcutAvailabilityJournal journal, ComponentReader reader, boolean complete) {
        List<List<Row>> snapshots = new ArrayList<>();
        for (Layout layout : layouts) {
            try {
                snapshots.add(rows(layout, reader));
            } catch (RuntimeException e) {
                snapshots.add(java.util.Collections.emptyList());
                complete = false; // Keep ordered package authority for the unreadable layout, without blocking readable grids.
            }
        }
        for (var removal : journal.pendingRemovals()) {
            List<ShortcutAvailabilityJournal.Target> targets = new ArrayList<>();
            for (List<Row> snapshot : snapshots) for (Row row : snapshot) {
                if (row.target.profile() == removal.profile() && row.target.packageName().equals(removal.packageName())
                        && journal.belongsToPendingRemoval(row.target)) targets.add(row.target);
            }
            // Do not consume the receipt until every existing layout has contributed its exact row snapshot.
            if (!journal.captureRemoval(removal.profile(), removal.packageName(), targets, complete)) {
                throw new IllegalStateException("Cannot migrate before confirmed removal snapshots are durable");
            }
        }
        for (int i = 0; i < layouts.size(); i++) for (Row row : snapshots.get(i)) {
            if (journal.isRemoved(row.target)) {
                layouts.get(i).database.execSQL("DELETE FROM favorites WHERE _id=? AND profileId=? AND intent=?",
                        new Object[] {row.target.id(), row.target.profile(), row.intent});
            }
        }
        return complete;
    }
    public static void prepareRestoredDatabase(File file, ShortcutAvailabilityJournal journal, ComponentReader reader) {
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READWRITE)) {
            WorkspaceDatabaseIdentity.reset(db);
            // Restored rows are new explicit user intent, not survivors of an old installation/removal generation.
            for (Row row : rows(new Layout(file.getName(), db), reader)) {
                if (!journal.created(row.target)) throw new IllegalStateException("Cannot fence restored workspace incarnation");
            }
        }
    }
}
