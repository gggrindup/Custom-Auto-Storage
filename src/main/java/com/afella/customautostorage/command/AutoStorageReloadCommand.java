package com.afella.customautostorage.command;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import com.afella.customautostorage.AutoStorage;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;

public final class AutoStorageReloadCommand extends CommandBase {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private final AutoStorage plugin;

    public AutoStorageReloadCommand(AutoStorage plugin) {
        super("reload", "Reload AutoStorage config");
        this.plugin = plugin;
    }

    protected void executeSync(@NonNullDecl CommandContext context) {
        try {
            this.plugin.reloadConfig();
            context.sendMessage(Message.raw("[AutoStorage] config reloaded"));
        } catch (Throwable throwable) {
            LOGGER.atWarning().withCause(throwable).log("[AutoStorage] Reload command failed.");
            context.sendMessage(Message.raw("[AutoStorage] reload failed. Check server logs."));
        }

    }
}
