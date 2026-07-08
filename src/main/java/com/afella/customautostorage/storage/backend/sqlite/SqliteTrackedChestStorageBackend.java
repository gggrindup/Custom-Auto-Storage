package com.afella.customautostorage.storage.backend.sqlite;

import com.afella.customautostorage.storage.backend.TrackedChestStorageBackend;
import com.afella.customautostorage.storage.backend.json.JsonTrackedChestStorageBackend;
import com.afella.customautostorage.storage.model.TrackedChestReadResult;
import com.afella.customautostorage.storage.model.TrackedChestReadResult.FailureReason;
import com.afella.customautostorage.storage.model.TrackedChestRecord;
import com.hypixel.hytale.logger.HytaleLogger;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collection;


public final class SqliteTrackedChestStorageBackend
        implements TrackedChestStorageBackend {


    private static final HytaleLogger LOGGER =
            HytaleLogger.forEnclosingClass();


    private final SqliteTrackedChestDao dao =
            new SqliteTrackedChestDao();


    private final SqliteTrackedChestMigrationService migrationService;



    public SqliteTrackedChestStorageBackend() {

        this.migrationService =
                new SqliteTrackedChestMigrationService(
                        dao,
                        new JsonTrackedChestStorageBackend()
                );
    }



    @Override
    public String name() {
        return "sqlite";
    }



    @Override
    public TrackedChestReadResult read(
            Path dataDir
    ) {

        try (
                Connection connection =
                        dao.open(dataDir)
        ) {


            dao.initialize(connection);


            migrationService.migrateIfNeeded(
                    connection,
                    dataDir
            );


            return TrackedChestReadResult.success(
                    dao.readAll(connection)
            );


        } catch (
                IOException |
                SQLException |
                RuntimeException exception
        ) {


            LOGGER.atSevere()
                    .withCause(exception)
                    .log(
                            "[AutoStorage] Failed to read SQLite tracked chests."
                    );


            return TrackedChestReadResult.failure(
                    classifyReadFailure(exception)
            );
        }
    }





    @Override
    public boolean write(
            Path dataDir,
            Collection<TrackedChestRecord> records
    ) {


        try (
                Connection connection =
                        dao.open(dataDir)
        ) {


            dao.initialize(connection);


            migrationService.migrateSchemaIfNeeded(
                    connection
            );


            boolean previousAutoCommit =
                    connection.getAutoCommit();


            connection.setAutoCommit(false);



            try {


                dao.replaceAll(
                        connection,
                        records
                );


                connection.commit();

                return true;



            } catch (SQLException exception) {


                rollbackQuietly(connection);

                throw exception;



            } finally {


                connection.setAutoCommit(
                        previousAutoCommit
                );
            }



        } catch (
                IOException |
                SQLException |
                RuntimeException exception
        ) {


            LOGGER.atSevere()
                    .withCause(exception)
                    .log(
                            "[AutoStorage] Failed to write SQLite tracked chests."
                    );


            return false;
        }
    }





    private void rollbackQuietly(
            Connection connection
    ) {

        if (connection == null) {
            return;
        }


        try {

            connection.rollback();

        } catch (SQLException ignored) {

        }
    }





    private FailureReason classifyReadFailure(
            Throwable throwable
    ) {


        return isSchemaFailure(throwable)
                ? FailureReason.INCOMPATIBLE_SCHEMA
                : FailureReason.STORAGE_UNAVAILABLE;
    }





    private boolean isSchemaFailure(
            Throwable throwable
    ) {


        Throwable current = throwable;


        while (current != null) {


            if (current instanceof SqliteTrackedChestMigrationService.IncompatibleSchemaException) {
                return true;
            }


            String message =
                    current.getMessage();



            if (message != null &&
                    (
                            message.startsWith(
                                    "SQLite tracked chest schema version"
                            )
                                    ||
                                    message.startsWith(
                                            "Invalid SQLite tracked chest schema version:"
                                    )
                                    ||
                                    message.startsWith(
                                            "Invalid negative SQLite tracked chest schema version:"
                                    )
                    )
            ) {

                return true;
            }


            current = current.getCause();
        }


        return false;
    }
}