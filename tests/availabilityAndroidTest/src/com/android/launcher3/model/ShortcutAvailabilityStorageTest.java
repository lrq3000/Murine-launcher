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

import static org.junit.Assert.*;

import android.database.sqlite.SQLiteDatabase;
import android.content.ContentValues;
import android.content.Intent;
import android.content.ComponentName;
import android.os.Process;
import com.android.launcher3.LauncherSettings.Favorites;
import com.android.launcher3.model.data.WorkspaceItemInfo;
import com.android.launcher3.util.ContentWriter;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import java.io.File;

/** Real Android persistence checks in the availability androidTest source set; no mocked database or AtomicFile. */
public class ShortcutAvailabilityStorageTest {
    @Test
    public void unavailablePositionWritePreservesLastValidAppearanceAndDisabledBits() {
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ContentValues values = new ContentValues();
        values.put(Favorites.TITLE, "Custom saved label");
        values.put(Favorites.ICON, new byte[] {1, 2, 3});
        WorkspaceItemInfo item = new WorkspaceItemInfo();
        item.id = 42;
        item.user = Process.myUserHandle();
        item.intent = new Intent(Intent.ACTION_MAIN).setComponent(new ComponentName("pkg", "pkg.Main"));
        item.title = "";
        item.runtimeStatusFlags = WorkspaceItemInfo.FLAG_DISABLED_NOT_AVAILABLE
                | WorkspaceItemInfo.FLAG_DISABLED_QUIET_USER | WorkspaceItemInfo.FLAG_DISABLED_SUSPENDED;
        int flags = item.runtimeStatusFlags;
        item.screenId = 3;
        item.cellX = 2;
        item.cellY = 1;
        item.container = Favorites.CONTAINER_DESKTOP;
        item.rank = 4;
        ContentWriter writer = new ContentWriter(values, context);
        item.onAddToDatabase(writer);
        ContentValues result = writer.getValues(context);
        assertEquals("Custom saved label", result.getAsString(Favorites.TITLE));
        assertArrayEquals(new byte[] {1, 2, 3}, result.getAsByteArray(Favorites.ICON));
        assertEquals(Integer.valueOf(3), result.getAsInteger(Favorites.SCREEN));
        assertEquals(Integer.valueOf(2), result.getAsInteger(Favorites.CELLX));
        assertEquals(Integer.valueOf(4), result.getAsInteger(Favorites.RANK));
        assertEquals(flags, item.runtimeStatusFlags);
        item.runtimeStatusFlags &= ~WorkspaceItemInfo.FLAG_DISABLED_NOT_AVAILABLE;
        item.bitmap = null;
        item.onAddToDatabase(writer);
        assertEquals("Custom saved label", writer.getValues(context).getAsString(Favorites.TITLE));
    }

    @Test
    public void databaseIdentityIsAdditiveAndChangesWithNewWorkspace() {
        try (SQLiteDatabase db = SQLiteDatabase.create(null)) {
            db.execSQL("CREATE TABLE favorites (_id INTEGER PRIMARY KEY, title TEXT)");
            db.execSQL("INSERT INTO favorites VALUES (42, 'Saved label')");
            String first = WorkspaceDatabaseIdentity.get(db);
            assertEquals(first, WorkspaceDatabaseIdentity.get(db));
            try (var cursor = db.rawQuery("SELECT title FROM favorites WHERE _id=42", null)) {
                assertTrue(cursor.moveToFirst());
                assertEquals("Saved label", cursor.getString(0));
            }
            WorkspaceDatabaseIdentity.reset(db);
            assertNotEquals(first, WorkspaceDatabaseIdentity.get(db));
        }
    }

    @Test
    public void atomicRemovalSurvivesProcessStyleReopenAndReinstall() {
        File file = new File(InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getCacheDir(), "availability-test-" + java.util.UUID.randomUUID());
        var journal = new ShortcutAvailabilityJournal(new AtomicAvailabilityPersistence(file));
        var row = new ShortcutAvailabilityJournal.Target("db-A", 42, 7, "pkg", "pkg/.Main");
        assertEquals(ShortcutAvailabilityJournal.Decision.PRESERVE,
                journal.evaluate(row, false, null, null, null));
        assertTrue(journal.removed(7, "pkg", java.util.Collections.singletonList(row)));
        journal.added(7, "pkg");
        var reopened = new ShortcutAvailabilityJournal(new AtomicAvailabilityPersistence(file));
        assertEquals(ShortcutAvailabilityJournal.Decision.REMOVE,
                reopened.evaluate(row, true, false, "internal", null));
    }
}
