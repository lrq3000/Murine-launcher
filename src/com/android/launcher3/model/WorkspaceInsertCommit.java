/* Copyright (C) 2026 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0: http://www.apache.org/licenses/LICENSE-2.0 */
package com.android.launcher3.model;

import java.util.function.IntSupplier;

/** The database result, not optimistic placement, is the publication/acknowledgement boundary. */
public final class WorkspaceInsertCommit {
    public static boolean execute(IntSupplier insert, Runnable publish, Runnable rejected) {
        if (insert.getAsInt() < 0) {
            rejected.run();
            return false;
        }
        publish.run();
        return true;
    }
}
