/* Copyright (C) 2026 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0: http://www.apache.org/licenses/LICENSE-2.0 */
package android.database;

/** Host boundary only; query results come from a real SQLite JDBC connection. */
public interface Cursor extends AutoCloseable {
    boolean moveToNext();
    boolean moveToFirst();
    String getString(int column);
    long getLong(int column);
    void close();
}
