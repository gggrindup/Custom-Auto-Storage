package com.afella.customautostorage;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.afella.customautostorage.AutoStorageConfig.SortingMode;
import com.afella.customautostorage.storage.model.TrackedChestReadResult;
import com.afella.customautostorage.storage.model.TrackedChestRecord;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.joml.Vector3i;

final class TrackedChestLifecycleManager {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final long TRACKED_CHESTS_SAVE_DEBOUNCE_MS = 2000L;
    private final AutoStorage plugin;
    private final TrackedChestStorage chestStorage = new TrackedChestStorage();
    private final Map<String, TrackedChest> trackedChests;
    private final Map<String, Set<String>> trackedChestChunkIndex = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> ignoredTargetIndex = new ConcurrentHashMap<>();
    private final Map<String, TrackedChestRecord> pendingTrackedRecords = new ConcurrentHashMap<>();
    private volatile boolean trackedChestsLoadFailed;
    private volatile boolean saveSkippedWarningLogged;
    private final Object trackedChestsSaveLock = new Object();
    private volatile ScheduledFuture<?> pendingTrackedChestsSave;

    TrackedChestLifecycleManager(AutoStorage plugin, Map<String, TrackedChest> trackedChests) {
        this.plugin = plugin;
        this.trackedChests = trackedChests;
    }

    void preloadTrackedChests() {
        TrackedChestReadResult result = this.chestStorage.read(this.plugin.getPluginDataDirectory());
        this.trackedChestsLoadFailed = result.failed;
        if (!this.trackedChestsLoadFailed) {
            this.saveSkippedWarningLogged = false;
            List<TrackedChestRecord> records = result.records;
            if (!records.isEmpty()) {
                for(TrackedChestRecord record : records) {
                    if (record != null && record.world() != null) {
                        String key = this.plugin.buildChestKey(record.world(), record.x(), record.y(), record.z());
                        this.pendingTrackedRecords.put(key, record);
                    }
                }

            }
        }
    }

    void registerAutoStorage(World world, Vector3i pos, UUID ownerUuid, boolean restoreFromPending) {
        if (world != null && pos != null) {
            this.trackAutoStorage(world, pos, true, null, null, null, ownerUuid, restoreFromPending);
        }
    }

    void untrackChest(TrackedChest chest) {
        if (chest != null && chest.key != null && !chest.key.isBlank()) {
            this.removeTrackedChestByKey(chest.key);
        }
    }

    void removeTrackedChest(World world, Vector3i pos) {
        if (world != null && pos != null) {
            String key = this.plugin.buildChestKey(world.getName(), pos);
            this.removeTrackedChestByKey(key);
        }
    }

    void clearIgnoredTargetAt(World world, Vector3i pos) {
        if (world != null && pos != null) {
            String worldName = world.getName();
            if (!worldName.isBlank()) {
                String targetKey = this.plugin.buildChestKey(worldName, pos);
                Set<String> chestKeys = this.ignoredTargetIndex.remove(targetKey);
                if (chestKeys != null && !chestKeys.isEmpty()) {
                    boolean changed = false;

                    for(String chestKey : chestKeys) {
                        if (chestKey != null && !chestKey.isBlank()) {
                            TrackedChest chest = this.trackedChests.get(chestKey);
                            if (chest != null && chest.ignoredTargets.remove(targetKey)) {
                                changed = true;
                            }
                        }
                    }

                    if (changed) {
                        this.saveTrackedChestsAsync();
                    }

                }
            }
        }
    }

    boolean toggleIgnoredTarget(String worldName, Vector3i pos, String targetKey) {
        TrackedChest chest = this.getTrackedChest(worldName, pos);
        if (chest != null && targetKey != null && !targetKey.isBlank()) {
            synchronized(chest) {
                boolean removed = chest.ignoredTargets.remove(targetKey);
                if (removed) {
                    this.deindexIgnoredTarget(chest.key, targetKey);
                } else {
                    chest.ignoredTargets.add(targetKey);
                    this.indexIgnoredTarget(chest.key, targetKey);
                }
            }

            this.saveTrackedChestsAsync();
            return true;
        } else {
            return false;
        }
    }

    TrackedChest getTrackedChest(String worldName, Vector3i pos) {
        return worldName != null && pos != null ? this.trackedChests.get(this.plugin.buildChestKey(worldName, pos)) : null;
    }

    Set<String> getTrackedChestKeysNear(String worldName, Vector3i pos, int horizontalRadius) {
        if (worldName != null && !worldName.isBlank() && pos != null) {
            int minX = pos.x() - horizontalRadius;
            int maxX = pos.x() + horizontalRadius;
            int minZ = pos.z() - horizontalRadius;
            int maxZ = pos.z() + horizontalRadius;
            int minChunkX = ChunkUtil.chunkCoordinate(minX);
            int maxChunkX = ChunkUtil.chunkCoordinate(maxX);
            int minChunkZ = ChunkUtil.chunkCoordinate(minZ);
            int maxChunkZ = ChunkUtil.chunkCoordinate(maxZ);
            Set<String> keys = new HashSet<>();

            for(int chunkX = minChunkX; chunkX <= maxChunkX; ++chunkX) {
                for(int chunkZ = minChunkZ; chunkZ <= maxChunkZ; ++chunkZ) {
                    long chunkIndex = ChunkUtil.indexChunk(chunkX, chunkZ);
                    Set<String> chunkKeys = this.trackedChestChunkIndex.get(this.buildTrackedChunkKey(worldName, chunkIndex));
                    if (chunkKeys != null && !chunkKeys.isEmpty()) {
                        keys.addAll(chunkKeys);
                    }
                }
            }

            return keys;
        } else {
            return Set.of();
        }
    }

    void loadTrackedChestsWithRetry() {
        if (!this.pendingTrackedRecords.isEmpty()) {
            int pending = 0;

            for(TrackedChestRecord record : new ArrayList<>(this.pendingTrackedRecords.values())) {
                try {
                    if (record != null && record.world() != null) {
                        String key = this.plugin.buildChestKey(record.world(), record.x(), record.y(), record.z());
                        if (this.trackedChests.containsKey(key)) {
                            this.pendingTrackedRecords.remove(key);
                        } else {
                            World world = this.plugin.findWorldByName(record.world());
                            if (world == null) {
                                ++pending;
                            } else {
                                long chunkIndex = ChunkUtil.indexChunkFromBlock(record.x(), record.z());
                                if (world.getChunkIfInMemory(chunkIndex) == null) {
                                    ++pending;
                                } else {
                                    AutoStorageConfig.SortingMode sortingMode = this.parseSortingMode(record.sortingMode());
                                    Integer horizontalRadius = record.horizontalRadius() > 0 ? record.horizontalRadius() : null;
                                    Integer verticalRadius = record.verticalRadius() > 0 ? record.verticalRadius() : null;
                                    UUID ownerUuid = this.parseUuid(record.ownerUuid());
                                    this.registerAutoStorageFromLoad(world, new Vector3i(record.x(), record.y(), record.z()), sortingMode, horizontalRadius, verticalRadius, ownerUuid);
                                }
                            }
                        }
                    }
                } catch (Throwable throwable) {
                    ++pending;
                    LOGGER.atWarning().withCause(throwable).log("[AutoStorage] Failed to restore one tracked chest record.");
                }
            }

            ScheduledExecutorService scheduler = this.plugin.getScheduler();
            if (pending > 0 && scheduler != null && !scheduler.isShutdown()) {
                try {
                    scheduler.schedule(this::loadTrackedChestsWithRetry, 5L, TimeUnit.SECONDS);
                } catch (RuntimeException throwable) {
                    LOGGER.atWarning().withCause(throwable).log("[AutoStorage] Failed to schedule tracked chest retry.");
                }
            }

        }
    }

    void saveTrackedChestsAsync() {
        ScheduledExecutorService scheduler = this.plugin.getScheduler();
        if (scheduler != null && !scheduler.isShutdown()) {
            synchronized(this.trackedChestsSaveLock) {
                ScheduledFuture<?> pending = this.pendingTrackedChestsSave;
                if (pending != null && !pending.isDone()) {
                    pending.cancel(false);
                }

                try {
                    this.pendingTrackedChestsSave = scheduler.schedule(this::saveTrackedChests, TRACKED_CHESTS_SAVE_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
                } catch (RuntimeException throwable) {
                    this.pendingTrackedChestsSave = null;
                    LOGGER.atWarning().withCause(throwable).log("[AutoStorage] Failed to schedule tracked chest save.");
                    this.saveTrackedChests();
                }

            }
        } else {
            this.saveTrackedChests();
        }
    }

    void flushTrackedChestsSaveNow() {
        ScheduledFuture<?> pending;
        synchronized(this.trackedChestsSaveLock) {
            pending = this.pendingTrackedChestsSave;
            this.pendingTrackedChestsSave = null;
        }

        if (pending != null && !pending.isDone()) {
            pending.cancel(false);
        }

        this.saveTrackedChests();
    }

    void clearRuntimeIndexes() {
        this.trackedChestChunkIndex.clear();
        this.ignoredTargetIndex.clear();
        this.pendingTrackedRecords.clear();
    }

    private void saveTrackedChests() {
        if (this.trackedChestsLoadFailed) {
            if (!this.saveSkippedWarningLogged) {
                this.saveSkippedWarningLogged = true;
                LOGGER.atWarning().log("[AutoStorage] Skipping tracked chest save because initial read failed; keeping existing persistence file unchanged.");
            }

        } else {
            this.chestStorage.write(this.plugin.getPluginDataDirectory(), this.trackedChests.values(), this.pendingTrackedRecords.values());
        }
    }

    private void removeTrackedChestByKey(String key) {
        if (key != null && !key.isBlank()) {
            boolean removedPending = this.pendingTrackedRecords.remove(key) != null;
            TrackedChest removed = this.trackedChests.remove(key);
            if (removed == null) {
                if (removedPending) {
                    this.saveTrackedChestsAsync();
                }

            } else {
                this.deindexTrackedChestChunk(removed);
                this.plugin.removeTargetScanCache(key);
                this.deindexIgnoredTargetsForChest(removed);
                this.plugin.cleanupTrackedChest(removed);
                this.plugin.recalculateTrackedHorizontalRadiusMax();
                this.saveTrackedChestsAsync();
            }
        }
    }

    private void trackAutoStorage(World world, Vector3i pos, boolean persist, AutoStorageConfig.SortingMode sortingMode, Integer horizontalRadius, Integer verticalRadius, UUID ownerUuid, boolean restoreFromPending) {
        AutoStorageConfig config = this.plugin.getAutoStorageConfig();
        if (world != null && pos != null && config != null) {
            String key = this.plugin.buildChestKey(world.getName(), pos);
            TrackedChest candidate = new TrackedChest(key, world, pos);
            TrackedChest existing = this.trackedChests.putIfAbsent(key, candidate);
            boolean created = existing == null;
            TrackedChest chest = created ? candidate : existing;
            TrackedChestRecord pending = this.pendingTrackedRecords.remove(key);
            if (created && restoreFromPending && pending != null) {
                AutoStorageConfig.SortingMode pendingMode = this.parseSortingMode(pending.sortingMode());
                if (pendingMode != null) {
                    chest.sortingModeOverride = pendingMode;
                }

                if (pending.horizontalRadius() > 0) {
                    chest.horizontalRadius = pending.horizontalRadius();
                }

                if (pending.verticalRadius() > 0) {
                    chest.verticalRadius = pending.verticalRadius();
                }

                UUID pendingOwner = this.parseUuid(pending.ownerUuid());
                if (pendingOwner != null) {
                    chest.ownerUuid = pendingOwner;
                }

                UUID pendingClaimAccess = this.parseUuid(pending.claimAccessUuid());
                if (pendingClaimAccess != null) {
                    chest.claimAccessUuid = pendingClaimAccess;
                }

                if (!pending.ignoredTargets().isEmpty()) {
                    chest.ignoredTargets.clear();
                    chest.ignoredTargets.addAll(pending.ignoredTargets());
                }

                chest.transferAnywhereIfMissing = pending.transferAnywhereIfMissing();
            }

            boolean ownerUpdated = false;
            if (ownerUuid != null) {
                boolean shouldReplaceOwner = created || chest.ownerUuid == null || !restoreFromPending;
                if (shouldReplaceOwner && !ownerUuid.equals(chest.ownerUuid)) {
                    chest.ownerUuid = ownerUuid;
                    ownerUpdated = true;
                }

                if (chest.claimAccessUuid == null) {
                    chest.claimAccessUuid = ownerUuid;
                }
            } else if (chest.claimAccessUuid == null && chest.ownerUuid != null) {
                chest.claimAccessUuid = chest.ownerUuid;
            }

            if (sortingMode != null) {
                chest.sortingModeOverride = sortingMode;
            }

            if (horizontalRadius != null) {
                chest.horizontalRadius = horizontalRadius;
            }

            if (verticalRadius != null) {
                chest.verticalRadius = verticalRadius;
            }

            this.plugin.ensureRadiusInitialized(chest);
            this.plugin.updateTrackedHorizontalRadiusMax(chest);
            this.indexTrackedChestChunk(chest);
            chest.idleDueToNonTransferable = false;
            this.plugin.resetIdleRetryBackoff(chest);
            this.plugin.markTargetScanCacheDirty(chest);
            this.indexIgnoredTargetsForChest(chest);
            if (persist && (created || ownerUpdated)) {
                this.saveTrackedChestsAsync();
            }

            long delay = config.isTransferImmediate() ? 0L : (long)config.getTransferAfterLastInteractionMs();
            if (!config.isTransferImmediate() && created) {
                chest.depositDelayUntil = System.currentTimeMillis() + delay;
            }

            if (created || chest.changeRegistration == null || !chest.changeRegistration.isRegistered()) {
                this.plugin.ensureChangeListener(chest);
            }

            if (created) {
                this.plugin.scheduleChest(chest, delay);
            }

        }
    }

    private void registerAutoStorageFromLoad(World world, Vector3i pos, AutoStorageConfig.SortingMode sortingMode, Integer horizontalRadius, Integer verticalRadius, UUID ownerUuid) {
        this.trackAutoStorage(world, pos, false, sortingMode, horizontalRadius, verticalRadius, ownerUuid, true);
    }

    private AutoStorageConfig.SortingMode parseSortingMode(String value) {
        if (value != null && !value.isEmpty()) {
            String normalized = value.trim().toUpperCase(Locale.ROOT);

            for(AutoStorageConfig.SortingMode mode : SortingMode.values()) {
                if (mode.name().equals(normalized)) {
                    return mode;
                }
            }

        }
        return null;
    }

    private UUID parseUuid(String value) {
        if (value != null && !value.isBlank()) {
            try {
                return UUID.fromString(value.trim());
            } catch (IllegalArgumentException var3) {
                return null;
            }
        } else {
            return null;
        }
    }

    private String buildTrackedChunkKey(String worldName, long chunkIndex) {
        return worldName + "|" + chunkIndex;
    }

    private void indexTrackedChestChunk(TrackedChest chest) {
        if (chest != null && chest.world != null && chest.pos != null && chest.key != null && !chest.key.isBlank()) {
            String worldName = chest.world.getName();
            if (!worldName.isBlank()) {
                long chunkIndex = ChunkUtil.indexChunkFromBlock(chest.pos.x(), chest.pos.z());
                this.trackedChestChunkIndex.computeIfAbsent(this.buildTrackedChunkKey(worldName, chunkIndex), (_) -> ConcurrentHashMap.newKeySet()).add(chest.key);
            }
        }
    }

    private void deindexTrackedChestChunk(TrackedChest chest) {
        if (chest != null && chest.world != null && chest.pos != null && chest.key != null && !chest.key.isBlank()) {
            String worldName = chest.world.getName();
            if (!worldName.isBlank()) {
                long chunkIndex = ChunkUtil.indexChunkFromBlock(chest.pos.x(), chest.pos.z());
                String trackedChunkKey = this.buildTrackedChunkKey(worldName, chunkIndex);
                Set<String> chunkKeys = this.trackedChestChunkIndex.get(trackedChunkKey);
                if (chunkKeys != null) {
                    chunkKeys.remove(chest.key);
                    if (chunkKeys.isEmpty()) {
                        this.trackedChestChunkIndex.remove(trackedChunkKey, chunkKeys);
                    }

                }
            }
        }
    }

    private void indexIgnoredTargetsForChest(TrackedChest chest) {
        if (chest != null && !chest.ignoredTargets.isEmpty()) {
            String chestKey = chest.key;
            if (chestKey != null && !chestKey.isBlank()) {
                for(String targetKey : chest.ignoredTargets) {
                    this.indexIgnoredTarget(chestKey, targetKey);
                }

            }
        }
    }

    private void deindexIgnoredTargetsForChest(TrackedChest chest) {
        if (chest != null && !chest.ignoredTargets.isEmpty()) {
            String chestKey = chest.key;
            if (chestKey != null && !chestKey.isBlank()) {
                for(String targetKey : chest.ignoredTargets) {
                    this.deindexIgnoredTarget(chestKey, targetKey);
                }

            }
        }
    }

    private void indexIgnoredTarget(String chestKey, String targetKey) {
        if (chestKey != null && !chestKey.isBlank() && targetKey != null && !targetKey.isBlank()) {
            this.ignoredTargetIndex.computeIfAbsent(targetKey, (_) -> ConcurrentHashMap.newKeySet()).add(chestKey);
        }
    }

    private void deindexIgnoredTarget(String chestKey, String targetKey) {
        if (chestKey != null && !chestKey.isBlank() && targetKey != null && !targetKey.isBlank()) {
            Set<String> chestKeys = this.ignoredTargetIndex.get(targetKey);
            if (chestKeys != null) {
                chestKeys.remove(chestKey);
                if (chestKeys.isEmpty()) {
                    this.ignoredTargetIndex.remove(targetKey, chestKeys);
                }

            }
        }
    }
}
