/* Copyright (C) 2026 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0: http://www.apache.org/licenses/LICENSE-2.0 */
package com.android.launcher3.model;

import java.util.ArrayList;
import java.util.List;

/** Executable publication-boundary and blocked/deferred-executor races; platform consumers have separate integration tests. */
public final class WorkspaceDeliveryRegressionTest {
    static int checks;
    static void check(Object want, Object got) { checks++; if (!want.equals(got)) throw new AssertionError("expected " + want + ", got " + got); }
    public static void main(String[] args) {
        if (args.length == 0 || args[0].equals("2")) rejectedInsertCannotPublishOrAcknowledge();
        if (args.length == 0 || args[0].equals("3")) removalInvalidatesBlockedAndDeferredDelivery();
        System.out.println("PASS " + checks + " delivery boundary assertions (findings " + (args.length == 0 ? "2,3" : args[0]) + ")");
    }
    static void rejectedInsertCannotPublishOrAcknowledge() {
        List<Integer> bound = new ArrayList<>();
        List<String> queue = new ArrayList<>(); queue.add("request");
        int[] failed = {0};
        boolean committed = WorkspaceInsertCommit.execute(() -> -1, () -> bound.add(42), () -> failed[0]++);
        if (committed) queue.clear();
        check(0, bound.size()); check(1, queue.size()); check(1, failed[0]);
        committed = WorkspaceInsertCommit.execute(() -> 42, () -> bound.add(42), () -> failed[0]++);
        if (committed) queue.clear();
        check(1, bound.size()); check(0, queue.size()); check(1, failed[0]);
    }
    static void removalInvalidatesBlockedAndDeferredDelivery() {
        WorkspaceBindGuard guard = new WorkspaceBindGuard();
        long snapshot = guard.snapshot();
        List<Runnable> blockedUi = new ArrayList<>(), deferredPage = new ArrayList<>();
        List<Integer> bound = new ArrayList<>();
        blockedUi.add(guard.guard(snapshot, () -> bound.add(42)));
        deferredPage.add(guard.guard(snapshot, () -> bound.add(43)));
        guard.invalidate(); // Receipt occurs after scheduling, before either UI delivery.
        blockedUi.forEach(Runnable::run); deferredPage.forEach(Runnable::run);
        check(0, bound.size());
        guard.guard(guard.snapshot(), () -> bound.add(44)).run();
        check(1, bound.size()); check(44, bound.get(0));
    }
}
