package com.afella.customautostorage;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.universe.world.World;
import com.afella.customautostorage.AutoStorageConfig.SortingMode;
import com.afella.customautostorage.compat.claim.ClaimProtectionProvider.ClaimAccessType;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import org.checkerframework.checker.nullness.compatqual.NonNullDecl;
import org.joml.Vector3i;

final class TargetResolver {
    private static final HytaleLogger LOGGER = HytaleLogger.get("AutoStorage");
    private static final long GROUP_FINGERPRINT_MULTIPLIER = -7046029254386353131L;
    private static final long GROUP_FINGERPRINT_RECHECK_MAX_MS = 1000L;
    private static final int ITEM_PREVIEW_ICON_LIMIT = 36;
    private final AutoStorage plugin;
    final Map<String, TargetScanCache> targetScanCaches = new ConcurrentHashMap<>();
    final Map<String, String> blockItemIdCache = new ConcurrentHashMap<>();

    TargetResolver(AutoStorage plugin) {
        this.plugin = plugin;
    }

    int countLinkedChests(String worldName, Vector3i pos) {
        NearbyTargetScanResult scan = this.scanNearbyTargets(worldName, pos, false);
        return scan.linkedTotal;
    }

    AutoStorage.NearbyChestsSnapshot getNearbyChests(String worldName, Vector3i pos, int offset, int limit) {
        NearbyTargetScanResult scan = this.scanNearbyTargets(worldName, pos, true);
        List<NearbyTargetCandidate> scannedTargets = scan.targets;
        if (scannedTargets.isEmpty()) {
            return new AutoStorage.NearbyChestsSnapshot(List.of(), 0, scan.linkedTotal);
        } else {
            List<NearbyChestCandidate> candidates = new ArrayList<>(scannedTargets.size());

            for (NearbyTargetCandidate candidate : scannedTargets) {
                ItemContainer target = this.plugin.getItemContainer(candidate.state);
                ItemPreviewResult previewResult = this.collectItemPreviews(target, ITEM_PREVIEW_ICON_LIMIT);
                String blockId = this.displayBlockId(candidate.blockType);
                String chestItemId = this.resolveItemIdForBlock(candidate.blockType);
                AutoStorage.NearbyChestView view = new AutoStorage.NearbyChestView(candidate.key, candidate.x, candidate.y, candidate.z, chestItemId, blockId, previewResult.previews, previewResult.total, candidate.ignored);
                candidates.add(new NearbyChestCandidate(candidate.distanceSq, view));
            }

            candidates.sort(Comparator.comparingLong((NearbyChestCandidate c) -> c.distanceSq).thenComparing(c -> c.view.key));
            int total = candidates.size();
            int safeOffset = Math.max(0, offset);
            int from = Math.min(safeOffset, total);
            int to = limit <= 0 ? total : Math.min(total, from + limit);
            List<AutoStorage.NearbyChestView> result = new ArrayList<>(Math.max(0, to - from));

            for (int i = from; i < to; ++i) {
                result.add(candidates.get(i).view);
            }

            return new AutoStorage.NearbyChestsSnapshot(result, total, scan.linkedTotal);
        }
    }

    NearbyTargetScanResult scanNearbyTargets(String worldName, Vector3i pos, boolean includeIgnoredTargets) {
        World world = this.plugin.findWorldByName(worldName);
        if (world != null && pos != null) {
            TrackedChest chest = this.plugin.getTrackedChest(worldName, pos);
            int rH = this.plugin.getHorizontalRadiusFor(chest);
            int rV = this.plugin.getVerticalRadiusFor(chest);
            if (rH >= 1 && rV >= 1) {
                if (chest != null && chest.world == world) {
                    TargetScanCache scanCache = this.getOrRebuildTargetScanCache(chest, pos, rH, rV);
                    NearbyTargetScanResult result = this.collectNearbyTargetsFromCache(worldName, chest, scanCache.targets, includeIgnoredTargets);
                    if (!result.cacheStale) {
                        return result;
                    } else {
                        this.markTargetScanCacheDirty(chest);
                        scanCache = this.getOrRebuildTargetScanCache(chest, pos, rH, rV);
                        return this.collectNearbyTargetsFromCache(worldName, chest, scanCache.targets, includeIgnoredTargets);
                    }
                } else {
                    return this.rescanNearbyTargets(worldName, world, pos, rH, rV, chest, includeIgnoredTargets);
                }
            } else {
                return TargetResolver.NearbyTargetScanResult.EMPTY;
            }
        } else {
            return TargetResolver.NearbyTargetScanResult.EMPTY;
        }
    }

    private NearbyTargetScanResult collectNearbyTargetsFromCache(String worldName, TrackedChest chest, List<CachedTarget> scanTargets, boolean includeIgnoredTargets) {
        if (chest != null && chest.world != null && scanTargets != null && !scanTargets.isEmpty()) {
            boolean hasIgnoredTargets = !chest.ignoredTargets.isEmpty();
            List<NearbyTargetCandidate> targets = new ArrayList<>(scanTargets.size());
            AutoStorage.ScanChunkCache chunkCache = new AutoStorage.ScanChunkCache();
            Map<Long, Boolean> claimAccessCache = new HashMap<>();
            int linkedTotal = 0;
            boolean cacheStale = false;

            for (CachedTarget cachedTarget : scanTargets) {
                int x = cachedTarget.x;
                int y = cachedTarget.y;
                int z = cachedTarget.z;
                if (this.plugin.isTargetAllowedForChestOwner(chest, x, z, claimAccessCache, ClaimAccessType.CHEST_INTERACT)) {
                    String key = this.plugin.buildChestKey(worldName, x, y, z);
                    boolean ignored = hasIgnoredTargets && this.plugin.isIgnoredTarget(chest, key);
                    if (!ignored || includeIgnoredTargets) {
                        Object targetState = this.plugin.getItemContainerStateAt(chest.world, x, y, z, chunkCache);
                        if (targetState == null) {
                            cacheStale = true;
                        } else {
                            BlockType targetType = this.plugin.getBlockTypeAt(chest.world, x, y, z, chunkCache);
                            if (!this.plugin.isAutoStorage(targetType) && this.plugin.isAllowedTargetContainer(targetType)) {
                                if (!ignored) {
                                    ++linkedTotal;
                                }

                                targets.add(new NearbyTargetCandidate(key, x, y, z, cachedTarget.distanceSq, targetState, targetType, ignored));
                            } else {
                                cacheStale = true;
                            }
                        }
                    }
                }
            }

            if (targets.isEmpty() && linkedTotal == 0 && !cacheStale) {
                return TargetResolver.NearbyTargetScanResult.EMPTY;
            } else {
                return new NearbyTargetScanResult(List.copyOf(targets), linkedTotal, cacheStale);
            }
        } else {
            return TargetResolver.NearbyTargetScanResult.EMPTY;
        }
    }

    private NearbyTargetScanResult rescanNearbyTargets(String worldName, World world, Vector3i center, int rH, int rV, TrackedChest chest, boolean includeIgnoredTargets) {
        if (world != null && center != null && rH >= 1 && rV >= 1) {
            boolean hasIgnoredTargets = chest != null && !chest.ignoredTargets.isEmpty();
            List<NearbyTargetCandidate> targets = new ArrayList<>();
            AutoStorage.ScanChunkCache chunkCache = new AutoStorage.ScanChunkCache();
            Map<Long, Boolean> claimAccessCache = new HashMap<>();
            int linkedTotal = 0;

            for (int dx = -rH; dx <= rH; ++dx) {
                for (int dy = -rV; dy <= rV; ++dy) {
                    for (int dz = -rH; dz <= rH; ++dz) {
                        if (dx != 0 || dy != 0 || dz != 0) {
                            int x = center.x() + dx;
                            int y = center.y() + dy;
                            int z = center.z() + dz;
                            if (this.plugin.isTargetAllowedForChestOwner(chest, x, z, claimAccessCache, ClaimAccessType.CHEST_INTERACT)) {
                                Object targetState = this.plugin.getItemContainerStateAt(world, x, y, z, chunkCache);
                                if (targetState != null) {
                                    BlockType targetType = this.plugin.getBlockTypeAt(world, x, y, z, chunkCache);
                                    if (!this.plugin.isAutoStorage(targetType) && this.plugin.isAllowedTargetContainer(targetType)) {
                                        String key = this.plugin.buildChestKey(worldName, x, y, z);
                                        boolean ignored = hasIgnoredTargets && this.plugin.isIgnoredTarget(chest, key);
                                        if (!ignored || includeIgnoredTargets) {
                                            if (!ignored) {
                                                ++linkedTotal;
                                            }

                                            long distanceSq = (long) dx * (long) dx + (long) dy * (long) dy + (long) dz * (long) dz;
                                            targets.add(new NearbyTargetCandidate(key, x, y, z, distanceSq, targetState, targetType, ignored));
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (targets.isEmpty() && linkedTotal == 0) {
                return TargetResolver.NearbyTargetScanResult.EMPTY;
            } else {
                return new NearbyTargetScanResult(List.copyOf(targets), linkedTotal, false);
            }
        } else {
            return TargetResolver.NearbyTargetScanResult.EMPTY;
        }
    }

    @NonNullDecl
    TransferTargetScanResult collectTransferTargetCandidates(TrackedChest chest, Vector3i center, int rH, int rV, AutoStorageConfig.SortingMode sortingMode, Set<String> sourceGroups, boolean allowFallback) {
        TargetScanCache scanCache = this.getOrRebuildTargetScanCache(chest, center, rH, rV);
        List<CachedTarget> scanTargets = scanCache.targets;
        TransferTargetScanResult result = this.collectTransferTargetCandidatesFromScan(chest, sortingMode, sourceGroups, allowFallback, scanTargets);
        if (!result.cacheStale) {
            return result;
        } else {
            this.markTargetScanCacheDirty(chest);
            scanCache = this.getOrRebuildTargetScanCache(chest, center, rH, rV);
            scanTargets = scanCache.targets;
            return this.collectTransferTargetCandidatesFromScan(chest, sortingMode, sourceGroups, allowFallback, scanTargets);
        }
    }

    private TransferTargetScanResult collectTransferTargetCandidatesFromScan(@NonNullDecl TrackedChest chest, AutoStorageConfig.SortingMode sortingMode, Set<String> sourceGroups, boolean allowFallback, List<CachedTarget> scanTargets) {
        if (chest.world.getName().isBlank()) {
            return new TransferTargetScanResult(new HashMap<>(), new HashMap<>(), List.of(), false);
        }
        boolean hasSourceGroups = sourceGroups != null && !sourceGroups.isEmpty();
        boolean hasIgnoredTargets = !chest.ignoredTargets.isEmpty();
        List<FallbackTarget> fallbackTargets = allowFallback ? new ArrayList<>() : List.of();
        Map<String, List<GroupCandidate>> candidatesByGroup = new HashMap<>();
        Map<String, List<GroupCandidate>> damagedCandidatesByGroup = new HashMap<>();
        AutoStorage.ScanChunkCache chunkCache = new AutoStorage.ScanChunkCache();
        Map<Long, Boolean> claimAccessCache = new HashMap<>();
        boolean cacheStale = false;
        long now = System.currentTimeMillis();
        for (CachedTarget cachedTarget : scanTargets) {
            int x = cachedTarget.x;
            int y = cachedTarget.y;
            int z = cachedTarget.z;
            String targetKey = this.plugin.buildChestKey(chest.world.getName(), x, y, z);
            boolean ignored = this.plugin.isIgnoredTarget(chest, targetKey);

            LOGGER.atInfo().log("Transfer candidate: key=%s ignored=%s", targetKey, ignored);

            if (ignored) {
                continue;
            }
            if (!this.plugin.isTargetAllowedForChestOwner(chest, x, z, claimAccessCache, ClaimAccessType.CHEST_INTERACT)) {
                continue;
            }

            // ВАЖНО: игнорируем раньше всего
            if (hasIgnoredTargets && this.plugin.isIgnoredTarget(chest, targetKey)) {
                continue;
            }

            Object state = this.plugin.getItemContainerStateAt(chest.world, x, y, z, chunkCache);

            if (state == null) {
                cacheStale = true;
                continue;
            }

            BlockType type = this.plugin.getBlockTypeAt(chest.world, x, y, z, chunkCache);

            if (this.plugin.isAutoStorage(type) || !this.plugin.isAllowedTargetContainer(type)) {
                cacheStale = true;
                continue;
            }

            ItemContainer target = this.plugin.getItemContainer(state);
            if (target == null) {
                continue;
            }

            List<SimpleItemContainer> containers = new ArrayList<>();
            this.plugin.getTransferEngine().collectContainers(target, containers);

            if (containers.isEmpty()) {
                continue;
            }

            TargetInfo info = null;
            if (allowFallback) {
                info = new TargetInfo(containers, cachedTarget);
                fallbackTargets.add(new FallbackTarget(cachedTarget.distanceSq, info));
            }

            if (!hasSourceGroups) {
                continue;
            }

            GroupCounts counts = getOrComputeGroupCounts(cachedTarget, containers, sortingMode, now);

            for (var entry : counts.total.entrySet()) {
                String group = entry.getKey();
                if (!sourceGroups.contains(group)) {
                    continue;
                }

                if (info == null) {
                    info = new TargetInfo(containers, cachedTarget);
                }

                candidatesByGroup.computeIfAbsent(group, _ -> new ArrayList<>()).add(new GroupCandidate(entry.getValue(), cachedTarget.distanceSq, info));

                int damaged = counts.damaged.getOrDefault(group, 0);
                if (damaged > 0) {
                    damagedCandidatesByGroup.computeIfAbsent(group, _ -> new ArrayList<>()).add(new GroupCandidate(damaged, cachedTarget.distanceSq, info));
                }
            }
        }

        return new TransferTargetScanResult(candidatesByGroup, damagedCandidatesByGroup, fallbackTargets, cacheStale);
    }

    @NonNullDecl
    Set<String> collectGroupKeys(@NonNullDecl List<SimpleItemContainer> containers, AutoStorageConfig.SortingMode sortingMode) {
        Set<String> groups = new HashSet<>();

        for (SimpleItemContainer container : containers) {
            this.collectGroupKeysFromContainer(container, groups, sortingMode);
        }

        return groups;
    }

    private void collectGroupKeysFromContainer(@NonNullDecl SimpleItemContainer container, Set<String> groups, AutoStorageConfig.SortingMode sortingMode) {
        for (short i = 0; i < container.getCapacity(); ++i) {
            ItemStack stack = container.getItemStack(i);
            if (stack != null && !stack.isEmpty()) {
                String key = this.resolveGroupKey(stack, sortingMode);
                if (key != null) {
                    groups.add(key);
                }
            }
        }

    }

    String resolveGroupKey(ItemStack stack, AutoStorageConfig.SortingMode sortingMode) {
        if (stack != null && !stack.isEmpty()) {
            String itemId = stack.getItemId();
            AutoStorageConfig config = this.plugin != null ? this.plugin.getAutoStorageConfig() : null;
            SortingMode mode = sortingMode != null ? sortingMode : (config != null ? config.getDefaultSortingMode() : SortingMode.ITEM_ID);
            if (mode == SortingMode.ITEM_ID) {
                return itemId;
            } else if (mode == SortingMode.ITEM_CATEGORY) {
                Item item = stack.getItem();
                String category = this.resolveCategoryKey(item);
                return category != null ? category : itemId;
            } else {
                return itemId;
            }
        } else {
            return null;
        }
    }

    private String resolveCategoryKey(Item item) {
        if (item == null) {
            return null;
        } else {
            String[] categories = item.getCategories();
            String selected = this.selectCategory(categories);
            if (selected != null) {
                return selected;
            } else if (item.getWeapon() != null) {
                return "weapon";
            } else if (item.getTool() != null) {
                return "tool";
            } else if (item.getArmor() != null) {
                return "armor";
            } else if (item.getGlider() != null) {
                return "glider";
            } else {
                return "utility";
            }
        }
    }

    private String selectCategory(String[] categories) {
        if (categories != null) {
            for (String category : categories) {
                if (category != null) {
                    String trimmed = category.trim();
                    if (!trimmed.isEmpty()) {
                        return trimmed;
                    }
                }
            }

        }
        return null;
    }

    private GroupCounts getOrComputeGroupCounts(CachedTarget cachedTarget, List<SimpleItemContainer> targetContainers, AutoStorageConfig.SortingMode sortingMode, long now) {
        if (cachedTarget == null) {
            return this.collectGroupCountsSnapshot(targetContainers, sortingMode).counts;
        } else {
            int sortingModeIndex = sortingMode == null ? 0 : sortingMode.ordinal();
            GroupCountsCacheEntry[] cachedByMode = cachedTarget.groupCountsCacheByMode;
            if (sortingModeIndex >= 0 && sortingModeIndex < cachedByMode.length) {
                GroupCountsCacheEntry cached = cachedByMode[sortingModeIndex];
                long maxAge = this.getTargetScanCacheMaxAgeMs();
                long fingerprintRecheckMs = this.getGroupFingerprintRecheckMs();
                if (cached != null && now - cached.cachedAtMs < maxAge) {
                    if (now < cached.nextFingerprintCheckAtMs) {
                        return cached.counts;
                    }

                    long fingerprint = this.computeGroupCountFingerprint(targetContainers);
                    if (cached.fingerprint == fingerprint) {
                        cached.nextFingerprintCheckAtMs = now + fingerprintRecheckMs;
                        return cached.counts;
                    }
                }

                GroupCountsSnapshot snapshot = this.collectGroupCountsSnapshot(targetContainers, sortingMode);
                cachedByMode[sortingModeIndex] = new GroupCountsCacheEntry(now, snapshot.counts, snapshot.fingerprint, now + fingerprintRecheckMs);
                return snapshot.counts;
            } else {
                return this.collectGroupCountsSnapshot(targetContainers, sortingMode).counts;
            }
        }
    }

    @NonNullDecl
    private GroupCountsSnapshot collectGroupCountsSnapshot(List<SimpleItemContainer> containers, AutoStorageConfig.SortingMode sortingMode) {
        GroupCounts counts = new GroupCounts();
        if (containers != null && !containers.isEmpty()) {
            long sum = 0L;
            long xor = 0L;
            long entries = 0L;
            TransferEngine transferEngine = this.plugin.getTransferEngine();

            for (SimpleItemContainer container : containers) {
                for (short i = 0; i < container.getCapacity(); ++i) {
                    ItemStack stack = container.getItemStack(i);
                    if (stack != null && !stack.isEmpty()) {
                        long value = this.computeStackFingerprintValue(stack);
                        sum += value;
                        xor ^= value;
                        ++entries;
                        String key = this.resolveGroupKey(stack, sortingMode);
                        if (key != null) {
                            counts.total.merge(key, stack.getQuantity(), Integer::sum);
                            if (transferEngine.isDamaged(stack)) {
                                counts.damaged.merge(key, stack.getQuantity(), Integer::sum);
                            }
                        }
                    }
                }
            }

            long fingerprint = this.composeGroupFingerprint(sum, xor, entries);
            return new GroupCountsSnapshot(counts, fingerprint);
        } else {
            return new GroupCountsSnapshot(counts, 0L);
        }
    }

    private long computeGroupCountFingerprint(List<SimpleItemContainer> containers) {
        if (containers != null && !containers.isEmpty()) {
            long sum = 0L;
            long xor = 0L;
            long entries = 0L;

            for (SimpleItemContainer container : containers) {
                if (container != null) {
                    for (short i = 0; i < container.getCapacity(); ++i) {
                        ItemStack stack = container.getItemStack(i);
                        if (stack != null && !stack.isEmpty()) {
                            long value = this.computeStackFingerprintValue(stack);
                            sum += value;
                            xor ^= value;
                            ++entries;
                        }
                    }
                }
            }

            return this.composeGroupFingerprint(sum, xor, entries);
        } else {
            return 0L;
        }
    }

    private long computeStackFingerprintValue(ItemStack stack) {
        if (stack != null && !stack.isEmpty()) {
            String itemId = stack.getItemId();
            long idHash = itemId.hashCode();
            long quantity = stack.getQuantity();
            long damaged = this.plugin.getTransferEngine().isDamaged(stack) ? 1L : 0L;
            long mixed = idHash * -7046029254386353131L;
            mixed ^= quantity * -4417276706812531889L;
            mixed ^= damaged * 1609587929392839161L;
            return this.mix64(mixed);
        } else {
            return 0L;
        }
    }

    private long composeGroupFingerprint(long sum, long xor, long entries) {
        return this.mix64(sum) ^ Long.rotateLeft(this.mix64(xor), 1) ^ entries * -GROUP_FINGERPRINT_MULTIPLIER;
    }

    private long mix64(long value) {
        long z = value - GROUP_FINGERPRINT_MULTIPLIER;
        z = (z ^ z >>> 30) * -GROUP_FINGERPRINT_MULTIPLIER;
        z = (z ^ z >>> 27) * -GROUP_FINGERPRINT_MULTIPLIER;
        return z ^ z >>> 31;
    }

    static void invalidateCachedGroupCounts(CachedTarget cachedTarget) {
        if (cachedTarget != null) {
            GroupCountsCacheEntry[] cachedByMode = cachedTarget.groupCountsCacheByMode;

            Arrays.fill(cachedByMode, null);

        }
    }

    TargetScanCache getOrRebuildTargetScanCache(TrackedChest chest, Vector3i center, int rH, int rV) {
        if (chest != null && chest.world != null && center != null) {
            TargetScanCache cache = this.targetScanCaches.computeIfAbsent(chest.key, (_) -> new TargetScanCache());
            Set<Long> loadedChunks = this.collectLoadedChunksInRange(chest.world, center, rH);
            long now = System.currentTimeMillis();
            synchronized (cache.lock) {
                boolean cacheExpired = cache.rebuiltAtMs <= 0L || now - cache.rebuiltAtMs >= this.getTargetScanCacheMaxAgeMs();
                boolean needsRebuild = cache.dirty || cacheExpired || cache.horizontalRadius != rH || cache.verticalRadius != rV || !loadedChunks.equals(cache.loadedChunks);
                if (needsRebuild) {
                    this.rebuildTargetScanCache(chest, cache, center, rH, rV, loadedChunks, now);
                }

                return cache;
            }
        } else {
            return new TargetScanCache();
        }
    }

    private void rebuildTargetScanCache(TrackedChest chest, TargetScanCache cache, Vector3i center, int rH, int rV, Set<Long> loadedChunks, long rebuiltAtMs) {
        List<CachedTarget> rebuilt = new ArrayList<>();
        AutoStorage.ScanChunkCache chunkCache = new AutoStorage.ScanChunkCache();

        for (int dx = -rH; dx <= rH; ++dx) {
            for (int dy = -rV; dy <= rV; ++dy) {
                for (int dz = -rH; dz <= rH; ++dz) {
                    if (dx != 0 || dy != 0 || dz != 0) {
                        int x = center.x() + dx;
                        int y = center.y() + dy;
                        int z = center.z() + dz;
                        Object targetState = this.plugin.getItemContainerStateAt(chest.world, x, y, z, chunkCache);
                        if (targetState != null) {
                            BlockType targetType = this.plugin.getBlockTypeAt(chest.world, x, y, z, chunkCache);
                            if (!this.plugin.isAutoStorage(targetType) && this.plugin.isAllowedTargetContainer(targetType)) {
                                long distanceSq = (long) dx * (long) dx + (long) dy * (long) dy + (long) dz * (long) dz;
                                rebuilt.add(new CachedTarget(x, y, z, distanceSq));
                            }
                        }
                    }
                }
            }
        }

        cache.targets = rebuilt.isEmpty() ? List.of() : List.copyOf(rebuilt);
        cache.loadedChunks = loadedChunks.isEmpty() ? Set.of() : Set.copyOf(loadedChunks);
        cache.horizontalRadius = rH;
        cache.verticalRadius = rV;
        cache.rebuiltAtMs = rebuiltAtMs;
        cache.dirty = false;
    }

    @NonNullDecl
    private Set<Long> collectLoadedChunksInRange(World world, Vector3i center, int rH) {
        if (world != null && center != null && rH >= 0) {
            int minX = center.x() - rH;
            int maxX = center.x() + rH;
            int minZ = center.z() - rH;
            int maxZ = center.z() + rH;
            int minChunkX = ChunkUtil.chunkCoordinate(minX);
            int maxChunkX = ChunkUtil.chunkCoordinate(maxX);
            int minChunkZ = ChunkUtil.chunkCoordinate(minZ);
            int maxChunkZ = ChunkUtil.chunkCoordinate(maxZ);
            Set<Long> loaded = new HashSet<>();

            for (int chunkX = minChunkX; chunkX <= maxChunkX; ++chunkX) {
                for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; ++chunkZ) {
                    long chunkIndex = ChunkUtil.indexChunk(chunkX, chunkZ);
                    if (world.getChunkIfInMemory(chunkIndex) != null) {
                        loaded.add(chunkIndex);
                    }
                }
            }

            return loaded;
        } else {
            return Set.of();
        }
    }

    void markAllTargetScanCachesDirty() {
        for (TargetScanCache cache : this.targetScanCaches.values()) {
            if (cache != null) {
                cache.dirty = true;
            }
        }

    }

    void removeTargetScanCache(String chestKey) {
        if (chestKey != null && !chestKey.isBlank()) {
            this.targetScanCaches.remove(chestKey);
        }
    }

    void clearTargetScanCaches() {
        this.targetScanCaches.clear();
    }

    void markTargetScanCacheDirty(TrackedChest chest) {
        if (chest != null && chest.key != null && !chest.key.isBlank()) {
            this.targetScanCaches.computeIfAbsent(chest.key, (_) -> new TargetScanCache()).dirty = true;
        }
    }

    void markTargetScanCachesDirtyAround(World world, Vector3i pos) {
        if (world != null && pos != null) {
            Map<String, TrackedChest> trackedChests = this.plugin.getTrackedChests();
            if (!trackedChests.isEmpty()) {
                String worldName = world.getName();
                if (!worldName.isBlank()) {
                    int horizontalMax = this.plugin.getTrackedHorizontalRadiusMax();
                    AutoStorageConfig config = this.plugin.getAutoStorageConfig();
                    if (horizontalMax < 1 && config != null) {
                        horizontalMax = config.getHorizontalRadiusMax();
                    }

                    if (horizontalMax < 0) {
                        horizontalMax = 0;
                    }

                    Set<String> nearbyChestKeys = this.plugin.getTrackedChestKeysNear(worldName, pos, horizontalMax);
                    if (!nearbyChestKeys.isEmpty()) {
                        for (String chestKey : nearbyChestKeys) {
                            if (chestKey != null && !chestKey.isBlank()) {
                                TrackedChest chest = trackedChests.get(chestKey);
                                if (chest != null && chest.world != null && this.plugin.isWithinRange(chest, pos)) {
                                    this.markTargetScanCacheDirty(chest);
                                    if (chest.idleDueToNonTransferable) {
                                        this.plugin.requestImmediateChestRun(chest, false);
                                    }
                                }
                            }
                        }

                    }
                }
            }
        }
    }

    private ItemPreviewResult collectItemPreviews(ItemContainer container, int limit) {
        if (container == null) {
            return new ItemPreviewResult(List.of(), 0);
        } else {
            List<SimpleItemContainer> containers = new ArrayList<>();
            this.plugin.getTransferEngine().collectContainers(container, containers);
            if (containers.isEmpty()) {
                return new ItemPreviewResult(List.of(), 0);
            } else {
                Map<String, Integer> counts = new HashMap<>();

                for (SimpleItemContainer simple : containers) {
                    for (short i = 0; i < simple.getCapacity(); ++i) {
                        ItemStack stack = simple.getItemStack(i);
                        if (stack != null && !stack.isEmpty()) {
                            String itemId = stack.getItemId();
                            if (!itemId.isBlank()) {
                                counts.merge(itemId, stack.getQuantity(), Integer::sum);
                            }
                        }
                    }
                }

                if (counts.isEmpty()) {
                    return new ItemPreviewResult(List.of(), 0);
                } else {
                    List<Map.Entry<String, Integer>> entries = new ArrayList<>(counts.entrySet());

                    entries.sort(Comparator.comparingInt((Map.Entry<String, Integer> entry) -> entry.getValue()).reversed().thenComparing(Map.Entry::getKey));
                    int total = entries.size();
                    int capped = limit <= 0 ? 0 : Math.min(limit, total);
                    List<AutoStorage.ItemPreview> previews = new ArrayList<>(capped);

                    for (int i = 0; i < capped; ++i) {
                        Map.Entry<String, Integer> entry = entries.get(i);
                        String itemId = entry.getKey();
                        previews.add(new AutoStorage.ItemPreview(itemId, entry.getValue()));
                    }

                    return new ItemPreviewResult(previews, total);
                }
            }
        }
    }

    @NonNullDecl
    String displayBlockId(BlockType blockType) {
        if (blockType == null) {
            return "unknown";
        } else {
            String id = blockType.getId();
            if (id != null && !id.isBlank()) {
                return id.charAt(0) == '*' ? id.substring(1) : id;
            } else {
                return "unknown";
            }
        }
    }

    String resolveItemIdForBlock(BlockType blockType) {
        if (blockType == null) {
            return null;
        } else {
            Item item = blockType.getItem();
            if (item != null) {
                String id = item.getId();
                if (id != null && !id.isBlank()) {
                    return id;
                }
            }

            String blockId = blockType.getId();
            return this.resolveItemIdForBlockId(blockId);
        }
    }

    private String resolveItemIdForBlockId(String blockId) {
        if (blockId != null && !blockId.isBlank()) {
            String normalized = this.normalizeBlockId(blockId);
            if (!normalized.isBlank() && !"unknown".equalsIgnoreCase(normalized)) {
                String cached = this.blockItemIdCache.get(normalized);
                if (cached != null) {
                    return cached.isBlank() ? null : cached;
                } else {
                    String found = null;
                    Item direct = Item.getAssetMap().getAsset(normalized);
                    if (direct != null) {
                        String id = direct.getId();
                        if (id != null && !id.isBlank()) {
                            found = id;
                        }
                    }

                    if (found == null) {
                        for (Item candidate : Item.getAssetMap().getAssetMap().values()) {
                            if (candidate != null && candidate.hasBlockType()) {
                                String candidateBlockId = candidate.getBlockId();
                                if (candidateBlockId != null && !candidateBlockId.isBlank()) {
                                    String normalizedCandidate = this.normalizeBlockId(candidateBlockId);
                                    if (normalizedCandidate.equalsIgnoreCase(normalized)) {
                                        String id = candidate.getId();
                                        if (id != null && !id.isBlank()) {
                                            found = id;
                                            break;
                                        }
                                    }
                                }
                            }
                        }
                    }

                    this.blockItemIdCache.put(normalized, found != null ? found : "");
                    return found;
                }
            } else {
                return null;
            }
        } else {
            return null;
        }
    }

    private String normalizeBlockId(String blockId) {
        if (blockId == null) {
            return "";
        } else {
            String id = blockId;
            if (!blockId.isEmpty() && blockId.charAt(0) == '*') {
                id = blockId.substring(1);
            }

            String[] suffixes = new String[]{"_OpenWindow", "_ClosedWindow", "_CloseWindow", "_Open", "_Closed", "_Close"};

            for (String suffix : suffixes) {
                if (this.endsWithIgnoreCase(id, suffix)) {
                    id = id.substring(0, id.length() - suffix.length());
                    break;
                }
            }

            return id;
        }
    }

    private boolean endsWithIgnoreCase(String value, String suffix) {
        if (value != null && suffix != null) {
            return suffix.length() <= value.length() && value.regionMatches(true, value.length() - suffix.length(), suffix, 0, suffix.length());
        } else {
            return false;
        }
    }

    private long getTargetScanCacheMaxAgeMs() {
        AutoStorageConfig config = this.plugin.getAutoStorageConfig();
        if (config == null) {
            return 15000L;
        } else {
            long configured = config.getTargetScanCacheMaxAgeMs();
            if (configured < 1L) {
                configured = 1L;
            }

            return configured;
        }
    }

    private long getGroupFingerprintRecheckMs() {
        long cacheMaxAge = this.getTargetScanCacheMaxAgeMs();
        long recheck = Math.min(cacheMaxAge, GROUP_FINGERPRINT_RECHECK_MAX_MS);
        if (recheck < 100L) {
            recheck = 100L;
        }

        return recheck;
    }

    record TargetInfo(List<SimpleItemContainer> containers, CachedTarget cachedTarget) {
    }

    record NearbyTargetScanResult(List<NearbyTargetCandidate> targets, int linkedTotal, boolean cacheStale) {
        static final NearbyTargetScanResult EMPTY = new NearbyTargetScanResult(List.of(), 0, false);
    }

    record NearbyTargetCandidate(String key, int x, int y, int z, long distanceSq, Object state, BlockType blockType,
                                 boolean ignored) {
    }

    record NearbyChestCandidate(long distanceSq, AutoStorage.NearbyChestView view) {
    }

    record TransferTargetScanResult(Map<String, List<GroupCandidate>> candidatesByGroup,
                                    Map<String, List<GroupCandidate>> damagedCandidatesByGroup,
                                    List<FallbackTarget> fallbackTargets, boolean cacheStale) {
    }

    record FallbackTarget(long distanceSq, TargetInfo target) {
    }

    record GroupCandidate(int count, long distanceSq, TargetInfo target) {
    }

    static final class CachedTarget {
        final int x;
        final int y;
        final int z;
        final long distanceSq;
        final GroupCountsCacheEntry[] groupCountsCacheByMode;

        CachedTarget(int x, int y, int z, long distanceSq) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.distanceSq = distanceSq;
            this.groupCountsCacheByMode = new GroupCountsCacheEntry[SortingMode.values().length];
        }
    }

    static final class TargetScanCache {
        final Object lock = new Object();

        volatile boolean dirty = true;
        volatile int horizontalRadius = -1;
        volatile int verticalRadius = -1;
        volatile long rebuiltAtMs = 0L;
        volatile Set<Long> loadedChunks = Set.of();
        volatile List<CachedTarget> targets = List.of();

        TargetScanCache() {
        }
    }

    static final class GroupCounts {
        final Map<String, Integer> total = new HashMap<>();
        final Map<String, Integer> damaged = new HashMap<>();

        GroupCounts() {
        }
    }

    private record GroupCountsSnapshot(GroupCounts counts, long fingerprint) {
    }

    static final class GroupCountsCacheEntry {
        final long cachedAtMs;
        final GroupCounts counts;
        final long fingerprint;
        volatile long nextFingerprintCheckAtMs;

        GroupCountsCacheEntry(long cachedAtMs, GroupCounts counts, long fingerprint, long nextFingerprintCheckAtMs) {
            this.cachedAtMs = cachedAtMs;
            this.counts = counts;
            this.fingerprint = fingerprint;
            this.nextFingerprintCheckAtMs = nextFingerprintCheckAtMs;
        }
    }

    private record ItemPreviewResult(List<AutoStorage.ItemPreview> previews, int total) {
        private ItemPreviewResult(List<AutoStorage.ItemPreview> previews, int total) {
            this.previews = previews != null ? previews : List.of();
            this.total = total;
        }
    }
}
