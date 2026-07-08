package com.afella.customautostorage.system;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.event.events.ecs.UseBlockEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.afella.customautostorage.AutoStorage;
import com.afella.customautostorage.AutoStorageIds;
import com.afella.customautostorage.compat.HytaleCompat;
import com.afella.customautostorage.ui.AutoStorageConfigPage;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;
import org.joml.Vector3i;

public class AutoStorageUISystem extends EntityEventSystem<EntityStore, UseBlockEvent.Pre> {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String PERMISSION_USE = "autostorage.use";
    private static final String KEY_CHAT_CONFIG_OWNER_ONLY = "server.autostorage.chat.config_owner_only";
    private static final String KEY_CHAT_CLAIM_PROTECTED = "server.autostorage.chat.claim_protected";
    private final AutoStorage plugin;

    public AutoStorageUISystem(AutoStorage plugin) {
        super(UseBlockEvent.Pre.class);
        this.plugin = plugin;
    }

    public Query<EntityStore> getQuery() {
        return Query.and(Player.getComponentType(), PlayerRef.getComponentType());
    }

    public void handle(int index, @NonNullDecl ArchetypeChunk<EntityStore> archetypeChunk, @NonNullDecl Store<EntityStore> store, @NonNullDecl CommandBuffer<EntityStore> commandBuffer, @NonNullDecl UseBlockEvent.Pre event) {
        try {
            BlockType blockType = event.getBlockType();
            if (!AutoStorageIds.isAutoStorageId(blockType.getId())) {
                return;
            }

            InteractionContext context = event.getContext();

            Ref<EntityStore> ref = context.getOwningEntity();
            if (ref == null) {
                return;
            }

            Player player = store.getComponent(ref, Player.getComponentType());
            if (player == null) {
                return;
            }

            PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
            if (playerRef == null) {
                return;
            }

            if (!HytaleCompat.hasPermission(playerRef, PERMISSION_USE, true)) {
                HytaleCompat.sendMessage(playerRef, Message.raw("[AutoStorage] You do not have permission to use AutoStorage."));
                event.setCancelled(true);
                return;
            }

            MovementStatesComponent movement = store.getComponent(ref, MovementStatesComponent.getComponentType());
            if (movement == null) {
                return;
            }

            String worldName = "default";
            World world = HytaleCompat.getWorldByUuid(playerRef.getWorldUuid());
            if (world != null) {
                worldName = world.getName();
            }

            Vector3i target = event.getTargetBlock();

            if (!this.plugin.canInteractWithClaims(playerRef.getUuid(), worldName, target)) {
                HytaleCompat.sendMessage(playerRef, Message.translation(KEY_CHAT_CLAIM_PROTECTED));
                event.setCancelled(true);
                return;
            }

            if (world != null) {
                this.plugin.ensureAutoStorageContainerReady(world, target);
                this.plugin.registerAutoStorage(world, target, playerRef.getUuid());
                this.plugin.recordClaimAccessActor(worldName, target, playerRef.getUuid());
                this.plugin.notifyAutoStorageOpened(world, target);
            }

            if (!HytaleCompat.isCrouching(movement)) {
                return;
            }

            if (!this.plugin.canConfigure(playerRef, playerRef.getUuid(), worldName, target)) {
                HytaleCompat.sendMessage(playerRef, Message.translation(KEY_CHAT_CONFIG_OWNER_ONLY));
                event.setCancelled(true);
                return;
            }

            boolean opened = HytaleCompat.openCustomPage(player, ref, store, new AutoStorageConfigPage(playerRef, this.plugin, target, worldName));
            if (opened) {
                event.setCancelled(true);
            }
        } catch (Throwable throwable) {
            LOGGER.atWarning().withCause(throwable).log("[AutoStorage] UI interaction handling failed.");
        }

    }
}
