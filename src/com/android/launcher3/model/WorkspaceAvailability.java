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
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.util.Log;

import com.android.launcher3.LauncherSettings.Favorites;
import com.android.launcher3.dagger.ApplicationContext;
import com.android.launcher3.dagger.LauncherAppSingleton;
import com.android.launcher3.model.ShortcutAvailabilityJournal.Decision;
import com.android.launcher3.model.ShortcutAvailabilityJournal.Target;
import com.android.launcher3.model.data.CollectionInfo;
import com.android.launcher3.model.data.ItemInfo;
import com.android.launcher3.model.data.ItemInfoWithIcon;
import com.android.launcher3.model.data.WorkspaceItemInfo;
import com.android.launcher3.pm.UserCache;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;

/** Model-owned Android boundary for exact-target queries and the device-local durable journal. */
@LauncherAppSingleton
public final class WorkspaceAvailability {
    private final Context mContext;
    private final ModelDbController mDatabase;
    private final UserCache mUsers;
    private ShortcutAvailabilityJournal mJournal;

    @Inject
    public WorkspaceAvailability(@ApplicationContext Context context, ModelDbController database,
            UserCache users) {
        mContext = context;
        mDatabase = database;
        mUsers = users;
    }

    private synchronized ShortcutAvailabilityJournal journal() {
        if (mJournal == null) {
            // Do not back up device-specific storage knowledge or pending system events to another device.
            mJournal = new ShortcutAvailabilityJournal(new AtomicAvailabilityPersistence(
                    new File(mContext.getNoBackupFilesDir(), "workspace-availability-v1")));
        }
        return mJournal;
    }

    /** Called off-UI at callback receipt, before a model task can be discarded or a running loader can bind. */
    public synchronized void recordEvent(int operation, UserHandle user, String[] packages) {
        long serial = mUsers.getSerialNumberForUser(user);
        if (serial < 0) return;
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
        for (var removal : journal().pendingRemovals()) {
            List<Target> targets = new ArrayList<>();
            String database = mDatabase.getWorkspaceIdentity();
            try (var cursor = mDatabase.query(new String[] {Favorites._ID, Favorites.INTENT},
                    Favorites.PROFILE_ID + "=? AND " + Favorites.ITEM_TYPE + "=? AND " + Favorites.RESTORED + "=0",
                    new String[] {Long.toString(removal.profile()), Integer.toString(Favorites.ITEM_TYPE_APPLICATION)}, null)) {
                while (cursor.moveToNext()) {
                    try {
                        Intent intent = Intent.parseUri(cursor.getString(1), 0);
                        ComponentName component = intent.getComponent();
                        if (component != null && removal.packageName().equals(component.getPackageName())) {
                            targets.add(new Target(database, cursor.getLong(0), removal.profile(),
                                    removal.packageName(), component.flattenToString()));
                        }
                    } catch (Exception e) {
                        // Corrupt intents remain on the loader's specialized validation path.
                        Log.w("WorkspaceAvailability", "Cannot snapshot corrupt shortcut", e);
                    }
                }
            }
            journal().removed(removal.profile(), removal.packageName(), targets);
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
        try {
            LauncherApps apps = mContext.getSystemService(LauncherApps.class);
            Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(component);
            if (apps.isPackageEnabled(component.getPackageName(), user) && apps.isActivityEnabled(component, user)) {
                activity = apps.resolveActivity(query, user);
                if (activity != null && !component.equals(activity.getComponentName())) activity = null;
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
        if (activity != null) {
            ApplicationInfo app = activity.getApplicationInfo();
            external = (app.flags & ApplicationInfo.FLAG_EXTERNAL_STORAGE) != 0
                    || app.storageUuid != null && !StorageManager.UUID_DEFAULT.equals(app.storageUuid);
            uuid = app.storageUuid == null ? null : app.storageUuid.toString();
            if (uuid != null && external) {
                try {
                    // API 29 has no reliable general adopted/private-to-public mapping. Record only an exact exposed UUID match.
                    for (StorageVolume volume : mContext.getSystemService(StorageManager.class).getStorageVolumes()) {
                        if (uuid.equalsIgnoreCase(volume.getUuid())) {
                            publicUuid = volume.getUuid();
                            volume.getState(); // Readiness is a hint only; exact registration above remains decisive.
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
        return evaluate(item.id, mUsers.getSerialNumberForUser(item.user), item.intent, item.user);
    }

    public synchronized boolean isConfirmedRemoved(ItemInfo item) {
        ComponentName component = item.getTargetComponent();
        if (!(item instanceof WorkspaceItemInfo workspace) || workspace.status != 0
                || item.itemType != Favorites.ITEM_TYPE_APPLICATION || component == null) return false;
        try {
            return journal().isRemoved(new Target(mDatabase.getWorkspaceIdentity(), item.id,
                    mUsers.getSerialNumberForUser(item.user), component.getPackageName(), component.flattenToString()));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Receipt and final bind have one linearization lock, but database/model work stays on MODEL_EXECUTOR. */
    public synchronized void reconcileBeforeBind(BgDataModel model, Runnable bind) {
        reconcilePendingRemovals();
        List<ItemInfo> removed = new ArrayList<>();
        for (ItemInfo item : model.itemsIdMap) {
            if (isConfirmedRemoved(item)) {
                mDatabase.delete(Favorites._ID + "=?", new String[] {Integer.toString(item.id)});
                removed.add(item);
            } else if (item instanceof WorkspaceItemInfo workspace
                    && workspace.itemType == Favorites.ITEM_TYPE_APPLICATION && workspace.status == 0) {
                Decision decision = evaluate(workspace);
                if (decision == Decision.PRESERVE) workspace.runtimeStatusFlags |= ItemInfoWithIcon.FLAG_DISABLED_NOT_AVAILABLE;
                if (decision == Decision.AVAILABLE) workspace.runtimeStatusFlags &= ~ItemInfoWithIcon.FLAG_DISABLED_NOT_AVAILABLE;
            }
        }
        for (ItemInfo item : model.itemsIdMap) {
            if (item instanceof CollectionInfo collection) collection.getContents().removeAll(removed);
        }
        model.removeItem(mContext, removed);
        bind.run();
    }

    public boolean hasRecovery(UserHandle user, String pkg) {
        return journal().hasRecovery(mUsers.getSerialNumberForUser(user), pkg);
    }
}
