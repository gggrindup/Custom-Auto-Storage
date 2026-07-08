package com.afella.customautostorage.system;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.event.events.ecs.BreakBlockEvent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.afella.customautostorage.AutoStorage;
import com.afella.customautostorage.AutoStorageIds;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;
import org.joml.Vector3i;

public class AutoStorageBreakSystem extends EntityEventSystem<EntityStore, BreakBlockEvent> {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private final AutoStorage plugin;

    public AutoStorageBreakSystem(AutoStorage plugin) {
        super(BreakBlockEvent.class);
        this.plugin = plugin;
    }

    public void handle(int index, @NonNullDecl ArchetypeChunk<EntityStore> archetypeChunk, Store<EntityStore> store, @NonNullDecl CommandBuffer<EntityStore> commandBuffer, BreakBlockEvent event) {
        BlockType blockType = event.getBlockType();
        EntityStore entityStore = store.getExternalData();
        World world = entityStore.getWorld();
        Vector3i pos = event.getTargetBlock();
        boolean breakingAutoStorage = AutoStorageIds.isAutoStorageId(blockType.getId());
        Runnable work = () -> {
            if (breakingAutoStorage) {
                this.plugin.removeTrackedChest(world, pos);
            } else {
                this.plugin.clearIgnoredTargetAt(world, pos);
            }

            this.plugin.notifyBlockChanged(world, pos);
        };
        if (world.isInThread()) {
            try {
                work.run();
            } catch (Throwable throwable) {
                LOGGER.atWarning().withCause(throwable).log("[AutoStorage] Failed handling break event update.");
            }

        } else {
            try {
                world.execute(() -> {
                    try {
                        work.run();
                    } catch (Throwable throwable) {
                        LOGGER.atWarning().withCause(throwable).log("[AutoStorage] Failed handling break event update.");
                    }

                });
            } catch (RuntimeException throwable) {
                LOGGER.atWarning().withCause(throwable).log("[AutoStorage] Failed scheduling break event update.");
            }

        }
    }

    public Query<EntityStore> getQuery() {
        return Archetype.empty();
    }
}
