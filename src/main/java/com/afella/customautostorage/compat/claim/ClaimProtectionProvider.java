package com.afella.customautostorage.compat.claim;

import java.util.UUID;

public interface ClaimProtectionProvider {
    String getName();

    boolean isReady();

    ClaimDecision canInteract(UUID playerUuid, String worldName, int blockX, int blockZ, ClaimAccessType accessType);

    enum ClaimAccessType {
        BLOCK_INTERACT,
        CHEST_INTERACT;

        ClaimAccessType() {
        }
    }

    enum ClaimDecision {
        PASS,
        ALLOW,
        DENY;

        ClaimDecision() {
        }
    }
}
