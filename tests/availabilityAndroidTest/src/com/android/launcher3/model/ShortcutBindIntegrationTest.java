/* Copyright (C) 2026 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0: http://www.apache.org/licenses/LICENSE-2.0 */
package com.android.launcher3.model;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;
import android.content.Context;
import android.content.Intent;
import android.content.ComponentName;
import android.os.Process;
import androidx.test.platform.app.InstrumentationRegistry;
import com.android.launcher3.InvariantDeviceProfile;
import com.android.launcher3.LauncherAppState;
import com.android.launcher3.LauncherSettings.Favorites;
import com.android.launcher3.model.data.ItemInfo;
import com.android.launcher3.model.data.WorkspaceItemInfo;
import com.android.launcher3.util.Executors;
import com.android.launcher3.util.IntArray;
import com.android.launcher3.util.IntSet;
import com.android.launcher3.util.RunnableList;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/** Real BaseLauncherBinder copies/chunks/deferred tasks, with deterministic delayed UI delivery. */
public class ShortcutBindIntegrationTest {
    static final class Callbacks implements BgDataModel.Callbacks {
        final List<Integer> ids = new ArrayList<>();
        RunnableList pending;
        public IntSet getPagesToBindSynchronously(IntArray screens) { return IntSet.wrap(0); }
        public void bindItems(List<ItemInfo> items, boolean animate) { for (ItemInfo item : items) ids.add(item.id); }
        public void onInitialBindComplete(IntSet pages, RunnableList pending, RunnableList complete, int count, boolean sync) { this.pending = pending; }
        public void bindWorkspaceComponentsRemoved(java.util.function.Predicate<ItemInfo> matcher) { ids.clear(); }
    }
    private static WorkspaceItemInfo row(int id, int screen) {
        WorkspaceItemInfo item = new WorkspaceItemInfo();
        item.id = id; item.user = Process.myUserHandle(); item.screenId = screen;
        item.container = Favorites.CONTAINER_DESKTOP;
        item.intent = new Intent().setComponent(new ComponentName("binding.integration", "binding.integration.Main"));
        return item;
    }
    @Test
    public void authoritativeRemovalInvalidatesAlreadyScheduledAndDeferredPages() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        LauncherAppState app = Executors.MAIN_EXECUTOR.submit(() -> LauncherAppState.getInstance(context)).get();
        InvariantDeviceProfile idp = Executors.MAIN_EXECUTOR.submit(() -> InvariantDeviceProfile.INSTANCE.get(context)).get();
        BgDataModel data = new BgDataModel(null);
        data.addItem(context, row(42, 0), false); data.addItem(context, row(43, 1), false);
        Callbacks callbacks = new Callbacks();
        BaseLauncherBinder binder = new BaseLauncherBinder(context, idp, app.getModel(), data, mock(AllAppsList.class), new BgDataModel.Callbacks[] {callbacks});
        Executors.MODEL_EXECUTOR.submit(() -> binder.bindWorkspace(true, false)).get();
        Executors.MAIN_EXECUTOR.submit(() -> { }).get();
        assertTrue(callbacks.ids.contains(42));
        assertNotNull(callbacks.pending);
        app.getModel().getWorkspaceAvailability().recordEvent(PackageUpdatedTask.OP_REMOVE, Process.myUserHandle(), new String[] {"binding.integration"});
        Executors.MAIN_EXECUTOR.submit(() -> {
            callbacks.bindWorkspaceComponentsRemoved(item -> true);
            callbacks.pending.executeAllAndDestroy();
        }).get();
        assertTrue(callbacks.ids.isEmpty());

        CountDownLatch blocked = new CountDownLatch(1), release = new CountDownLatch(1);
        Executors.MAIN_EXECUTOR.execute(() -> {
            blocked.countDown();
            try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        assertTrue(blocked.await(10, TimeUnit.SECONDS));
        try {
            Executors.MODEL_EXECUTOR.submit(() -> binder.bindWorkspace(true, false)).get();
            app.getModel().getWorkspaceAvailability().recordEvent(PackageUpdatedTask.OP_REMOVE, Process.myUserHandle(), new String[] {"binding.integration"});
        } finally { release.countDown(); }
        Executors.MAIN_EXECUTOR.submit(() -> { }).get();
        assertTrue(callbacks.ids.isEmpty());
    }
}
