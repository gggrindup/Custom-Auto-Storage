package com.afella.customautostorage;

import com.hypixel.hytale.logger.HytaleLogger;
import com.afella.customautostorage.storage.backend.TrackedChestStorageBackend;
import com.afella.customautostorage.storage.backend.json.JsonTrackedChestStorageBackend;
import com.afella.customautostorage.storage.backend.sqlite.SqliteTrackedChestStorageBackend;
import com.afella.customautostorage.storage.model.TrackedChestReadResult;
import com.afella.customautostorage.storage.model.TrackedChestRecord;
import com.afella.customautostorage.storage.model.TrackedChestReadResult.FailureReason;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.joml.Vector3i;

final class TrackedChestStorage {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private final Object lock = new Object();
    private final TrackedChestStorageBackend jsonBackend = new JsonTrackedChestStorageBackend();
    private final TrackedChestStorageBackend sqliteBackend = new SqliteTrackedChestStorageBackend();
    private volatile TrackedChestStorageBackend activeBackend;
    private volatile boolean fallbackToJsonLogged;

    TrackedChestStorage() {
        this.activeBackend = this.sqliteBackend;
    }

    TrackedChestReadResult read(Path dataDir) {
        synchronized (this.lock) {
            TrackedChestReadResult sqlite = this.sqliteBackend.read(dataDir);
            if (!sqlite.failed) {
                this.activeBackend = this.sqliteBackend;
                this.fallbackToJsonLogged = false;
                return sqlite;
            } else if (sqlite.failureReason == FailureReason.INCOMPATIBLE_SCHEMA) {
                return sqlite;
            } else if (dataDir != null && Files.exists(dataDir.resolve("tracked_autostorage.json"))) {
                TrackedChestReadResult json = this.jsonBackend.read(dataDir);
                if (!json.failed) {
                    this.activeBackend = this.jsonBackend;
                    if (!this.fallbackToJsonLogged) {
                        this.fallbackToJsonLogged = true;
                        LOGGER.atWarning().log("[AutoStorage] SQLite unavailable, switched to JSON persistence fallback.");
                    }
                }

                return json;
            } else {
                return sqlite;
            }
        }
    }

    void write(Path dataDir, Collection<TrackedChest> chests, Collection<TrackedChestRecord> extras) {
        synchronized (this.lock) {
            Map<String, TrackedChestRecord> merged = this.mergeRecords(chests, extras);
            List<TrackedChestRecord> records = new ArrayList<>(merged.values());
            if (!this.activeBackend.write(dataDir, records)) {
                if (this.activeBackend != this.jsonBackend && this.jsonBackend.write(dataDir, records)) {
                    this.activeBackend = this.jsonBackend;
                    if (!this.fallbackToJsonLogged) {
                        this.fallbackToJsonLogged = true;
                        LOGGER.atWarning().log("[AutoStorage] SQLite write failed, switched to JSON persistence fallback.");
                    }
                }

            }
        }
    }

    private Map<String, TrackedChestRecord> mergeRecords(Collection<TrackedChest> chests, Collection<TrackedChestRecord> extras) {
        Map<String, TrackedChestRecord> merged = new LinkedHashMap<>();
        if (extras != null) {
            for (TrackedChestRecord record : extras) {
                if (record != null && record.world() != null) {
                    merged.put(this.buildKey(record.world(), record.x(), record.y(), record.z()), record);
                }
            }
        }

        if (chests != null) {
            for (TrackedChest chest : chests) {
                if (chest != null && chest.world != null && chest.pos != null) {
                    Vector3i pos = chest.pos;
                    String worldName = chest.world.getName();
                    if (!worldName.isBlank()) {
                        String sortingMode = chest.sortingModeOverride != null ? chest.sortingModeOverride.name() : null;
                        String ownerUuid = chest.ownerUuid != null ? chest.ownerUuid.toString() : null;
                        String claimAccessUuid = chest.claimAccessUuid != null ? chest.claimAccessUuid.toString() : null;
                        List<String> ignoredTargets = (chest.ignoredTargets.isEmpty() ? Collections.emptyList() : new ArrayList<>(chest.ignoredTargets));
                        String key = this.buildKey(worldName, pos.x(), pos.y(), pos.z());
                        merged.put(key, new TrackedChestRecord(worldName, pos.x(), pos.y(), pos.z(), sortingMode, chest.horizontalRadius, chest.verticalRadius, ownerUuid, claimAccessUuid, ignoredTargets, chest.transferAnywhereIfMissing));
                    }
                }
            }

        }
        return merged;
    }

    private String buildKey(String world, int x, int y, int z) {
        return world + "|" + x + "," + y + "," + z;
    }
}
