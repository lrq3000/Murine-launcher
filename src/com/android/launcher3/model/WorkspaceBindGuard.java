/* Copyright (C) 2026 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0: http://www.apache.org/licenses/LICENSE-2.0 */
package com.android.launcher3.model;

/** Immutable bind snapshots are valid only until the next authoritative package-removal receipt. */
public final class WorkspaceBindGuard {
    private final java.util.concurrent.atomic.AtomicLong generation = new java.util.concurrent.atomic.AtomicLong();
    public long snapshot() { return generation.get(); }
    public void invalidate() { generation.incrementAndGet(); }
    public boolean isCurrent(long snapshot) { return generation.get() == snapshot; }
    public Runnable guard(long snapshot, Runnable delivery) {
        return () -> { if (isCurrent(snapshot)) delivery.run(); };
    }
}
