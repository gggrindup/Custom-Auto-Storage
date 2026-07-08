package com.afella.customautostorage.system;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.CraftRecipeEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.afella.customautostorage.AutoStorage;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;

public final class AutoStorageCraftPermissionCheckSystem extends EntityEventSystem<EntityStore, CraftRecipeEvent.Pre> {
    private static final Query<EntityStore> QUERY = Archetype.of(Player.getComponentType(), PlayerRef.getComponentType());
    private final AutoStorage plugin;

    public AutoStorageCraftPermissionCheckSystem(AutoStorage plugin) {
        super(CraftRecipeEvent.Pre.class);
        this.plugin = plugin;
    }

    public Query<EntityStore> getQuery() {
        return QUERY;
    }

    public void handle(int index, ArchetypeChunk<EntityStore> archetypeChunk, @NonNullDecl Store<EntityStore> store, @NonNullDecl CommandBuffer<EntityStore> commandBuffer, @NonNullDecl CraftRecipeEvent.Pre event) {
        Player player = archetypeChunk.getComponent(index, Player.getComponentType());
        if (player != null && this.plugin != null) {
            PlayerRef playerRef = archetypeChunk.getComponent(index, PlayerRef.getComponentType());
            if (!this.plugin.isCraftAllowed(playerRef, event.getCraftedRecipe())) {
                event.setCancelled(true);
            }

        }
    }
}
