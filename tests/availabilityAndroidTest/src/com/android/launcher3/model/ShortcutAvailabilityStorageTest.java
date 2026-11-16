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
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import java.io.File;
import java.util.List;

/** Real Android persistence checks in the availability androidTest source set; no mocked database or AtomicFile. */
public class ShortcutAvailabilityStorageTest {
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
        assertTrue(journal.removed(7, "pkg", List.of(row)));
        journal.added(7, "pkg");
        var reopened = new ShortcutAvailabilityJournal(new AtomicAvailabilityPersistence(file));
        assertEquals(ShortcutAvailabilityJournal.Decision.REMOVE,
                reopened.evaluate(row, true, false, "internal", null));
    }
}
