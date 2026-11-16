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
    private final File mUncertain;

    AtomicAvailabilityPersistence(File file) {
        mFile = new AtomicFile(file);
        mUncertain = new File(file + ".uncertain");
    }

    @Override
    public boolean isUncertain() { return mUncertain.exists(); }

    @Override
    public void markUncertain() throws IOException {
        try (FileOutputStream stream = new FileOutputStream(mUncertain)) {
            stream.write(1);
            stream.getFD().sync();
        } catch (IOException e) {
            // If even the latch cannot be written, invalidate older authority rather than silently reuse it on restart.
            mFile.delete();
            throw e;
        }
    }

    @Override
    public void clearUncertain() throws IOException {
        if (mUncertain.exists() && !mUncertain.delete()) throw new IOException("Cannot clear journal uncertainty latch");
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
            stream.getFD().sync();
            mFile.finishWrite(stream);
        } catch (IOException | RuntimeException e) {
            mFile.failWrite(stream);
            Log.e("ShortcutAvailability", "Journal write failed; retaining workspace", e);
            throw e;
        }
    }
}
