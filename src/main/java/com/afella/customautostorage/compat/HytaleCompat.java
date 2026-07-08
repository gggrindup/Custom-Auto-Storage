package com.afella.customautostorage.compat;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.event.EventPriority;
import com.hypixel.hytale.event.EventRegistration;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.DebugShape;
import com.hypixel.hytale.protocol.MovementStates;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.protocol.packets.player.ClearDebugShapes;
import com.hypixel.hytale.protocol.packets.player.DisplayDebug;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.io.PacketHandler;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.checkerframework.checker.nullness.compatqual.NonNullDecl;
import org.checkerframework.checker.nullness.compatqual.NullableDecl;
import org.joml.Vector3f;
import org.joml.Vector3fc;

public final class HytaleCompat {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final float DEFAULT_DEBUG_TIME_SECONDS = 1.0F;
    private static final float DEFAULT_DEBUG_OPACITY = 1.0F;
    private static volatile Constructor<?> cachedDisplayDebugCtor;
    private static final AtomicBoolean DISPLAY_DEBUG_LOGGED = new AtomicBoolean(false);

    private HytaleCompat() {
    }

    public static boolean isCrouching(MovementStatesComponent movement) {
        if (movement == null) {
            return false;
        }
        MovementStates states = movement.getMovementStates();
        return states != null && states.crouching;
    }

    public static boolean hasPermission(PlayerRef playerRef, String permission) {
        return hasPermission(playerRef, permission, false);
    }

    public static boolean hasPermission(PlayerRef playerRef, String permission, boolean defaultValue) {
        return playerRef != null && permission != null && !permission.isBlank() && playerRef.hasPermission(permission, defaultValue);
    }

    public static void sendMessage(PlayerRef playerRef, Message message) {
        if (playerRef != null && message != null) {
            playerRef.sendMessage(message);
        }
    }

    public static CustomPageLifetime pageLifetimeCanDismiss() {
        return CustomPageLifetime.CanDismiss;
    }

    public static void bindUiAction(UIEventBuilder events, String selector, String action) {
        if (events == null || selector == null || action == null) {
            return;
        }
        events.addEventBinding(
                CustomUIEventBindingType.Activating,
                selector,
                EventData.of("Action", action)
        );
    }

    public static boolean openCustomPage(Player player, Ref<EntityStore> ref, Store<EntityStore> store, InteractiveCustomUIPage<?> page) {
        if (player != null && ref != null && store != null && page != null) {
            try {
                player.getPageManager().openCustomPage(ref, store, page);
                return true;
            } catch (RuntimeException exception) {
                LOGGER.atWarning()
                        .withCause(exception)
                        .log("[AutoStorage] Failed to open custom page.");
                return false;
            }
        } else {
            return false;
        }
    }

    public static boolean canSendDebug(PlayerRef playerRef) {
        return playerRef != null;
    }

    public static void clearDebugShapes(PlayerRef playerRef) {
        PacketHandler packetHandler = playerRef != null ? playerRef.getPacketHandler() : null;
        if (packetHandler != null) {
            packetHandler.writeNoCache(new ClearDebugShapes());
        }
    }

    public static boolean sendDebugCube(PlayerRef playerRef, float centerX, float centerY, float centerZ, float sizeX, float sizeY, float sizeZ, Vector3fc color) {
        PacketHandler packetHandler = playerRef != null ? playerRef.getPacketHandler() : null;
        if (packetHandler == null) {
            return false;
        } else {
            Vector3fc effectiveColor = color != null ? color : new Vector3f(0.0F, 1.0F, 0.6F);

            try {
                float[] matrix = new float[16];
                matrix[0] = sizeX;
                matrix[5] = sizeY;
                matrix[10] = sizeZ;
                matrix[15] = 1.0F;
                matrix[12] = centerX;
                matrix[13] = centerY;
                matrix[14] = centerZ;
                DisplayDebug packet = tryCreateDisplayDebugPacket(matrix, effectiveColor);
                if (packet == null) {
                    return false;
                } else {
                    packetHandler.writeNoCache(packet);
                    return true;
                }
            } catch (RuntimeException exception) {
                logDisplayDebugUnsupportedOnce(exception);
                return false;
            }
        }
    }

    public static World getWorldByName(String name) {
        return name == null ? null : Universe.get().getWorld(name);
    }

    public static World getWorldByUuid(UUID worldUuid) {
        return worldUuid == null ? null : Universe.get().getWorld(worldUuid);
    }

    @NonNullDecl
    public static Collection<PlayerRef> getPlayers() {
        return Universe.get().getPlayers();
    }

    public static PlayerRef getPlayer(UUID playerUuid) {
        return playerUuid == null ? null : Universe.get().getPlayer(playerUuid);
    }

    public static EventRegistration<Void, ItemContainer.ItemContainerChangeEvent> registerContainerChangeEvent(
            ItemContainer container,
            EventPriority priority,
            Consumer<ItemContainer.ItemContainerChangeEvent> consumer
    ) {
        if (container == null || consumer == null) {
            return null;
        }
        if (priority == null) {
            return container.registerChangeEvent(consumer);
        }
        return container.registerChangeEvent(priority, consumer);
    }

    @NullableDecl
    private static DisplayDebug tryCreateDisplayDebugPacket(float[] matrix, Vector3fc color) {
        Constructor<?> ctor = cachedDisplayDebugCtor;
        if (ctor != null) {
            DisplayDebug packet = instantiateDisplayDebug(ctor, matrix, color);
            if (packet != null) {
                return packet;
            }
            cachedDisplayDebugCtor = null;
        }
        try {
            Constructor<?>[] constructors = DisplayDebug.class.getConstructors();
            Arrays.sort(
                    constructors,
                    (a, b) -> Integer.compare(
                            b.getParameterCount(),
                            a.getParameterCount()
                    )
            );
            for (Constructor<?> candidate : constructors) {
                DisplayDebug packet = instantiateDisplayDebug(candidate, matrix, color);

                if (packet != null) {
                    cachedDisplayDebugCtor = candidate;
                    return packet;
                }
            }
        } catch (SecurityException e) {
            logDisplayDebugUnsupportedOnce(e);
            return null;
        }
        logDisplayDebugUnsupportedOnce(
                new IllegalStateException("No compatible DisplayDebug constructor found")
        );
        return null;
    }

    @NullableDecl
    private static DisplayDebug instantiateDisplayDebug(
            @NonNullDecl Constructor<?> constructor,
            float[] matrix,
            Vector3fc color
    ) {
        Class<?>[] parameterTypes = constructor.getParameterTypes();
        Object[] args = new Object[parameterTypes.length];
        int floatArrayIndex = 0;
        float[] frustumProjection = createIdentityMatrix();
        Vector3fc effectiveColor = color != null
                ? color
                : new Vector3f(1.0F, 1.0F, 1.0F);

        for (int i = 0; i < parameterTypes.length; i++) {
            Class<?> type = parameterTypes[i];

            if (type == DebugShape.class) {
                args[i] = DebugShape.Cube;
            } else if (type == float[].class) {
                args[i] = floatArrayIndex++ == 0
                        ? matrix
                        : frustumProjection;
            } else if (Vector3fc.class.isAssignableFrom(type)
                    || type == Vector3f.class) {
                args[i] = effectiveColor;
            } else if (type == float.class || type == Float.class) {
                args[i] = 1.0F;
            } else if (type == double.class || type == Double.class) {
                args[i] = 0.0D;
            } else if (type == long.class || type == Long.class) {
                args[i] = 0L;
            } else if (type == int.class || type == Integer.class) {
                args[i] = 0;
            } else if (type == short.class || type == Short.class) {
                args[i] = (short) 0;
            } else if (type == byte.class || type == Byte.class) {
                args[i] = (byte) 0;
            } else if (type == boolean.class || type == Boolean.class) {
                args[i] = true;
            } else if (type == char.class || type == Character.class) {
                args[i] = '\0';
            } else {
                return null;
            }
        }

        try {
            Object packet = constructor.newInstance(args);
            if (!(packet instanceof DisplayDebug displayDebug)) {
                return null;
            }
            normalizeDisplayDebugPacket(
                    displayDebug,
                    matrix,
                    effectiveColor,
                    frustumProjection
            );
            return displayDebug;

        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            logDisplayDebugUnsupportedOnce(
                    cause != null ? cause : e
            );
            return null;

        } catch (ReflectiveOperationException | IllegalArgumentException e) {
            return null;

        } catch (RuntimeException e) {
            logDisplayDebugUnsupportedOnce(e);
            return null;
        }
    }

    private static void normalizeDisplayDebugPacket(@NonNullDecl DisplayDebug packet, float[] matrix, Vector3fc color, float[] frustumProjection) {
        packet.shape = DebugShape.Cube;
        packet.matrix = matrix;
        packet.color = color;
        if (!(packet.time > 0.0F)) {
            packet.time = DEFAULT_DEBUG_TIME_SECONDS;
        }

        if (!(packet.opacity > 0.0F)) {
            packet.opacity = DEFAULT_DEBUG_OPACITY;
        }

        if (packet.frustumProjection == null || packet.frustumProjection.length == 0) {
            packet.frustumProjection = frustumProjection;
        }

    }

    private static float[] createIdentityMatrix() {
        float[] matrix = new float[16];
        matrix[0] = 1.0F;
        matrix[5] = 1.0F;
        matrix[10] = 1.0F;
        matrix[15] = 1.0F;
        return matrix;
    }

    private static void logDisplayDebugUnsupportedOnce(Throwable throwable) {
        if (!DISPLAY_DEBUG_LOGGED.compareAndSet(false, true)) {
            return;
        }

        if (throwable == null) {
            LOGGER.atWarning().log(
                    "[AutoStorage] Debug range visualization disabled: DisplayDebug API is incompatible with this Hytale version."
            );
        } else {
            LOGGER.atWarning()
                    .withCause(throwable)
                    .log(
                            "[AutoStorage] Debug range visualization disabled: DisplayDebug API is incompatible with this Hytale version."
                    );
        }
    }
}
