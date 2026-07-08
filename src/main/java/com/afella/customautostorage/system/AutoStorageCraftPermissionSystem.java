package com.afella.customautostorage.system;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.afella.customautostorage.AutoStorage;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;

public final class AutoStorageCraftPermissionSystem extends RefSystem<EntityStore> {
    private static final Query<EntityStore> QUERY = Archetype.of(Player.getComponentType(), PlayerRef.getComponentType());
    private final AutoStorage plugin;

    public AutoStorageCraftPermissionSystem(AutoStorage plugin) {
        this.plugin = plugin;
    }

    public Query<EntityStore> getQuery() {
        return QUERY;
    }

    public void onEntityAdded(@NonNullDecl Ref<EntityStore> ref, @NonNullDecl AddReason reason, @NonNullDecl Store<EntityStore> store, @NonNullDecl CommandBuffer<EntityStore> commandBuffer) {
        this.plugin.syncCraftPermissionForPlayer(ref, store);
    }

    public void onEntityRemove(@NonNullDecl Ref<EntityStore> ref, @NonNullDecl RemoveReason reason, @NonNullDecl Store<EntityStore> store, @NonNullDecl CommandBuffer<EntityStore> commandBuffer) {
    }
}
