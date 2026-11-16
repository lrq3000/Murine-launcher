/* Copyright (C) 2026 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0: http://www.apache.org/licenses/LICENSE-2.0 */
package com.android.launcher3.model;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import android.content.Context;
import android.content.Intent;
import android.content.ComponentName;
import android.os.Process;
import android.util.Pair;
import androidx.test.platform.app.InstrumentationRegistry;
import com.android.launcher3.LauncherAppState;
import com.android.launcher3.LauncherModel;
import com.android.launcher3.LauncherSettings.Favorites;
import com.android.launcher3.model.data.ItemInfo;
import com.android.launcher3.model.data.WorkspaceItemInfo;
import com.android.launcher3.pm.UserCache;
import com.android.launcher3.util.Executors;
import com.android.launcher3.util.IntArray;
import com.android.launcher3.util.IntSet;
import java.io.IOException;
import java.util.Collections;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/** Real ModelWriter, ModelDbController, SQLite and AddWorkspaceItemsTask; only the storage failure and placement are injected. */
public class ShortcutInsertionIntegrationTest {
    @Test
    public void rejectedFenceDoesNotBindOrAddGhostAndRetryCommitsBeforeSuccess() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        LauncherAppState app = Executors.MAIN_EXECUTOR.submit(() -> LauncherAppState.getInstance(context)).get();
        LauncherModel model = app.getModel();
        String pkg = "insertion.integration.missing";
        AtomicInteger bound = new AtomicInteger(), failures = new AtomicInteger();
        BgDataModel.Callbacks callbacks = new BgDataModel.Callbacks() {
            public IntSet getPagesToBindSynchronously(IntArray screens) { return IntSet.wrap(0); }
            public void bindItemInsertFailed() { failures.incrementAndGet(); }
            public void bindAppsAdded(IntArray screens, ArrayList<ItemInfo> plain, ArrayList<ItemInfo> animated) {
                for (ItemInfo item : plain) if (pkg.equals(item.getTargetPackage())) bound.incrementAndGet();
                for (ItemInfo item : animated) if (pkg.equals(item.getTargetPackage())) bound.incrementAndGet();
            }
        };
        Executors.MAIN_EXECUTOR.submit(() -> model.addCallbacks(callbacks)).get();
        final class Disk implements ShortcutAvailabilityJournal.Persistence {
            byte[] bytes; boolean fail;
            public byte[] read() { return bytes; }
            public void write(byte[] value) throws IOException { if (fail) throw new IOException("fence failed"); bytes = value.clone(); }
        }
        Disk disk = new Disk();
        WorkspaceAvailability original = model.getWorkspaceAvailability();
        ModelDbController database = model.getModelDbController();
        WorkspaceItemInfo item = new WorkspaceItemInfo();
        item.user = Process.myUserHandle(); item.title = "New shortcut";
        item.intent = new Intent(Intent.ACTION_MAIN).setComponent(new ComponentName(pkg, pkg + ".Main"));
        item.bitmap = app.getIconCache().getDefaultIcon(item.user);
        try {
            Executors.MODEL_EXECUTOR.submit(() -> {
                WorkspaceAvailability availability = new WorkspaceAvailability(context, database, UserCache.INSTANCE.get(context), disk);
                availability.recordEvent(PackageUpdatedTask.OP_REMOVE, item.user, new String[] {pkg});
                disk.fail = true;
                WorkspaceItemSpaceFinder finder = mock(WorkspaceItemSpaceFinder.class);
                when(finder.findSpaceForItem(any(), any(), anyInt(), anyInt())).thenReturn(new int[] {19, 0, 0});
                ModelTaskController controller = new ModelTaskController(context, app.getIconCache(), model.getBgDataModel(), mock(AllAppsList.class), model);
                AtomicBoolean committed = new AtomicBoolean(true);
                new AddWorkspaceItemsTask(Collections.singletonList(Pair.create((ItemInfo) item, new Object())), finder, committed::set)
                        .execute(controller, model.getBgDataModel(), controller.getAllAppsList());
                assertFalse(committed.get()); assertEquals(ItemInfo.NO_ID, item.id);
                assertFalse(model.getBgDataModel().itemsIdMap.stream().anyMatch(candidate -> candidate == item));
            }).get();
            Executors.MAIN_EXECUTOR.submit(() -> { }).get();
            assertEquals(0, bound.get()); assertTrue(failures.get() > 0);
            Executors.MODEL_EXECUTOR.submit(() -> {
                disk.fail = false;
                WorkspaceItemSpaceFinder finder = mock(WorkspaceItemSpaceFinder.class);
                when(finder.findSpaceForItem(any(), any(), anyInt(), anyInt())).thenReturn(new int[] {19, 0, 0});
                ModelTaskController controller = new ModelTaskController(context, app.getIconCache(), model.getBgDataModel(), mock(AllAppsList.class), model);
                AtomicBoolean committed = new AtomicBoolean(false);
                new AddWorkspaceItemsTask(Collections.singletonList(Pair.create((ItemInfo) item, new Object())), finder, committed::set)
                        .execute(controller, model.getBgDataModel(), controller.getAllAppsList());
                assertTrue(committed.get());
                try (var row = database.query(new String[] {Favorites._ID}, Favorites._ID + "=?", new String[] {Integer.toString(item.id)}, null)) {
                    assertTrue(row.moveToFirst());
                }
                assertSame(item, model.getBgDataModel().itemsIdMap.get(item.id));
            }).get();
            Executors.MAIN_EXECUTOR.submit(() -> { }).get();
            assertEquals(1, bound.get());
        } finally {
            Executors.MODEL_EXECUTOR.submit(() -> {
                database.setWorkspaceAvailability(original);
                if (item.id != ItemInfo.NO_ID) { database.delete(Favorites._ID + "=?", new String[] {Integer.toString(item.id)}); model.getBgDataModel().removeItem(context, item); }
            }).get();
            Executors.MAIN_EXECUTOR.submit(() -> model.removeCallbacks(callbacks)).get();
        }
    }
}
