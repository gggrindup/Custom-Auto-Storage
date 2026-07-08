package com.afella.customautostorage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.assetstore.AssetUpdateQuery;
import com.hypixel.hytale.builtin.crafting.CraftingPlugin;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.event.EventRegistry;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.permissions.PlayerPermissionChangeEvent;
import com.hypixel.hytale.server.core.inventory.MaterialQuantity;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.afella.customautostorage.compat.HytaleCompat;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

final class CraftManager {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final Gson ASSET_GSON = (new GsonBuilder()).setPrettyPrinting().create();
    private static final String AUTOSTORAGE_ITEM_ID = "Furniture_AutoStorage";
    private static final String AUTOSTORAGE_ITEM_RESOURCE = "Server/Item/Items/Furniture/AutoStorage/Furniture_AutoStorage.json";
    private static final String AUTOSTORAGE_ITEM_OVERRIDE_PATH = "asset-overrides/Server/Item/Items/Furniture/AutoStorage/Furniture_AutoStorage.json";
    private final AutoStorage plugin;
    private final AtomicBoolean applyingRecipeOverride = new AtomicBoolean(false);
    private final AtomicInteger selfAssetReloadDepth = new AtomicInteger(0);

    CraftManager(AutoStorage plugin) {
        this.plugin = plugin;
    }

    boolean isSelfReloadingAssets() {
        return this.selfAssetReloadDepth.get() > 0;
    }

    void syncCraftPermissionForPlayer(Ref<EntityStore> ref, Store<EntityStore> store) {
        if (ref != null && store != null) {
            Player player = store.getComponent(ref, Player.getComponentType());
            if (player != null) {
                this.syncCraftPermissionForPlayer(ref, store, player);
            }
        }
    }

    private void syncCraftPermissionForPlayer(Ref<EntityStore> ref, Store<EntityStore> store, Player player) {
        AutoStorageConfig current = this.plugin.getAutoStorageConfig();
        if (this.shouldSyncCraftPermissions(current)) {
            if (!current.isItemIsCraftable()) {
                Set<String> known = new HashSet<>(player.getPlayerConfigData().getKnownRecipes());
                if (known.remove(AUTOSTORAGE_ITEM_ID)) {
                    player.getPlayerConfigData().setKnownRecipes(known);
                    CraftingPlugin.sendKnownRecipes(ref, store);
                }
            } else {
                String permission = current.getCraftRequiresPermissionName();
                if (permission != null && !permission.isBlank()) {
                    PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
                    boolean allowed = HytaleCompat.hasPermission(playerRef, permission);
                    Set<String> known = new HashSet<>(player.getPlayerConfigData().getKnownRecipes());
                    boolean changed = allowed ? known.add(AUTOSTORAGE_ITEM_ID) : known.remove(AUTOSTORAGE_ITEM_ID);
                    if (changed) {
                        player.getPlayerConfigData().setKnownRecipes(known);
                        CraftingPlugin.sendKnownRecipes(ref, store);
                    }
                }
            }
        }
    }

    void syncCraftPermissionForAllPlayers() {
        AutoStorageConfig config = this.plugin.getAutoStorageConfig();
        if (this.shouldSyncCraftPermissions(config)) {
            for(PlayerRef playerRef : HytaleCompat.getPlayers()) {
                Ref<EntityStore> ref = playerRef.getReference();
                if (ref != null) {
                    Store<EntityStore> store = ref.getStore();
                    World world = store.getExternalData().getWorld();
                    this.runOnWorld(world, "sync craft permissions (all players)", () -> this.syncCraftPermissionForPlayer(ref, store));
                }
            }
        }
    }

    void scheduleCraftPermissionSync(UUID uuid) {
        AutoStorageConfig config = this.plugin.getAutoStorageConfig();
        if (this.shouldSyncCraftPermissions(config)) {
            if (uuid != null) {
                PlayerRef playerRef = HytaleCompat.getPlayer(uuid);
                if (playerRef != null) {
                    Ref<EntityStore> ref = playerRef.getReference();
                    if (ref != null) {
                        Store<EntityStore> store = ref.getStore();
                        World world = store.getExternalData().getWorld();
                        this.runOnWorld(world, "sync craft permissions (single player)", () -> this.syncCraftPermissionForPlayer(ref, store));
                    }
                }
            }
        }
    }

    boolean isCraftAllowed(PlayerRef playerRef, CraftingRecipe recipe) {
        if (!this.isAutoStorageRecipe(recipe)) {
            return true;
        } else {
            AutoStorageConfig current = this.plugin.getAutoStorageConfig();
            if (current != null && current.isItemIsCraftable()) {
                if (!current.isCraftPermissionEnabled()) {
                    return true;
                } else {
                    String permission = current.getCraftRequiresPermissionName();
                    return HytaleCompat.hasPermission(playerRef, permission);
                }
            } else {
                return false;
            }
        }
    }

    private boolean isAutoStorageRecipe(CraftingRecipe recipe) {
        if (recipe == null) {
            return false;
        } else {
            MaterialQuantity primary = recipe.getPrimaryOutput();
            if (primary != null && AUTOSTORAGE_ITEM_ID.equals(primary.getItemId())) {
                return true;
            } else {
                MaterialQuantity[] outputs = recipe.getOutputs();
                if (outputs != null) {
                    for (MaterialQuantity output : outputs) {
                        if (output != null && AUTOSTORAGE_ITEM_ID.equals(output.getItemId())) {
                            return true;
                        }
                    }
                }
                return false;
            }
        }
    }

    void applyRecipeOverride(AutoStorageConfig config) {
        if (config == null) {
            return;
        }
        if (!this.applyingRecipeOverride.compareAndSet(false, true)) {
            return;
        }
        try {
            JsonObject itemJson = this.loadItemTemplate();
            if (itemJson == null) {
                return;
            }
            if (!config.isItemIsCraftable()) {
                disableCrafting(itemJson);
                return;
            }
            applyCraftingRecipe(itemJson, config);
        } finally {
            this.applyingRecipeOverride.set(false);
        }
    }

    private void applyCraftingRecipe(JsonObject itemJson, AutoStorageConfig config) {
        JsonObject recipeJson = itemJson.getAsJsonObject("Recipe");
        if (recipeJson == null) {
            recipeJson = new JsonObject();
            itemJson.add("Recipe", recipeJson);
        }
        recipeJson.addProperty(
                "OutputQuantity",
                config.getCraftOutputQuantity()
        );
        boolean applied = false;
        AutoStorageConfig.RecipeOverride override = config.getRecipeOverride();
        if (override != null && override.isEnabled()) {
            JsonArray inputArray = getJsonElements(override);

            if (inputArray.isEmpty()) {
                LOGGER.atWarning().log(
                        "[AutoStorage] Recipe override enabled but invalid input list, using default recipe."
                );
            } else {
                recipeJson.add("Input", inputArray);
                recipeJson.addProperty(
                        "TimeSeconds",
                        override.getTimeSeconds()
                );
                recipeJson.add(
                        "BenchRequirement",
                        createBenchRequirements(override)
                );
                applied = true;
            }
        }
        if (config.isCraftPermissionEnabled()) {
            recipeJson.addProperty("KnowledgeRequired", true);
        } else {
            recipeJson.remove("KnowledgeRequired");
        }
        Path overridePath = this.plugin.getDataDirectory()
                .resolve(AUTOSTORAGE_ITEM_OVERRIDE_PATH);
        if (!this.writeItemOverride(overridePath, itemJson)) {
            return;
        }
        if (!reloadOverrideAsset(overridePath)) {
            return;
        }
        if (applied) {
            LOGGER.atInfo().log(
                    "[AutoStorage] Recipe override applied for Furniture_AutoStorage"
            );
        }
    }

    private JsonArray createBenchRequirements(
            AutoStorageConfig.RecipeOverride override
    ) {
        JsonArray benchArray = new JsonArray();
        for (AutoStorageConfig.RecipeBenchRequirement bench : override.getBenchRequirement()) {
            if (bench == null) {
                continue;
            }
            String id = bench.getId();
            if (id == null || id.isBlank()) {
                continue;
            }
            JsonObject benchJson = new JsonObject();
            benchJson.addProperty("Id", id);
            String type = bench.getType();
            benchJson.addProperty(
                    "Type",
                    type == null || type.isBlank()
                            ? "Crafting"
                            : type
            );

            String[] categories = bench.getCategories();
            if (categories != null && categories.length > 0) {
                JsonArray categoriesJson = new JsonArray();
                for (String category : categories) {
                    if (category != null && !category.isBlank()) {
                        categoriesJson.add(category);
                    }
                }
                if (!categoriesJson.isEmpty()) {
                    benchJson.add("Categories", categoriesJson);
                }
            }

            int tier = bench.getRequiredTierLevel();
            if (tier > 0) {
                benchJson.addProperty(
                        "RequiredTierLevel",
                        tier
                );
            }
            benchArray.add(benchJson);
        }
        return benchArray;
    }

    private void disableCrafting(JsonObject itemJson) {
        itemJson.remove("Recipe");
        Path overridePath = this.plugin.getDataDirectory()
                .resolve(AUTOSTORAGE_ITEM_OVERRIDE_PATH);
        if (!this.writeItemOverride(overridePath, itemJson)) {
            return;
        }
        if (reloadOverrideAsset(overridePath)) {
            LOGGER.atInfo().log(
                    "[AutoStorage] Crafting disabled for Furniture_AutoStorage"
            );
        }
    }

    private boolean reloadOverrideAsset(Path path) {
        try {
            this.loadOverrideAsset(path);
            return true;
        } catch (RuntimeException e) {
            LOGGER.atWarning()
                    .withCause(e)
                    .log("[AutoStorage] Failed to reload item assets.");
            return false;
        }
    }

    @NonNullDecl
    private static JsonArray getJsonElements(AutoStorageConfig.RecipeOverride override) {
        JsonArray inputArray = new JsonArray();

        for(AutoStorageConfig.RecipeInput entry : override.getInput()) {
            if (entry != null) {
                String itemId = entry.getItemId();
                int quantity = entry.getQuantity();
                if (itemId != null && !itemId.isBlank() && quantity >= 1) {
                    JsonObject inputEntry = new JsonObject();
                    inputEntry.addProperty("ItemId", itemId);
                    inputEntry.addProperty("Quantity", quantity);
                    inputArray.add(inputEntry);
                }
            }
        }
        return inputArray;
    }

    void registerCraftPermissionSyncListeners() {
        EventRegistry registry = this.plugin.getEventRegistry();
        registry.register(
                PlayerPermissionChangeEvent.PermissionsAdded.class,
                (event) -> this.scheduleCraftPermissionSync(event.getPlayerUuid())
        );
        registry.register(
                PlayerPermissionChangeEvent.PermissionsRemoved.class,
                (event) -> this.scheduleCraftPermissionSync(event.getPlayerUuid())
        );
        registry.register(
                PlayerPermissionChangeEvent.GroupAdded.class,
                (event) -> this.scheduleCraftPermissionSync(event.getPlayerUuid())
        );
        registry.register(
                PlayerPermissionChangeEvent.GroupRemoved.class,
                (event) -> this.scheduleCraftPermissionSync(event.getPlayerUuid())
        );
    }

    private boolean shouldSyncCraftPermissions(AutoStorageConfig current) {
        if (current == null) {
            return false;
        } else {
            return !current.isItemIsCraftable() || current.isCraftPermissionEnabled();
        }
    }

    private JsonObject loadItemTemplate() {
        try (InputStream stream = this.getClass()
                .getClassLoader()
                .getResourceAsStream(AUTOSTORAGE_ITEM_RESOURCE)) {
            if (stream == null) {
                LOGGER.atWarning().log(
                        "[AutoStorage] Missing item template: " + AUTOSTORAGE_ITEM_RESOURCE
                );
                return null;
            }
            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                JsonElement root = JsonParser.parseReader(reader);
                if (root != null && root.isJsonObject()) {
                    return root.getAsJsonObject();
                }
                LOGGER.atWarning().log(
                        "[AutoStorage] Invalid item template JSON: " + AUTOSTORAGE_ITEM_RESOURCE
                );
                return null;
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.atWarning()
                    .withCause(e)
                    .log("[AutoStorage] Failed to read item template: " + AUTOSTORAGE_ITEM_RESOURCE);
            return null;
        }
    }

    private boolean writeItemOverride(Path overridePath, JsonObject itemJson) {
        try {
            Path parent = overridePath.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }

            try (Writer writer = Files.newBufferedWriter(overridePath, StandardCharsets.UTF_8)) {
                ASSET_GSON.toJson(itemJson, writer);
            }

            return true;
        } catch (IOException e) {
            ((LOGGER.atWarning()).withCause(e)).log("[AutoStorage] Failed to write item override: " + overridePath);
            return false;
        }
    }

    private void runOnWorld(World world, String context, Runnable action) {
        if (world != null && action != null) {
            if (world.isInThread()) {
                this.runSafe(context, action);
            } else {
                try {
                    world.execute(() -> this.runSafe(context, action));
                } catch (RuntimeException throwable) {
                    ((LOGGER.atWarning()).withCause(throwable)).log("[AutoStorage] Failed to schedule world task: " + context);
                }
            }
        }
    }

    private void runSafe(String context, Runnable action) {
        try {
            action.run();
        } catch (Throwable throwable) {
            ((LOGGER.atWarning()).withCause(throwable)).log("[AutoStorage] World task failed: " + context);
        }
    }

    private void loadOverrideAsset(Path overridePath) {
        this.selfAssetReloadDepth.incrementAndGet();
        try {
            Item.getAssetStore().loadAssetsFromPaths(this.plugin.getIdentifier().toString(), List.of(overridePath), AssetUpdateQuery.DEFAULT_NO_REBUILD);
        } finally {
            this.selfAssetReloadDepth.decrementAndGet();
        }
    }
}
