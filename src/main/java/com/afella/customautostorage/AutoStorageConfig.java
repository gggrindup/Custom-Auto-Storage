package com.afella.customautostorage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.logger.HytaleLogger;
import lombok.Getter;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class AutoStorageConfig {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final Gson GSON = (new GsonBuilder()).setPrettyPrinting().create();
    private static final int BASE_HORIZONTAL_RADIUS = 14;
    private static final int BASE_VERTICAL_RADIUS = 6;
    private static final int MAX_RADIUS_LIMIT = 200;
    private static final int CURRENT_CONFIG_VERSION = 6;
    private int configVersion = 6;
    private RadiusConfig radius = new RadiusConfig();
    private TransferConfig transferConfig = new TransferConfig();
    private SortingMode defaultSortingMode;
    @Getter
    private int idleRetryMs;
    @Getter
    private int idleRetryBackoffMaxMs;
    @Getter
    private int targetScanCacheMaxAgeMs;
    @Getter
    private boolean onlyStorageOwnerCanConfig;
    @Getter
    private boolean itemIsCraftable;
    @Getter
    private boolean craftRequiresPermission;
    @Getter
    private String craftRequiresPermissionName;
    @Getter
    private int craftOutputQuantity;
    @Getter
    private RecipeOverride recipeOverride;
    @Getter
    private TargetFilterConfig targetFilter;
    private boolean claimModCompatibility;

    public AutoStorageConfig() {
        this.defaultSortingMode = AutoStorageConfig.SortingMode.ITEM_ID;
        this.idleRetryMs = 5000;
        this.idleRetryBackoffMaxMs = 60000;
        this.targetScanCacheMaxAgeMs = 15000;
        this.onlyStorageOwnerCanConfig = false;
        this.itemIsCraftable = true;
        this.craftRequiresPermission = false;
        this.craftRequiresPermissionName = "autostorage.craft";
        this.craftOutputQuantity = 1;
        this.recipeOverride = new RecipeOverride();
        this.targetFilter = new TargetFilterConfig();
        this.claimModCompatibility = false;
    }

    public int getHorizontalRadiusMax() {
        return this.radius != null ? this.radius.horizontalRadiusMax : 20;
    }

    public int getVerticalRadiusMax() {
        return this.radius != null ? this.radius.verticalRadiusMax : 8;
    }

    public int getRadiusHardLimit() {
        return MAX_RADIUS_LIMIT;
    }

    public int getDefaultHorizontalRadius() {
        int configured = this.radius != null ? this.radius.getDefaultHorizontalRadius() : BASE_HORIZONTAL_RADIUS;
        int max = this.getHorizontalRadiusMax();
        return this.clampRadius(configured, 1, max);
    }

    public int getDefaultVerticalRadius() {
        int configured = this.radius != null ? this.radius.getDefaultVerticalRadius() : 6;
        int max = this.getVerticalRadiusMax();
        return this.clampRadius(configured, 1, max);
    }

    public boolean isTransferImmediate() {
        return this.transferConfig != null && this.transferConfig.transferImmediate;
    }

    public int getTransferAfterLastInteractionInSeconds() {
        return this.transferConfig != null ? this.transferConfig.transferAfterLastInteractionInSeconds : 0;
    }

    public int getTransferAfterLastInteractionMs() {
        return this.getTransferAfterLastInteractionInSeconds() * 1000;
    }

    public boolean isTransferOnlyWhenClose() {
        return this.transferConfig != null && this.transferConfig.transferOnlyWhenClose;
    }

    public TransferMode getTransferMode() {
        return this.transferConfig != null ? this.transferConfig.transferMode : AutoStorageConfig.TransferMode.ITEMS;
    }

    public SortingMode getDefaultSortingMode() {
        return this.defaultSortingMode != null ? this.defaultSortingMode : AutoStorageConfig.SortingMode.ITEM_ID;
    }

    public int getTransferAmountPerTransfer() {
        return this.transferConfig != null ? this.transferConfig.transferAmountPerTransfer : 1;
    }

    public int getTransferIntervalMs() {
        return this.transferConfig != null ? this.transferConfig.transferIntervalMs : 1000;
    }

    public boolean isCraftPermissionEnabled() {
        return this.craftRequiresPermission && this.craftRequiresPermissionName != null && !this.craftRequiresPermissionName.isBlank();
    }

    public boolean isClaimCompatibilityEnabled() {
        return this.claimModCompatibility;
    }

    public static AutoStorageConfig load(Path configFolder) {
        Path configFile = configFolder.resolve("config.json");

        try {
            if (!Files.exists(configFolder)) {
                Files.createDirectories(configFolder);
            }

            if (!Files.exists(configFile)) {
                AutoStorageConfig config = new AutoStorageConfig();
                save(configFile, config);
                return config;
            } else {
                try (Reader reader = Files.newBufferedReader(configFile)) {
                    JsonElement root = JsonParser.parseReader(reader);
                    JsonObject obj = root != null && root.isJsonObject() ? root.getAsJsonObject() : new JsonObject();
                    AutoStorageConfig config = GSON.fromJson(obj, AutoStorageConfig.class);
                    if (config == null) {
                        config = new AutoStorageConfig();
                    }

                    int version = 0;
                    if (obj.has("configVersion")) {
                        try {
                            version = obj.get("configVersion").getAsInt();
                        } catch (RuntimeException var10) {
                            version = 0;
                        }
                    }

                    boolean upgraded = version < 6;
                    if (upgraded) {
                        migrateConfig(obj, config, version);
                    }

                    migrateLegacyConfig(obj, config);
                    migrateClaimCompatibility(obj, config);
                    migrateTargetFilterFlags(obj, config);
                    config.configVersion = 6;
                    config.validate();
                    if (upgraded) {
                        save(configFile, config);
                    }

                    return config;
                }
            }
        } catch (IOException e) {
            ((LOGGER.atSevere()).withCause(e)).log("Failed to load config.json");
            return new AutoStorageConfig();
        } catch (RuntimeException e) {
            ((LOGGER.atSevere()).withCause(e)).log("Failed to parse config.json");
            return new AutoStorageConfig();
        }
    }

    private static void save(Path configFile, AutoStorageConfig config) {
        try (Writer writer = Files.newBufferedWriter(configFile)) {
            GSON.toJson(config, writer);
        } catch (IOException e) {
            ((LOGGER.atSevere()).withCause(e)).log("Failed to save config.json");
        }

    }

    private void validate() {
        if (this.configVersion <= 0) {
            this.configVersion = CURRENT_CONFIG_VERSION;
        }

        if (this.radius == null) {
            this.radius = new RadiusConfig();
        }

        if (this.transferConfig == null) {
            this.transferConfig = new TransferConfig();
        }

        if (this.radius.horizontalRadiusMax < 1) {
            this.radius.horizontalRadiusMax = 1;
        } else if (this.radius.horizontalRadiusMax > MAX_RADIUS_LIMIT) {
            this.radius.horizontalRadiusMax = MAX_RADIUS_LIMIT;
        }

        if (this.radius.verticalRadiusMax < 1) {
            this.radius.verticalRadiusMax = 1;
        } else if (this.radius.verticalRadiusMax > MAX_RADIUS_LIMIT) {
            this.radius.verticalRadiusMax = MAX_RADIUS_LIMIT;
        }

        if (this.radius.defaultHorizontalRadius < 1) {
            this.radius.defaultHorizontalRadius = BASE_HORIZONTAL_RADIUS;
        }

        if (this.radius.defaultVerticalRadius < 1) {
            this.radius.defaultVerticalRadius = BASE_VERTICAL_RADIUS;
        }

        if (this.radius.defaultHorizontalRadius > this.radius.horizontalRadiusMax) {
            this.radius.defaultHorizontalRadius = this.radius.horizontalRadiusMax;
        }

        if (this.radius.defaultVerticalRadius > this.radius.verticalRadiusMax) {
            this.radius.defaultVerticalRadius = this.radius.verticalRadiusMax;
        }

        if (this.transferConfig.transferMode == null) {
            this.transferConfig.transferMode = AutoStorageConfig.TransferMode.ITEMS;
        }

        if (this.defaultSortingMode == null) {
            this.defaultSortingMode = AutoStorageConfig.SortingMode.ITEM_ID;
        }

        if (this.transferConfig.transferAfterLastInteractionInSeconds < 0) {
            this.transferConfig.transferAfterLastInteractionInSeconds = 0;
        } else if (this.transferConfig.transferAfterLastInteractionInSeconds > 60) {
            this.transferConfig.transferAfterLastInteractionInSeconds = 60;
        }

        if (this.transferConfig.transferAmountPerTransfer < 1) {
            this.transferConfig.transferAmountPerTransfer = 1;
        } else if (this.transferConfig.transferAmountPerTransfer > 1000) {
            this.transferConfig.transferAmountPerTransfer = 1000;
        }

        if (this.transferConfig.transferIntervalMs < 50) {
            this.transferConfig.transferIntervalMs = 50;
        } else if (this.transferConfig.transferIntervalMs > 5000) {
            this.transferConfig.transferIntervalMs = 5000;
        }

        if (this.idleRetryMs < 500) {
            this.idleRetryMs = 500;
        } else if (this.idleRetryMs > 60000) {
            this.idleRetryMs = 60000;
        }

        if (this.idleRetryBackoffMaxMs < 1000) {
            this.idleRetryBackoffMaxMs = 1000;
        } else if (this.idleRetryBackoffMaxMs > 60000) {
            this.idleRetryBackoffMaxMs = 60000;
        }

        if (this.targetScanCacheMaxAgeMs < 1000) {
            this.targetScanCacheMaxAgeMs = 1000;
        } else if (this.targetScanCacheMaxAgeMs > 120000) {
            this.targetScanCacheMaxAgeMs = 120000;
        }

        if (this.targetScanCacheMaxAgeMs > this.idleRetryBackoffMaxMs) {
            this.targetScanCacheMaxAgeMs = this.idleRetryBackoffMaxMs;
        }

        if (this.craftRequiresPermissionName == null) {
            this.craftRequiresPermissionName = "";
        } else {
            this.craftRequiresPermissionName = this.craftRequiresPermissionName.trim();
        }

        if (this.craftRequiresPermission && this.craftRequiresPermissionName.isBlank()) {
            this.craftRequiresPermissionName = "autostorage.craft";
        }

        if (this.craftOutputQuantity < 1) {
            this.craftOutputQuantity = 1;
        } else if (this.craftOutputQuantity > 1000) {
            this.craftOutputQuantity = 1000;
        }

        if (this.recipeOverride == null) {
            this.recipeOverride = new RecipeOverride();
        }

        this.recipeOverride.validate();
        if (this.targetFilter == null) {
            this.targetFilter = new TargetFilterConfig();
        }

        this.targetFilter.validate();
    }

    private int clampRadius(int value, int min, int max) {
        if (value < min) {
            return min;
        } else {
            return Math.min(value, max);
        }
    }

    private static void migrateConfig(JsonObject obj, AutoStorageConfig config, int version) {
        if (version < 1) {
            migrateLegacyConfig(obj, config);
        }

        if (version < 5) {
            migrateClaimCompatibility(obj, config);
        }

        if (version < 6) {
            migrateTargetFilterFlags(obj, config);
        }

    }

    private static void migrateClaimCompatibility(JsonObject obj, AutoStorageConfig config) {
        if (obj != null && config != null) {
            try {
                if (obj.has("claimModCompatibility")) {
                    config.claimModCompatibility = obj.get("claimModCompatibility").getAsBoolean();
                    return;
                }

                if (obj.has("claimCompatibility") && obj.get("claimCompatibility").isJsonObject()) {
                    JsonObject legacy = obj.getAsJsonObject("claimCompatibility");
                    if (legacy.has("enabled")) {
                        config.claimModCompatibility = legacy.get("enabled").getAsBoolean();
                    }
                }
            } catch (RuntimeException exception) {
                LOGGER.atWarning()
                        .withCause(exception)
                        .log("[AutoStorage] Failed to migrate claim compatibility config.");
            }
        }
    }

    private static void migrateTargetFilterFlags(JsonObject obj, AutoStorageConfig config) {
        if (obj != null && config != null) {
            if (config.targetFilter == null) {
                config.targetFilter = new TargetFilterConfig();
            }

            if (obj.has("targetFilter") && obj.get("targetFilter").isJsonObject()) {
                JsonObject filterObj = obj.getAsJsonObject("targetFilter");

                try {
                    if (!filterObj.has("allowBlockIdsEnabled") && hasArray(filterObj, "allowBlockIds")) {
                        config.targetFilter.allowBlockIdsEnabled = hasNonEmptyStringArray(filterObj, "allowBlockIds");
                    }

                    if (!filterObj.has("denyBlockIdsEnabled") && hasArray(filterObj, "denyBlockIds")) {
                        config.targetFilter.denyBlockIdsEnabled = hasNonEmptyStringArray(filterObj, "denyBlockIds");
                    }

                    if (!filterObj.has("allowIdContainsEnabled") && hasArray(filterObj, "allowIdContains")) {
                        config.targetFilter.allowIdContainsEnabled = hasNonEmptyStringArray(filterObj, "allowIdContains");
                    }

                    if (!filterObj.has("denyIdContainsEnabled") && hasArray(filterObj, "denyIdContains")) {
                        config.targetFilter.denyIdContainsEnabled = hasNonEmptyStringArray(filterObj, "denyIdContains");
                    }
                } catch (RuntimeException exception) {
                    LOGGER.atWarning()
                            .withCause(exception)
                            .log("[AutoStorage] Failed to migrate target filter flags config.");
                }
            }
        }
    }

    private static boolean hasArray(JsonObject obj, String key) {
        return obj != null && key != null && !key.isBlank() && obj.has(key) && obj.get(key).isJsonArray();
    }

    private static boolean hasNonEmptyStringArray(JsonObject obj, String key) {
        if (obj != null && key != null && !key.isBlank() && obj.has(key) && obj.get(key).isJsonArray()) {
            for(JsonElement element : obj.getAsJsonArray(key)) {
                if (element != null && element.isJsonPrimitive()) {
                    try {
                        String value = element.getAsString();
                        if (value != null && !value.isBlank()) {
                            return true;
                        }
                    } catch (RuntimeException exception) {
                        LOGGER.atWarning()
                                .withCause(exception)
                                .log("[AutoStorage] Invalid value in config array '" + key + "'.");
                    }
                }
            }
            return false;
        } else {
            return false;
        }
    }

    private static void migrateLegacyConfig(JsonObject obj, AutoStorageConfig config) {
        if (obj != null && config != null) {
            if (config.radius == null) {
                config.radius = new RadiusConfig();
            }

            if (config.transferConfig == null) {
                config.transferConfig = new TransferConfig();
            }

            try {
                boolean hasRadiusHorizontal = hasNestedField(obj, "radius", "horizontalRadiusMax");
                boolean hasRadiusVertical = hasNestedField(obj, "radius", "verticalRadiusMax");
                boolean hasTransferImmediate = hasNestedField(obj, "transferConfig", "transferImmediate");
                boolean hasTransferDelay = hasNestedField(obj, "transferConfig", "transferAfterLastInteractionInSeconds");
                boolean hasTransferOnlyWhenClose = hasNestedField(obj, "transferConfig", "transferOnlyWhenClose");
                boolean hasTransferMode = hasNestedField(obj, "transferConfig", "transferMode");
                boolean hasTransferAmount = hasNestedField(obj, "transferConfig", "transferAmountPerTransfer");
                boolean hasTransferInterval = hasNestedField(obj, "transferConfig", "transferIntervalMs");
                if (!hasRadiusHorizontal && obj.has("horizontalRadiusMax")) {
                    config.radius.horizontalRadiusMax = obj.get("horizontalRadiusMax").getAsInt();
                } else if (!hasRadiusHorizontal && obj.has("horizontalRadius")) {
                    config.radius.horizontalRadiusMax = obj.get("horizontalRadius").getAsInt();
                } else if (!hasRadiusHorizontal && obj.has("extendedRadius")) {
                    config.radius.horizontalRadiusMax = obj.get("extendedRadius").getAsInt();
                }

                if (!hasRadiusVertical && obj.has("verticalRadiusMax")) {
                    config.radius.verticalRadiusMax = obj.get("verticalRadiusMax").getAsInt();
                } else if (!hasRadiusVertical && obj.has("verticalRadius")) {
                    config.radius.verticalRadiusMax = obj.get("verticalRadius").getAsInt();
                } else if (!hasRadiusVertical && obj.has("extendedVerticalRadius")) {
                    config.radius.verticalRadiusMax = obj.get("extendedVerticalRadius").getAsInt();
                }

                boolean immediateFromMode = false;
                if (!hasTransferImmediate && obj.has("transferImmediate")) {
                    config.transferConfig.transferImmediate = obj.get("transferImmediate").getAsBoolean();
                } else if (!hasTransferImmediate && obj.has("transferMode")) {
                    String legacyMode = obj.get("transferMode").getAsString();
                    if (legacyMode != null) {
                        String upper = legacyMode.toUpperCase(Locale.ROOT);
                        if (upper.equals("IMMEDIATE")) {
                            config.transferConfig.transferImmediate = true;
                            immediateFromMode = true;
                        } else if (upper.equals("DELAYED") || upper.equals("ON_CLOSE")) {
                            config.transferConfig.transferImmediate = false;
                            immediateFromMode = true;
                        }
                    }
                }

                if (!hasTransferDelay && obj.has("transferAfterLastInteractionInSeconds")) {
                    config.transferConfig.transferAfterLastInteractionInSeconds = obj.get("transferAfterLastInteractionInSeconds").getAsInt();
                } else if (!hasTransferDelay && obj.has("transferDelaySeconds")) {
                    config.transferConfig.transferAfterLastInteractionInSeconds = obj.get("transferDelaySeconds").getAsInt();
                }

                if (!hasTransferOnlyWhenClose && obj.has("transferOnlyWhenClose")) {
                    config.transferConfig.transferOnlyWhenClose = obj.get("transferOnlyWhenClose").getAsBoolean();
                } else if (!hasTransferOnlyWhenClose && obj.has("transferWhileOpen")) {
                    config.transferConfig.transferOnlyWhenClose = !obj.get("transferWhileOpen").getAsBoolean();
                } else if (!hasTransferOnlyWhenClose && obj.has("transferOpenMode")) {
                    String openMode = obj.get("transferOpenMode").getAsString();
                    if (openMode != null && openMode.equalsIgnoreCase("CLOSED_ONLY")) {
                        config.transferConfig.transferOnlyWhenClose = true;
                    } else if (openMode != null) {
                        config.transferConfig.transferOnlyWhenClose = false;
                    }
                }

                boolean modeSet = false;
                if (!hasTransferMode && obj.has("transferMode")) {
                    String mode = obj.get("transferMode").getAsString();
                    if (!immediateFromMode) {
                        modeSet = applyTransferMode(mode, config);
                    }
                }

                if (!hasTransferMode && !modeSet && obj.has("transferPerTransferMode")) {
                    String mode = obj.get("transferPerTransferMode").getAsString();
                    modeSet = applyTransferMode(mode, config);
                }

                if (!hasTransferMode && !modeSet && obj.has("transferRateMode")) {
                    String mode = obj.get("transferRateMode").getAsString();
                    applyTransferMode(mode, config);
                }

                if (!hasTransferAmount && obj.has("transferAmountPerTransfer")) {
                    config.transferConfig.transferAmountPerTransfer = obj.get("transferAmountPerTransfer").getAsInt();
                } else if (!hasTransferAmount && obj.has("transferRatePerSecond")) {
                    config.transferConfig.transferAmountPerTransfer = obj.get("transferRatePerSecond").getAsInt();
                }

                if (!hasTransferInterval && obj.has("transferIntervalMs")) {
                    config.transferConfig.transferIntervalMs = obj.get("transferIntervalMs").getAsInt();
                }

                if (obj.has("idleRetryMs")) {
                    config.idleRetryMs = obj.get("idleRetryMs").getAsInt();
                }

                if (!obj.has("defaultSortingMode") && obj.has("sortingMode")) {
                    config.defaultSortingMode = parseSortingMode(obj.get("sortingMode").getAsString());
                } else if (obj.has("defaultSortingMode")) {
                    config.defaultSortingMode = parseSortingMode(obj.get("defaultSortingMode").getAsString());
                }

                if (obj.has("configOwnerOnly")) {
                    config.onlyStorageOwnerCanConfig = obj.get("configOwnerOnly").getAsBoolean();
                } else if (obj.has("onlyStorageOwnerCanConfig")) {
                    config.onlyStorageOwnerCanConfig = obj.get("onlyStorageOwnerCanConfig").getAsBoolean();
                }

                if (obj.has("itemCraftable")) {
                    config.itemIsCraftable = obj.get("itemCraftable").getAsBoolean();
                } else if (obj.has("itemIsCraftable")) {
                    config.itemIsCraftable = obj.get("itemIsCraftable").getAsBoolean();
                }

                boolean craftPermissionFlagSet = false;
                if (obj.has("craftRequiresPermission")) {
                    config.craftRequiresPermission = obj.get("craftRequiresPermission").getAsBoolean();
                    craftPermissionFlagSet = true;
                }

                String legacyCraftPermissionName = null;
                if (obj.has("craftRequiresPermissionName")) {
                    legacyCraftPermissionName = obj.get("craftRequiresPermissionName").getAsString();
                } else if (obj.has("craftPermission")) {
                    legacyCraftPermissionName = obj.get("craftPermission").getAsString();
                }

                if (legacyCraftPermissionName != null) {
                    config.craftRequiresPermissionName = legacyCraftPermissionName;
                    if (!craftPermissionFlagSet && !legacyCraftPermissionName.isBlank()) {
                        config.craftRequiresPermission = true;
                    }
                }
            } catch (RuntimeException var14) {
            }

        }
    }

    private static boolean hasNestedField(JsonObject root, String objectKey, String fieldKey) {
        if (root != null && objectKey != null && !objectKey.isBlank() && fieldKey != null && !fieldKey.isBlank()) {
            return root.has(objectKey) && root.get(objectKey).isJsonObject() && root.getAsJsonObject(objectKey).has(fieldKey);
        } else {
            return false;
        }
    }

    private static boolean applyTransferMode(String mode, AutoStorageConfig config) {
        if (mode != null && config != null && config.transferConfig != null) {
            String upper = mode.toUpperCase(Locale.ROOT);
            if (upper.contains("STACK")) {
                config.transferConfig.transferMode = AutoStorageConfig.TransferMode.STACKS;
                return true;
            } else if (upper.contains("ITEM")) {
                config.transferConfig.transferMode = AutoStorageConfig.TransferMode.ITEMS;
                return true;
            } else {
                return false;
            }
        } else {
            return false;
        }
    }

    private static SortingMode parseSortingMode(String value) {
        if (value == null) {
            return AutoStorageConfig.SortingMode.ITEM_ID;
        } else {
            String normalized = value.trim().toUpperCase(Locale.ROOT);

            try {
                return AutoStorageConfig.SortingMode.valueOf(normalized);
            } catch (RuntimeException var3) {
                return AutoStorageConfig.SortingMode.ITEM_ID;
            }
        }
    }

    public static enum TransferMode {
        STACKS,
        ITEMS;

        TransferMode() {
        }
    }

    @Getter
    public enum SortingMode {
        ITEM_ID("server.autostorage.ui.mode.item.label"),
        ITEM_CATEGORY("server.autostorage.ui.mode.category.label");

        private final String translationKey;

        private SortingMode(String translationKey) {
            this.translationKey = translationKey;
        }

    }

    @Getter
    public static final class RadiusConfig {
        private int horizontalRadiusMax = 18;
        private int verticalRadiusMax = 15;
        private int defaultHorizontalRadius = BASE_HORIZONTAL_RADIUS;
        private int defaultVerticalRadius = BASE_VERTICAL_RADIUS;

        public RadiusConfig() {
        }

    }

    @Getter
    public static final class TransferConfig {
        private boolean transferImmediate = true;
        private int transferAfterLastInteractionInSeconds = 3;
        private boolean transferOnlyWhenClose = false;
        private TransferMode transferMode;
        private int transferAmountPerTransfer;
        private int transferIntervalMs;

        public TransferConfig() {
            this.transferMode = AutoStorageConfig.TransferMode.STACKS;
            this.transferAmountPerTransfer = 1;
            this.transferIntervalMs = 1000;
        }

    }

    public static final class TargetFilterConfig {
        @Getter
        private boolean allowBlockIdsEnabled = false;
        @Getter
        private boolean denyBlockIdsEnabled = false;
        @Getter
        private boolean allowIdContainsEnabled = false;
        @Getter
        private boolean denyIdContainsEnabled = true;
        private String[] allowBlockIds = new String[0];
        private String[] denyBlockIds = new String[0];
        private String[] allowIdContains = defaultAllowIdContains();
        private String[] denyIdContains = defaultDenyIdContains();

        public TargetFilterConfig() {
        }

        public String[] getAllowBlockIds() {
            return this.allowBlockIds != null ? this.allowBlockIds : new String[0];
        }

        public String[] getDenyBlockIds() {
            return this.denyBlockIds != null ? this.denyBlockIds : new String[0];
        }

        public String[] getAllowIdContains() {
            return this.allowIdContains != null ? this.allowIdContains : new String[0];
        }

        public String[] getDenyIdContains() {
            return this.denyIdContains != null ? this.denyIdContains : new String[0];
        }

        private void validate() {
            this.allowBlockIds = normalizeList(this.allowBlockIds, true);
            this.denyBlockIds = normalizeList(this.denyBlockIds, true);
            this.allowIdContains = normalizeList(this.allowIdContains, false);
            this.denyIdContains = normalizeList(this.denyIdContains, false);
        }

        private static String[] defaultAllowIdContains() {
            return new String[]{"chest", "drawer", "storage"};
        }

        private static String[] defaultDenyIdContains() {
            return new String[]{"autostorage", "salvage", "campfire", "furnace", "tannery", "trashcan"};
        }

        private static String[] normalizeList(String[] values, boolean stripLeadingWildcard) {
            if (values != null && values.length != 0) {
                List<String> result = new ArrayList<>();

                for(String value : values) {
                    if (value != null) {
                        String trimmed = value.trim();
                        if (!trimmed.isEmpty()) {
                            if (stripLeadingWildcard && trimmed.charAt(0) == '*') {
                                trimmed = trimmed.substring(1).trim();
                                if (trimmed.isEmpty()) {
                                    continue;
                                }
                            }
                            result.add(trimmed.toLowerCase(Locale.ROOT));
                        }
                    }
                }
                return result.toArray(new String[0]);
            } else {
                return new String[0];
            }
        }
    }

    public static final class RecipeOverride {
        @Getter
        private boolean enabled = false;
        @Getter
        private float timeSeconds = 2.0F;
        private RecipeInput[] input = defaultInput();
        private RecipeBenchRequirement[] benchRequirement = defaultBenchRequirements();

        public RecipeOverride() {
        }

        public RecipeInput[] getInput() {
            return this.input != null ? this.input : new RecipeInput[0];
        }

        public RecipeBenchRequirement[] getBenchRequirement() {
            return this.benchRequirement != null ? this.benchRequirement : new RecipeBenchRequirement[0];
        }

        private void validate() {
            if (this.timeSeconds < 0.0F) {
                this.timeSeconds = 0.0F;
            }

            if (this.input == null) {
                this.input = new RecipeInput[0];
            } else {
                for(RecipeInput entry : this.input) {
                    if (entry != null && entry.quantity < 1) {
                        entry.quantity = 1;
                    }
                }
            }

            if (this.benchRequirement == null) {
                this.benchRequirement = new RecipeBenchRequirement[0];
            } else {
                for(RecipeBenchRequirement entry : this.benchRequirement) {
                    if (entry != null) {
                        if (entry.type == null || entry.type.isBlank()) {
                            entry.type = "Crafting";
                        }

                        if (entry.requiredTierLevel < 0) {
                            entry.requiredTierLevel = 0;
                        }
                    }
                }
            }

        }

        private static RecipeInput[] defaultInput() {
            return new RecipeInput[]{
                    new RecipeInput("Ingredient_Life_Essence_Concentrated", 5),
                    new RecipeInput("Ingredient_Bar_Copper", 20),
                    new RecipeInput("Ingredient_Bar_Iron", 20),
                    new RecipeInput("Ingredient_Bar_Gold", 1)
            };
        }

        private static RecipeBenchRequirement[] defaultBenchRequirements() {
            return new RecipeBenchRequirement[]{
                    new RecipeBenchRequirement("Workbench", "Crafting",
                    new String[]{"Workbench_Tinkering"}, 2)
            };
        }
    }

    @Getter
    public static final class RecipeInput {
        private String itemId;
        private int quantity;

        public RecipeInput() {
        }

        public RecipeInput(String itemId, int quantity) {
            this.itemId = itemId;
            this.quantity = quantity;
        }

    }

    @Getter
    public static final class RecipeBenchRequirement {
        private String id;
        private String type;
        private String[] categories;
        private int requiredTierLevel;

        public RecipeBenchRequirement() {
        }

        public RecipeBenchRequirement(String id, String type, String[] categories, int requiredTierLevel) {
            this.id = id;
            this.type = type;
            this.categories = categories;
            this.requiredTierLevel = requiredTierLevel;
        }

    }
}
