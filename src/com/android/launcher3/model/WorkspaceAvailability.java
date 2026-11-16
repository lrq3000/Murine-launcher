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

import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.database.sqlite.SQLiteDatabase;
import com.android.launcher3.LauncherFiles;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.text.TextUtils;
import android.util.Log;

import com.android.launcher3.LauncherSettings.Favorites;
import com.android.launcher3.dagger.ApplicationContext;
import com.android.launcher3.dagger.LauncherAppSingleton;
import com.android.launcher3.icons.IconCache;
import com.android.launcher3.icons.GraphicsUtils;
import com.android.launcher3.icons.BitmapInfo;
import com.android.launcher3.model.ShortcutAvailabilityJournal.Decision;
import com.android.launcher3.model.ShortcutAvailabilityJournal.Target;
import com.android.launcher3.model.data.CollectionInfo;
import com.android.launcher3.model.data.ItemInfo;
import com.android.launcher3.model.data.ItemInfoWithIcon;
import com.android.launcher3.model.data.IconRequestInfo;
import com.android.launcher3.model.data.WorkspaceItemInfo;
import com.android.launcher3.pm.UserCache;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.Arrays;
import javax.inject.Inject;

/** Model-owned Android boundary for exact-target queries and the device-local durable journal. */
@LauncherAppSingleton
public final class WorkspaceAvailability {
    private final Context mContext;
    private final ModelDbController mDatabase;
    private final ShortcutAvailabilityJournal.Persistence mPersistenceOverride;
    private ShortcutAvailabilityJournal mJournal;
    private static final java.util.concurrent.atomic.AtomicLong RESTORE_EPOCH = new java.util.concurrent.atomic.AtomicLong();
    private long mRestoreEpoch = -1;
    private boolean mLayoutsReconciled;
    private final WorkspaceBindGuard mBindGuard = new WorkspaceBindGuard();
    public WorkspaceBindGuard getBindGuard() { return mBindGuard; }

    @Inject
    public WorkspaceAvailability(@ApplicationContext Context context, ModelDbController database,
            UserCache users) {
        this(context, database, users, null);
    }

    WorkspaceAvailability(Context context, ModelDbController database, UserCache users,
            ShortcutAvailabilityJournal.Persistence persistence) {
        mContext = context;
        mDatabase = database;
        mPersistenceOverride = persistence;
        database.setWorkspaceAvailability(this);
    }

    private synchronized ShortcutAvailabilityJournal journal() {
        if (mJournal == null || mRestoreEpoch != RESTORE_EPOCH.get()) {
            // Do not back up device-specific storage knowledge or pending system events to another device.
            mJournal = new ShortcutAvailabilityJournal(mPersistenceOverride != null ? mPersistenceOverride
                    : new AtomicAvailabilityPersistence(new File(mContext.getNoBackupFilesDir(), "workspace-availability-v1")));
            mRestoreEpoch = RESTORE_EPOCH.get();
            mLayoutsReconciled = false;
        }
        return mJournal;
    }

    public void beginLoad() {
        mLayoutsReconciled = false;
        journal().beginBatch();
    }

    public void endLoad() {
        journal().endBatch();
    }

    /** Invoked on MODEL_EXECUTOR before inserting a new favorites row, not after a crash-sensitive database commit. */
    public synchronized boolean beforeInsert(ContentValues values) {
        Integer type = values.getAsInteger(Favorites.ITEM_TYPE);
        Long id = values.getAsLong(Favorites._ID);
        Long profile = values.getAsLong(Favorites.PROFILE_ID);
        if (type == null || type != Favorites.ITEM_TYPE_APPLICATION || id == null || profile == null) return true;
        ComponentName component;
        try {
            component = Intent.parseUri(values.getAsString(Favorites.INTENT), 0).getComponent();
        } catch (Exception e) {
            return true;
        }
        if (component == null) return true;
        try (var cursor = mDatabase.query(new String[] {Favorites._ID}, Favorites._ID + "=?", new String[] {id.toString()}, null)) {
            // A failed duplicate-ID insertion must not retire a live row's uninstall tombstone.
            if (cursor.moveToFirst()) return true;
        }
        return journal().created(new Target(mDatabase.getWorkspaceIdentity(), id, profile,
                component.getPackageName(), component.flattenToString()));
    }

    /** Called off-UI at callback receipt, before a model task can be discarded or a running loader can bind. */
    public synchronized void recordEvent(int operation, UserHandle user, String[] packages) {
        // UserCache deliberately falls back to the handle ID before its model-thread refresh; that is not a stable serial.
        long serial = mContext.getSystemService(UserManager.class).getSerialNumberForUser(user);
        if (serial < 0) return;
        if (operation == PackageUpdatedTask.OP_REMOVE) mBindGuard.invalidate();
        for (String pkg : packages) {
            switch (operation) {
                case PackageUpdatedTask.OP_REMOVE -> journal().packageRemoved(serial, pkg);
                case PackageUpdatedTask.OP_UNAVAILABLE -> journal().unavailable(serial, pkg);
                case PackageUpdatedTask.OP_ADD -> journal().added(serial, pkg);
                default -> { }
            }
        }
    }

    /** Snapshot only ordinary rows belonging to this confirmed uninstall; ADD never cancels their removal. */
    public synchronized void reconcilePendingRemovals() {
        ShortcutAvailabilityJournal store = journal();
        if (mLayoutsReconciled && store.pendingRemovals().isEmpty()) return;
        Set<File> files = new HashSet<>();
        for (String name : LauncherFiles.GRID_DB_FILES) {
            File file = mContext.getDatabasePath(name);
            if (file.exists()) files.add(file);
        }
        File active = mDatabase.getWorkspaceDatabaseFile();
        if (active != null && active.exists()) files.add(active);
        List<WorkspaceLayoutStore.Layout> layouts = new ArrayList<>();
        boolean complete = true;
        try {
            for (File file : files) {
                try {
                    layouts.add(new WorkspaceLayoutStore.Layout(file.getName(),
                            SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READWRITE)));
                } catch (RuntimeException e) {
                    complete = false;
                    Log.w("WorkspaceAvailability", "Retaining removal receipt for unreadable layout " + file.getName(), e);
                }
            }
            mLayoutsReconciled = WorkspaceLayoutStore.reconcile(layouts, store, WorkspaceAvailability::parseComponent, complete);
        } finally {
            for (var layout : layouts) layout.database().close();
        }
    }

    public static WorkspaceLayoutStore.Component parseComponent(String uri) {
        try {
            ComponentName component = Intent.parseUri(uri, 0).getComponent();
            return component == null ? null : new WorkspaceLayoutStore.Component(component.getPackageName(), component.flattenToString());
        } catch (Exception e) {
            return null; // Corrupt intents retain their dedicated loader validation.
        }
    }

    /** Called on staged files before replacing live databases, while local restore still has no model owner. */
    public static void prepareRestoredDatabase(Context context, File file) {
        ShortcutAvailabilityJournal store = new ShortcutAvailabilityJournal(new AtomicAvailabilityPersistence(
                new File(context.getNoBackupFilesDir(), "workspace-availability-v1")));
        WorkspaceLayoutStore.prepareRestoredDatabase(file, store, WorkspaceAvailability::parseComponent);
        RESTORE_EPOCH.incrementAndGet();
    }

    /** Call before a loader or install queue can reuse old row IDs after a completed uninstall. */
    public synchronized void retireAbsentRows() {
        try {
            Set<Target> present = new HashSet<>();
            String database = mDatabase.getWorkspaceIdentity();
            try (var cursor = mDatabase.query(new String[] {Favorites._ID, Favorites.PROFILE_ID, Favorites.INTENT},
                    Favorites.ITEM_TYPE + "=?", new String[] {Integer.toString(Favorites.ITEM_TYPE_APPLICATION)}, null)) {
                while (cursor.moveToNext()) {
                    try {
                        ComponentName component = Intent.parseUri(cursor.getString(2), 0).getComponent();
                        if (component != null) present.add(new Target(database, cursor.getLong(0), cursor.getLong(1),
                                component.getPackageName(), component.flattenToString()));
                    } catch (Exception e) {
                        Log.d("WorkspaceAvailability", "Corrupt row is not recovery metadata", e);
                    }
                }
            }
            journal().retireAbsent(database, present);
        } catch (RuntimeException e) {
            Log.w("WorkspaceAvailability", "Metadata retirement deferred", e);
        }
    }

    public synchronized Decision evaluate(long id, long serial, Intent intent, UserHandle user) {
        ComponentName component = intent.getComponent();
        if (component == null) return Decision.NORMAL;
        Target target;
        try {
            target = new Target(mDatabase.getWorkspaceIdentity(), id, serial,
                    component.getPackageName(), component.flattenToString());
        } catch (RuntimeException e) {
            Log.e("WorkspaceAvailability", "Cannot establish workspace identity; retaining shortcut", e);
            return Decision.PRESERVE;
        }
        LauncherActivityInfo activity = null;
        ApplicationInfo application = null;
        try {
            LauncherApps apps = mContext.getSystemService(LauncherApps.class);
            Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(component);
            if (apps.isPackageEnabled(component.getPackageName(), user) && apps.isActivityEnabled(component, user)) {
                activity = apps.resolveActivity(query, user);
                if (activity != null && !component.equals(activity.getComponentName())) activity = null;
            }
            if (activity != null) {
                application = activity.getApplicationInfo();
            } else {
                try {
                    application = apps.getApplicationInfo(component.getPackageName(), PackageManager.MATCH_UNINSTALLED_PACKAGES, user);
                } catch (PackageManager.NameNotFoundException e) {
                    // Absence cannot classify a formerly unknown package as internal.
                }
            }
            UserManager users = mContext.getSystemService(UserManager.class);
            if (activity == null && (!users.isUserUnlocked(user) || users.isQuietModeEnabled(user))) {
                journal().unavailable(serial, component.getPackageName());
            }
        } catch (RuntimeException e) {
            // A service/security failure is not an authoritative negative result, even for a previously internal app.
            journal().unavailable(serial, component.getPackageName());
            Log.w("WorkspaceAvailability", "Target query failed; retaining shortcut", e);
        }
        Boolean external = null;
        String uuid = null;
        String publicUuid = null;
        if (application != null) {
            ApplicationInfo app = application;
            external = (app.flags & ApplicationInfo.FLAG_EXTERNAL_STORAGE) != 0
                    || app.storageUuid != null && !StorageManager.UUID_DEFAULT.equals(app.storageUuid);
            uuid = app.storageUuid == null ? null : app.storageUuid.toString();
            if (uuid != null && external) {
                try {
                    // API 29 has no reliable general adopted/private-to-public mapping. Record only an exact exposed UUID match.
                    for (StorageVolume volume : mContext.getSystemService(StorageManager.class).getStorageVolumes()) {
                        if (uuid.equalsIgnoreCase(volume.getUuid()) && Environment.MEDIA_MOUNTED.equals(volume.getState())) {
                            publicUuid = volume.getUuid();
                            break;
                        }
                    }
                } catch (RuntimeException e) {
                    Log.d("WorkspaceAvailability", "Public storage mapping unresolved", e);
                }
            }
        }
        return journal().evaluate(target, activity != null, external, uuid, publicUuid);
    }

    public Decision evaluate(WorkspaceItemInfo item) {
        return evaluate(item.id, mContext.getSystemService(UserManager.class).getSerialNumberForUser(item.user), item.intent, item.user);
    }

    public synchronized boolean isConfirmedRemoved(ItemInfo item) {
        ComponentName component = item.getTargetComponent();
        if (!(item instanceof WorkspaceItemInfo workspace) || workspace.status != 0
                || item.itemType != Favorites.ITEM_TYPE_APPLICATION || component == null) return false;
        try {
            return journal().isRemoved(new Target(mDatabase.getWorkspaceIdentity(), item.id,
                    mContext.getSystemService(UserManager.class).getSerialNumberForUser(item.user), component.getPackageName(), component.flattenToString()));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Receipt and final bind have one linearization lock, but database/model work stays on MODEL_EXECUTOR. */
    public synchronized void reconcileBeforeBind(BgDataModel model, IconCache icons, Runnable bind) {
        reconcilePendingRemovals();
        List<ItemInfo> removed = new ArrayList<>();
        for (ItemInfo item : model.itemsIdMap) {
            if (isConfirmedRemoved(item)) {
                mDatabase.delete(Favorites._ID + "=?", new String[] {Integer.toString(item.id)});
                removed.add(item);
            } else if (item instanceof WorkspaceItemInfo workspace
                    && workspace.itemType == Favorites.ITEM_TYPE_APPLICATION && workspace.status == 0 && !workspace.isArchived()) {
                Decision decision = evaluate(workspace);
                if (decision == Decision.PRESERVE) {
                    workspace.runtimeStatusFlags |= ItemInfoWithIcon.FLAG_DISABLED_NOT_AVAILABLE;
                    hydrateUnavailable(workspace);
                }
                if (decision == Decision.AVAILABLE
                        && (workspace.runtimeStatusFlags & ItemInfoWithIcon.FLAG_DISABLED_NOT_AVAILABLE) != 0) {
                    refreshAppearance(workspace, icons);
                    if (evaluate(workspace) == Decision.AVAILABLE) {
                        workspace.runtimeStatusFlags &= ~ItemInfoWithIcon.FLAG_DISABLED_NOT_AVAILABLE;
                    }
                }
            }
        }
        for (ItemInfo item : model.itemsIdMap) {
            if (item instanceof CollectionInfo collection) collection.getContents().removeAll(removed);
        }
        model.removeItem(mContext, removed);
        retireAbsentRows();
        bind.run();
    }

    public boolean hasWorkspacePackage(UserHandle user, String pkg) {
        if (pkg == null) return false;
        try (var cursor = mDatabase.query(new String[] {Favorites.INTENT}, Favorites.PROFILE_ID + "=? AND " + Favorites.ITEM_TYPE + "=?",
                new String[] {Long.toString(mContext.getSystemService(UserManager.class).getSerialNumberForUser(user)), Integer.toString(Favorites.ITEM_TYPE_APPLICATION)}, null)) {
            while (cursor.moveToNext()) {
                try {
                    ComponentName component = Intent.parseUri(cursor.getString(0), 0).getComponent();
                    if (component != null && pkg.equals(component.getPackageName())) return true;
                } catch (Exception e) {
                    Log.d("WorkspaceAvailability", "Ignoring corrupt install-queue comparison", e);
                }
            }
        }
        return false;
    }

    /** Persist a normal full-resolution cache for every ordinary row, including low-resolution folder children. */
    public void cacheAppearance(WorkspaceItemInfo item, IconCache icons) {
        if (item.itemType != Favorites.ITEM_TYPE_APPLICATION || item.status != 0 || item.id == ItemInfo.NO_ID || item.isArchived()) return;
        Decision decision = evaluate(item);
        if (decision == Decision.PRESERVE) {
            // A valid image already read from the old icon cache is still useful even if the APK is now missing.
            if (item.bitmap == null || item.bitmap.isNullOrLowRes() || icons.isDefaultIcon(item.bitmap, item.user)) return;
            try (var cursor = mDatabase.query(new String[] {Favorites.ICON}, Favorites._ID + "=?",
                    new String[] {Integer.toString(item.id)}, null)) {
                if (!cursor.moveToFirst() || cursor.getBlob(0) != null) return;
            }
            byte[] image = GraphicsUtils.flattenBitmap(item.bitmap.icon);
            if (image != null && image.length > 0) {
                ContentValues values = new ContentValues();
                values.put(Favorites.ICON, image);
                mDatabase.update(values, Favorites._ID + "=?", new String[] {Integer.toString(item.id)});
            }
            return;
        }
        if (decision != Decision.AVAILABLE) return;
        WorkspaceItemInfo cached = new WorkspaceItemInfo(item);
        if (cached.bitmap == null || cached.bitmap.isNullOrLowRes()) {
            icons.getTitleAndIcon(cached, com.android.launcher3.icons.cache.CacheLookupFlag.DEFAULT_LOOKUP_FLAG);
        }
        if (cached.bitmap == null || cached.bitmap.isNullOrLowRes() || icons.isDefaultIcon(cached.bitmap, item.user)) return;
        byte[] image = GraphicsUtils.flattenBitmap(cached.bitmap.icon);
        if (image == null || image.length == 0) return;
        CharSequence label = TextUtils.isEmpty(item.title) ? cached.title : item.title;
        if (TextUtils.isEmpty(label)) return;
        try (var cursor = mDatabase.query(new String[] {Favorites.TITLE, Favorites.ICON}, Favorites._ID + "=?",
                new String[] {Integer.toString(item.id)}, null)) {
            if (!cursor.moveToFirst() || TextUtils.equals(label, cursor.getString(0)) && Arrays.equals(image, cursor.getBlob(1))) return;
        }
        // An unavailable state is display-only; never serialize a grayed-out bitmap or a fallback into the cache.
        ContentValues values = new ContentValues();
        values.put(Favorites.TITLE, label.toString());
        values.put(Favorites.ICON, image);
        mDatabase.update(values, Favorites._ID + "=?", new String[] {Integer.toString(item.id)});
    }

    /** Protect against a package disappearing between positive resolution and the cache refresh. */
    public void refreshAppearance(WorkspaceItemInfo item, IconCache icons) {
        BitmapInfo bitmap = item.bitmap;
        CharSequence title = item.title;
        CharSequence description = item.contentDescription;
        WorkspaceItemInfo candidate = new WorkspaceItemInfo(item);
        candidate.runtimeStatusFlags &= ~ItemInfoWithIcon.FLAG_DISABLED_NOT_AVAILABLE;
        icons.getTitleAndIcon(candidate, item.getMatchingLookupFlag());
        boolean stillAvailable = evaluate(item) == Decision.AVAILABLE;
        if (stillAvailable && candidate.bitmap != null && !icons.isDefaultIcon(candidate.bitmap, item.user)) {
            item.bitmap = candidate.bitmap;
            item.title = candidate.title;
            item.contentDescription = candidate.contentDescription;
        } else {
            if (bitmap != null) item.bitmap = bitmap;
            item.title = title;
            item.contentDescription = description;
        }
        if (TextUtils.isEmpty(item.title)) item.title = title;
        if (!stillAvailable) item.runtimeStatusFlags |= ItemInfoWithIcon.FLAG_DISABLED_NOT_AVAILABLE;
        populateRuntimeFlags(item);
    }

    /** Reconstruct independently observable restrictions even when no LauncherActivityInfo exists after restart. */
    public void populateRuntimeFlags(WorkspaceItemInfo item) {
        try {
            UserManager users = mContext.getSystemService(UserManager.class);
            item.runtimeStatusFlags = users.isQuietModeEnabled(item.user)
                    ? item.runtimeStatusFlags | ItemInfoWithIcon.FLAG_DISABLED_QUIET_USER
                    : item.runtimeStatusFlags & ~ItemInfoWithIcon.FLAG_DISABLED_QUIET_USER;
            item.runtimeStatusFlags = users.isUserUnlocked(item.user)
                    ? item.runtimeStatusFlags & ~ItemInfoWithIcon.FLAG_DISABLED_LOCKED_USER
                    : item.runtimeStatusFlags | ItemInfoWithIcon.FLAG_DISABLED_LOCKED_USER;
            ApplicationInfo app = mContext.getSystemService(LauncherApps.class).getApplicationInfo(
                    item.getTargetPackage(), PackageManager.MATCH_UNINSTALLED_PACKAGES, item.user);
            if ((app.flags & ApplicationInfo.FLAG_SUSPENDED) != 0) {
                item.runtimeStatusFlags |= ItemInfoWithIcon.FLAG_DISABLED_SUSPENDED;
            } else if ((app.flags & ApplicationInfo.FLAG_INSTALLED) != 0) {
                item.runtimeStatusFlags &= ~ItemInfoWithIcon.FLAG_DISABLED_SUSPENDED;
            }
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            // Missing independent evidence must not erase publisher/admin/lock/suspension bits already supplied elsewhere.
            Log.d("WorkspaceAvailability", "Independent disabled state partly unresolved", e);
        }
    }

    public void hydrateUnavailable(WorkspaceItemInfo item) {
        loadSavedAppearance(item);
        populateRuntimeFlags(item);
    }

    private void loadSavedAppearance(WorkspaceItemInfo item) {
        try (var cursor = mDatabase.query(new String[] {Favorites.TITLE, Favorites.ICON}, Favorites._ID + "=?",
                new String[] {Integer.toString(item.id)}, null)) {
            if (!cursor.moveToFirst()) return;
            if (!TextUtils.isEmpty(cursor.getString(0))) item.title = cursor.getString(0);
            new IconRequestInfo<>(item, null, cursor.getBlob(1), false).loadIconFromDbBlob(mContext);
        }
    }
}
