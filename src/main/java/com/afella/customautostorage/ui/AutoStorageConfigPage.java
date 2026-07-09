package com.afella.customautostorage.ui;

import com.afella.customautostorage.AutoStorage;
import com.afella.customautostorage.AutoStorageConfig;
import com.afella.customautostorage.AutoStorageConfig.SortingMode;
import com.afella.customautostorage.compat.HytaleCompat;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.modules.i18n.I18nModule;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import lombok.Getter;
import lombok.Setter;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;
import org.joml.Vector3i;

import java.util.List;
import java.util.UUID;

import static com.afella.customautostorage.ui.AutoStorageUiConstants.*;

public final class AutoStorageConfigPage extends InteractiveCustomUIPage<AutoStorageConfigPage.PageData> {

    private final AutoStorage plugin;
    private final Vector3i targetBlock;
    private final String worldName;
    private Tab currentTab;
    private int chestPage;
    private List<AutoStorage.NearbyChestView> cachedChests;
    private List<AutoStorage.NearbyChestView> cachedAllChests;
    private int cachedLinkedChests;
    private boolean chestsDirty;
    private boolean linkedDirty;

    public AutoStorageConfigPage(PlayerRef playerRef, AutoStorage plugin, Vector3i targetBlock, String worldName) {
        super(playerRef, HytaleCompat.pageLifetimeCanDismiss(), PageData.CODEC);
        this.plugin = plugin;
        this.targetBlock = targetBlock;
        this.worldName = worldName;
        this.currentTab = Tab.CONFIG;
        this.chestPage = 0;
        this.cachedChests = List.of();
        this.cachedAllChests = List.of();
        this.cachedLinkedChests = 0;
        this.chestsDirty = true;
        this.linkedDirty = true;
    }

    @Override
    public void build(@NonNullDecl Ref<EntityStore> ref, @NonNullDecl UICommandBuilder commands, @NonNullDecl UIEventBuilder events, @NonNullDecl Store<EntityStore> store) {
        try {
            UUID actorUuid = playerRef.getUuid();
            if (!plugin.canInteractWithClaims(actorUuid, worldName, targetBlock)) {
                HytaleCompat.sendMessage(playerRef, Message.translation(KEY_CHAT_CLAIM_PROTECTED));
                close();
                return;
            }

            if (!plugin.canConfigure(playerRef, actorUuid, worldName, targetBlock)) {
                HytaleCompat.sendMessage(playerRef, Message.translation(KEY_CHAT_CONFIG_OWNER_ONLY));
                close();
                return;
            }

            commands.append("AutoStorage/AutoStorageConfig.ui");

            bindAction(events, SELECTOR_CLOSE_BUTTON, ACTION_CLOSE);
            bindAction(events, SELECTOR_TAB_CONFIG_BUTTON, ACTION_TAB_CONFIG);
            bindAction(events, SELECTOR_TAB_CHESTS_BUTTON, ACTION_TAB_CHESTS);
            bindAction(events, "#ChestsPrevButton", ACTION_PAGE_PREV);
            bindAction(events, "#ChestsNextButton", ACTION_PAGE_NEXT);
            bindAction(events, SELECTOR_MODE_ITEM_BUTTON, ACTION_MODE_ITEM);
            bindAction(events, SELECTOR_MODE_CATEGORY_BUTTON, ACTION_MODE_CATEGORY);
            bindAction(events, SELECTOR_FALLBACK_BUTTON, ACTION_FALLBACK_TOGGLE);
            bindAction(events, SELECTOR_HORIZONTAL_RADIUS_MINUS, ACTION_RADIUS_H_MINUS);
            bindAction(events, SELECTOR_HORIZONTAL_RADIUS_PLUS, ACTION_RADIUS_H_PLUS);
            bindAction(events, SELECTOR_VERTICAL_RADIUS_MINUS, ACTION_RADIUS_V_MINUS);
            bindAction(events, SELECTOR_VERTICAL_RADIUS_PLUS, ACTION_RADIUS_V_PLUS);
            bindAction(events, SELECTOR_SHOW_RANGE_BUTTON, ACTION_SHOW_RANGE);
            AutoStorageConfig config = plugin.getAutoStorageConfig();
            SortingMode mode = plugin.getSortingModeFor(worldName, targetBlock);
            int horizontalRadius = plugin.getHorizontalRadiusFor(worldName, targetBlock);
            int verticalRadius = plugin.getVerticalRadiusFor(worldName, targetBlock);

            boolean showConfig = currentTab == Tab.CONFIG;

            if (!showConfig) {
                ensureChestsCache();
            }
            ensureLinkedCount();

            int linkedChests = cachedLinkedChests;

            int hardLimit = config.getRadiusHardLimit();
            int displayHorizontalMax = Math.min(config.getHorizontalRadiusMax(), hardLimit);
            int displayVerticalMax = Math.min(config.getVerticalRadiusMax(), hardLimit);

            Message activeLabel = Message.translation(KEY_UI_STATE_ACTIVE);

            setText(commands, SELECTOR_TITLE, resolveItemDisplayName(ITEM_AUTOSTORAGE));

            setText(commands, SELECTOR_SUBTITLE, Message.translation(KEY_UI_SUBTITLE));

            setText(commands, SELECTOR_CURRENT_MODE, Message.translation(KEY_UI_CURRENT_MODE).param("mode", getSortingModeLabelText(mode)));

            setText(commands, SELECTOR_LINKED_CHEST_COUNT, Message.translation(KEY_UI_LINKED_CHESTS).param("count", linkedChests));
            setText(commands, SELECTOR_RANGE_HEADER, Message.translation(KEY_UI_SECTION_RANGE));
            setText(commands, SELECTOR_HORIZONTAL_RADIUS_VALUE, Message.translation(KEY_UI_HORIZONTAL_RANGE).param("current", horizontalRadius).param("max", displayHorizontalMax));
            setText(commands, SELECTOR_VERTICAL_RADIUS_VALUE, Message.translation(KEY_UI_VERTICAL_RANGE).param("current", verticalRadius).param("max", displayVerticalMax));

            if (mode == SortingMode.ITEM_CATEGORY) {
                setText(commands, SELECTOR_MODE_CATEGORY_STATE, activeLabel);
                setText(commands, SELECTOR_MODE_ITEM_STATE, "");
            } else {
                setText(commands, SELECTOR_MODE_ITEM_STATE, activeLabel);
                setText(commands, SELECTOR_MODE_CATEGORY_STATE, "");
            }

            setText(commands, SELECTOR_MODE_ITEM_LABEL, Message.translation(KEY_UI_MODE_ITEM_LABEL));
            setText(commands, SELECTOR_MODE_ITEM_DESC, Message.translation(KEY_UI_MODE_ITEM_DESC));
            setText(commands, SELECTOR_MODE_CATEGORY_LABEL, Message.translation(KEY_UI_MODE_CATEGORY_LABEL));
            setText(commands, SELECTOR_MODE_CATEGORY_DESC, Message.translation(KEY_UI_MODE_CATEGORY_DESC));
            setText(commands, SELECTOR_MODE_CATEGORY_WARNING, Message.translation(KEY_UI_MODE_CATEGORY_WARNING));

            setText(commands, SELECTOR_SORTING_MODE_HEADER, Message.translation(KEY_UI_SECTION_SORTING));
            setText(commands, SELECTOR_OPTIONS_HEADER, Message.translation(KEY_UI_SECTION_OPTIONS));

            setText(commands, SELECTOR_FALLBACK_LABEL, Message.translation(KEY_UI_FALLBACK_LABEL));
            setText(commands, SELECTOR_FALLBACK_DESC, Message.translation(KEY_UI_FALLBACK_DESC));

            boolean fallbackEnabled = plugin.isTransferAnywhereIfMissing(worldName, targetBlock);

            commands.set("#FallbackTransferActiveBadge.Visible", fallbackEnabled);
            commands.set("#FallbackTransferInactiveBadge.Visible", !fallbackEnabled);

            setText(commands, "#FallbackTransferActiveBadgeLabel", Message.translation(KEY_UI_STATE_ACTIVE));
            setText(commands, "#FallbackTransferInactiveBadgeLabel", Message.translation(KEY_UI_STATE_INACTIVE));

            setText(commands, "#CloseButtonLabel", "X");
            setText(commands, "#ShowRangeButtonLabel", Message.translation("server.autostorage.ui.show_range"));

            commands.set(SELECTOR_CONFIG_CONTENT + ".Visible", showConfig);
            commands.set(SELECTOR_CHESTS_CONTENT + ".Visible", !showConfig);
            commands.set(SELECTOR_TAB_CONFIG_ACTIVE + ".Visible", showConfig);
            commands.set(SELECTOR_TAB_CHESTS_ACTIVE + ".Visible", !showConfig);

            if (!showConfig) {
                int pageSize = CHEST_ROW_COUNT;
                int offset = chestPage * pageSize;
                int totalChests = cachedAllChests.size();

                int pageCount = totalChests == 0 ? 1 : (totalChests + pageSize - 1) / pageSize;

                if (totalChests > 0 && offset >= totalChests) {
                    chestPage = Math.max(0, pageCount - 1);
                    offset = chestPage * pageSize;
                }

                int from = Math.min(offset, totalChests);
                int to = Math.min(totalChests, from + pageSize);

                cachedChests = from >= to ? List.of() : cachedAllChests.subList(from, to);

                int currentPage = totalChests == 0 ? 0 : chestPage + 1;

                setText(commands, "#ChestsTitle", Message.translation(KEY_UI_CHESTS_TITLE));

                setText(commands, "#ChestsEmpty", Message.translation(KEY_UI_CHESTS_EMPTY));

                commands.set("#ChestsEmpty.Visible", totalChests == 0);

                setText(commands, "#ChestsPageLabel", Message.translation(KEY_UI_CHESTS_PAGE).param("current", currentPage).param("total", pageCount));
                commands.set("#ChestsPageLabel.Visible", totalChests > 0);

                setText(commands, "#ChestsPrevLabel", Message.translation(KEY_UI_CHESTS_PREV));

                setText(commands, "#ChestsNextLabel", Message.translation(KEY_UI_CHESTS_NEXT));
                commands.set("#ChestsPrevButton.Visible", totalChests > 0 && chestPage > 0);
                commands.set("#ChestsNextButton.Visible", totalChests > 0 && chestPage + 1 < pageCount);

                commands.set(SELECTOR_CHEST_ROWS + ".Visible", totalChests > 0);
                commands.clear(SELECTOR_CHEST_ROWS);

                for (int i = 0; i < cachedChests.size(); i++) {
                    AutoStorage.NearbyChestView view = cachedChests.get(i);

                    commands.append(SELECTOR_CHEST_ROWS, TEMPLATE_CHEST_ROW);

                    String rowWrapper = SELECTOR_CHEST_ROWS + "[" + i + "]";

                    String rowBase = rowWrapper + " " + SELECTOR_CHEST_ROW;

                    commands.set(rowWrapper + " " + SELECTOR_CHEST_ROW_SPACER + ".Visible", i + 1 < cachedChests.size());

                    setText(commands, rowBase + " " + SELECTOR_CHEST_TITLE, resolveChestDisplayName(view));

                    String coords = "X= " + view.x + "; Y= " + view.y + "; Z= " + view.z;

                    commands.set(rowBase + " " + SELECTOR_CHEST_ICON + ".ItemId", view.chestItemId != null ? view.chestItemId : "");

                    int remaining = Math.max(0, view.itemTypesTotal - ITEM_ICON_DISPLAY_LIMIT);

                    if (remaining > 0) {
                        coords += " - " + getTranslationText(KEY_UI_CHESTS_MORE).replace("{count}", String.valueOf(remaining));
                    }

                    setText(commands, rowBase + " " + SELECTOR_CHEST_COORDS, coords);

                    boolean ignored = plugin.isIgnoredTarget(worldName, targetBlock, view.key);

                    setText(commands, rowBase + " " + SELECTOR_IGNORE_LABEL, ignored ? getTranslationText(KEY_UI_IGNORED) : getTranslationText(KEY_UI_IGNORE));

                    commands.set(rowBase + " #IgnoredBadge.Visible", !ignored);
                    commands.set(rowBase + " #ActiveBadge.Visible", ignored);

                    setText(commands, rowBase + " #IgnoredBadgeLabel", Message.translation(KEY_UI_BADGE_NOT_IGNORED));

                    setText(commands, rowBase + " #ActiveBadgeLabel", Message.translation(KEY_UI_BADGE_IGNORED));

                    bindAction(events, rowBase + " " + SELECTOR_IGNORE_BUTTON, ACTION_IGNORE_PREFIX + i);

                    List<AutoStorage.ItemPreview> previews = view.items;

                    int itemCount = previews == null ? 0 : previews.size();

                    int displayCount = Math.min(itemCount, ITEM_ICON_DISPLAY_LIMIT);

                    int rowsNeeded = displayCount == 0 ? 0 : Math.min((displayCount + ITEM_ICON_COLUMNS - 1) / ITEM_ICON_COLUMNS, ITEM_ICON_ROWS);

                    for (int row = 0; row < rowsNeeded; row++) {

                        commands.append(rowBase + " " + SELECTOR_ITEMS_ROWS, TEMPLATE_ITEM_ROW);

                        int rowStart = row * ITEM_ICON_COLUMNS;

                        int rowCount = Math.min(ITEM_ICON_COLUMNS, displayCount - rowStart);

                        for (int col = 0; col < rowCount; col++) {

                            int slotIndex = rowStart + col;

                            AutoStorage.ItemPreview preview = previews.get(slotIndex);

                            commands.append(rowBase + " " + SELECTOR_ITEMS_ROWS + "[" + row + "]", TEMPLATE_ITEM_SLOT);

                            String itemSelector = rowBase + " " + SELECTOR_ITEMS_ROWS + "[" + row + "][" + col + "]";

                            commands.set(itemSelector + " " + SELECTOR_ITEM_ICON + ".ItemId", preview.itemId != null ? preview.itemId : "");

                            boolean showQty = preview.quantity > 1;

                            commands.set(itemSelector + " " + SELECTOR_ITEM_QTY + ".Visible", showQty);

                            setText(commands, itemSelector + " " + SELECTOR_ITEM_QTY, showQty ? "x" + Math.min(preview.quantity, 9999) : "");

                            commands.set(itemSelector + " " + SELECTOR_ITEM_SLOT + ".TooltipText", resolveItemDisplayName(preview.itemId));
                        }
                    }
                }
            }
        } catch (Throwable throwable) {
            close();
        }
    }

    @Override
    public void handleDataEvent(@NonNullDecl Ref<EntityStore> ref, @NonNullDecl Store<EntityStore> store, @NonNullDecl PageData data) {
        try {
            if (data.getAction() == null) {
                return;
            }

            UUID actorUuid = playerRef.getUuid();

            if (!plugin.canInteractWithClaims(actorUuid, worldName, targetBlock)) {
                HytaleCompat.sendMessage(playerRef, Message.translation(KEY_CHAT_CLAIM_PROTECTED));
                close();
                return;
            }

            if (!plugin.canConfigure(playerRef, actorUuid, worldName, targetBlock)) {
                HytaleCompat.sendMessage(playerRef, Message.translation(KEY_CHAT_CONFIG_OWNER_ONLY));
                close();
                return;
            }

            String action = data.getAction();

            if (action.startsWith(ACTION_IGNORE_PREFIX)) {
                handleIgnoreAction(action);
                rebuild();
                return;
            }

            switch (action) {
                case ACTION_CLOSE:
                    close();
                    return;

                case ACTION_TAB_CONFIG:
                    currentTab = Tab.CONFIG;
                    rebuild();
                    return;

                case ACTION_TAB_CHESTS:
                    currentTab = Tab.CHESTS;
                    chestPage = 0;
                    rebuild();
                    return;

                case ACTION_PAGE_PREV:
                    if (chestPage > 0) {
                        chestPage--;
                    }
                    rebuild();
                    return;

                case ACTION_PAGE_NEXT:
                    chestPage++;
                    rebuild();
                    return;

                case ACTION_MODE_ITEM:
                    updateSortingMode(SortingMode.ITEM_ID);
                    break;

                case ACTION_MODE_CATEGORY:
                    updateSortingMode(SortingMode.ITEM_CATEGORY);
                    break;

                case ACTION_FALLBACK_TOGGLE:
                    plugin.toggleTransferAnywhereIfMissing(worldName, targetBlock);
                    rebuild();
                    return;

                case ACTION_RADIUS_H_MINUS:
                    updateRadius(-1, 0);
                    break;

                case ACTION_RADIUS_H_PLUS:
                    updateRadius(1, 0);
                    break;

                case ACTION_RADIUS_V_MINUS:
                    updateRadius(0, -1);
                    break;

                case ACTION_RADIUS_V_PLUS:
                    updateRadius(0, 1);
                    break;

                case ACTION_SHOW_RANGE:
                    showRange();
                    return;

                default:
                    return;
            }

            rebuild();
        } catch (Throwable throwable) {
            close();
        }
    }

    private void handleIgnoreAction(String action) {
        String suffix = action.substring(ACTION_IGNORE_PREFIX.length());

        int index;
        try {
            index = Integer.parseInt(suffix);
        } catch (NumberFormatException e) {
            return;
        }

        if (index < 0 || index >= cachedChests.size()) {
            return;
        }

        AutoStorage.NearbyChestView view = cachedChests.get(index);

        boolean wasIgnored = plugin.isIgnoredTarget(worldName, targetBlock, view.key);

        if (plugin.toggleIgnoredTarget(worldName, targetBlock, view.key)) {
            if (!linkedDirty) {
                int delta = wasIgnored ? 1 : -1;

                cachedLinkedChests = Math.max(0, cachedLinkedChests + delta);
            }
        }
    }

    private void updateSortingMode(SortingMode mode) {
        boolean updated = plugin.setSortingModeFor(worldName, targetBlock, mode);

        if (updated) {
            HytaleCompat.sendMessage(playerRef, Message.translation(KEY_CHAT_SORTING_MODE).param("mode", getSortingModeLabelMessage(mode)));
        } else {
            HytaleCompat.sendMessage(playerRef, Message.translation(KEY_CHAT_CHEST_NOT_FOUND));
        }
    }

    private void updateRadius(int horizontalDelta, int verticalDelta) {
        boolean updated = plugin.adjustRadiusFor(worldName, targetBlock, horizontalDelta, verticalDelta);

        if (!updated) {
            HytaleCompat.sendMessage(playerRef, Message.translation(KEY_CHAT_CHEST_NOT_FOUND));
        } else {
            markChestsDirty();
            markLinkedDirty();
        }
    }

    private void showRange() {
        close();
        plugin.showRangeVisualization(playerRef, worldName, targetBlock);

        HytaleCompat.sendMessage(playerRef, Message.translation(KEY_CHAT_RANGE_DISPLAYED));
    }

    private void ensureChestsCache() {
        if (!chestsDirty) {
            return;
        }

        AutoStorage.NearbyChestsSnapshot snapshot = plugin.getNearbyChests(worldName, targetBlock, 0, 0);

        cachedAllChests = snapshot.chests != null ? snapshot.chests : List.of();

        cachedLinkedChests = snapshot.linkedTotal;

        chestsDirty = false;
        linkedDirty = false;
    }

    private void ensureLinkedCount() {
        if (!linkedDirty) {
            return;
        }

        if (!chestsDirty) {
            int linked = 0;

            for (AutoStorage.NearbyChestView view : cachedAllChests) {
                if (view != null && !view.ignored) {
                    linked++;
                }
            }

            cachedLinkedChests = linked;
        } else {
            cachedLinkedChests = plugin.countLinkedChests(worldName, targetBlock);
        }

        linkedDirty = false;
    }

    private void markChestsDirty() {
        chestsDirty = true;
    }

    private void markLinkedDirty() {
        linkedDirty = true;
    }

    private static void bindAction(UIEventBuilder events, String selector, String action) {
        HytaleCompat.bindUiAction(events, selector, action);
    }

    private static void setText(UICommandBuilder commands, String selector, String value) {
        commands.set(selector + ".Text", value);
    }

    private static void setText(UICommandBuilder commands, String selector, Message value) {
        commands.set(selector + ".Text", value);
    }

    private static Message getSortingModeLabelMessage(SortingMode mode) {
        SortingMode resolved = mode != null ? mode : SortingMode.ITEM_ID;

        return Message.translation(resolved.getTranslationKey());
    }

    private String getSortingModeLabelText(SortingMode mode) {
        SortingMode resolved = mode != null ? mode : SortingMode.ITEM_ID;

        return getTranslationText(resolved.getTranslationKey());
    }

    private String getTranslationText(String key) {
        I18nModule i18n = I18nModule.get();

        if (i18n == null) {
            return key;
        }

        String language = playerRef.getLanguage();

        String translated = i18n.getMessage(language, key);

        return translated != null ? translated : key;
    }

    private String resolveItemDisplayName(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return "";
        }

        String translationKey = plugin.resolveItemTranslationKey(itemId);

        if (translationKey != null) {
            String translated = getTranslationText(translationKey);

            if (!translated.equals(translationKey)) {
                return translated;
            }
        }

        return itemId;
    }

    private String resolveChestDisplayName(AutoStorage.NearbyChestView view) {
        if (view == null) {
            return "Chest";
        }

        String translationKey = plugin.resolveItemTranslationKey(view.chestItemId);

        if (translationKey != null) {
            String translated = getTranslationText(translationKey);

            if (!translated.equals(translationKey)) {
                return translated;
            }
        }

        return view.blockId != null && !view.blockId.isBlank() ? view.blockId : "Chest";
    }

    private enum Tab {
        CONFIG, CHESTS
    }

    @Setter
    @Getter
    public static final class PageData {
        private String action;

        public static final BuilderCodec<PageData> CODEC = BuilderCodec.builder(PageData.class, PageData::new).append(new KeyedCodec<>("Action", Codec.STRING), PageData::setAction, PageData::getAction).add().build();

    }
}