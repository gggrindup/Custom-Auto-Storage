package com.afella.customautostorage.compat.claim;

import com.afella.customautostorage.compat.claim.ClaimProtectionProvider.ClaimDecision;
import com.hypixel.hytale.logger.HytaleLogger;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

public final class ClaimProtectionService {
    private static final HytaleLogger LOGGER =
            HytaleLogger.forEnclosingClass();
    private final List<ClaimProtectionProvider> providers =
            new CopyOnWriteArrayList<>();

    public ClaimProtectionService() {
        register(new SimpleClaimsProtectionProvider());
    }

    public void register(ClaimProtectionProvider provider) {
        if (provider == null) {
            return;
        }
        providers.add(provider);
    }

    public boolean isInteractionAllowed(
            UUID playerUuid,
            String worldName,
            int blockX,
            int blockZ,
            ClaimProtectionProvider.ClaimAccessType accessType
    ) {
        if (worldName == null || worldName.isBlank()) {
            return true;
        }

        for (ClaimProtectionProvider provider : providers) {
            if (!provider.isReady()) {
                continue;
            }

            final ClaimDecision decision;
            try {
                decision = provider.canInteract(
                        playerUuid,
                        worldName,
                        blockX,
                        blockZ,
                        accessType
                );
            } catch (RuntimeException e) {
                LOGGER.atWarning()
                        .withCause(e)
                        .log(
                                "[AutoStorage] Claim provider '"
                                        + provider.getName()
                                        + "' threw an exception. "
                                        + "Denying interaction for safety."
                        );
                return false;
            }

            if (decision == ClaimDecision.DENY) {
                return false;
            }
        }

        return true;
    }
}