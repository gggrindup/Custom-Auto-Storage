package com.afella.customautostorage;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.afella.customautostorage.compat.HytaleCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.joml.Vector3f;
import org.joml.Vector3i;

final class RangeVisualizationManager {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private final AutoStorage plugin;
    private final Map<UUID, RangeVisualization> rangeVisualizations = new ConcurrentHashMap<>();

    RangeVisualizationManager(AutoStorage plugin) {
        this.plugin = plugin;
    }

    void showRangeVisualization(PlayerRef playerRef, String worldName, Vector3i pos) {
        if (playerRef != null && worldName != null && pos != null) {
            TrackedChest chest = this.plugin.getTrackedChest(worldName, pos);
            if (chest != null) {
                int rH = this.plugin.getHorizontalRadiusFor(worldName, pos);
                int rV = this.plugin.getVerticalRadiusFor(worldName, pos);
                if (HytaleCompat.canSendDebug(playerRef)) {
                    UUID playerUuid = playerRef.getUuid();
                    RangeVisualization previous = this.cancelRangeVisualization(playerUuid);
                    if (previous != null) {
                        HytaleCompat.clearDebugShapes(playerRef);
                    }

                    RangeCube cube = calculateRangeCube(pos, rH, rV);
                    Vector3f color = new Vector3f(0.0F, 1.0F, 0.6F);
                    long durationMs = 10000L;
                    long refreshMs = 800L;
                    boolean sent = HytaleCompat.sendDebugCube(playerRef, cube.centerX, cube.centerY, cube.centerZ, cube.sizeX, cube.sizeY, cube.sizeZ, color);
                    if (sent) {
                        ScheduledExecutorService scheduler = this.plugin.getScheduler();
                        if (scheduler != null && !scheduler.isShutdown()) {
                            RangeVisualization session = new RangeVisualization();
                            this.rangeVisualizations.put(playerUuid, session);

                            ScheduledFuture<?> repeater;
                            try {
                                repeater = scheduler.scheduleAtFixedRate(() -> {
                                    try {
                                        RangeVisualization current = this.rangeVisualizations.get(playerUuid);
                                        if (current != session) {
                                            return;
                                        }
                                        HytaleCompat.sendDebugCube(playerRef, cube.centerX, cube.centerY, cube.centerZ, cube.sizeX, cube.sizeY, cube.sizeZ, color);
                                    } catch (Throwable throwable) {
                                        ((LOGGER.atWarning()).withCause(throwable)).log("[AutoStorage] Range repeater task failed.");
                                    }
                                }, refreshMs, refreshMs, TimeUnit.MILLISECONDS);
                            } catch (RuntimeException exception) {
                                ((LOGGER.atWarning()).withCause(exception)).log("[AutoStorage] Failed to schedule range repeater.");
                                this.rangeVisualizations.remove(playerUuid, session);
                                return;
                            }

                            session.addTask(repeater);

                            try {
                                ScheduledFuture<?> cleanup = scheduler.schedule(() -> {
                                    try {
                                        RangeVisualization current = this.rangeVisualizations.get(playerUuid);
                                        if (current != session) {
                                            return;
                                        }
                                        repeater.cancel(false);
                                        HytaleCompat.clearDebugShapes(playerRef);
                                        this.rangeVisualizations.remove(playerUuid, session);
                                    } catch (Throwable throwable) {
                                        ((LOGGER.atWarning()).withCause(throwable)).log("[AutoStorage] Range cleanup task failed.");
                                    }

                                }, durationMs, TimeUnit.MILLISECONDS);
                                session.addTask(cleanup);
                            } catch (RuntimeException exception) {
                                ((LOGGER.atWarning()).withCause(exception)).log("[AutoStorage] Failed to schedule range cleanup.");
                                repeater.cancel(false);
                                this.rangeVisualizations.remove(playerUuid, session);

                                HytaleCompat.clearDebugShapes(playerRef);
                            }
                        }
                    }
                }
            }
        }
    }

    private RangeVisualization cancelRangeVisualization(UUID playerUuid) {
        if (playerUuid == null) {
            return null;
        } else {
            RangeVisualization existing = this.rangeVisualizations.remove(playerUuid);
            if (existing == null) {
                return null;
            } else {
                existing.cancelAll();
                return existing;
            }
        }
    }

    void cleanupAll() {
        if (!this.rangeVisualizations.isEmpty()) {
            for (UUID uuid : new ArrayList<>(this.rangeVisualizations.keySet())) {
                this.cancelRangeVisualization(uuid);
            }
            this.rangeVisualizations.clear();
        }
    }

    static RangeCube calculateRangeCube(Vector3i pos, int horizontalRadius, int verticalRadius) {
        return pos == null ? null : new RangeCube((float) pos.x() + 0.5F, (float) pos.y() + 0.5F, (float) pos.z() + 0.5F, (float) (horizontalRadius * 2) + 1.02F, (float) (verticalRadius * 2) + 1.02F, (float) (horizontalRadius * 2) + 1.02F);
    }

    static final class RangeCube {
        final float centerX;
        final float centerY;
        final float centerZ;
        final float sizeX;
        final float sizeY;
        final float sizeZ;

        private RangeCube(float centerX, float centerY, float centerZ, float sizeX, float sizeY, float sizeZ) {
            this.centerX = centerX;
            this.centerY = centerY;
            this.centerZ = centerZ;
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.sizeZ = sizeZ;
        }
    }

    private static final class RangeVisualization {
        private final List<ScheduledFuture<?>> tasks = new ArrayList<>();
        private RangeVisualization() {
        }
        private void addTask(ScheduledFuture<?> task) {
            synchronized (this) {
                this.tasks.add(task);
            }
        }
        private void cancelAll() {
            synchronized (this) {
                for (ScheduledFuture<?> task : this.tasks) {
                    if (task != null && !task.isDone()) {
                        task.cancel(false);
                    }
                }
                this.tasks.clear();
            }
        }
    }
}
