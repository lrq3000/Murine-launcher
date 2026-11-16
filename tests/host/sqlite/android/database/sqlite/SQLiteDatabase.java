/* Copyright (C) 2026 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0: http://www.apache.org/licenses/LICENSE-2.0 */
package android.database.sqlite;

import android.database.Cursor;
import java.sql.*;

/** Android SQLite API boundary backed by SQLite itself, not an in-memory mirror of launcher decisions. */
public final class SQLiteDatabase implements AutoCloseable {
    public static final int OPEN_READWRITE = 0;
    private final Connection connection;
    private SQLiteDatabase(String path) {
        try { connection = DriverManager.getConnection("jdbc:sqlite:" + path); }
        catch (SQLException e) { throw new IllegalStateException(e); }
    }
    public static SQLiteDatabase openDatabase(String path, Object factory, int flags) { return new SQLiteDatabase(path); }
    public void execSQL(String sql) { execSQL(sql, new Object[0]); }
    public void execSQL(String sql, Object[] args) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            statement.execute();
        } catch (SQLException e) { throw new IllegalStateException(e); }
    }
    public Cursor rawQuery(String sql, String[] args) {
        try {
            PreparedStatement statement = connection.prepareStatement(sql);
            if (args != null) for (int i = 0; i < args.length; i++) statement.setString(i + 1, args[i]);
            ResultSet result = statement.executeQuery();
            return new Cursor() {
                public boolean moveToNext() { try { return result.next(); } catch (SQLException e) { throw new IllegalStateException(e); } }
                public boolean moveToFirst() { return moveToNext(); }
                public String getString(int column) { try { return result.getString(column + 1); } catch (SQLException e) { throw new IllegalStateException(e); } }
                public long getLong(int column) { try { return result.getLong(column + 1); } catch (SQLException e) { throw new IllegalStateException(e); } }
                public void close() { try { result.close(); statement.close(); } catch (SQLException e) { throw new IllegalStateException(e); } }
            };
        } catch (SQLException e) { throw new IllegalStateException(e); }
    }
    public void close() { try { connection.close(); } catch (SQLException e) { throw new IllegalStateException(e); } }
}
