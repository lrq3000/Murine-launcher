/* Copyright (C) 2026 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0: http://www.apache.org/licenses/LICENSE-2.0 */
package com.android.launcher3.model;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ContentValues;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherApps;
import android.content.pm.LauncherActivityInfo;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.os.Process;
import android.os.UserManager;
import androidx.test.platform.app.InstrumentationRegistry;
import com.android.launcher3.LauncherAppState;
import com.android.launcher3.InvariantDeviceProfile;
import com.android.launcher3.LauncherSettings.Favorites;
import com.android.launcher3.icons.BitmapInfo;
import com.android.launcher3.icons.GraphicsUtils;
import com.android.launcher3.model.data.WorkspaceItemInfo;
import com.android.launcher3.pm.UserCache;
import com.android.launcher3.util.Executors;
import java.io.File;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/** Android-boundary regressions: real journal/adapter, real saved bitmap, and real async icon-cache request. */
public class ShortcutCallbackIntegrationTest {
    @Test
    public void removalBeforeUserCacheInitializationUsesStableSystemSerial() {
        Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
        UserManager manager = mock(UserManager.class);
        when(manager.getSerialNumberForUser(Process.myUserHandle())).thenReturn(301L);
        UserCache staleCache = mock(UserCache.class);
        when(staleCache.getSerialNumberForUser(any())).thenReturn(10L);
        File directory = new File(base.getCacheDir(), "ingress-" + UUID.randomUUID());
        assertTrue(directory.mkdirs());
        Context context = new ContextWrapper(base) {
            @Override public Object getSystemService(String name) {
                return USER_SERVICE.equals(name) ? manager : super.getSystemService(name);
            }
            @Override public File getNoBackupFilesDir() { return directory; }
        };
        WorkspaceAvailability availability = new WorkspaceAvailability(context, mock(ModelDbController.class), staleCache);
        availability.recordEvent(PackageUpdatedTask.OP_REMOVE, Process.myUserHandle(), new String[] {"pkg"});
        var reopened = new ShortcutAvailabilityJournal(new AtomicAvailabilityPersistence(new File(directory, "workspace-availability-v1")));
        assertTrue(reopened.hasPendingRemoval(301, "pkg"));
        assertFalse(reopened.hasPendingRemoval(10, "pkg"));
        when(staleCache.getSerialNumberForUser(any())).thenReturn(301L);
        when(manager.getSerialNumberForUser(Process.myUserHandle())).thenReturn(401L);
        availability.recordEvent(PackageUpdatedTask.OP_REMOVE, Process.myUserHandle(), new String[] {"new.profile.pkg"});
        reopened = new ShortcutAvailabilityJournal(new AtomicAvailabilityPersistence(new File(directory, "workspace-availability-v1")));
        assertTrue(reopened.hasPendingRemoval(401, "new.profile.pkg"));
        assertFalse(reopened.hasPendingRemoval(301, "new.profile.pkg"));
    }

    @Test
    public void missingActivityRebuildsSuspensionQuietAndLockWithoutErasingOtherDisabledBits() throws Exception {
        Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
        UserManager users = mock(UserManager.class);
        when(users.getSerialNumberForUser(any())).thenReturn(301L);
        when(users.isQuietModeEnabled(any())).thenReturn(true);
        when(users.isUserUnlocked(any())).thenReturn(false);
        LauncherApps apps = mock(LauncherApps.class);
        ApplicationInfo application = new ApplicationInfo();
        application.flags = ApplicationInfo.FLAG_SUSPENDED;
        when(apps.getApplicationInfo(anyString(), anyInt(), any())).thenReturn(application);
        File directory = new File(base.getCacheDir(), "flags-" + UUID.randomUUID());
        assertTrue(directory.mkdirs());
        Context context = new ContextWrapper(base) {
            @Override public Object getSystemService(String name) {
                if (USER_SERVICE.equals(name)) return users;
                if (LAUNCHER_APPS_SERVICE.equals(name)) return apps;
                return super.getSystemService(name);
            }
            @Override public File getNoBackupFilesDir() { return directory; }
        };
        ModelDbController database = mock(ModelDbController.class);
        when(database.getWorkspaceIdentity()).thenReturn("flags-db");
        WorkspaceAvailability availability = new WorkspaceAvailability(context, database, mock(UserCache.class));
        LauncherAppState state = Executors.MAIN_EXECUTOR.submit(() -> LauncherAppState.getInstance(base)).get();
        InvariantDeviceProfile idp = Executors.MAIN_EXECUTOR.submit(() -> InvariantDeviceProfile.INSTANCE.get(base)).get();
        // LoaderCursor and recovery use the real icon cache, which is confined to the model worker in production.
        Executors.MODEL_EXECUTOR.submit(() -> {
            Intent intent = new Intent().setComponent(new ComponentName("pkg", "pkg.Main"));
            String[] columns = {Favorites._ID, Favorites.CONTAINER, Favorites.ITEM_TYPE, Favorites.SCREEN,
                    Favorites.CELLX, Favorites.CELLY, Favorites.PROFILE_ID, Favorites.RESTORED, Favorites.INTENT,
                    Favorites.APPWIDGET_ID, Favorites.APPWIDGET_PROVIDER, Favorites.SPANX, Favorites.SPANY,
                    Favorites.RANK, Favorites.OPTIONS, Favorites.APPWIDGET_SOURCE, Favorites.TITLE, Favorites.ICON};
            Object[] values = new Object[columns.length];
            java.util.Arrays.fill(values, 0);
            values[0] = 42; values[1] = Favorites.CONTAINER_DESKTOP; values[6] = 301;
            values[8] = intent.toUri(0); values[10] = null; values[16] = "Saved title"; values[17] = null;
            MatrixCursor saved = new MatrixCursor(columns); saved.addRow(values);
            UserManagerState userState = new UserManagerState(); userState.allUsers.put(301, Process.myUserHandle());
            WorkspaceItemInfo item;
            try (LoaderCursor cursor = new LoaderCursor(saved, userState, null, context, state.getIconCache(), idp,
                    state.getModel(), com.android.launcher3.util.PackageManagerHelper.INSTANCE.get(base), availability)) {
                assertTrue(cursor.moveToNext());
                item = cursor.loadUnavailableApplication(cursor.parseIntent());
            }
            // Recreated from the saved row, not a pre-populated runtime flag snapshot.
            item.runtimeStatusFlags |= WorkspaceItemInfo.FLAG_DISABLED_NOT_AVAILABLE | WorkspaceItemInfo.FLAG_DISABLED_BY_PUBLISHER;
            int independent = WorkspaceItemInfo.FLAG_DISABLED_SUSPENDED | WorkspaceItemInfo.FLAG_DISABLED_QUIET_USER
                    | WorkspaceItemInfo.FLAG_DISABLED_LOCKED_USER | WorkspaceItemInfo.FLAG_DISABLED_BY_PUBLISHER;
            assertEquals(independent, item.runtimeStatusFlags & independent);
            LauncherActivityInfo recovered = mock(LauncherActivityInfo.class);
            application.flags |= ApplicationInfo.FLAG_INSTALLED;
            when(recovered.getApplicationInfo()).thenReturn(application);
            when(recovered.getComponentName()).thenReturn(intent.getComponent());
            when(apps.isPackageEnabled(anyString(), any())).thenReturn(true);
            when(apps.isActivityEnabled(any(), any())).thenReturn(true);
            when(apps.resolveActivity(any(), any())).thenReturn(recovered);
            availability.refreshAppearance(item, state.getIconCache());
            assertEquals(ShortcutAvailabilityJournal.Decision.AVAILABLE, availability.evaluate(item));
            item.runtimeStatusFlags &= ~WorkspaceItemInfo.FLAG_DISABLED_NOT_AVAILABLE;
            assertEquals(independent, item.runtimeStatusFlags & independent);
        }).get();
    }

    @Test
    public void unavailableLowResFolderChildSurvivesEvictionAndRealAsyncHighResRequest() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        LauncherAppState app = Executors.MAIN_EXECUTOR.submit(() -> LauncherAppState.getInstance(context)).get();
        WorkspaceItemInfo item = new WorkspaceItemInfo();
        item.user = Process.myUserHandle();
        String pkg = "missing.shortcut.integration";
        item.intent = new Intent(Intent.ACTION_MAIN).setComponent(new ComponentName(pkg, pkg + ".Main"));
        Bitmap image = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888);
        image.eraseColor(0xff19aa42);
        byte[] saved = GraphicsUtils.flattenBitmap(image);
        Executors.MODEL_EXECUTOR.submit(() -> {
            ModelDbController db = app.getModel().getModelDbController();
            item.id = db.generateNewItemId();
            ContentValues values = new ContentValues();
            values.put(Favorites._ID, item.id);
            values.put(Favorites.ITEM_TYPE, Favorites.ITEM_TYPE_APPLICATION);
            values.put(Favorites.PROFILE_ID, context.getSystemService(UserManager.class).getSerialNumberForUser(item.user));
            values.put(Favorites.INTENT, item.intent.toUri(0));
            values.put(Favorites.TITLE, "Saved custom label");
            values.put(Favorites.ICON, saved);
            assertTrue(db.insert(values) >= 0);
            app.getIconCache().removeIconsForPkg(pkg, item.user);
        }).get();
        item.bitmap = BitmapInfo.LOW_RES_INFO;
        item.title = "Saved custom label";
        item.runtimeStatusFlags = WorkspaceItemInfo.FLAG_DISABLED_NOT_AVAILABLE;
        CountDownLatch done = new CountDownLatch(1);
        app.getIconCache().updateIconInBackground(info -> done.countDown(), item);
        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertEquals("Saved custom label", item.title.toString());
        assertFalse(item.bitmap.isNullOrLowRes());
        assertEquals(0xff19aa42, item.bitmap.icon.getPixel(16, 16));
    }
}
