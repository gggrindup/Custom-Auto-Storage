package com.afella.customautostorage.storage.backend.sqlite;

import com.afella.customautostorage.storage.TrackedChestStorageFiles;
import com.afella.customautostorage.storage.model.TrackedChestRecord;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;



final class SqliteTrackedChestDao {


    private static final Gson GSON =
            new GsonBuilder()
                    .setPrettyPrinting()
                    .create();


    private static final Type STRING_LIST_TYPE =
            new TypeToken<List<String>>() {}.getType();



    private static final String DATABASE_FILE =
            TrackedChestStorageFiles.TRACKED_CHESTS_SQLITE;



    private static final String CREATE_TRACKED_CHESTS_TABLE = """
            CREATE TABLE IF NOT EXISTS tracked_chests (
                world TEXT NOT NULL,
                x INTEGER NOT NULL,
                y INTEGER NOT NULL,
                z INTEGER NOT NULL,

                sorting_mode TEXT,

                horizontal_radius INTEGER NOT NULL DEFAULT 0,
                vertical_radius INTEGER NOT NULL DEFAULT 0,

                owner_uuid TEXT,
                claim_access_uuid TEXT,

                ignored_targets_json TEXT NOT NULL DEFAULT '[]',

                transfer_anywhere_if_missing INTEGER NOT NULL DEFAULT 0,

                created_at INTEGER NOT NULL DEFAULT (strftime('%s','now')),
                updated_at INTEGER NOT NULL DEFAULT (strftime('%s','now')),

                PRIMARY KEY(world,x,y,z)
            )
            """;



    private static final String CREATE_META_TABLE = """
            CREATE TABLE IF NOT EXISTS storage_meta (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            )
            """;



    private static final String SELECT_ALL = """
            SELECT
                world,
                x,
                y,
                z,
                sorting_mode,
                horizontal_radius,
                vertical_radius,
                owner_uuid,
                claim_access_uuid,
                ignored_targets_json,
                transfer_anywhere_if_missing
            FROM tracked_chests
            """;



    private static final String UPSERT_TRACKED = """
            INSERT INTO tracked_chests (
                world,
                x,
                y,
                z,
                sorting_mode,
                horizontal_radius,
                vertical_radius,
                owner_uuid,
                claim_access_uuid,
                ignored_targets_json,
                transfer_anywhere_if_missing,
                updated_at
            )
            VALUES (
                ?,?,?,?,?,?,?,?,?,?,?,
                strftime('%s','now')
            )

            ON CONFLICT(world,x,y,z)
            DO UPDATE SET

                sorting_mode =
                    excluded.sorting_mode,

                horizontal_radius =
                    excluded.horizontal_radius,

                vertical_radius =
                    excluded.vertical_radius,

                owner_uuid =
                    excluded.owner_uuid,

                claim_access_uuid =
                    excluded.claim_access_uuid,

                ignored_targets_json =
                    excluded.ignored_targets_json,

                transfer_anywhere_if_missing =
                    excluded.transfer_anywhere_if_missing,

                updated_at =
                    excluded.updated_at
            """;



    private static final String UPSERT_META = """
            INSERT INTO storage_meta(
                key,
                value
            )
            VALUES (?,?)

            ON CONFLICT(key)
            DO UPDATE SET
                value = excluded.value
            """;



    private static final String READ_META = """
            SELECT value
            FROM storage_meta
            WHERE key=?
            """;



    private static final String HAS_RECORDS = """
            SELECT 1
            FROM tracked_chests
            LIMIT 1
            """;



    SqliteTrackedChestDao() {
    }





    Connection open(
            Path dataDir
    ) throws IOException, SQLException {


        Files.createDirectories(dataDir);


        loadDriver();


        Path database =
                dataDir.resolve(DATABASE_FILE)
                        .toAbsolutePath();



        return DriverManager.getConnection(
                "jdbc:sqlite:" + database
        );
    }





    private void loadDriver()
            throws SQLException {


        try {

            Class.forName(
                    "org.sqlite.JDBC"
            );


        } catch (ClassNotFoundException exception) {


            throw new SQLException(
                    "SQLite JDBC driver is missing.",
                    exception
            );
        }
    }





    void initialize(
            Connection connection
    ) throws SQLException {


        try (
                Statement statement =
                        connection.createStatement()
        ) {


            statement.execute(
                    "PRAGMA journal_mode=WAL"
            );

            statement.execute(
                    "PRAGMA synchronous=NORMAL"
            );

            statement.execute(
                    "PRAGMA foreign_keys=ON"
            );

            statement.execute(
                    "PRAGMA busy_timeout=5000"
            );


            statement.execute(
                    CREATE_TRACKED_CHESTS_TABLE
            );


            statement.execute(
                    CREATE_META_TABLE
            );
        }
    }





    List<TrackedChestRecord> readAll(
            Connection connection
    ) throws SQLException {


        List<TrackedChestRecord> result =
                new ArrayList<>();



        try (
                PreparedStatement statement =
                        connection.prepareStatement(
                                SELECT_ALL
                        );

                ResultSet rs =
                        statement.executeQuery()
        ) {


            while (rs.next()) {


                result.add(
                        new TrackedChestRecord(
                                rs.getString("world"),
                                rs.getInt("x"),
                                rs.getInt("y"),
                                rs.getInt("z"),
                                rs.getString("sorting_mode"),
                                rs.getInt("horizontal_radius"),
                                rs.getInt("vertical_radius"),
                                rs.getString("owner_uuid"),
                                rs.getString("claim_access_uuid"),
                                parseIgnoredTargets(
                                        rs.getString(
                                                "ignored_targets_json"
                                        )
                                ),
                                rs.getInt(
                                        "transfer_anywhere_if_missing"
                                ) != 0
                        )
                );
            }
        }


        return result;
    }





    void replaceAll(
            Connection connection,
            Collection<TrackedChestRecord> records
    ) throws SQLException {


        clearAll(connection);

        upsertAll(
                connection,
                records
        );
    }





    void upsertAll(
            Connection connection,
            Collection<TrackedChestRecord> records
    ) throws SQLException {


        if (records == null ||
                records.isEmpty()) {

            return;
        }



        try (
                PreparedStatement statement =
                        connection.prepareStatement(
                                UPSERT_TRACKED
                        )
        ) {



            for (TrackedChestRecord record : records) {


                if (record == null ||
                        record.world() == null ||
                        record.world().isBlank()) {

                    continue;
                }


                statement.setString(
                        1,
                        record.world()
                );

                statement.setInt(
                        2,
                        record.x()
                );

                statement.setInt(
                        3,
                        record.y()
                );

                statement.setInt(
                        4,
                        record.z()
                );

                statement.setString(
                        5,
                        record.sortingMode()
                );

                statement.setInt(
                        6,
                        record.horizontalRadius()
                );

                statement.setInt(
                        7,
                        record.verticalRadius()
                );

                statement.setString(
                        8,
                        record.ownerUuid()
                );

                statement.setString(
                        9,
                        record.claimAccessUuid()
                );

                statement.setString(
                        10,
                        GSON.toJson(
                                record.ignoredTargets() == null
                                        ? List.of()
                                        : record.ignoredTargets()
                        )
                );

                statement.setInt(
                        11,
                        record.transferAnywhereIfMissing()
                                ? 1
                                : 0
                );


                statement.addBatch();
            }


            statement.executeBatch();
        }
    }





    void clearAll(
            Connection connection
    ) throws SQLException {


        try (
                Statement statement =
                        connection.createStatement()
        ) {

            statement.executeUpdate(
                    "DELETE FROM tracked_chests"
            );
        }
    }





    boolean hasAnyRecords(
            Connection connection
    ) throws SQLException {


        try (
                Statement statement =
                        connection.createStatement();

                ResultSet rs =
                        statement.executeQuery(
                                HAS_RECORDS
                        )
        ) {


            return rs.next();
        }
    }





    String readMeta(
            Connection connection,
            String key
    ) throws SQLException {


        if (key == null ||
                key.isBlank()) {

            return null;
        }



        try (
                PreparedStatement statement =
                        connection.prepareStatement(
                                READ_META
                        )
        ) {


            statement.setString(
                    1,
                    key
            );


            try (
                    ResultSet rs =
                            statement.executeQuery()
            ) {

                return rs.next()
                        ? rs.getString(1)
                        : null;
            }
        }
    }





    void upsertMeta(
            Connection connection,
            String key,
            String value
    ) throws SQLException {


        if (key == null ||
                key.isBlank() ||
                value == null) {

            return;
        }



        try (
                PreparedStatement statement =
                        connection.prepareStatement(
                                UPSERT_META
                        )
        ) {


            statement.setString(
                    1,
                    key
            );

            statement.setString(
                    2,
                    value
            );


            statement.executeUpdate();
        }
    }





    private List<String> parseIgnoredTargets(
            String json
    ) {


        if (json == null ||
                json.isBlank()) {

            return List.of();
        }



        try {


            List<String> result =
                    GSON.fromJson(
                            json,
                            STRING_LIST_TYPE
                    );


            return result == null
                    ? List.of()
                    : result;



        } catch (RuntimeException exception) {


            return List.of();
        }
    }
}