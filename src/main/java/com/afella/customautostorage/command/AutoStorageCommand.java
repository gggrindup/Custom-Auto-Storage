package com.afella.customautostorage.command;

import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import com.afella.customautostorage.AutoStorage;

public final class AutoStorageCommand extends AbstractCommandCollection {
    public AutoStorageCommand(AutoStorage plugin) {
        super("autostorage", "AutoStorage commands");
        this.addAliases(new String[]{"as"});
        this.addSubCommand(new AutoStorageReloadCommand(plugin));
    }
}
