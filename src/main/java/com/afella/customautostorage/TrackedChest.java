package com.afella.customautostorage;

import com.hypixel.hytale.event.EventRegistration;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.world.World;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.joml.Vector3i;

final class TrackedChest {
    final Object lock = new Object();
    final String key;
    final World world;
    final Vector3i pos;
    final AtomicBoolean inFlight = new AtomicBoolean(false);
    final AtomicBoolean eventPending = new AtomicBoolean(false);
    volatile ItemContainer container;
    volatile EventRegistration<Void, ItemContainer.ItemContainerChangeEvent> changeRegistration;
    volatile ScheduledFuture<?> scheduledTask;
    volatile long scheduledAt = 0L;
    volatile long cooldownUntil = 0L;
    volatile long depositDelayUntil = 0L;
    volatile boolean suppressContainerChangeEvents = false;
    volatile long idleRetryBackoffMs = 0L;
    volatile boolean idleDueToNonTransferable = false;
    volatile UUID ownerUuid;
    volatile UUID claimAccessUuid;
    volatile AutoStorageConfig.SortingMode sortingModeOverride;
    volatile int horizontalRadius;
    volatile int verticalRadius;
    volatile boolean transferAnywhereIfMissing;
    volatile int missingSourceChecks;
    volatile long firstMissingSourceAt;
    volatile boolean wasOpen = false;
    final Set<String> ignoredTargets = ConcurrentHashMap.newKeySet();

    TrackedChest(String key, World world, Vector3i pos) {
        this.key = key;
        this.world = world;
        this.pos = pos;
    }
}
