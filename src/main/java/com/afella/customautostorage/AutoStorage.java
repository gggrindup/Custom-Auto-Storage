package com.afella.customautostorage;

import com.hypixel.hytale.assetstore.AssetRegistry;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.event.EventPriority;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.LoadAssetEvent;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.afella.customautostorage.command.AutoStorageCommand;
import com.afella.customautostorage.compat.HytaleCompat;
import com.afella.customautostorage.compat.claim.ClaimProtectionProvider;
import com.afella.customautostorage.compat.claim.ClaimProtectionService;
import com.afella.customautostorage.compat.claim.ClaimProtectionProvider.ClaimAccessType;
import com.afella.customautostorage.system.AutoStorageBreakSystem;
import com.afella.customautostorage.system.AutoStorageCraftPermissionCheckSystem;
import com.afella.customautostorage.system.AutoStorageCraftPermissionSystem;
import com.afella.customautostorage.system.AutoStoragePlaceSystem;
import com.afella.customautostorage.system.AutoStorageUISystem;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;

import org.checkerframework.checker.nullness.compatqual.NonNullDecl;
import org.joml.Vector3i;

public class AutoStorage extends JavaPlugin {
    private static final HytaleLogger LOGGER = HytaleLogger.get("AutoStorage");
    private static final String PERMISSION_CONFIG_ANY = "autostorage.config.any";
    private static final long TARGET_SCAN_CACHE_MAX_AGE_DEFAULT_MS = 15000L;
    private static final long IDLE_RETRY_BACKOFF_MAX_DEFAULT_MS = 60000L;
    private static final long LISTENER_HEALTH_CHECK_INTERVAL_MS = 120000L;
    private static final int MISSING_SOURCE_UNTRACK_CONFIRMATIONS = 8;
    private static final long MISSING_SOURCE_UNTRACK_GRACE_MS = 30000L;
    private static final long MISSING_SOURCE_CONFIRMATION_WINDOW_MS = 600000L;
    private static final long GLOBAL_MIN_DELAY_MS = 1L;
    private static final short AUTO_STORAGE_CONTAINER_CAPACITY = 54;
    private AutoStorageConfig config;
    private ScheduledExecutorService scheduler;
    private final CraftManager craftManager = new CraftManager(this);
    private final TransferEngine transferEngine = new TransferEngine(this);
    private final TargetResolver targetResolver = new TargetResolver(this);
    private final RangeVisualizationManager rangeVisualizationManager = new RangeVisualizationManager(this);
    private final ClaimProtectionService claimProtectionService = new ClaimProtectionService();
    private final ContainerComponentBridge containerBridge = new ContainerComponentBridge();
    private final Map<String, TrackedChest> trackedChests = new ConcurrentHashMap<>();
    private final TrackedChestLifecycleManager trackedChestLifecycle;
    private final Map<String, Boolean> allowedTargetBlockCache;
    private volatile int trackedHorizontalRadiusMax;

    public AutoStorage(@Nonnull JavaPluginInit init) {
        super(init);
        this.trackedChestLifecycle = new TrackedChestLifecycleManager(this, this.trackedChests);
        this.allowedTargetBlockCache = new ConcurrentHashMap<>();
        this.trackedHorizontalRadiusMax = 0;
    }

    protected void setup() {
        this.config = AutoStorageConfig.load(this.getDataDirectory());
        this.scheduler = Executors.newSingleThreadScheduledExecutor((r) -> {
            Thread t = new Thread(r, "AutoStorage");
            t.setDaemon(true);
            return t;
        });
        this.preloadTrackedChests();
        this.getEventRegistry().register(EventPriority.LATE, LoadAssetEvent.class, (_) -> {
            if (!this.craftManager.isSelfReloadingAssets()) {
                try {
                    this.craftManager.applyRecipeOverride(this.config);
                } catch (Throwable throwable) {
                    LOGGER.atWarning().withCause(throwable).log("[AutoStorage] Failed to apply recipe override on asset load.");
                }

            }
        });
        if (AssetRegistry.HAS_INIT) {
            try {
                this.craftManager.applyRecipeOverride(this.config);
            } catch (Throwable throwable) {
                LOGGER.atWarning().withCause(throwable).log("[AutoStorage] Failed to apply recipe override during setup.");
            }
        }

        this.getEntityStoreRegistry().registerSystem(new AutoStoragePlaceSystem(this));
        this.getEntityStoreRegistry().registerSystem(new AutoStorageBreakSystem(this));
        this.getEntityStoreRegistry().registerSystem(new AutoStorageUISystem(this));
        this.getEntityStoreRegistry().registerSystem(new AutoStorageCraftPermissionSystem(this));
        this.getEntityStoreRegistry().registerSystem(new AutoStorageCraftPermissionCheckSystem(this));
        this.craftManager.registerCraftPermissionSyncListeners();
        this.getCommandRegistry().registerCommand(new AutoStorageCommand(this));
        this.enableEventMode();
        this.craftManager.syncCraftPermissionForAllPlayers();

        try {
            this.scheduler.schedule(this::loadTrackedChestsWithRetry, GLOBAL_MIN_DELAY_MS, TimeUnit.SECONDS);
        } catch (RuntimeException throwable) {
            LOGGER.atWarning().withCause(throwable).log("[AutoStorage] Failed to schedule tracked chest loading.");
        }

        this.scheduleListenerHealthCheck();
    }

    public AutoStorageConfig getAutoStorageConfig() {
        return this.config;
    }

    ScheduledExecutorService getScheduler() {
        return this.scheduler;
    }

    Path getPluginDataDirectory() {
        return this.getDataDirectory();
    }

    void removeTargetScanCache(String chestKey) {
        this.targetResolver.removeTargetScanCache(chestKey);
    }

    TransferEngine getTransferEngine() {
        return this.transferEngine;
    }

    Map<String, TrackedChest> getTrackedChests() {
        return this.trackedChests;
    }

    int getTrackedHorizontalRadiusMax() {
        return this.trackedHorizontalRadiusMax;
    }

    public void reloadConfig() {
        this.config = AutoStorageConfig.load(this.getDataDirectory());
        this.allowedTargetBlockCache.clear();
        this.clampTrackedChestRadii();
        this.resetIdleRetryBackoffForAllChests();
        this.markAllTargetScanCachesDirty();
        this.disableEventMode();
        this.enableEventMode();
        this.applyRecipeOverride(this.config);
        this.syncCraftPermissionForAllPlayers();
        (LOGGER.atInfo()).log("[AutoStorage] Config reloaded");
    }

    public boolean canConfigure(PlayerRef playerRef, UUID playerUuid, String worldName, Vector3i pos) {
        if (!this.canInteractWithClaims(playerUuid, worldName, pos)) {
            return false;
        } else if (!this.config.isOnlyStorageOwnerCanConfig()) {
            return true;
        } else if (HytaleCompat.hasPermission(playerRef, PERMISSION_CONFIG_ANY)) {
            return true;
        } else if (playerUuid == null) {
            return false;
        } else {
            TrackedChest chest = this.getTrackedChest(worldName, pos);
            if (chest == null) {
                return true;
            } else {
                UUID ownerUuid = chest.ownerUuid;
                return ownerUuid == null || ownerUuid.equals(playerUuid);
            }
        }
    }

    public boolean canInteractWithClaims(UUID playerUuid, String worldName, Vector3i pos) {
        return pos != null && this.canInteractWithClaims(playerUuid, worldName, pos.x(), pos.z(), ClaimAccessType.CHEST_INTERACT);
    }

    public void registerClaimProtectionProvider(ClaimProtectionProvider provider) {
        this.claimProtectionService.register(provider);
    }

    public void recordClaimAccessActor(String worldName, Vector3i pos, UUID playerUuid) {
        if (playerUuid != null && worldName != null && !worldName.isBlank() && pos != null) {
            TrackedChest chest = this.getTrackedChest(worldName, pos);
            if (chest != null) {
                if (!playerUuid.equals(chest.claimAccessUuid)) {
                    chest.claimAccessUuid = playerUuid;
                    this.saveTrackedChestsAsync();
                }

            }
        }
    }

    public AutoStorageConfig.SortingMode getSortingModeFor(String worldName, Vector3i pos) {
        TrackedChest chest = this.getTrackedChest(worldName, pos);
        return chest != null && chest.sortingModeOverride != null ? chest.sortingModeOverride : this.config.getDefaultSortingMode();
    }

    public boolean setSortingModeFor(String worldName, Vector3i pos, AutoStorageConfig.SortingMode mode) {
        TrackedChest chest = this.getTrackedChest(worldName, pos);
        if (chest == null) {
            return false;
        } else {
            chest.sortingModeOverride = mode;
            this.saveTrackedChestsAsync();
            return true;
        }
    }

    public boolean isTransferAnywhereIfMissing(String worldName, Vector3i pos) {
        TrackedChest chest = this.getTrackedChest(worldName, pos);
        return chest != null && chest.transferAnywhereIfMissing;
    }

    public void toggleTransferAnywhereIfMissing(String worldName, Vector3i pos) {
        TrackedChest chest = this.getTrackedChest(worldName, pos);
        synchronized (chest.lock) {
            chest.transferAnywhereIfMissing = !chest.transferAnywhereIfMissing;
            this.saveTrackedChestsAsync();
        }
    }

    public int getHorizontalRadiusFor(String worldName, Vector3i pos) {
        TrackedChest chest = this.getTrackedChest(worldName, pos);
        return this.getHorizontalRadiusFor(chest);
    }

    public int getVerticalRadiusFor(String worldName, Vector3i pos) {
        TrackedChest chest = this.getTrackedChest(worldName, pos);
        return this.getVerticalRadiusFor(chest);
    }

    public boolean adjustRadiusFor(String worldName, Vector3i pos, int horizontalDelta, int verticalDelta) {
        TrackedChest chest = this.getTrackedChest(worldName, pos);
        if (chest == null) {
            return false;
        } else {
            this.ensureRadiusInitialized(chest);
            int maxHorizontal = this.config.getHorizontalRadiusMax();
            int maxVertical = this.config.getVerticalRadiusMax();
            int nextHorizontal = this.clampRadius(chest.horizontalRadius + horizontalDelta, 1, maxHorizontal);
            int nextVertical = this.clampRadius(chest.verticalRadius + verticalDelta, 1, maxVertical);
            boolean changed = false;
            if (nextHorizontal != chest.horizontalRadius) {
                chest.horizontalRadius = nextHorizontal;
                changed = true;
            }

            if (nextVertical != chest.verticalRadius) {
                chest.verticalRadius = nextVertical;
                changed = true;
            }

            if (changed) {
                this.markTargetScanCacheDirty(chest);
                this.recalculateTrackedHorizontalRadiusMax();
                this.saveTrackedChestsAsync();
            }

            return true;
        }
    }

    public int countLinkedChests(String worldName, Vector3i pos) {
        return this.targetResolver.countLinkedChests(worldName, pos);
    }

    public NearbyChestsSnapshot getNearbyChests(String worldName, Vector3i pos, int offset, int limit) {
        return this.targetResolver.getNearbyChests(worldName, pos, offset, limit);
    }

    public boolean toggleIgnoredTarget(String worldName, Vector3i pos, String targetKey) {
        return this.trackedChestLifecycle.toggleIgnoredTarget(worldName, pos, targetKey);
    }

    public boolean isIgnoredTarget(String worldName, Vector3i pos, String targetKey) {
        TrackedChest chest = this.getTrackedChest(worldName, pos);
        return this.isIgnoredTarget(chest, targetKey);
    }

    private void enableEventMode() {
        if (this.scheduler != null) {
            long delay = this.config.isTransferImmediate() ? 0L : (long) this.config.getTransferAfterLastInteractionMs();
            long now = System.currentTimeMillis();

            for (TrackedChest chest : this.trackedChests.values()) {
                this.ensureChangeListener(chest);
                if (!this.config.isTransferImmediate()) {
                    chest.depositDelayUntil = now + delay;
                }

                this.scheduleChest(chest, delay);
            }

        }
    }

    private void disableEventMode() {
        for (TrackedChest chest : this.trackedChests.values()) {
            this.cleanupTrackedChest(chest);
        }
    }

    void scheduleChest(TrackedChest chest, long delayMs) {
        if (this.scheduler != null && !this.scheduler.isShutdown()) {
            long now = System.currentTimeMillis();
            long targetAt = now + Math.max(0L, delayMs);
            long cooldownAt = chest.cooldownUntil;
            if (cooldownAt > targetAt) {
                targetAt = cooldownAt;
            }

            long depositAt = chest.depositDelayUntil;
            if (depositAt > targetAt) {
                targetAt = depositAt;
            }

            long delay = Math.max(0L, targetAt - now);
            synchronized (chest) {
                ScheduledFuture<?> existing = chest.scheduledTask;
                if (existing != null && !existing.isDone()) {
                    if (chest.scheduledAt > now && chest.scheduledAt <= targetAt) {
                        return;
                    }

                    existing.cancel(false);
                }

                chest.scheduledAt = targetAt;

                try {
                    chest.scheduledTask = this.scheduler.schedule(() -> this.runEventTransfer(chest), delay, TimeUnit.MILLISECONDS);
                } catch (RuntimeException throwable) {
                    chest.scheduledTask = null;
                    chest.scheduledAt = 0L;
                    chest.inFlight.set(false);
                    LOGGER.atWarning().withCause(throwable).log("[AutoStorage] Failed to schedule transfer run.");
                }

            }
        }
    }

    private void runEventTransfer(@NonNullDecl TrackedChest chest) {
        if (this.trackedChests.containsKey(chest.key)) {
            long depositAt = chest.depositDelayUntil;
            if (depositAt > 0L) {
                long now = System.currentTimeMillis();
                if (depositAt > now) {
                    this.scheduleChest(chest, depositAt - now);
                    return;
                }
            }

            if (!chest.inFlight.compareAndSet(false, true)) {
                this.scheduleChest(chest, this.config.getTransferIntervalMs());
            } else if (chest.world.isInThread()) {
                try {
                    this.processTransfer(chest);
                } catch (Throwable throwable) {
                    chest.inFlight.set(false);
                    chest.idleDueToNonTransferable = true;
                    this.scheduleIdleRetryWithBackoff(chest);
                    LOGGER.atSevere().withCause(throwable).log("[AutoStorage] Transfer processing crashed on world thread.");
                }

            } else {
                try {
                    chest.world.execute(() -> {
                        try {
                            this.processTransfer(chest);
                        } catch (Throwable throwable) {
                            chest.inFlight.set(false);
                            chest.idleDueToNonTransferable = true;
                            this.scheduleIdleRetryWithBackoff(chest);
                            LOGGER.atSevere().withCause(throwable).log("[AutoStorage] Transfer processing crashed in scheduled world task.");
                        }

                    });
                } catch (RuntimeException var7) {
                    chest.inFlight.set(false);
                    this.scheduleChest(chest, 500L);
                }

            }
        }
    }

    void requestImmediateChestRun(TrackedChest chest, boolean clearDepositDelay) {
        if (chest != null && this.trackedChests.containsKey(chest.key)) {
            chest.idleDueToNonTransferable = false;
            this.resetIdleRetryBackoff(chest);
            if (clearDepositDelay) {
                chest.depositDelayUntil = 0L;
            }

            if (chest.inFlight.get()) {
                chest.eventPending.set(true);
            } else {
                this.scheduleChest(chest, 0L);
            }
        }
    }

    private void onAutoStorageContainerChange(@NonNullDecl TrackedChest chest) {
        if (this.trackedChests.containsKey(chest.key)) {
            if (!chest.suppressContainerChangeEvents) {
                chest.idleDueToNonTransferable = false;
                this.resetIdleRetryBackoff(chest);
                long delay = this.config.isTransferImmediate() ? 0L : (long) this.config.getTransferAfterLastInteractionMs();
                if (chest.inFlight.get()) {
                    if (!this.config.isTransferImmediate()) {
                        chest.depositDelayUntil = System.currentTimeMillis() + delay;
                    } else {
                        chest.depositDelayUntil = 0L;
                    }

                    chest.eventPending.set(true);
                } else {
                    if (!this.config.isTransferImmediate()) {
                        chest.depositDelayUntil = System.currentTimeMillis() + delay;
                    } else {
                        chest.depositDelayUntil = 0L;
                    }

                    this.scheduleChest(chest, delay);
                }
            }
        }
    }

    private void scheduleListenerHealthCheck() {
        if (this.scheduler != null && !this.scheduler.isShutdown()) {
            try {
                this.scheduler.schedule(this::runListenerHealthCheck, LISTENER_HEALTH_CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);
            } catch (RuntimeException throwable) {
                ((LOGGER.atWarning()).withCause(throwable)).log("[AutoStorage] Failed to schedule listener health check.");
            }
        }
    }

    private void runListenerHealthCheck() {
        try {
            if (!this.trackedChests.isEmpty()) {
                Map<World, List<TrackedChest>> byWorld = new HashMap<>();

                for (TrackedChest chest : this.trackedChests.values()) {
                    if (chest != null && chest.world != null && chest.key != null && !chest.key.isBlank() && this.trackedChests.containsKey(chest.key)) {
                        (byWorld.computeIfAbsent(chest.world, ignored -> new ArrayList<>())).add(chest);
                    }
                }

                for (Map.Entry<World, List<TrackedChest>> entry : byWorld.entrySet()) {
                    this.refreshListenersForWorld(entry.getKey(), entry.getValue());
                }
            }
        } catch (Throwable throwable) {
            ((LOGGER.atWarning()).withCause(throwable)).log("[AutoStorage] Listener health check failed.");
        } finally {
            this.scheduleListenerHealthCheck();
        }
    }

    private void refreshListenersForWorld(World world, List<TrackedChest> chests) {
        if (world != null && chests != null && !chests.isEmpty()) {
            Runnable work = () -> {
                for (TrackedChest chest : chests) {
                    if (chest != null && this.trackedChests.containsKey(chest.key)) {
                        this.ensureChangeListenerOnWorldThread(chest, null);
                    }
                }

            };
            if (world.isInThread()) {
                try {
                    work.run();
                } catch (Throwable throwable) {
                    (LOGGER.atWarning()).withCause(throwable).log("[AutoStorage] Listener health check failed on world thread.");
                }

            } else {
                try {
                    world.execute(work);
                } catch (RuntimeException throwable) {
                    if (this.isWorldThreadNotAcceptingTasks(throwable)) {
                        return;
                    }

                    ((LOGGER.atWarning()).withCause(throwable)).log("[AutoStorage] Failed to schedule listener health check on world thread.");
                }

            }
        }
    }

    boolean isWorldThreadNotAcceptingTasks(Throwable throwable) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current instanceof IllegalThreadStateException) {
                String message = current.getMessage();
                if (message != null && message.contains("World thread is not accepting tasks")) {
                    return true;
                }
            }
        }

        return false;
    }

    void ensureChangeListener(@NonNullDecl TrackedChest chest) {
        World world = chest.world;
        if (world != null) {
            if (world.isInThread()) {
                this.ensureChangeListenerOnWorldThread(chest, null);
            } else {
                try {
                    world.execute(() -> this.ensureChangeListenerOnWorldThread(chest, null));
                } catch (RuntimeException exception) {
                    if (this.isWorldThreadNotAcceptingTasks(exception)) {
                        return;
                    }
                    LOGGER.atWarning().withCause(exception).log("[AutoStorage] Failed to schedule container listener registration.");
                }
            }
        }
    }

    private void ensureChangeListenerOnWorldThread(@NonNullDecl TrackedChest chest, Object state) {
        if (this.trackedChests.containsKey(chest.key)) {
            BlockType blockType = this.getBlockTypeAt(chest.world, chest.pos.x(), chest.pos.y(), chest.pos.z());
            if (this.isAutoStorage(blockType)) {
                Object resolved = state;
                if (state == null) {
                    resolved = this.getItemContainerStateAt(chest.world, chest.pos.x(), chest.pos.y(), chest.pos.z());
                    if (resolved == null && this.ensureItemContainerComponentAt(chest.world, chest.pos.x(), chest.pos.y(), chest.pos.z())) {
                        resolved = this.getItemContainerStateAt(chest.world, chest.pos.x(), chest.pos.y(), chest.pos.z());
                    }
                }

                if (resolved != null) {
                    this.ensureAutoStorageContainerCapacity(resolved);
                    ItemContainer container = this.getItemContainer(resolved);
                    if (container != null) {
                        if (chest.container != container || chest.changeRegistration == null || !chest.changeRegistration.isRegistered()) {
                            this.unregisterChangeListener(chest);
                            chest.container = container;
                            chest.changeRegistration = HytaleCompat.registerContainerChangeEvent(container, EventPriority.NORMAL, (_) -> this.onAutoStorageContainerChange(chest));
                        }
                    }
                }
            }
        }
    }

    private void unregisterChangeListener(@NonNullDecl TrackedChest chest) {
        if (chest.changeRegistration != null) {
            chest.changeRegistration.unregister();
            chest.changeRegistration = null;
        }
        chest.container = null;
    }

    private void cancelScheduledChest(@NonNullDecl TrackedChest chest) {
        synchronized (chest.lock) {
            ScheduledFuture<?> existing = chest.scheduledTask;
            if (existing != null) {
                existing.cancel(false);
                chest.scheduledTask = null;
            }
            chest.scheduledAt = 0L;
        }
    }

    void cleanupTrackedChest(TrackedChest chest) {
        this.cancelScheduledChest(chest);
        this.unregisterChangeListener(chest);
        chest.eventPending.set(false);
        chest.cooldownUntil = 0L;
        chest.depositDelayUntil = 0L;
        chest.suppressContainerChangeEvents = false;
        chest.idleDueToNonTransferable = false;
        chest.wasOpen = false;
        this.resetIdleRetryBackoff(chest);
        this.resetMissingSourceConfirmation(chest);
    }

    private void untrackChest(TrackedChest chest) {
        this.trackedChestLifecycle.untrackChest(chest);
    }

    private void resetMissingSourceConfirmation(@NonNullDecl TrackedChest chest) {
        chest.missingSourceChecks = 0;
        chest.firstMissingSourceAt = 0L;
    }

    private boolean confirmMissingSourceBeforeUntrack(@NonNullDecl TrackedChest chest) {
        synchronized (chest.lock) {
            long now = System.currentTimeMillis();
            if (chest.firstMissingSourceAt == 0L || now - chest.firstMissingSourceAt > MISSING_SOURCE_CONFIRMATION_WINDOW_MS) {
                chest.firstMissingSourceAt = now;
                chest.missingSourceChecks = 0;
            }

            ++chest.missingSourceChecks;
            long elapsed = now - chest.firstMissingSourceAt;
            return chest.missingSourceChecks >= MISSING_SOURCE_UNTRACK_CONFIRMATIONS && elapsed >= MISSING_SOURCE_UNTRACK_GRACE_MS;
        }
    }

    private void retryTransferLater(TrackedChest chest, boolean resetMissingSourceConfirmation) {
        if (resetMissingSourceConfirmation) {
            this.resetMissingSourceConfirmation(chest);
        }

        chest.idleDueToNonTransferable = false;
        this.resetIdleRetryBackoff(chest);
        chest.inFlight.set(false);
        this.scheduleChest(chest, this.getIdleRetryBaseDelayMs());
    }

    private long getIdleRetryBaseDelayMs() {
        if (this.config == null) {
            return 1000L;
        } else {
            long configured = this.config.getIdleRetryMs();
            if (configured < GLOBAL_MIN_DELAY_MS) {
                configured = GLOBAL_MIN_DELAY_MS;
            }

            return Math.min(configured, this.getIdleRetryBackoffMaxMs());
        }
    }

    private long getIdleRetryBackoffMaxMs() {
        if (this.config == null) {
            return IDLE_RETRY_BACKOFF_MAX_DEFAULT_MS;
        } else {
            long configured = this.config.getIdleRetryBackoffMaxMs();
            if (configured < GLOBAL_MIN_DELAY_MS) {
                configured = GLOBAL_MIN_DELAY_MS;
            }

            return configured;
        }
    }

    private long getTargetScanCacheMaxAgeMs() {
        if (this.config == null) {
            return TARGET_SCAN_CACHE_MAX_AGE_DEFAULT_MS;
        } else {
            long configured = this.config.getTargetScanCacheMaxAgeMs();
            if (configured < GLOBAL_MIN_DELAY_MS) {
                configured = GLOBAL_MIN_DELAY_MS;
            }

            return configured;
        }
    }

    void resetIdleRetryBackoff(TrackedChest chest) {
        if (chest != null) {
            chest.idleRetryBackoffMs = this.getIdleRetryBaseDelayMs();
        }
    }

    private void resetIdleRetryBackoffForAllChests() {
        for (TrackedChest chest : this.trackedChests.values()) {
            this.resetIdleRetryBackoff(chest);
        }

    }

    private void scheduleIdleRetryWithBackoff(TrackedChest chest) {
        if (chest != null) {
            long baseDelay = this.getIdleRetryBaseDelayMs();
            long maxDelay = this.getIdleRetryBackoffMaxMs();
            long delay = chest.idleRetryBackoffMs;
            if (delay < baseDelay) {
                delay = baseDelay;
            } else if (delay > maxDelay) {
                delay = maxDelay;
            }

            this.scheduleChest(chest, delay);
            long next = delay >= maxDelay ? maxDelay : Math.min(maxDelay, delay * 2L);
            if (next < baseDelay) {
                next = baseDelay;
            }

            chest.idleRetryBackoffMs = next;
        }
    }

    private void scheduleIdleRetryForNonTransferable(TrackedChest chest) {
        if (chest != null) {
            long baseDelay = this.getIdleRetryBaseDelayMs();
            long delay = Math.min(this.getIdleRetryBackoffMaxMs(), this.getTargetScanCacheMaxAgeMs());
            if (delay < baseDelay) {
                delay = baseDelay;
            }

            if (delay < GLOBAL_MIN_DELAY_MS) {
                delay = GLOBAL_MIN_DELAY_MS;
            }

            chest.idleRetryBackoffMs = delay;
            this.scheduleChest(chest, delay);
        }
    }

    private void processTransfer(TrackedChest chest) {
        int moved = 0;

        boolean checkedItems = false;
        boolean sourceHasItems = false;
        boolean sourceCurrentlyNonTransferable = false;
        boolean transferFailed = false;

        boolean suppressContainerEvents = false;
        boolean justClosed = false;

        try {
            long chunkIndex = ChunkUtil.indexChunkFromBlock(
                    chest.pos.x(),
                    chest.pos.z()
            );
            if (chest.world.getChunkIfInMemory(chunkIndex) == null) {
                this.retryTransferLater(chest, true);
                return;
            }
            BlockType sourceType = this.getBlockTypeAt(
                    chest.world,
                    chest.pos.x(),
                    chest.pos.y(),
                    chest.pos.z()
            );
            if (sourceType == null) {
                this.retryTransferLater(chest, true);
                return;
            }
            if (!this.isAutoStorage(sourceType)) {
                if (this.confirmMissingSourceBeforeUntrack(chest)) {
                    this.untrackChest(chest);
                } else {
                    this.retryTransferLater(chest, false);
                }
                return;
            }

            /*
             * Работаем только с состоянием открытия.
             * Тяжёлые операции ниже не блокируем.
             */
            synchronized (chest.lock) {
                if (!this.config.isTransferImmediate()) {
                    boolean currentlyOpen = this.isAutoStorageOpen(sourceType);
                    if (chest.wasOpen && !currentlyOpen) {
                        justClosed = true;
                        chest.depositDelayUntil =
                                System.currentTimeMillis()
                                        + this.config.getTransferAfterLastInteractionMs();
                    }
                    chest.wasOpen = currentlyOpen;
                }
            }

            if (justClosed) {
                return;
            }

            Object sourceState = this.getItemContainerStateAt(
                    chest.world,
                    chest.pos.x(),
                    chest.pos.y(),
                    chest.pos.z()
            );
            if (sourceState == null) {
                this.ensureAutoStorageContainerReady(
                        chest.world,
                        chest.pos,
                        false
                );
                this.retryTransferLater(chest, true);
                return;
            }

            this.ensureAutoStorageContainerCapacity(sourceState);
            this.resetMissingSourceConfirmation(chest);
            this.ensureChangeListenerOnWorldThread(chest, sourceState);

            ItemContainer source = this.getItemContainer(sourceState);
            if (source == null) {
                return;
            }

            ArrayList<SimpleItemContainer> sourceContainers = new ArrayList<>();

            this.transferEngine.collectContainers(
                    source,
                    sourceContainers
            );

            if (sourceContainers.isEmpty()) {
                return;
            }

            sourceHasItems =
                    this.transferEngine.hasAnyItems(sourceContainers);

            checkedItems = true;

            if (!sourceHasItems) {
                return;
            }

            if (!this.isTargetAllowedForChestOwner(
                    chest,
                    chest.pos.x(),
                    chest.pos.z(),
                    null,
                    ClaimProtectionProvider.ClaimAccessType.CHEST_INTERACT
            )) {
                sourceCurrentlyNonTransferable = true;
                return;
            }

            if (!this.isTransferAllowedByOpenMode(sourceType)) {
                sourceCurrentlyNonTransferable = true;
                return;
            }

            AutoStorageConfig.SortingMode sortingMode =
                    this.getSortingModeFor(chest);

            Set<String> sourceGroups =
                    this.targetResolver.collectGroupKeys(
                            sourceContainers,
                            sortingMode
                    );

            int rH = this.getHorizontalRadiusFor(chest);
            int rV = this.getVerticalRadiusFor(chest);

            TransferBudget budget =
                    this.transferEngine.createTransferBudget();

            boolean allowFallback =
                    chest.transferAnywhereIfMissing;

            if (sourceGroups.isEmpty() && !allowFallback) {
                sourceCurrentlyNonTransferable = true;
                return;
            }

            TargetResolver.TransferTargetScanResult scanResult =
                    this.targetResolver.collectTransferTargetCandidates(
                            chest,
                            chest.pos,
                            rH,
                            rV,
                            sortingMode,
                            sourceGroups,
                            allowFallback
                    );

            List<TargetResolver.FallbackTarget> fallbackTargets =
                    scanResult.fallbackTargets();

            Map<String, List<TargetResolver.GroupCandidate>> candidatesByGroup =
                    scanResult.candidatesByGroup();

            Map<String, List<TargetResolver.GroupCandidate>> damagedCandidatesByGroup =
                    scanResult.damagedCandidatesByGroup();

            boolean canFallback =
                    allowFallback && !fallbackTargets.isEmpty();

            if (canFallback) {
                fallbackTargets.sort(
                        Comparator.comparingLong(
                                TargetResolver.FallbackTarget::distanceSq
                        )
                );
            }

            if (candidatesByGroup.isEmpty() && !canFallback) {
                sourceCurrentlyNonTransferable = true;
                return;
            }

            this.transferEngine.sortCandidates(candidatesByGroup);
            this.transferEngine.sortCandidates(damagedCandidatesByGroup);

            chest.suppressContainerChangeEvents = true;
            suppressContainerEvents = true;

            for (SimpleItemContainer sourceContainer : sourceContainers) {
                for (
                        short slot = 0;
                        slot < sourceContainer.getCapacity()
                                && !budget.isExhausted();
                        slot++
                ) {
                    ItemStack item =
                            sourceContainer.getItemStack(slot);

                    if (item == null || item.isEmpty()) {
                        continue;
                    }

                    String groupKey =
                            this.targetResolver.resolveGroupKey(
                                    item,
                                    sortingMode
                            );

                    if (groupKey == null) {
                        if (!canFallback ||
                                this.transferEngine.moveToFallback(
                                        sourceContainer,
                                        slot,
                                        fallbackTargets,
                                        budget
                                ) >= 0) {
                            continue;
                        }
                        transferFailed = true;
                        continue;
                    }

                    List<TargetResolver.GroupCandidate> candidates =
                            candidatesByGroup.get(groupKey);

                    if (candidates == null || candidates.isEmpty()) {
                        if (!canFallback ||
                                this.transferEngine.moveToFallback(
                                        sourceContainer,
                                        slot,
                                        fallbackTargets,
                                        budget
                                ) >= 0) {
                            continue;
                        }
                        transferFailed = true;
                        continue;
                    }

                    Set<TargetResolver.TargetInfo> tried =
                            this.transferEngine.newTargetIdentitySet();

                    if (this.transferEngine.isDamaged(item)) {
                        List<TargetResolver.GroupCandidate> damaged =
                                damagedCandidatesByGroup.get(groupKey);

                        if (damaged != null && !damaged.isEmpty()) {
                            int movedDamaged =
                                    this.transferEngine.moveToCandidates(
                                            sourceContainer,
                                            slot,
                                            damaged,
                                            budget,
                                            tried
                                    );

                            if (movedDamaged < 0) {
                                transferFailed = true;
                                continue;
                            }

                            if (movedDamaged > 0 || budget.isExhausted()) {
                                continue;
                            }
                        }
                    }

                    int movedQty =
                            this.transferEngine.moveToCandidates(
                                    sourceContainer,
                                    slot,
                                    candidates,
                                    budget,
                                    tried
                            );

                    if (movedQty < 0) {
                        transferFailed = true;
                        continue;
                    }

                    if (movedQty > 0
                            || budget.isExhausted()
                            || !canFallback) {
                        continue;
                    }

                    if (this.transferEngine.moveToFallbackByDistanceExcluding(
                            sourceContainer,
                            slot,
                            fallbackTargets,
                            budget,
                            tried
                    ) < 0) {
                        transferFailed = true;
                    }
                }
            }


            moved = budget.moved;

        } catch (Throwable throwable) {

            transferFailed = true;

            LOGGER.atSevere()
                    .withCause(throwable)
                    .log("[AutoStorage] Unexpected transfer failure.");

        } finally {

            if (suppressContainerEvents) {
                chest.suppressContainerChangeEvents = false;
            }


            chest.inFlight.set(false);


            if (justClosed) {

                chest.eventPending.set(false);

                if (this.trackedChests.containsKey(chest.key)) {
                    this.scheduleChest(
                            chest,
                            this.config.getTransferAfterLastInteractionMs()
                    );
                }

            } else {

                boolean pending =
                        chest.eventPending.getAndSet(false);


                if (this.trackedChests.containsKey(chest.key)) {

                    long finishedAt =
                            System.currentTimeMillis();


                    chest.cooldownUntil =
                            moved > 0
                                    ? finishedAt + this.config.getTransferIntervalMs()
                                    : 0L;


                    if (chest.depositDelayUntil <= finishedAt) {
                        chest.depositDelayUntil = 0L;
                    }


                    if (transferFailed) {

                        chest.idleDueToNonTransferable = false;

                        if (pending) {
                            this.resetIdleRetryBackoff(chest);
                            this.scheduleChest(chest, 0L);
                        } else {
                            this.scheduleIdleRetryWithBackoff(chest);
                        }


                    } else if (moved > 0) {

                        chest.idleDueToNonTransferable = false;
                        this.resetIdleRetryBackoff(chest);
                        this.scheduleChest(
                                chest,
                                this.config.getTransferIntervalMs()
                        );


                    } else if (pending) {

                        chest.idleDueToNonTransferable = false;
                        this.resetIdleRetryBackoff(chest);
                        this.scheduleChest(chest, 0L);


                    } else if (checkedItems && !sourceHasItems) {

                        chest.idleDueToNonTransferable = false;
                        this.resetIdleRetryBackoff(chest);
                        this.scheduleChest(
                                chest,
                                this.getTargetScanCacheMaxAgeMs()
                        );


                    } else if (sourceCurrentlyNonTransferable) {

                        chest.idleDueToNonTransferable = true;
                        this.scheduleIdleRetryForNonTransferable(chest);


                    } else {

                        chest.idleDueToNonTransferable = false;
                        this.scheduleIdleRetryWithBackoff(chest);
                    }
                }
            }
        }
    }

    public void notifyBlockChanged(World world, Vector3i pos) {
        if (world != null && pos != null) {
            this.markTargetScanCachesDirtyAround(world, pos);
        }
    }

    public void notifyAutoStorageOpened(World world, Vector3i pos) {
        if (world != null && pos != null) {
            if (world.isInThread()) {
                this.notifyAutoStorageOpenedOnWorldThread(world, pos);
            } else {
                try {
                    world.execute(() -> this.notifyAutoStorageOpenedOnWorldThread(world, pos));
                } catch (RuntimeException exception) {
                    if (this.isWorldThreadNotAcceptingTasks(exception)) {
                        return;
                    }
                    ((LOGGER.atWarning()).withCause(exception)).log("[AutoStorage] Failed to schedule AutoStorage open notification.");
                }
            }
        }
    }

    private void notifyAutoStorageOpenedOnWorldThread(World world, Vector3i pos) {
        if (world != null && pos != null) {
            String worldName = world.getName();
            if (!worldName.isBlank()) {
                TrackedChest chest = this.trackedChests.get(this.buildChestKey(worldName, pos));
                if (chest != null) {
                    this.ensureAutoStorageContainerReadyOnWorldThread(world, pos, false);
                    if (this.config.isTransferImmediate()) {
                        this.requestImmediateChestRun(chest, true);
                    } else {
                        long delay = this.config.getTransferAfterLastInteractionMs();
                        chest.idleDueToNonTransferable = false;
                        this.resetIdleRetryBackoff(chest);
                        if (chest.inFlight.get()) {
                            chest.depositDelayUntil = System.currentTimeMillis() + delay;
                            chest.eventPending.set(true);
                            return;
                        }
                        chest.depositDelayUntil = System.currentTimeMillis() + delay;
                        this.scheduleChest(chest, delay);
                    }
                }
            }
        }
    }

    public void ensureAutoStorageContainerReady(World world, Vector3i pos) {
        this.ensureAutoStorageContainerReady(world, pos, false);
    }

    void ensureAutoStorageContainerReady(World world, Vector3i pos, boolean requestImmediateRun) {
        if (world != null && pos != null) {
            if (world.isInThread()) {
                this.ensureAutoStorageContainerReadyOnWorldThread(world, pos, requestImmediateRun);
            } else {
                try {
                    world.execute(() -> this.ensureAutoStorageContainerReadyOnWorldThread(world, pos, requestImmediateRun));
                } catch (RuntimeException exception) {
                    if (this.isWorldThreadNotAcceptingTasks(exception)) {
                        return;
                    }
                    ((LOGGER.atWarning()).withCause(exception)).log("[AutoStorage] Failed to schedule container readiness check.");
                }
            }
        }
    }

    void ensureAutoStorageContainerReadyOnWorldThread(
            World world,
            Vector3i pos,
            boolean requestImmediateRun
    ) {
        if (world == null || pos == null) {
            return;
        }

        BlockType blockType = this.getBlockTypeAt(
                world,
                pos.x(),
                pos.y(),
                pos.z()
        );

        if (!this.isAutoStorage(blockType)) {
            return;
        }

        boolean componentCreated = false;

        Object state = this.getItemContainerStateAt(
                world,
                pos.x(),
                pos.y(),
                pos.z()
        );

        if (state == null &&
                this.ensureItemContainerComponentAt(
                        world,
                        pos.x(),
                        pos.y(),
                        pos.z())) {

            state = this.getItemContainerStateAt(
                    world,
                    pos.x(),
                    pos.y(),
                    pos.z()
            );

            componentCreated = state != null;
        }

        if (state == null) {
            return;
        }

        boolean resized = this.ensureAutoStorageContainerCapacity(state);

        String worldName = world.getName();

        if (!worldName.isBlank()) {
            TrackedChest chest =
                    this.trackedChests.get(
                            this.buildChestKey(worldName, pos)
                    );

            if (chest != null
                    && chest.world == world
                    && chest.pos != null) {

                this.ensureChangeListenerOnWorldThread(chest, state);

                if (requestImmediateRun) {
                    this.requestImmediateChestRun(chest, true);
                }
            }
        }

        if (componentCreated || resized) {
            this.notifyBlockChanged(world, pos);
        }
    }

    private void markAllTargetScanCachesDirty() {
        this.targetResolver.markAllTargetScanCachesDirty();
    }

    void markTargetScanCacheDirty(TrackedChest chest) {
        this.targetResolver.markTargetScanCacheDirty(chest);
    }

    private void markTargetScanCachesDirtyAround(World world, Vector3i pos) {
        this.targetResolver.markTargetScanCachesDirtyAround(world, pos);
    }

    Set<String> getTrackedChestKeysNear(String worldName, Vector3i pos, int horizontalRadius) {
        return this.trackedChestLifecycle.getTrackedChestKeysNear(worldName, pos, horizontalRadius);
    }

    public String resolveItemTranslationKey(String itemId) {
        if (itemId != null && !itemId.isBlank()) {
            Item item = Item.getAssetMap().getAsset(itemId);
            if (item == null) {
                return null;
            } else {
                String key = item.getTranslationKey();
                return !key.isBlank() ? key : null;
            }
        } else {
            return null;
        }
    }

    private boolean canInteractWithClaims(UUID playerUuid, String worldName, int blockX, int blockZ, ClaimProtectionProvider.ClaimAccessType accessType) {
        return this.config == null || !this.config.isClaimCompatibilityEnabled() || this.claimProtectionService.isInteractionAllowed(playerUuid, worldName, blockX, blockZ, accessType);
    }

    boolean isTargetAllowedForChestOwner(TrackedChest chest, int blockX, int blockZ, Map<Long, Boolean> claimAccessCache, ClaimProtectionProvider.ClaimAccessType accessType) {
        if (chest != null && chest.world != null) {
            UUID actorUuid = chest.claimAccessUuid != null ? chest.claimAccessUuid : chest.ownerUuid;
            long key = this.columnKey(blockX, blockZ);
            if (claimAccessCache != null) {
                Boolean cached = claimAccessCache.get(key);
                if (cached != null) {
                    return cached;
                }
            }

            boolean allowed = this.canInteractWithClaims(actorUuid, chest.world.getName(), blockX, blockZ, accessType);
            if (claimAccessCache != null) {
                claimAccessCache.put(key, allowed);
            }

            return allowed;
        } else {
            return true;
        }
    }

    private long columnKey(int x, int z) {
        return (long) x << 32 | (long) z & 4294967295L;
    }

    public void syncCraftPermissionForPlayer(Ref<EntityStore> ref, Store<EntityStore> store) {
        this.craftManager.syncCraftPermissionForPlayer(ref, store);
    }

    private void syncCraftPermissionForAllPlayers() {
        this.craftManager.syncCraftPermissionForAllPlayers();
    }

    public boolean isCraftAllowed(PlayerRef playerRef, CraftingRecipe recipe) {
        return this.craftManager.isCraftAllowed(playerRef, recipe);
    }

    private void applyRecipeOverride(AutoStorageConfig config) {
        this.craftManager.applyRecipeOverride(config);
    }

    private Object getItemContainerStateAt(World world, int x, int y, int z) {
        return this.getItemContainerStateAt(world, x, y, z, null);
    }

    Object getItemContainerStateAt(World world, int x, int y, int z, ScanChunkCache chunkCache) {
        if (world == null) {
            return null;
        } else {
            WorldChunk chunk = this.getChunkIfInMemory(world, x, z, chunkCache);
            if (chunk == null) {
                return null;
            } else {
                int localX = ChunkUtil.localCoordinate(x);
                int localZ = ChunkUtil.localCoordinate(z);
                return this.containerBridge.resolveContainerState(chunk, localX, y, localZ);
            }
        }
    }

    private boolean ensureItemContainerComponentAt(World world, int x, int y, int z) {
        if (world != null && world.isInThread()) {
            WorldChunk chunk = this.getChunkIfInMemory(world, x, z, null);
            if (chunk == null) {
                return false;
            } else {
                int localX = ChunkUtil.localCoordinate(x);
                int localZ = ChunkUtil.localCoordinate(z);
                return this.containerBridge.ensureContainerComponent(chunk, localX, y, localZ);
            }
        } else {
            return false;
        }
    }

    private BlockType getBlockTypeAt(World world, int x, int y, int z) {
        return this.getBlockTypeAt(world, x, y, z, null);
    }

    BlockType getBlockTypeAt(World world, int x, int y, int z, ScanChunkCache chunkCache) {
        if (world == null) {
            return null;
        } else if (!world.isInThread()) {
            return world.getBlockType(x, y, z);
        } else {
            WorldChunk chunk = this.getChunkIfInMemory(world, x, z, chunkCache);
            if (chunk == null) {
                return null;
            } else {
                int localX = ChunkUtil.localCoordinate(x);
                int localZ = ChunkUtil.localCoordinate(z);
                int blockId = chunk.getBlock(localX, y, localZ);
                return BlockType.getAssetMap().getAsset(blockId);
            }
        }
    }

    private WorldChunk getChunkIfInMemory(World world, int x, int z, ScanChunkCache chunkCache) {
        if (world == null) {
            return null;
        } else {
            long chunkIndex = ChunkUtil.indexChunkFromBlock(x, z);
            if (chunkCache == null) {
                return world.getChunkIfInMemory(chunkIndex);
            } else {
                WorldChunk cachedChunk = chunkCache.loadedChunks.get(chunkIndex);
                if (cachedChunk != null) {
                    return cachedChunk;
                } else if (chunkCache.missingChunks.contains(chunkIndex)) {
                    return null;
                } else {
                    WorldChunk chunk = world.getChunkIfInMemory(chunkIndex);
                    if (chunk == null) {
                        chunkCache.missingChunks.add(chunkIndex);
                        return null;
                    } else {
                        chunkCache.loadedChunks.put(chunkIndex, chunk);
                        return chunk;
                    }
                }
            }
        }
    }

    ItemContainer getItemContainer(Object state) {
        return this.containerBridge.getItemContainer(state);
    }

    private boolean ensureAutoStorageContainerCapacity(Object state) {
        return this.containerBridge.ensureContainerCapacity(state, AUTO_STORAGE_CONTAINER_CAPACITY);
    }

    boolean isAutoStorage(BlockType blockType) {
        return blockType != null && AutoStorageIds.isAutoStorageId(blockType.getId());
    }

    boolean isAutoStorageAt(World world, Vector3i pos) {
        if (world != null && pos != null) {
            BlockType blockType = this.getBlockTypeAt(world, pos.x(), pos.y(), pos.z());
            return this.isAutoStorage(blockType);
        } else {
            return false;
        }
    }

    boolean isAllowedTargetContainer(BlockType blockType) {
        AutoStorageConfig.TargetFilterConfig filter = this.config.getTargetFilter();
        if (filter == null) {
            return true;
        } else if (blockType == null) {
            return false;
        } else {
            String id = blockType.getId();
            if (id != null && !id.isBlank()) {
                String normalized = id.charAt(0) == '*' ? id.substring(1) : id;
                String lower = normalized.toLowerCase(Locale.ROOT);
                Boolean cached = this.allowedTargetBlockCache.get(lower);
                if (cached != null) {
                    return cached;
                } else {
                    boolean allowed = this.evaluateAllowedTargetContainer(lower, filter);
                    this.allowedTargetBlockCache.put(lower, allowed);
                    return allowed;
                }
            } else {
                return false;
            }
        }
    }

    private boolean evaluateAllowedTargetContainer(String lowerBlockId, AutoStorageConfig.TargetFilterConfig filter) {
        if (lowerBlockId != null && !lowerBlockId.isBlank() && filter != null) {
            if (filter.isDenyBlockIdsEnabled() && this.matchesAny(lowerBlockId, filter.getDenyBlockIds())) {
                return false;
            } else if (filter.isDenyIdContainsEnabled() && this.containsAny(lowerBlockId, filter.getDenyIdContains())) {
                return false;
            } else {
                boolean allowByExact = filter.isAllowBlockIdsEnabled();
                boolean allowByContains = filter.isAllowIdContainsEnabled();
                if (!allowByExact && !allowByContains) {
                    return true;
                } else if (allowByExact && this.matchesAny(lowerBlockId, filter.getAllowBlockIds())) {
                    return true;
                } else {
                    return allowByContains && this.containsAny(lowerBlockId, filter.getAllowIdContains());
                }
            }
        } else {
            return false;
        }
    }

    boolean isIgnoredTarget(TrackedChest chest, String targetKey) {
        return chest != null && targetKey != null && !targetKey.isBlank() && chest.ignoredTargets.contains(targetKey);
    }

    private boolean matchesAny(String value, String[] candidates) {
        if (value != null && candidates != null) {
            for (String candidate : candidates) {
                if (candidate != null && !candidate.isEmpty() && value.equals(candidate)) {
                    return true;
                }
            }

        }
        return false;
    }

    private boolean containsAny(String value, String[] tokens) {
        if (value != null && tokens != null) {
            for (String token : tokens) {
                if (token != null && !token.isEmpty() && value.contains(token)) {
                    return true;
                }
            }

        }
        return false;
    }

    int getHorizontalRadiusFor(TrackedChest chest) {
        if (chest == null) {
            return this.config.getDefaultHorizontalRadius();
        } else {
            this.ensureRadiusInitialized(chest);
            return chest.horizontalRadius;
        }
    }

    int getVerticalRadiusFor(TrackedChest chest) {
        if (chest == null) {
            return this.config.getDefaultVerticalRadius();
        } else {
            this.ensureRadiusInitialized(chest);
            return chest.verticalRadius;
        }
    }

    void ensureRadiusInitialized(TrackedChest chest) {
        if (chest != null) {
            int maxHorizontal = this.config.getHorizontalRadiusMax();
            int maxVertical = this.config.getVerticalRadiusMax();
            if (chest.horizontalRadius <= 0) {
                chest.horizontalRadius = this.config.getDefaultHorizontalRadius();
            }

            if (chest.verticalRadius <= 0) {
                chest.verticalRadius = this.config.getDefaultVerticalRadius();
            }

            synchronized (chest.lock) {
                chest.horizontalRadius = this.clampRadius(chest.horizontalRadius, 1, maxHorizontal);
                chest.verticalRadius = this.clampRadius(chest.verticalRadius, 1, maxVertical);
            }
        }
    }

    private void clampTrackedChestRadii() {
        boolean changed = false;

        for (TrackedChest chest : this.trackedChests.values()) {
            int maxHorizontal = this.config.getHorizontalRadiusMax();
            int maxVertical = this.config.getVerticalRadiusMax();
            int beforeHorizontal = chest.horizontalRadius;
            int beforeVertical = chest.verticalRadius;
            if (chest.horizontalRadius > maxHorizontal) {
                chest.horizontalRadius = maxHorizontal;
            }

            if (chest.verticalRadius > maxVertical) {
                chest.verticalRadius = maxVertical;
            }

            if (beforeHorizontal != chest.horizontalRadius || beforeVertical != chest.verticalRadius) {
                this.markTargetScanCacheDirty(chest);
                changed = true;
            }
        }

        this.recalculateTrackedHorizontalRadiusMax();
        if (changed) {
            this.saveTrackedChestsAsync();
        }

    }

    private int clampRadius(int value, int min, int max) {
        if (value < min) {
            return min;
        } else {
            return Math.min(value, max);
        }
    }

    boolean isWithinRange(TrackedChest chest, Vector3i pos) {
        if (chest != null && pos != null) {
            int rH = this.getHorizontalRadiusFor(chest);
            int rV = this.getVerticalRadiusFor(chest);
            if (rH >= 1 && rV >= 1) {
                Vector3i center = chest.pos;
                if (center == null) {
                    return false;
                } else {
                    int dx = Math.abs(pos.x() - center.x());
                    int dy = Math.abs(pos.y() - center.y());
                    int dz = Math.abs(pos.z() - center.z());
                    return dx <= rH && dy <= rV && dz <= rH;
                }
            } else {
                return false;
            }
        } else {
            return false;
        }
    }

    private AutoStorageConfig.SortingMode getSortingModeFor(TrackedChest chest) {
        return chest != null && chest.sortingModeOverride != null ? chest.sortingModeOverride : this.config.getDefaultSortingMode();
    }

    private boolean isTransferAllowedByOpenMode(BlockType blockType) {
        if (!this.config.isTransferOnlyWhenClose()) {
            return true;
        } else {
            boolean open = this.isAutoStorageOpen(blockType);
            return !open;
        }
    }

    private boolean isAutoStorageOpen(BlockType blockType) {
        if (blockType == null) {
            return false;
        } else {
            String id = blockType.getId();
            if (id != null && !id.isEmpty()) {
                String normalized = id.charAt(0) == '*' ? id.substring(1) : id;
                String lower = normalized.toLowerCase(Locale.ROOT);
                if (lower.contains("openwindow")) {
                    return true;
                } else {
                    return !lower.contains("closewindow") && !lower.contains("closedwindow") && lower.contains("open");
                }
            } else {
                return false;
            }
        }
    }

    public void registerAutoStorage(World world, Vector3i pos) {
        this.registerAutoStorageInternal(world, pos, null, true);
    }

    public void registerAutoStorage(World world, Vector3i pos, UUID ownerUuid) {
        this.registerAutoStorageInternal(world, pos, ownerUuid, true);
    }

    public void registerAutoStoragePlaced(World world, Vector3i pos, UUID ownerUuid) {
        this.registerAutoStorageInternal(world, pos, ownerUuid, false);
    }

    private void registerAutoStorageInternal(World world, Vector3i pos, UUID ownerUuid, boolean restoreFromPending) {
        this.trackedChestLifecycle.registerAutoStorage(world, pos, ownerUuid, restoreFromPending);
    }

    public void removeTrackedChest(World world, Vector3i pos) {
        this.trackedChestLifecycle.removeTrackedChest(world, pos);
    }

    public void clearIgnoredTargetAt(World world, Vector3i pos) {
        this.trackedChestLifecycle.clearIgnoredTargetAt(world, pos);
    }

    TrackedChest getTrackedChest(String worldName, Vector3i pos) {
        return this.trackedChestLifecycle.getTrackedChest(worldName, pos);
    }

    public void shutdown() {
        this.flushTrackedChestsSaveNow();

        for (TrackedChest chest : this.trackedChests.values()) {
            this.cleanupTrackedChest(chest);
        }

        this.targetResolver.clearTargetScanCaches();
        this.trackedChestLifecycle.clearRuntimeIndexes();
        this.trackedHorizontalRadiusMax = 0;
        this.cleanupRangeVisualizations();
        if (this.scheduler != null) {
            this.scheduler.shutdown();
        }

    }

    private void preloadTrackedChests() {
        this.trackedChestLifecycle.preloadTrackedChests();
    }

    private void loadTrackedChestsWithRetry() {
        this.trackedChestLifecycle.loadTrackedChestsWithRetry();
    }

    World findWorldByName(String name) {
        return HytaleCompat.getWorldByName(name);
    }

    private void saveTrackedChestsAsync() {
        this.trackedChestLifecycle.saveTrackedChestsAsync();
    }

    private void flushTrackedChestsSaveNow() {
        this.trackedChestLifecycle.flushTrackedChestsSaveNow();
    }

    public void showRangeVisualization(PlayerRef playerRef, String worldName, Vector3i pos) {
        this.rangeVisualizationManager.showRangeVisualization(playerRef, worldName, pos);
    }

    private void cleanupRangeVisualizations() {
        this.rangeVisualizationManager.cleanupAll();
    }

    String buildChestKey(String worldName, Vector3i pos) {
        return this.buildChestKey(worldName, pos.x(), pos.y(), pos.z());
    }

    void updateTrackedHorizontalRadiusMax(TrackedChest chest) {
        if (chest != null) {
            int radius = this.getHorizontalRadiusFor(chest);
            if (radius > 0) {
                if (radius > this.trackedHorizontalRadiusMax) {
                    this.trackedHorizontalRadiusMax = radius;
                }

            }
        }
    }

    void recalculateTrackedHorizontalRadiusMax() {
        int max = 0;

        for (TrackedChest chest : this.trackedChests.values()) {
            int radius = this.getHorizontalRadiusFor(chest);
            if (radius > max) {
                max = radius;
            }
        }

        this.trackedHorizontalRadiusMax = max;
    }

    String buildChestKey(String worldName, int x, int y, int z) {
        return worldName + "|" + x + "," + y + "," + z;
    }

    public static final class NearbyChestView {
        public final String key;
        public final int x;
        public final int y;
        public final int z;
        public final String chestItemId;
        public final String blockId;
        public final List<ItemPreview> items;
        public final int itemTypesTotal;
        public final boolean ignored;

        NearbyChestView(String key, int x, int y, int z, String chestItemId, String blockId, List<ItemPreview> items, int itemTypesTotal, boolean ignored) {
            this.key = key;
            this.x = x;
            this.y = y;
            this.z = z;
            this.chestItemId = chestItemId;
            this.blockId = blockId;
            this.items = items != null ? items : List.of();
            this.itemTypesTotal = itemTypesTotal;
            this.ignored = ignored;
        }
    }

    public static final class ItemPreview {
        public final String itemId;
        public final int quantity;

        ItemPreview(String itemId, int quantity) {
            this.itemId = itemId;
            this.quantity = quantity;
        }
    }

    public static final class NearbyChestsSnapshot {
        public final List<NearbyChestView> chests;
        public final int total;
        public final int linkedTotal;

        NearbyChestsSnapshot(List<NearbyChestView> chests, int total, int linkedTotal) {
            this.chests = chests;
            this.total = total;
            this.linkedTotal = linkedTotal;
        }
    }

    static final class ScanChunkCache {
        private final Map<Long, WorldChunk> loadedChunks = new HashMap<>();
        private final Set<Long> missingChunks = new HashSet<>();

        ScanChunkCache() {
        }
    }
}
