package com.afella.customautostorage;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.CombinedItemContainer;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackSlotTransaction;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackTransaction;
import com.hypixel.hytale.server.core.inventory.transaction.SlotTransaction;
import com.afella.customautostorage.AutoStorageConfig.TransferMode;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class TransferEngine {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private final AutoStorage plugin;
    private final ConcurrentHashMap<Class<?>, Boolean> customAddItemStackCache = new ConcurrentHashMap<>();
    private final Set<Class<?>> unsafeTargetContainerTypes = ConcurrentHashMap.newKeySet();

    TransferEngine(AutoStorage plugin) {
        this.plugin = plugin;
    }

    int moveToTarget(SimpleItemContainer source, short slot, TargetResolver.TargetInfo target, TransferBudget budget) {
        return this.moveToTarget(source, slot, target, budget, true);
    }

    private int moveToTarget(SimpleItemContainer source, short slot, TargetResolver.TargetInfo target, TransferBudget budget, boolean consumeStackBudget) {
        int movedTotal = 0;

        for (SimpleItemContainer targetContainer : target.containers()) {
            if (budget.isExhausted()) {
                break;
            }

            ItemStack current = source.getItemStack(slot);
            if (current == null || current.isEmpty()) {
                break;
            }

            int moved = this.moveStack(source, slot, current, targetContainer, budget);
            if (moved < 0) {
                return -1;
            }

            movedTotal += moved;
        }

        if (movedTotal > 0) {
            TargetResolver.invalidateCachedGroupCounts(target.cachedTarget());
            if (consumeStackBudget && budget.mode == TransferMode.STACKS) {
                budget.consumeStack();
            }
        }

        return movedTotal;
    }

    int moveToFallback(SimpleItemContainer source, short slot, List<TargetResolver.FallbackTarget> fallbackTargets, TransferBudget budget) {
        int movedTotal = 0;

        for (TargetResolver.FallbackTarget fallback : fallbackTargets) {
            if (budget.isExhausted() || !this.sourceSlotHasItems(source, slot)) {
                break;
            }

            int moved = this.moveToTarget(source, slot, fallback.target(), budget, false);
            if (moved < 0) {
                return -1;
            }

            if (moved > 0) {
                movedTotal += moved;
            }
        }

        this.consumeStackBudgetAfterGroupedMove(budget, movedTotal);
        return movedTotal;
    }

    int moveToCandidates(SimpleItemContainer source, short slot, List<TargetResolver.GroupCandidate> candidates, TransferBudget budget, Set<TargetResolver.TargetInfo> tried) {
        int movedTotal = 0;

        for (TargetResolver.GroupCandidate candidate : candidates) {
            if (budget.isExhausted() || !this.sourceSlotHasItems(source, slot)) {
                break;
            }

            if (tried.add(candidate.target())) {
                int moved = this.moveToTarget(source, slot, candidate.target(), budget, false);
                if (moved < 0) {
                    return -1;
                }

                if (moved > 0) {
                    movedTotal += moved;
                }
            }
        }

        this.consumeStackBudgetAfterGroupedMove(budget, movedTotal);
        return movedTotal;
    }

    int moveToFallbackByDistanceExcluding(SimpleItemContainer source, short slot, List<TargetResolver.FallbackTarget> fallbackTargets, TransferBudget budget, Set<TargetResolver.TargetInfo> tried) {
        if (!budget.isExhausted() && !fallbackTargets.isEmpty()) {
            int movedTotal = 0;

            for (TargetResolver.FallbackTarget fallback : fallbackTargets) {
                if (budget.isExhausted() || !this.sourceSlotHasItems(source, slot)) {
                    break;
                }

                TargetResolver.TargetInfo target = fallback.target();
                if (!tried.contains(target)) {
                    int moved = this.moveToTarget(source, slot, target, budget, false);
                    if (moved < 0) {
                        return -1;
                    }

                    if (moved > 0) {
                        movedTotal += moved;
                    }
                }
            }

            this.consumeStackBudgetAfterGroupedMove(budget, movedTotal);
            return movedTotal;
        } else {
            return 0;
        }
    }

    private void consumeStackBudgetAfterGroupedMove(TransferBudget budget, int movedTotal) {
        if (budget != null && budget.mode == TransferMode.STACKS && movedTotal > 0) {
            budget.consumeStack();
        }

    }

    private boolean sourceSlotHasItems(SimpleItemContainer source, short slot) {
        if (source == null) {
            return false;
        } else {
            ItemStack current = source.getItemStack(slot);
            return current != null && !current.isEmpty();
        }
    }

    private int moveStack(SimpleItemContainer source, short slot, ItemStack item, SimpleItemContainer target, TransferBudget budget) {
        if (budget.isExhausted()) {
            return 0;
        } else {
            boolean customTarget = this.shouldUseAddItemStack(target);
            if (customTarget && this.isUnsafeTargetContainer(target)) {
                return 0;
            } else {
                int sourceBefore = this.getStackableQuantityInSlot(source, slot, item);
                if (sourceBefore <= 0) {
                    return 0;
                } else {
                    int targetBefore = this.countStackableQuantity(target, item);
                    int allowed = sourceBefore;
                    if (budget.mode == TransferMode.ITEMS) {
                        allowed = Math.min(sourceBefore, budget.remaining);
                    }

                    if (allowed <= 0) {
                        return 0;
                    } else {
                        int removedFromSource = this.removeFromSourceVerified(source, slot, item, allowed);
                        if (removedFromSource <= 0) {
                            return 0;
                        } else {
                            int targetAdded = this.addToTargetVerified(target, item, removedFromSource);
                            if (targetAdded < 0) {
                                if (customTarget) {
                                    this.markUnsafeTargetContainer(target);
                                }

                                return -1;
                            } else {
                                if (targetAdded < removedFromSource) {
                                    int missingAtTarget = removedFromSource - targetAdded;
                                    this.addBackToSourceSlot(source, slot, item, missingAtTarget, sourceBefore);
                                }

                                if (!this.reconcileTransferIntegrity(source, slot, item, sourceBefore, target, targetBefore)) {
                                    if (customTarget) {
                                        this.markUnsafeTargetContainer(target);
                                    }

                                    return -1;
                                } else {
                                    int sourceAfter = this.getStackableQuantityInSlot(source, slot, item);
                                    int targetAfter = this.countStackableQuantity(target, item);
                                    int sourceDelta = Math.max(0, sourceBefore - sourceAfter);
                                    int targetDelta = Math.max(0, targetAfter - targetBefore);
                                    if (sourceDelta != targetDelta) {
                                        if (customTarget) {
                                            this.markUnsafeTargetContainer(target);
                                        }

                                        return -1;
                                    } else if (sourceDelta == 0) {
                                        return 0;
                                    } else {
                                        if (budget.mode == TransferMode.ITEMS) {
                                            budget.consume(sourceDelta);
                                        }

                                        return sourceDelta;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private int getStackableQuantityInSlot(SimpleItemContainer container, short slot, ItemStack item) {
        if (container != null && item != null && !item.isEmpty()) {
            ItemStack current = container.getItemStack(slot);
            if (current != null && !current.isEmpty()) {
                return !ItemStack.isStackableWith(item, current) ? 0 : Math.max(0, current.getQuantity());
            } else {
                return 0;
            }
        } else {
            return 0;
        }
    }

    private int countStackableQuantity(SimpleItemContainer container, ItemStack item) {
        if (container != null && item != null && !item.isEmpty()) {
            int total = 0;

            for (short i = 0; i < container.getCapacity(); ++i) {
                ItemStack stack = container.getItemStack(i);
                if (stack != null && !stack.isEmpty() && ItemStack.isStackableWith(item, stack)) {
                    total += Math.max(0, stack.getQuantity());
                }
            }

            return total;
        } else {
            return 0;
        }
    }

    private int addToTargetVerified(SimpleItemContainer target, ItemStack item, int quantity) {
        if (target != null && item != null && !item.isEmpty() && quantity > 0) {
            int before = this.countStackableQuantity(target, item);
            if (this.shouldUseAddItemStack(target)) {
                this.addToCustomContainer(target, item, quantity);
            } else {
                this.addToContainer(target, item, quantity, true, this.getMaxStackSize(item));
            }

            int after = this.countStackableQuantity(target, item);
            int added = Math.max(0, after - before);
            if (added <= quantity) {
                return added;
            } else {
                int excess = added - quantity;
                int removed = this.removeFromTargetVerified(target, item, excess);
                if (removed < excess) {
                    return -1;
                } else {
                    int adjustedAfter = this.countStackableQuantity(target, item);
                    int verifiedAdded = Math.max(0, adjustedAfter - before);
                    return Math.min(quantity, verifiedAdded);
                }
            }
        } else {
            return 0;
        }
    }

    private int removeFromSourceVerified(SimpleItemContainer source, short slot, ItemStack item, int quantity) {
        if (source != null && item != null && !item.isEmpty() && quantity > 0) {
            int before = this.getStackableQuantityInSlot(source, slot, item);
            if (before <= 0) {
                return 0;
            } else {
                int toRemove = Math.min(before, quantity);
                this.removeFromSource(source, slot, item, before, toRemove);
                int after = this.getStackableQuantityInSlot(source, slot, item);
                int removed = Math.max(0, before - after);
                return Math.min(toRemove, removed);
            }
        } else {
            return 0;
        }
    }

    private int removeFromTargetVerified(SimpleItemContainer target, ItemStack item, int quantity) {
        if (target != null && item != null && !item.isEmpty() && quantity > 0) {
            int before = this.countStackableQuantity(target, item);
            if (before <= 0) {
                return 0;
            } else {
                int toRemove = Math.min(before, quantity);
                if (this.shouldUseAddItemStack(target)) {
                    this.removeFromCustomContainer(target, item, toRemove);
                }

                int afterCustom = this.countStackableQuantity(target, item);
                int removedByCustom = Math.max(0, before - afterCustom);
                int remaining = toRemove - removedByCustom;
                if (remaining > 0) {
                    this.removeFromContainer(target, item, remaining);
                }

                int after = this.countStackableQuantity(target, item);
                int removed = Math.max(0, before - after);
                return Math.min(toRemove, removed);
            }
        } else {
            return 0;
        }
    }

    private int addBackToSourceSlot(SimpleItemContainer source, short slot, ItemStack item, int quantity, int sourceBefore) {
        if (source == null || item == null || item.isEmpty() || quantity <= 0) {
            return 0;
        }
        int before = getStackableQuantityInSlot(source, slot, item);
        if (before >= sourceBefore) {
            return 0;
        }
        int maxRestorable = sourceBefore - before;
        int toRestore = Math.min(quantity, maxRestorable);
        if (toRestore <= 0) {
            return 0;
        }
        ItemStack current = source.getItemStack(slot);
        ItemStack next;
        if (current != null && !current.isEmpty()) {
            if (!ItemStack.isStackableWith(item, current)) {
                return 0;
            }
            next = current.withQuantity(current.getQuantity() + toRestore);
        } else {
            next = item.withQuantity(toRestore);
        }
        if (next == null || next.isEmpty()) {
            return 0;
        }
        source.setItemStackForSlot(slot, next);
        int after = getStackableQuantityInSlot(source, slot, item);
        return Math.max(0, after - before);
    }

    private boolean reconcileTransferIntegrity(SimpleItemContainer source, short slot, ItemStack item, int sourceBefore, SimpleItemContainer target, int targetBefore) {
        long beforeTotal = (long) sourceBefore + (long) targetBefore;
        int sourceAfter = this.getStackableQuantityInSlot(source, slot, item);
        int targetAfter = this.countStackableQuantity(target, item);
        int diff = (int) ((long) sourceAfter + (long) targetAfter - beforeTotal);
        if (diff > 0) {
            int removedTarget = this.removeFromTargetVerified(target, item, diff);
            diff -= removedTarget;
            if (diff > 0) {
                return false;
            }
        } else if (diff < 0) {
            int missing = -diff;
            int restoredSource = this.addBackToSourceSlot(source, slot, item, missing, sourceBefore);
            missing -= restoredSource;
            if (missing > 0) {
                int restoredTarget = this.addToTargetVerified(target, item, missing);
                missing -= restoredTarget;
            }

            if (missing > 0) {
                return false;
            }
        }

        int sourceFinal = this.getStackableQuantityInSlot(source, slot, item);
        int targetFinal = this.countStackableQuantity(target, item);
        return (long) sourceFinal + (long) targetFinal == beforeTotal;
    }

    private boolean isUnsafeTargetContainer(SimpleItemContainer target) {
        return target != null && this.unsafeTargetContainerTypes.contains(target.getClass());
    }

    private void markUnsafeTargetContainer(SimpleItemContainer target) {
        if (target != null) {
            this.unsafeTargetContainerTypes.add(target.getClass());
        }
    }

    private void removeFromSource(
            SimpleItemContainer source,
            short slot,
            ItemStack item,
            int available,
            int movedQty
    ) {
        int left = Math.max(0, available - movedQty);
        if (left > 0) {
            ItemStackSlotTransaction tx =
                    source.setItemStackForSlot(slot, item.withQuantity(left));
            if (tx.succeeded()) {
                return;
            }
            this.sourceSlotMatchesExpected(source, slot, item, left);
            return;
        }
        SlotTransaction removeTx = source.removeItemStackFromSlot(slot);
        if (removeTx.succeeded()) {
            return;
        }
        ItemStack empty = item.withQuantity(0);
        if (empty != null) {
            ItemStackSlotTransaction setEmptyTx =
                    source.setItemStackForSlot(slot, empty);
            if (setEmptyTx.succeeded()) {
                return;
            }
        }
        this.sourceSlotMatchesExpected(source, slot, item, 0);
    }

    private void sourceSlotMatchesExpected(@NonNullDecl SimpleItemContainer source,
                                           short slot,
                                           ItemStack original,
                                           int expectedLeft) {
        ItemStack current = source.getItemStack(slot);
        if (expectedLeft <= 0) {
            if (current != null) {
                current.isEmpty();
            }
            return;
        }
        if (current != null && !current.isEmpty()) {
            ItemStack.isStackableWith(original, current);
        }
    }

    private void removeFromContainer(SimpleItemContainer target, ItemStack item, int quantity) {
        if (target == null || item == null || quantity <= 0) {
            return;
        }
        int remaining = quantity;
        for (short slot = (short) (target.getCapacity() - 1);
             slot >= 0 && remaining > 0;
             slot--) {
            ItemStack stack = target.getItemStack(slot);
            if (stack == null
                    || stack.isEmpty()
                    || !ItemStack.isStackableWith(item, stack)) {
                continue;
            }
            int take = Math.min(remaining, stack.getQuantity());
            int after = stack.getQuantity() - take;
            if (after > 0) {
                target.setItemStackForSlot(slot, stack.withQuantity(after));
            } else {
                target.removeItemStackFromSlot(slot);
            }
            remaining -= take;
        }
    }

    private boolean shouldUseAddItemStack(SimpleItemContainer container) {
        if (container == null) {
            return false;
        }

        Class<?> type = container.getClass();

        return customAddItemStackCache.computeIfAbsent(type, clazz -> {
            try {
                Method method = clazz.getMethod("addItemStack", ItemStack.class);
                return method.getDeclaringClass() != SimpleItemContainer.class;
            } catch (NoSuchMethodException | SecurityException e) {
                return false;
            }
        });
    }

    private int addToCustomContainer(SimpleItemContainer target, ItemStack source, int quantity) {
        if (target == null || source == null || source.isEmpty() || quantity <= 0) {
            return 0;
        }

        try {
            ItemStack toAdd = source.withQuantity(quantity);
            if (toAdd == null || toAdd.isEmpty()) {
                return 0;
            }

            int requestedQuantity = toAdd.getQuantity();

            ItemStackTransaction result = target.addItemStack(toAdd);
            if (!result.succeeded()) {
                return 0;
            }

            int movedByRemainder = calculateMovedByRemainder(result, source, requestedQuantity);

            int movedBySlots = 0;
            List<ItemStackSlotTransaction> slotTransactions = result.getSlotTransactions();

            for (ItemStackSlotTransaction slotTransaction : slotTransactions) {
                movedBySlots += computeStackableNetDelta(slotTransaction, source);
            }

            int moved = Math.max(movedByRemainder, movedBySlots);
            return Math.clamp(moved, 0, quantity);

        } catch (RuntimeException exception) {
            ((LOGGER.atWarning()).withCause(exception))
                    .log("[AutoStorage] Failed to add item stack to custom container.");
            return 0;
        }
    }

    private int calculateMovedByRemainder(@NonNullDecl ItemStackTransaction result, ItemStack source, int requestedQuantity) {
        ItemStack remainder = result.getRemainder();
        if (remainder == null || remainder.isEmpty()) {
            return requestedQuantity;
        }
        if (!ItemStack.isStackableWith(source, remainder)) {
            return 0;
        }
        return Math.max(0, requestedQuantity - remainder.getQuantity());
    }

    private int removeFromCustomContainer(SimpleItemContainer target, ItemStack source, int quantity) {
        if (target == null || source == null || source.isEmpty() || quantity <= 0) {
            return 0;
        }
        try {
            ItemStack toRemove = source.withQuantity(quantity);
            if (toRemove == null || toRemove.isEmpty()) {
                return 0;
            }
            ItemStackTransaction result = target.removeItemStack(toRemove);
            if (!result.succeeded()) {
                return 0;
            }
            ItemStack remainder = result.getRemainder();
            if (remainder == null || remainder.isEmpty()) {
                return quantity;
            }
            if (!ItemStack.isStackableWith(source, remainder)) {
                return 0;
            }
            return Math.clamp(quantity - remainder.getQuantity(), 0, quantity);
        } catch (RuntimeException e) {
            ((LOGGER.atWarning()).withCause(e))
                    .log("[AutoStorage] Failed removing item stack from custom container.");
            return 0;
        }
    }

    int addToContainer(SimpleItemContainer target, ItemStack source, int quantity, boolean fillEmpty, int maxStack) {
        if (target == null || source == null || source.isEmpty() || quantity <= 0) {
            return 0;
        }
        int remaining = quantity;
        int lastMatchedSlot = -1;
        for (short i = 0; i < target.getCapacity() && remaining > 0; i++) {
            ItemStack stack = target.getItemStack(i);
            if (stack != null && !stack.isEmpty() && ItemStack.isStackableWith(source, stack)) {
                lastMatchedSlot = i;
                int space = maxStack - stack.getQuantity();
                if (space > 0) {
                    int move = Math.min(space, remaining);
                    ItemStackSlotTransaction tx =
                            target.setItemStackForSlot(i, stack.withQuantity(stack.getQuantity() + move));
                    int added = computeStackableDelta(tx, source);
                    remaining -= Math.min(remaining, added);
                }
            }
        }
        if (fillEmpty && remaining > 0) {
            int capacity = target.getCapacity();

            if (lastMatchedSlot >= 0) {
                remaining = fillEmptySlots(target, source, remaining, maxStack,
                        lastMatchedSlot + 1, capacity - 1);
                if (remaining > 0) {
                    remaining = fillEmptySlots(target, source, remaining, maxStack,
                            0, lastMatchedSlot);
                }
            } else {
                remaining = fillEmptySlots(target, source, remaining, maxStack,
                        0, capacity - 1);
            }
        }
        return quantity - remaining;
    }

    private int fillEmptySlots(SimpleItemContainer target, ItemStack source,
                               int remaining, int maxStack, int start, int end) {
        for (int i = start; i <= end && remaining > 0; i++) {
            ItemStack stack = target.getItemStack((short) i);
            if (stack != null && !stack.isEmpty()) {
                continue;
            }
            int move = Math.min(maxStack, remaining);
            ItemStack created = source.withQuantity(move);
            ItemStackSlotTransaction tx =
                    target.setItemStackForSlot((short) i, created);
            int added = computeStackableDelta(tx, source);
            remaining -= Math.min(remaining, added);
        }
        return remaining;
    }

    private int computeStackableDelta(SlotTransaction transaction, ItemStack source) {
        int delta = this.computeStackableNetDelta(transaction, source);
        return Math.max(0, delta);
    }

    private int computeStackableNetDelta(SlotTransaction transaction, ItemStack source) {
        if (transaction == null || !transaction.succeeded() || source == null || source.isEmpty()) {
            return 0;
        }

        ItemStack before = transaction.getSlotBefore();
        ItemStack after = transaction.getSlotAfter();

        int beforeQty = 0;
        if (before != null && !before.isEmpty() && ItemStack.isStackableWith(source, before)) {
            beforeQty = before.getQuantity();
        }

        int afterQty = 0;
        if (after != null && !after.isEmpty() && ItemStack.isStackableWith(source, after)) {
            afterQty = after.getQuantity();
        }

        return afterQty - beforeQty;
    }

    void collectContainers(ItemContainer container, List<SimpleItemContainer> out) {
        if (container instanceof SimpleItemContainer simple) {
            out.add(simple);
            return;
        }
        if (container instanceof CombinedItemContainer combined) {
            for (int i = 0; i < combined.getContainersSize(); i++) {
                collectContainers(combined.getContainer(i), out);
            }
        }
    }

    boolean hasAnyItems(@NonNullDecl List<SimpleItemContainer> containers) {
        for (SimpleItemContainer container : containers) {
            for (short i = 0; i < container.getCapacity(); i++) {
                ItemStack stack = container.getItemStack(i);
                if (stack != null && !stack.isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    TransferBudget createTransferBudget() {
        AutoStorageConfig config = plugin.getAutoStorageConfig();
        return new TransferBudget(
                config.getTransferAmountPerTransfer(),
                config.getTransferMode()
        );
    }

    void sortCandidates(Map<String, List<TargetResolver.GroupCandidate>> candidatesByGroup) {
        if (candidatesByGroup != null && !candidatesByGroup.isEmpty()) {
            Comparator<TargetResolver.GroupCandidate> comparator = Comparator.comparingInt((TargetResolver.GroupCandidate c) -> -c.count()).thenComparingLong(TargetResolver.GroupCandidate::distanceSq);

            for (List<TargetResolver.GroupCandidate> candidates : candidatesByGroup.values()) {
                if (candidates != null && candidates.size() > 1) {
                    candidates.sort(comparator);
                }
            }
        }
    }

    @NonNullDecl
    Set<TargetResolver.TargetInfo> newTargetIdentitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    int getMaxStackSize(ItemStack stack) {
        if (stack == null) {
            return 64;
        } else {
            stack.getItem();
        }
        int maxStack = stack.getItem().getMaxStack();
        return maxStack > 0 ? maxStack : 64;
    }

    boolean isDamaged(ItemStack stack) {
        double max = getMaxDurability(stack);
        return max > 0 && stack.getDurability() < max;
    }

    private double getMaxDurability(ItemStack stack) {
        if (stack == null) {
            return 0;
        }
        if (stack.getMaxDurability() > 0) {
            return stack.getMaxDurability();
        }
        Item item = stack.getItem();
        return item.getMaxDurability();
    }
}
