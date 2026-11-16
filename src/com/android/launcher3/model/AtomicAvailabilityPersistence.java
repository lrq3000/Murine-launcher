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

import android.util.AtomicFile;
import android.util.Log;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

/** AtomicFile is available before API 29 and recovers an interrupted commit from its backup. */
final class AtomicAvailabilityPersistence implements ShortcutAvailabilityJournal.Persistence {
    private final AtomicFile mFile;

    AtomicAvailabilityPersistence(File file) {
        mFile = new AtomicFile(file);
    }

    @Override
    public byte[] read() throws IOException {
        try {
            return mFile.readFully();
        } catch (FileNotFoundException e) {
            if (mFile.getBaseFile().exists() || new File(mFile.getBaseFile() + ".bak").exists()) throw e;
            return null;
        }
    }

    @Override
    public void write(byte[] bytes) throws IOException {
        FileOutputStream stream = null;
        try {
            stream = mFile.startWrite();
            stream.write(bytes);
            mFile.finishWrite(stream);
        } catch (IOException | RuntimeException e) {
            mFile.failWrite(stream);
            Log.e("ShortcutAvailability", "Journal write failed; retaining workspace", e);
            throw e;
        }
    }
}
