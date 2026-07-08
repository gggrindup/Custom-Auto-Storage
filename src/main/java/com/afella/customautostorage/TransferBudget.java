package com.afella.customautostorage;

import com.afella.customautostorage.AutoStorageConfig.TransferMode;

final class TransferBudget {
    int remaining;
    int moved;
    final AutoStorageConfig.TransferMode mode;

    TransferBudget(int remaining, AutoStorageConfig.TransferMode mode) {
        this.remaining = remaining;
        this.mode = mode == null ? TransferMode.ITEMS : mode;
    }

    boolean isExhausted() {
        return this.remaining <= 0;
    }

    void consume(int amount) {
        this.remaining -= amount;
        this.moved += amount;
    }

    void consumeStack() {
        --this.remaining;
        ++this.moved;
    }
}
