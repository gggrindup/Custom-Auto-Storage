package com.afella.customautostorage.compat.claim;

import com.hypixel.hytale.logger.HytaleLogger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
final class SimpleClaimsProtectionProvider implements ClaimProtectionProvider {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String PROVIDER_NAME = "SimpleClaims";
    private static final String CLASS_MAIN =
            "com.buuz135.simpleclaims.Main";
    private static final String CLASS_CLAIM_MANAGER =
            "com.buuz135.simpleclaims.claim.ClaimManager";
    private static final String CLASS_PARTY_INFO =
            "com.buuz135.simpleclaims.claim.party.PartyInfo";
    private static final String BLOCK_INTERACT_PERMISSION =
            "simpleclaims.party.protection.interact";
    private static final String CHEST_INTERACT_PERMISSION =
            "simpleclaims.party.protection.interact.chest";

    private volatile boolean ready;
    private volatile boolean permanentlyUnavailable;
    private final AtomicBoolean initFailureLogged = new AtomicBoolean();
    private final AtomicBoolean runtimeFailureLogged = new AtomicBoolean();

    private volatile Method claimManagerGetInstance;
    private volatile Method claimManagerIsAllowedToInteract;
    private volatile Method partyInfoIsBlockInteractEnabled;
    private volatile Method partyInfoIsChestInteractEnabled;

    private final Predicate<Object> blockInteractPredicate =
            party -> invokeBoolean(partyInfoIsBlockInteractEnabled, party);
    private final Predicate<Object> chestInteractPredicate =
            party -> invokeBoolean(partyInfoIsChestInteractEnabled, party);

    SimpleClaimsProtectionProvider() {
    }

    private boolean invokeBoolean(Method method, Object target) {
        if (method == null || target == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(method.invoke(target));
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return false;
        }
    }

    @Override
    public String getName() {
        return PROVIDER_NAME;
    }

    @Override
    public boolean isReady() {
        if (ready) {
            return true;
        }
        if (permanentlyUnavailable) {
            return false;
        }

        synchronized (this) {
            if (ready) {
                return true;
            }
            if (permanentlyUnavailable) {
                return false;
            }

            if (!isSimpleClaimsPresent()) {
                permanentlyUnavailable = true;
                return false;
            }

            if (!isSimpleClaimsInitialized()) {
                return false;
            }

            try {
                Class<?> claimManagerClass =
                        Class.forName(CLASS_CLAIM_MANAGER);
                Class<?> partyInfoClass =
                        Class.forName(CLASS_PARTY_INFO);

                claimManagerGetInstance =
                        claimManagerClass.getMethod("getInstance");

                claimManagerIsAllowedToInteract =
                        claimManagerClass.getMethod(
                                "isAllowedToInteract",
                                UUID.class,
                                String.class,
                                int.class,
                                int.class,
                                Predicate.class,
                                String.class
                        );

                partyInfoIsBlockInteractEnabled =
                        partyInfoClass.getMethod(
                                "isBlockInteractEnabled"
                        );

                partyInfoIsChestInteractEnabled =
                        partyInfoClass.getMethod(
                                "isChestInteractEnabled"
                        );

                ready = true;
                return true;

            } catch (ReflectiveOperationException | RuntimeException exception) {
                ready = false;
                permanentlyUnavailable = true;
                logInitFailureOnce(exception);
                return false;
            }
        }
    }

    @Override
    public ClaimDecision canInteract(
            UUID playerUuid,
            String worldName,
            int blockX,
            int blockZ,
            ClaimAccessType accessType
    ) {
        if (!isReady()) {
            return ClaimDecision.PASS;
        }

        try {
            Object manager =
                    claimManagerGetInstance.invoke(null);

            if (manager == null) {
                return ClaimDecision.PASS;
            }

            Predicate<Object> predicate =
                    predicateFor(accessType);
            String permission =
                    permissionFor(accessType);

            if (predicate == null ||
                    permission == null ||
                    permission.isBlank()) {
                return ClaimDecision.PASS;
            }

            Object result =
                    claimManagerIsAllowedToInteract.invoke(
                            manager,
                            playerUuid,
                            worldName,
                            blockX,
                            blockZ,
                            predicate,
                            permission
                    );

            return Boolean.TRUE.equals(result)
                    ? ClaimDecision.ALLOW
                    : ClaimDecision.DENY;

        } catch (ReflectiveOperationException | RuntimeException exception) {
            logRuntimeFailureOnce(exception);
            return ClaimDecision.DENY;
        }
    }

    private Predicate<Object> predicateFor(ClaimAccessType accessType) {
        if (accessType == ClaimAccessType.BLOCK_INTERACT) {
            return blockInteractPredicate;
        }
        if (accessType == ClaimAccessType.CHEST_INTERACT) {
            return chestInteractPredicate;
        }
        return null;
    }

    private String permissionFor(ClaimAccessType accessType) {
        if (accessType == ClaimAccessType.BLOCK_INTERACT) {
            return BLOCK_INTERACT_PERMISSION;
        }
        if (accessType == ClaimAccessType.CHEST_INTERACT) {
            return CHEST_INTERACT_PERMISSION;
        }
        return null;
    }

    private boolean isSimpleClaimsPresent() {
        try {
            Class.forName(CLASS_MAIN);
            return true;
        } catch (ClassNotFoundException exception) {
            return false;
        }
    }

    private boolean isSimpleClaimsInitialized() {
        try {
            Class<?> mainClass =
                    Class.forName(CLASS_MAIN);

            Field configField =
                    mainClass.getField("CONFIG");

            return configField.get(null) != null;

        } catch (ReflectiveOperationException | RuntimeException exception) {
            return false;
        }
    }

    private void logInitFailureOnce(Throwable throwable) {
        if (!initFailureLogged.compareAndSet(false, true)) {
            return;
        }

        LOGGER.atWarning()
                .withCause(throwable)
                .log(
                        "[AutoStorage] Claim provider 'SimpleClaims' disabled (incompatible API)."
                );
    }

    private void logRuntimeFailureOnce(Throwable throwable) {
        if (!runtimeFailureLogged.compareAndSet(false, true)) {
            return;
        }

        LOGGER.atWarning()
                .withCause(throwable)
                .log(
                        "[AutoStorage] Claim provider 'SimpleClaims' failed while checking access. " +
                                "Denying interaction for safety."
                );
    }
}