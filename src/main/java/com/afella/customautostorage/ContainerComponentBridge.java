package com.afella.customautostorage;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.modules.block.components.ItemContainerBlock;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockComponentChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

final class ContainerComponentBridge {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final Field ITEM_CONTAINER_CAPACITY_FIELD = resolveCapacityField();

    ContainerComponentBridge() {
    }

    Object resolveContainerState(WorldChunk chunk, int localX, int y, int localZ) {
        if (chunk == null) {
            return null;
        } else {
            Ref<ChunkStore> blockEntityRef = chunk.getBlockComponentEntity(localX, y, localZ);
            return blockEntityRef != null && blockEntityRef.isValid() ? blockEntityRef.getStore().getComponent(blockEntityRef, ItemContainerBlock.getComponentType()) : null;
        }
    }

    boolean ensureContainerComponent(WorldChunk chunk, int localX, int y, int localZ) {
        if (chunk == null) {
            return false;
        } else {
            Ref<ChunkStore> existingRef = chunk.getBlockComponentEntity(localX, y, localZ);
            if (existingRef != null && existingRef.isValid()) {
                return true;
            } else {
                try {
                    Ref<ChunkStore> blockEntityRef = BlockModule.ensureBlockEntity(chunk, localX, y, localZ);
                    return blockEntityRef != null && blockEntityRef.isValid();
                } catch (IllegalArgumentException exception) {
                    if (!isDuplicateBlockComponentsFailure(exception)) {
                        throw exception;
                    } else {
                        return this.repairMissingEntityReference(chunk, localX, y, localZ);
                    }
                }
            }
        }
    }

    private boolean repairMissingEntityReference(WorldChunk chunk, int localX, int y, int localZ) {
        Ref<ChunkStore> existingRef = chunk.getBlockComponentEntity(localX, y, localZ);
        if (existingRef != null && existingRef.isValid()) {
            return true;
        } else {
            BlockComponentChunk blockComponentChunk = chunk.getBlockComponentChunk();
            if (blockComponentChunk == null) {
                return false;
            } else {
                int blockIndex = ChunkUtil.indexBlockInColumn(localX, y, localZ);
                Holder<ChunkStore> holder = blockComponentChunk.removeEntityHolder(blockIndex);
                if (holder == null) {
                    return false;
                } else {
                    try {
                        if (chunk.getWorld() != null) {
                            Store<ChunkStore> store = chunk.getWorld().getChunkStore().getStore();
                            Ref<ChunkStore> restoredRef = store.addEntity(holder, AddReason.LOAD);
                            return restoredRef != null && restoredRef.isValid();
                        } else {
                            this.restoreEntityHolderIfNeeded(blockComponentChunk, blockIndex, holder);
                            return false;
                        }
                    } catch (RuntimeException var11) {
                        this.restoreEntityHolderIfNeeded(blockComponentChunk, blockIndex, holder);
                        return false;
                    }
                }
            }
        }
    }

    private void restoreEntityHolderIfNeeded(BlockComponentChunk blockComponentChunk, int blockIndex, Holder<ChunkStore> holder) {
        if (blockComponentChunk != null && holder != null) {
            try {
                if (blockComponentChunk.getEntityHolder(blockIndex) != null || blockComponentChunk.getEntityReference(blockIndex) != null) {
                    return;
                }

                blockComponentChunk.storeEntityHolder(blockIndex, holder);
            } catch (RuntimeException exception) {
                LOGGER.atWarning()
                        .withCause(exception)
                        .log("[AutoStorage] Failed to restore entity holder for block index " + blockIndex + ".");
            }

        }
    }

    private static boolean isDuplicateBlockComponentsFailure(IllegalArgumentException exception) {
        if (exception == null) {
            return false;
        } else {
            String message = exception.getMessage();
            return message != null && message.startsWith("Duplicate block components");
        }
    }

    ItemContainer getItemContainer(Object state) {
        if (state instanceof ItemContainerBlock itemContainerBlock) {
            return itemContainerBlock.getItemContainer();
        } else if (state instanceof ItemContainer directContainer) {
            return directContainer;
        } else {
            return null;
        }
    }

    boolean ensureContainerCapacity(Object state, short minimumCapacity) {
        if (state instanceof ItemContainerBlock itemContainerBlock) {
            if (minimumCapacity >= 1) {
                boolean changed = false;
                SimpleItemContainer current = itemContainerBlock.getItemContainer();
                if (current.getCapacity() < minimumCapacity) {
                    SimpleItemContainer replacement = new SimpleItemContainer(minimumCapacity);
                    List<ItemStack> overflow = new ArrayList<>();

                    try {
                        ItemContainer.copy(current, replacement, overflow);
                    } catch (RuntimeException var9) {
                        return false;
                    }

                    itemContainerBlock.setItemContainer(replacement);
                    changed = true;
                }

                if (setCapacityFieldIfNeeded(itemContainerBlock, minimumCapacity)) {
                    changed = true;
                }

                return changed;
            }
        }

        return false;
    }

    private static Field resolveCapacityField() {
        try {
            Field field = ItemContainerBlock.class.getDeclaredField("capacity");
            field.setAccessible(true);
            return field;
        } catch (RuntimeException | ReflectiveOperationException var1) {
            return null;
        }
    }

    private static boolean setCapacityFieldIfNeeded(ItemContainerBlock state, short minimumCapacity) {
        if (state != null && minimumCapacity >= 1) {
            if (state.getCapacity() >= minimumCapacity) {
                return false;
            } else {
                Field field = ITEM_CONTAINER_CAPACITY_FIELD;
                if (field == null) {
                    return false;
                } else {
                    try {
                        field.setShort(state, minimumCapacity);
                        return true;
                    } catch (IllegalArgumentException | IllegalAccessException var4) {
                        return false;
                    }
                }
            }
        } else {
            return false;
        }
    }
}
