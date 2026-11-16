/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.launcher3.model;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.android.launcher3.LauncherModel;
import com.android.launcher3.util.PackageUserKey;

import java.util.Set;

/**
 * Helper class to re-query app status when SD-card becomes available.
 *
 * Legacy one-shot hook; normal boot/media observations are lifecycle-owned by ModelInitializer. Boot completion only requests a recheck and cannot prove uninstall.
 */
public class SdCardAvailableReceiver extends BroadcastReceiver {

    private final LauncherModel mModel;
    private final Context mContext;

    public SdCardAvailableReceiver(
            Context context, LauncherModel model, Set<PackageUserKey> packages) {
        mContext = context;
        mModel = model;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        // Boot completion is a revalidation hint, never evidence that a missing app was uninstalled.
        mModel.requestAvailabilityRevalidation();

        // Unregister the broadcast receiver, just in case
        mContext.unregisterReceiver(this);
    }
}
