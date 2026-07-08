package com.afella.customautostorage.storage.backend.sqlite;

import com.afella.customautostorage.storage.TrackedChestStorageFiles;
import com.afella.customautostorage.storage.backend.json.JsonTrackedChestStorageBackend;
import com.afella.customautostorage.storage.model.TrackedChestReadResult;
import com.hypixel.hytale.logger.HytaleLogger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.SQLException;

final class SqliteTrackedChestMigrationService {

    private static final HytaleLogger LOGGER =
            HytaleLogger.forEnclosingClass();

    private static final String META_JSON_MIGRATION_DONE =
            "tracked_chests.json_migration_done";

    private static final String META_JSON_MIGRATION_TIME =
            "tracked_chests.json_migration_completed_at";

    private static final String META_SCHEMA_VERSION =
            "tracked_chests.schema_version";

    private static final int CURRENT_SCHEMA_VERSION = 1;

    private final SqliteTrackedChestDao dao;
    private final JsonTrackedChestStorageBackend jsonBackend;


    SqliteTrackedChestMigrationService(
            SqliteTrackedChestDao dao,
            JsonTrackedChestStorageBackend jsonBackend
    ) {
        this.dao = dao;
        this.jsonBackend = jsonBackend;
    }


    void migrateIfNeeded(
            Connection connection,
            Path dataDir
    ) throws SQLException, IOException {

        migrateSchemaIfNeeded(connection);
        migrateJsonIfNeeded(connection, dataDir);
    }


    void migrateSchemaIfNeeded(
            Connection connection
    ) throws SQLException {

        int currentVersion = readSchemaVersion(connection);

        if (currentVersion > CURRENT_SCHEMA_VERSION) {
            throw new IncompatibleSchemaException(
                    "SQLite tracked chest schema version "
                            + currentVersion
                            + " is newer than supported version "
                            + CURRENT_SCHEMA_VERSION
            );
        }


        if (currentVersion == CURRENT_SCHEMA_VERSION) {
            return;
        }


        boolean autoCommit = connection.getAutoCommit();

        connection.setAutoCommit(false);

        try {
            int version = currentVersion;


            while (version < CURRENT_SCHEMA_VERSION) {

                switch (version) {

                    case 0 -> {
                        migrateSchema0To1(connection);
                        version = 1;
                    }

                    default -> throw new SQLException(
                            "No SQLite tracked chest migration registered for schema version "
                                    + version
                    );
                }


                dao.upsertMeta(
                        connection,
                        META_SCHEMA_VERSION,
                        Integer.toString(version)
                );
            }


            connection.commit();

        } catch (SQLException exception) {

            rollbackQuietly(connection);
            throw exception;

        } finally {

            connection.setAutoCommit(autoCommit);
        }
    }


    private void migrateSchema0To1(
            Connection connection
    ) throws SQLException {

        /*
         * Version 1 is the initial schema.
         *
         * All tables are created by dao.initialize().
         * This migration only registers the schema version.
         */

    }


    private void migrateJsonIfNeeded(
            Connection connection,
            Path dataDir
    ) throws SQLException, IOException {

        String done = dao.readMeta(
                connection,
                META_JSON_MIGRATION_DONE
        );


        if ("true".equalsIgnoreCase(done)) {
            return;
        }


        Path jsonFile = dataDir.resolve(
                TrackedChestStorageFiles.TRACKED_CHESTS_JSON
        );


        if (!Files.exists(jsonFile)) {

            markJsonMigrationDone(connection);
            return;
        }


        if (dao.hasAnyRecords(connection)) {

            markJsonMigrationDone(connection);

            LOGGER.atWarning().log(
                    "[AutoStorage] SQLite already contains tracked chest records. "
                            + "Skipping JSON migration."
            );

            return;
        }



        TrackedChestReadResult result =
                jsonBackend.read(dataDir);


        if (result.failed) {
            throw new SQLException(
                    "JSON source unreadable during SQLite migration."
            );
        }



        boolean autoCommit = connection.getAutoCommit();

        connection.setAutoCommit(false);


        try {

            dao.upsertAll(
                    connection,
                    result.records
            );


            markJsonMigrationDone(connection);


            connection.commit();


        } catch (SQLException exception) {

            rollbackQuietly(connection);
            throw exception;


        } finally {

            connection.setAutoCommit(autoCommit);
        }


        backupMigratedJson(jsonFile);


        LOGGER.atInfo().log(
                "[AutoStorage] Migrated "
                        + result.records.size()
                        + " tracked chest records from JSON to SQLite."
        );
    }



    private int readSchemaVersion(
            Connection connection
    ) throws SQLException {

        String value =
                dao.readMeta(
                        connection,
                        META_SCHEMA_VERSION
                );


        if (value == null || value.isBlank()) {
            return 0;
        }


        try {

            int version =
                    Integer.parseInt(value.trim());


            if (version < 0) {
                throw new IncompatibleSchemaException(
                        "Invalid negative SQLite tracked chest schema version: "
                                + value
                );
            }


            return version;


        } catch (NumberFormatException exception) {

            throw new IncompatibleSchemaException(
                    "Invalid SQLite tracked chest schema version: "
                            + value,
                    exception
            );
        }
    }



    private void markJsonMigrationDone(
            Connection connection
    ) throws SQLException {

        dao.upsertMeta(
                connection,
                META_JSON_MIGRATION_DONE,
                "true"
        );


        dao.upsertMeta(
                connection,
                META_JSON_MIGRATION_TIME,
                Long.toString(
                        System.currentTimeMillis()
                )
        );
    }



    private void backupMigratedJson(
            Path jsonFile
    ) {

        Path backup =
                jsonFile.resolveSibling(
                        jsonFile.getFileName()
                                + ".migrated-"
                                + System.currentTimeMillis()
                                + ".bak"
                );


        try {

            Files.move(
                    jsonFile,
                    backup,
                    StandardCopyOption.REPLACE_EXISTING
            );


        } catch (IOException exception) {

            LOGGER.atWarning()
                    .withCause(exception)
                    .log(
                            "[AutoStorage] Failed to backup migrated JSON tracked chest file."
                    );
        }
    }



    private void rollbackQuietly(
            Connection connection
    ) {

        try {

            connection.rollback();

        } catch (SQLException ignored) {

        }
    }



    static final class IncompatibleSchemaException
            extends SQLException {


        IncompatibleSchemaException(
                String message
        ) {
            super(message);
        }


        IncompatibleSchemaException(
                String message,
                Throwable cause
        ) {
            super(message, cause);
        }
    }
}