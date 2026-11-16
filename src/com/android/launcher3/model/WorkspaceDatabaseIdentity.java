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

import android.database.sqlite.SQLiteDatabase;
import java.util.UUID;

/** Additive, idempotent metadata: no favorites migration, schema-version bump or destructive fallback. */
final class WorkspaceDatabaseIdentity {
    private static final java.util.concurrent.atomic.AtomicLong EPOCH = new java.util.concurrent.atomic.AtomicLong();
    static long epoch() { return EPOCH.get(); }
    private static final String TABLE = "shortcut_availability_identity";

    static String get(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE + " (_id INTEGER PRIMARY KEY CHECK (_id=1), token TEXT NOT NULL)");
        db.execSQL("INSERT OR IGNORE INTO " + TABLE + " VALUES (1, ?)", new Object[] {UUID.randomUUID().toString()});
        try (var cursor = db.rawQuery("SELECT token FROM " + TABLE + " WHERE _id=1", null)) {
            if (!cursor.moveToFirst()) throw new IllegalStateException("Workspace identity unavailable");
            return cursor.getString(0);
        }
    }

    static void reset(SQLiteDatabase db) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE);
        EPOCH.incrementAndGet();
    }
}
