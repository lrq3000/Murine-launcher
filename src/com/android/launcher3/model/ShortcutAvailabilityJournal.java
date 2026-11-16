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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Durable ordinary-application availability decisions, independent of Android package queries. */
public final class ShortcutAvailabilityJournal {
    public enum Decision { NORMAL, PRESERVE, AVAILABLE, REMOVE }
    public interface Persistence {
        byte[] read() throws IOException;
        void write(byte[] bytes) throws IOException;
    }
    public record Target(String database, long id, long profile, String packageName, String component) { }
    private record PackageKey(long profile, String name) { }
    private static final int MAGIC = 0x53415631;
    private static final int VERSION = 1;
    private static final int MAX_ENTRIES = 100_000;
    private static final class PackageState {
        int external = -1;
        boolean recovering;
        long generation;
        String installationUuid = "";
        String publicVolumeUuid = "";
    }

    private final Persistence mPersistence;
    private final Map<PackageKey, PackageState> mPackages = new HashMap<>();
    private final Map<Target, Boolean> mItems = new HashMap<>();
    private final Set<Target> mRemoved = new HashSet<>();
    private boolean mHealthy = true;

    public ShortcutAvailabilityJournal(Persistence persistence) {
        mPersistence = persistence;
        try {
            byte[] bytes = persistence.read();
            if (bytes != null) decode(bytes);
        } catch (IOException | RuntimeException e) {
            // Never trust a partially decoded removal list or internal classification.
            mPackages.clear();
            mItems.clear();
            mRemoved.clear();
            mHealthy = false;
        }
    }

    /** Only exact target resolution can end recovery; volume state and wall-clock time are deliberately not evidence. */
    public synchronized Decision evaluate(Target target, boolean exact, Boolean external,
            String uuid, String volume) {
        if (mHealthy && mRemoved.contains(target)) return Decision.REMOVE;
        PackageState state = state(target.profile, target.packageName);
        if (exact) {
            boolean changed = !Boolean.FALSE.equals(mItems.put(target, false));
            if (external != null && state.external != (external ? 1 : 0)) {
                state.external = external ? 1 : 0;
                changed = true;
            }
            if (uuid != null && !Objects.equals(uuid, state.installationUuid)) {
                state.installationUuid = uuid;
                // An installation move invalidates any earlier public-volume association.
                state.publicVolumeUuid = "";
                changed = true;
            }
            if (volume != null && !Objects.equals(volume, state.publicVolumeUuid)) {
                state.publicVolumeUuid = volume;
                changed = true;
            }
            if (changed) persist();
            return Decision.AVAILABLE;
        }
        if (!mHealthy || state.external != 0 || state.recovering
                || Boolean.TRUE.equals(mItems.get(target))) {
            if (!Boolean.TRUE.equals(mItems.put(target, true))) persist();
            return Decision.PRESERVE;
        }
        return Decision.NORMAL;
    }

    public synchronized void unavailable(long profile, String pkg) {
        PackageState state = state(profile, pkg);
        state.recovering = true;
        mItems.replaceAll((target, recovering) ->
                target.profile == profile && target.packageName.equals(pkg) || recovering);
        persist();
    }

    /** The durable row snapshot precedes favorites deletion, so interruption cannot resurrect old rows after reinstall. */
    public synchronized boolean removed(long profile, String pkg, List<Target> targets) {
        if (!mHealthy) return false;
        for (Target target : targets) {
            if (target.profile != profile || !target.packageName.equals(pkg)) {
                throw new IllegalArgumentException("Removal snapshot crosses profile/package");
            }
        }
        mRemoved.addAll(targets);
        PackageState state = state(profile, pkg);
        state.generation++;
        state.recovering = false;
        // Failed atomic commits disable destructive decisions in this process, including staged tombstones.
        return persist();
    }

    public synchronized void added(long profile, String pkg) {
        PackageState state = state(profile, pkg);
        state.generation++;
        state.external = -1;
        state.recovering = false;
        state.installationUuid = "";
        state.publicVolumeUuid = "";
        persist();
    }

    public synchronized boolean hasRecovery(long profile, String pkg) {
        return mItems.entrySet().stream().anyMatch(entry -> entry.getKey().profile == profile
                && entry.getKey().packageName.equals(pkg) && entry.getValue()
                && !mRemoved.contains(entry.getKey()));
    }

    public synchronized String installationUuid(long profile, String pkg) {
        return state(profile, pkg).installationUuid;
    }

    public synchronized String publicVolumeUuid(long profile, String pkg) {
        return state(profile, pkg).publicVolumeUuid;
    }

    private PackageState state(long profile, String pkg) {
        return mPackages.computeIfAbsent(new PackageKey(profile, pkg), key -> new PackageState());
    }

    private boolean persist() {
        try {
            mPersistence.write(encode());
            return mHealthy;
        } catch (IOException | RuntimeException e) {
            mHealthy = false;
            return false;
        }
    }

    private byte[] encode() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeInt(mPackages.size());
            for (var entry : mPackages.entrySet()) {
                out.writeLong(entry.getKey().profile);
                out.writeUTF(entry.getKey().name);
                PackageState state = entry.getValue();
                out.writeInt(state.external);
                out.writeBoolean(state.recovering);
                out.writeLong(state.generation);
                out.writeUTF(state.installationUuid);
                out.writeUTF(state.publicVolumeUuid);
            }
            out.writeInt(mItems.size());
            for (var entry : mItems.entrySet()) {
                writeTarget(out, entry.getKey());
                out.writeBoolean(entry.getValue());
            }
            out.writeInt(mRemoved.size());
            for (Target target : mRemoved) writeTarget(out, target);
        }
        return bytes.toByteArray();
    }

    private void decode(byte[] bytes) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) throw new IOException("Unknown journal format");
            for (int count = readCount(in); count > 0; count--) {
                PackageKey key = new PackageKey(in.readLong(), in.readUTF());
                PackageState state = new PackageState();
                state.external = in.readInt();
                if (state.external < -1 || state.external > 1) throw new IOException("Invalid storage class");
                state.recovering = in.readBoolean();
                state.generation = in.readLong();
                state.installationUuid = in.readUTF();
                state.publicVolumeUuid = in.readUTF();
                mPackages.put(key, state);
            }
            for (int count = readCount(in); count > 0; count--) mItems.put(readTarget(in), in.readBoolean());
            for (int count = readCount(in); count > 0; count--) mRemoved.add(readTarget(in));
            if (in.read() != -1) throw new IOException("Trailing journal data");
        }
    }

    private static int readCount(DataInputStream in) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > MAX_ENTRIES) throw new IOException("Invalid journal count");
        return count;
    }

    private static void writeTarget(DataOutputStream out, Target target) throws IOException {
        out.writeUTF(target.database);
        out.writeLong(target.id);
        out.writeLong(target.profile);
        out.writeUTF(target.packageName);
        out.writeUTF(target.component);
    }

    private static Target readTarget(DataInputStream in) throws IOException {
        return new Target(in.readUTF(), in.readLong(), in.readLong(), in.readUTF(), in.readUTF());
    }
}
