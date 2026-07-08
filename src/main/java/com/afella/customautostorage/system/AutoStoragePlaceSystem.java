package com.afella.customautostorage.system;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.event.events.ecs.PlaceBlockEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.afella.customautostorage.AutoStorage;
import com.afella.customautostorage.AutoStorageIds;

import java.util.UUID;

import org.checkerframework.checker.nullness.compatqual.NonNullDecl;
import org.joml.Vector3i;

public class AutoStoragePlaceSystem extends EntityEventSystem<EntityStore, PlaceBlockEvent> {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private final AutoStorage plugin;

    public AutoStoragePlaceSystem(AutoStorage plugin) {
        super(PlaceBlockEvent.class);
        this.plugin = plugin;
    }

    public void handle(int index,
                       @NonNullDecl ArchetypeChunk<EntityStore> archetypeChunk,
                       @NonNullDecl Store<EntityStore> store,
                       @NonNullDecl CommandBuffer<EntityStore> commandBuffer,
                       @NonNullDecl PlaceBlockEvent event) {
        ItemStack item = event.getItemInHand();
        final boolean placingAutoStorage =
                item != null
                        && !item.isEmpty()
                        && AutoStorageIds.isAutoStorageId(item.getItemId());

        EntityStore entityStore = store.getExternalData();
        World world = entityStore.getWorld();
        Vector3i pos = event.getTargetBlock();
        final UUID ownerUuid;
        if (placingAutoStorage) {
            Ref<EntityStore> ref = archetypeChunk.getReferenceTo(index);
            PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
            ownerUuid = playerRef != null ? playerRef.getUuid() : null;
        } else {
            ownerUuid = null;
        }

        Runnable work = () -> {
            if (placingAutoStorage) {
                this.plugin.registerAutoStoragePlaced(world, pos, ownerUuid);
            }

            this.plugin.notifyBlockChanged(world, pos);
        };
        if (world.isInThread()) {
            try {
                work.run();
            } catch (Throwable throwable) {
                ((LOGGER.atWarning()).withCause(throwable)).log("[AutoStorage] Failed handling place event update.");
            }

        } else {
            try {
                world.execute(() -> {
                    try {
                        work.run();
                    } catch (Throwable throwable) {
                        ((LOGGER.atWarning()).withCause(throwable)).log("[AutoStorage] Failed handling place event update.");
                    }

                });
            } catch (RuntimeException throwable) {
                ((LOGGER.atWarning()).withCause(throwable)).log("[AutoStorage] Failed scheduling place event update.");
            }

        }
    }

    public Query<EntityStore> getQuery() {
        return Query.any();
    }
}
