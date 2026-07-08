package com.afella.customautostorage.storage.backend.json;

import com.afella.customautostorage.storage.TrackedChestStorageFiles;
import com.afella.customautostorage.storage.backend.TrackedChestStorageBackend;
import com.afella.customautostorage.storage.model.TrackedChestReadResult;
import com.afella.customautostorage.storage.model.TrackedChestRecord;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.hypixel.hytale.logger.HytaleLogger;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.List;

public final class JsonTrackedChestStorageBackend implements TrackedChestStorageBackend {

    private static final HytaleLogger LOGGER =
            HytaleLogger.forEnclosingClass();
    private static final Gson GSON =
            new GsonBuilder()
                    .setPrettyPrinting()
                    .create();
    private static final Type RECORD_LIST_TYPE =
            new TypeToken<List<TrackedChestRecord>>() {}.getType();

    private static final String FILE_NAME =
            TrackedChestStorageFiles.TRACKED_CHESTS_JSON;

    @Override
    public String name() {
        return "json";
    }

    @Override
    public TrackedChestReadResult read(@NonNullDecl Path dataDir) {
        Path file = dataDir.resolve(FILE_NAME);
        if (!Files.exists(file)) {
            return TrackedChestReadResult.success(List.of());
        }

        try (Reader reader = Files.newBufferedReader(file)) {
            List<TrackedChestRecord> records =
                    GSON.fromJson(reader, RECORD_LIST_TYPE);

            return TrackedChestReadResult.success(
                    records != null ? records : List.of()
            );

        } catch (IOException | RuntimeException exception) {
            LOGGER.atSevere()
                    .withCause(exception)
                    .log("[AutoStorage] Failed to read tracked_autostorage.json");

            return TrackedChestReadResult.failure();
        }
    }

    @Override
    public boolean write(
            @NonNullDecl Path dataDir,
            Collection<TrackedChestRecord> records
    ) {
        Path file =
                dataDir.resolve(FILE_NAME);
        Path temp =
                file.resolveSibling(FILE_NAME + ".tmp");

        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            try (Writer writer = Files.newBufferedWriter(temp)) {
                GSON.toJson(records, writer);
            }

            try {
                Files.move(
                        temp,
                        file,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                );

            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(
                        temp,
                        file,
                        StandardCopyOption.REPLACE_EXISTING
                );
            }

            return true;

        } catch (IOException | RuntimeException exception) {
            LOGGER.atSevere()
                    .withCause(exception)
                    .log("[AutoStorage] Failed to save tracked_autostorage.json");

            try {
                Files.deleteIfExists(temp);
            } catch (IOException cleanupException) {
                LOGGER.atWarning()
                        .withCause(cleanupException)
                        .log("[AutoStorage] Failed to cleanup temporary storage file.");
            }

            return false;
        }
    }
}