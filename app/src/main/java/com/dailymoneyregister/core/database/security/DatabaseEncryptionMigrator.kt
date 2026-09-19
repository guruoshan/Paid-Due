package com.dailymoneyregister.core.database.security

import android.content.Context
import android.database.sqlite.SQLiteDatabase as PlatformSQLiteDatabase
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import net.zetetic.database.sqlcipher.SQLiteDatabase as CipherSQLiteDatabase

enum class DatabaseEncryptionPreparationResult {
    NO_DATABASE,
    MIGRATED,
    ALREADY_ENCRYPTED
}

/**
 * Converts an existing plaintext SQLite database to SQLCipher.
 *
 * This class deliberately does NOT:
 * - create Room
 * - use Hilt
 * - obtain the database passphrase
 * - change the Room schema version
 *
 * It must run before Room opens the production database.
 *
 * Crash-safety model:
 * 1. Reconcile any previously interrupted migration.
 * 2. Checkpoint plaintext WAL into the main database.
 * 3. Force DELETE journal mode so the source becomes self-contained.
 * 4. Export into a separate encrypted temporary database.
 * 5. Verify encrypted schema, row counts, version and integrity.
 * 6. Atomically rename plaintext source to a backup.
 * 7. Atomically rename encrypted temporary database into place.
 * 8. Reopen and verify the final encrypted database.
 * 9. Delete the plaintext backup only after final verification.
 */
class DatabaseEncryptionMigrator(
    context: Context,
    private val databaseName: String
) {

    private val appContext =
        context.applicationContext

    private val databaseFile =
        appContext.getDatabasePath(databaseName)

    private val encryptedTempFile =
        File(
            databaseFile.parentFile,
            "$databaseName$ENCRYPTED_TEMP_SUFFIX"
        )

    private val plaintextBackupFile =
        File(
            databaseFile.parentFile,
            "$databaseName$PLAINTEXT_BACKUP_SUFFIX"
        )

    fun prepareDatabase(
        passphrase: ByteArray
    ): DatabaseEncryptionPreparationResult =
        synchronized(PROCESS_LOCK) {

            require(passphrase.isNotEmpty()) {
                "Database encryption passphrase must not be empty"
            }

            System.loadLibrary("sqlcipher")

            databaseFile.parentFile?.mkdirs()

            reconcileInterruptedMigration(
                passphrase
            )

            if (!databaseFile.exists()) {

                if (
                    encryptedTempFile.exists() ||
                    plaintextBackupFile.exists()
                ) {
                    throw IllegalStateException(
                        "Database migration state is incomplete"
                    )
                }

                return@synchronized DatabaseEncryptionPreparationResult.NO_DATABASE
            }

            if (
                canOpenEncryptedDatabase(
                    databaseFile,
                    passphrase
                )
            ) {

                verifyEncryptedDatabaseBasic(
                    databaseFile,
                    passphrase
                )

                deleteDatabaseSet(
                    encryptedTempFile
                )

                return@synchronized DatabaseEncryptionPreparationResult.ALREADY_ENCRYPTED
            }

            if (
                !hasPlainSqliteHeader(
                    databaseFile
                )
            ) {
                throw IllegalStateException(
                    "Database is neither valid plaintext SQLite nor readable with the supplied encryption key"
                )
            }

            migratePlaintextDatabase(
                passphrase
            )

            DatabaseEncryptionPreparationResult.MIGRATED
        }

    private fun reconcileInterruptedMigration(
        passphrase: ByteArray
    ) {

        if (plaintextBackupFile.exists()) {

            if (databaseFile.exists()) {

                if (
                    canOpenEncryptedDatabase(
                        databaseFile,
                        passphrase
                    )
                ) {

                    verifyEncryptedDatabaseBasic(
                        databaseFile,
                        passphrase
                    )

                    deleteDatabaseSet(
                        plaintextBackupFile
                    )

                    deleteDatabaseSet(
                        encryptedTempFile
                    )

                    return
                }

                throw IllegalStateException(
                    "Ambiguous database migration state: both active database and plaintext backup exist"
                )
            }

            validatePlaintextDatabase(
                plaintextBackupFile
            )

            deleteDatabaseSet(
                encryptedTempFile
            )

            atomicMove(
                plaintextBackupFile,
                databaseFile
            )
        }

        if (encryptedTempFile.exists()) {

            if (!databaseFile.exists()) {
                throw IllegalStateException(
                    "Encrypted temporary database exists without an authoritative source database"
                )
            }

            // A temporary encrypted file with the original database still
            // present means the crash happened before the authoritative swap.
            // The original remains authoritative, so the temp is discarded.
            deleteDatabaseSet(
                encryptedTempFile
            )
        }
    }

    private fun migratePlaintextDatabase(
        passphrase: ByteArray
    ) {

        checkpointAndNormalizePlaintextDatabase()

        val sourceSnapshot =
            readPlaintextSnapshot(
                databaseFile
            )

        deleteDatabaseSet(
            encryptedTempFile
        )

        createEncryptedExport(
            source = databaseFile,
            destination = encryptedTempFile,
            passphrase = passphrase,
            sourceVersion = sourceSnapshot.userVersion
        )

        verifyEncryptedSnapshot(
            encryptedTempFile,
            passphrase,
            sourceSnapshot
        )

        ensureNoSidecars(
            encryptedTempFile
        )

        if (plaintextBackupFile.exists()) {
            throw IllegalStateException(
                "Plaintext migration backup already exists"
            )
        }

        atomicMove(
            databaseFile,
            plaintextBackupFile
        )

        try {

            atomicMove(
                encryptedTempFile,
                databaseFile
            )

        } catch (error: Exception) {

            if (!databaseFile.exists()) {

                atomicMove(
                    plaintextBackupFile,
                    databaseFile
                )
            }

            throw error
        }

        try {

            verifyEncryptedSnapshot(
                databaseFile,
                passphrase,
                sourceSnapshot
            )

        } catch (error: Exception) {

            deleteDatabaseSet(
                databaseFile
            )

            if (plaintextBackupFile.exists()) {

                atomicMove(
                    plaintextBackupFile,
                    databaseFile
                )
            }

            throw IllegalStateException(
                "Encrypted database failed final verification; plaintext database restored",
                error
            )
        }

        deleteDatabaseSet(
            plaintextBackupFile
        )

        deleteDatabaseSet(
            encryptedTempFile
        )
    }

    /**
     * Makes any committed WAL data part of the main plaintext file.
     *
     * This must run while no Room instance has been created.
     */
    private fun checkpointAndNormalizePlaintextDatabase() {

        val db =
            PlatformSQLiteDatabase.openDatabase(
                databaseFile.absolutePath,
                null,
                PlatformSQLiteDatabase.OPEN_READWRITE
            )

        try {

            assertPlainIntegrity(
                db
            )

            db.rawQuery(
                "PRAGMA wal_checkpoint(TRUNCATE)",
                null
            ).use { cursor ->

                if (!cursor.moveToFirst()) {
                    throw IllegalStateException(
                        "SQLite WAL checkpoint returned no status"
                    )
                }

                val busy =
                    cursor.getInt(0)

                val logFrames =
                    cursor.getInt(1)

                val checkpointedFrames =
                    cursor.getInt(2)

                if (busy != 0) {
                    throw IllegalStateException(
                        "SQLite WAL checkpoint could not obtain an exclusive checkpoint"
                    )
                }

                if (
                    logFrames >= 0 &&
                    checkpointedFrames >= 0 &&
                    checkpointedFrames < logFrames
                ) {
                    throw IllegalStateException(
                        "SQLite WAL checkpoint was incomplete"
                    )
                }
            }

            db.rawQuery(
                "PRAGMA journal_mode=DELETE",
                null
            ).use { cursor ->

                if (!cursor.moveToFirst()) {
                    throw IllegalStateException(
                        "Unable to switch plaintext database journal mode"
                    )
                }

                val mode =
                    cursor.getString(0)

                if (
                    !mode.equals(
                        "delete",
                        ignoreCase = true
                    )
                ) {
                    throw IllegalStateException(
                        "Plaintext database journal mode did not switch to DELETE"
                    )
                }
            }

            assertPlainIntegrity(
                db
            )

        } finally {
            db.close()
        }

        deleteSafeSidecar(
            File(
                databaseFile.absolutePath +
                    "-wal"
            )
        )

        deleteSafeSidecar(
            File(
                databaseFile.absolutePath +
                    "-shm"
            )
        )

        deleteSafeSidecar(
            File(
                databaseFile.absolutePath +
                    "-journal"
            )
        )
    }

    private fun createEncryptedExport(
        source: File,
        destination: File,
        passphrase: ByteArray,
        sourceVersion: Int
    ) {

        val encrypted =
            CipherSQLiteDatabase.openDatabase(
                destination.absolutePath,
                passphrase,
                null,
                CipherSQLiteDatabase.OPEN_READWRITE or
                    CipherSQLiteDatabase.CREATE_IF_NECESSARY,
                null
            )

        try {

            encrypted.execSQL(
                "ATTACH DATABASE ? AS plain KEY ''",
                arrayOf(
                    source.absolutePath
                )
            )

            try {

                encrypted.rawExecSQL(
                    "SELECT sqlcipher_export('main', 'plain');"
                )

                encrypted.execSQL(
                    "PRAGMA user_version = $sourceVersion"
                )

            } finally {

                encrypted.execSQL(
                    "DETACH DATABASE plain"
                )
            }

            encrypted.rawQuery(
                "PRAGMA wal_checkpoint(TRUNCATE)",
                emptyArray()
            ).use {
                // Result is intentionally consumed.
            }

            encrypted.rawQuery(
                "PRAGMA journal_mode=DELETE",
                emptyArray()
            ).use { cursor ->

                if (!cursor.moveToFirst()) {
                    throw IllegalStateException(
                        "Unable to normalize encrypted database journal mode"
                    )
                }

                if (
                    !cursor.getString(0).equals(
                        "delete",
                        ignoreCase = true
                    )
                ) {
                    throw IllegalStateException(
                        "Encrypted database journal mode did not switch to DELETE"
                    )
                }
            }

        } finally {
            encrypted.close()
        }
    }

    private fun readPlaintextSnapshot(
        file: File
    ): DatabaseSnapshot {

        val db =
            PlatformSQLiteDatabase.openDatabase(
                file.absolutePath,
                null,
                PlatformSQLiteDatabase.OPEN_READONLY
            )

        return try {

            assertPlainIntegrity(
                db
            )

            val schemaObjects =
                readPlatformSchemaObjects(
                    db
                )

            val tables =
                schemaObjects
                    .filter {
                        it.type == "table"
                    }
                    .map {
                        it.name
                    }

            val rowCounts =
                tables.associateWith {
                    readPlatformRowCount(
                        db,
                        it
                    )
                }

            DatabaseSnapshot(
                userVersion = db.version,
                schemaObjects = schemaObjects,
                rowCounts = rowCounts
            )

        } finally {
            db.close()
        }
    }

    private fun verifyEncryptedSnapshot(
        file: File,
        passphrase: ByteArray,
        expected: DatabaseSnapshot
    ) {

        val db =
            CipherSQLiteDatabase.openDatabase(
                file.absolutePath,
                passphrase,
                null,
                CipherSQLiteDatabase.OPEN_READONLY,
                null
            )

        try {

            assertCipherIntegrity(
                db
            )

            assertEncryptedSqliteIntegrity(
                db
            )

            val actualVersion =
                readCipherUserVersion(
                    db
                )

            if (
                actualVersion !=
                expected.userVersion
            ) {
                throw IllegalStateException(
                    "Encrypted database user_version mismatch: expected ${expected.userVersion}, found $actualVersion"
                )
            }

            val actualSchema =
                readCipherSchemaObjects(
                    db
                )

            if (
                actualSchema !=
                expected.schemaObjects
            ) {
                throw IllegalStateException(
                    "Encrypted database schema objects do not match plaintext source"
                )
            }

            expected.rowCounts
                .forEach { (table, expectedCount) ->

                    val actualCount =
                        readCipherRowCount(
                            db,
                            table
                        )

                    if (
                        actualCount !=
                        expectedCount
                    ) {
                        throw IllegalStateException(
                            "Encrypted database row count mismatch for '$table': expected $expectedCount, found $actualCount"
                        )
                    }
                }

        } finally {
            db.close()
        }

        if (
            hasPlainSqliteHeader(
                file
            )
        ) {
            throw IllegalStateException(
                "Encrypted database still exposes a plaintext SQLite header"
            )
        }
    }

    private fun verifyEncryptedDatabaseBasic(
        file: File,
        passphrase: ByteArray
    ) {

        val db =
            CipherSQLiteDatabase.openDatabase(
                file.absolutePath,
                passphrase,
                null,
                CipherSQLiteDatabase.OPEN_READONLY,
                null
            )

        try {

            assertCipherIntegrity(
                db
            )

            assertEncryptedSqliteIntegrity(
                db
            )

            db.rawQuery(
                "SELECT count(*) FROM sqlite_master",
                emptyArray()
            ).use { cursor ->

                if (!cursor.moveToFirst()) {
                    throw IllegalStateException(
                        "Encrypted database schema could not be read"
                    )
                }
            }

        } finally {
            db.close()
        }

        if (
            hasPlainSqliteHeader(
                file
            )
        ) {
            throw IllegalStateException(
                "Database unexpectedly exposes a plaintext SQLite header"
            )
        }
    }

    private fun validatePlaintextDatabase(
        file: File
    ) {

        if (
            !hasPlainSqliteHeader(
                file
            )
        ) {
            throw IllegalStateException(
                "Plaintext recovery backup does not contain a valid SQLite header"
            )
        }

        val db =
            PlatformSQLiteDatabase.openDatabase(
                file.absolutePath,
                null,
                PlatformSQLiteDatabase.OPEN_READONLY
            )

        try {
            assertPlainIntegrity(
                db
            )
        } finally {
            db.close()
        }
    }

    private fun canOpenEncryptedDatabase(
        file: File,
        passphrase: ByteArray
    ): Boolean {

        if (!file.exists()) {
            return false
        }

        return try {

            val db =
                CipherSQLiteDatabase.openDatabase(
                    file.absolutePath,
                    passphrase,
                    null,
                    CipherSQLiteDatabase.OPEN_READONLY,
                    null
                )

            try {

                db.rawQuery(
                    "SELECT count(*) FROM sqlite_master",
                    emptyArray()
                ).use { cursor ->
                    cursor.moveToFirst()
                }

            } finally {
                db.close()
            }

            true

        } catch (_: Exception) {
            false
        }
    }

    private fun assertPlainIntegrity(
        db: PlatformSQLiteDatabase
    ) {

        db.rawQuery(
            "PRAGMA integrity_check",
            null
        ).use { cursor ->

            if (
                !cursor.moveToFirst() ||
                !cursor.getString(0).equals(
                    "ok",
                    ignoreCase = true
                )
            ) {
                throw IllegalStateException(
                    "Plaintext SQLite integrity check failed"
                )
            }
        }
    }

    private fun assertEncryptedSqliteIntegrity(
        db: CipherSQLiteDatabase
    ) {

        db.rawQuery(
            "PRAGMA integrity_check",
            emptyArray()
        ).use { cursor ->

            if (
                !cursor.moveToFirst() ||
                !cursor.getString(0).equals(
                    "ok",
                    ignoreCase = true
                )
            ) {
                throw IllegalStateException(
                    "Encrypted SQLite integrity check failed"
                )
            }
        }
    }

    private fun assertCipherIntegrity(
        db: CipherSQLiteDatabase
    ) {

        db.rawQuery(
            "PRAGMA cipher_status",
            emptyArray()
        ).use { cursor ->

            if (
                !cursor.moveToFirst() ||
                cursor.getInt(0) != 1
            ) {
                throw IllegalStateException(
                    "SQLCipher connection is not encrypted"
                )
            }
        }

        db.rawQuery(
            "PRAGMA cipher_integrity_check",
            emptyArray()
        ).use { cursor ->

            if (cursor.moveToFirst()) {
                throw IllegalStateException(
                    "SQLCipher integrity check failed: ${cursor.getString(0)}"
                )
            }
        }
    }

    private fun readPlatformSchemaObjects(
        db: PlatformSQLiteDatabase
    ): List<SchemaObject> {

        val results =
            mutableListOf<SchemaObject>()

        db.rawQuery(
            """
            SELECT type, name, tbl_name
            FROM sqlite_master
            WHERE type IN ('table', 'index', 'trigger', 'view')
              AND name NOT LIKE 'sqlite_%'
            ORDER BY type, name, tbl_name
            """.trimIndent(),
            null
        ).use { cursor ->

            while (cursor.moveToNext()) {

                results +=
                    SchemaObject(
                        type = cursor.getString(0),
                        name = cursor.getString(1),
                        tableName = cursor.getString(2)
                    )
            }
        }

        return results
    }

    private fun readCipherSchemaObjects(
        db: CipherSQLiteDatabase
    ): List<SchemaObject> {

        val results =
            mutableListOf<SchemaObject>()

        db.rawQuery(
            """
            SELECT type, name, tbl_name
            FROM sqlite_master
            WHERE type IN ('table', 'index', 'trigger', 'view')
              AND name NOT LIKE 'sqlite_%'
            ORDER BY type, name, tbl_name
            """.trimIndent(),
            emptyArray()
        ).use { cursor ->

            while (cursor.moveToNext()) {

                results +=
                    SchemaObject(
                        type = cursor.getString(0),
                        name = cursor.getString(1),
                        tableName = cursor.getString(2)
                    )
            }
        }

        return results
    }

    private fun readPlatformRowCount(
        db: PlatformSQLiteDatabase,
        table: String
    ): Long {

        db.rawQuery(
            "SELECT COUNT(*) FROM ${quoteIdentifier(table)}",
            null
        ).use { cursor ->

            if (!cursor.moveToFirst()) {
                throw IllegalStateException(
                    "Unable to count plaintext table '$table'"
                )
            }

            return cursor.getLong(0)
        }
    }

    private fun readCipherRowCount(
        db: CipherSQLiteDatabase,
        table: String
    ): Long {

        db.rawQuery(
            "SELECT COUNT(*) FROM ${quoteIdentifier(table)}",
            emptyArray()
        ).use { cursor ->

            if (!cursor.moveToFirst()) {
                throw IllegalStateException(
                    "Unable to count encrypted table '$table'"
                )
            }

            return cursor.getLong(0)
        }
    }

    private fun readCipherUserVersion(
        db: CipherSQLiteDatabase
    ): Int {

        db.rawQuery(
            "PRAGMA user_version",
            emptyArray()
        ).use { cursor ->

            if (!cursor.moveToFirst()) {
                throw IllegalStateException(
                    "Encrypted database user_version could not be read"
                )
            }

            return cursor.getInt(0)
        }
    }

    private fun ensureNoSidecars(
        baseFile: File
    ) {

        listOf(
            "-wal",
            "-shm",
            "-journal"
        ).forEach { suffix ->

            val sidecar =
                File(
                    baseFile.absolutePath +
                        suffix
                )

            if (
                sidecar.exists() &&
                !sidecar.delete()
            ) {
                throw IllegalStateException(
                    "Unable to remove temporary database sidecar '${sidecar.name}'"
                )
            }
        }
    }

    private fun deleteSafeSidecar(
        file: File
    ) {

        if (
            file.exists() &&
            !file.delete()
        ) {
            throw IllegalStateException(
                "Unable to remove SQLite sidecar '${file.name}'"
            )
        }
    }

    private fun deleteDatabaseSet(
        baseFile: File
    ) {

        listOf(
            baseFile,
            File(baseFile.absolutePath + "-wal"),
            File(baseFile.absolutePath + "-shm"),
            File(baseFile.absolutePath + "-journal")
        ).forEach { file ->

            if (
                file.exists() &&
                !file.delete()
            ) {
                throw IllegalStateException(
                    "Unable to delete database migration file '${file.name}'"
                )
            }
        }
    }

    private fun atomicMove(
        source: File,
        destination: File
    ) {

        if (!source.exists()) {
            throw IllegalStateException(
                "Atomic move source does not exist: ${source.name}"
            )
        }

        if (destination.exists()) {
            throw IllegalStateException(
                "Atomic move destination already exists: ${destination.name}"
            )
        }

        Files.move(
            source.toPath(),
            destination.toPath(),
            StandardCopyOption.ATOMIC_MOVE
        )
    }

    private fun hasPlainSqliteHeader(
        file: File
    ): Boolean {

        if (
            !file.exists() ||
            file.length() <
            SQLITE_HEADER.size
        ) {
            return false
        }

        val actual =
            ByteArray(
                SQLITE_HEADER.size
            )

        FileInputStream(file)
            .use { input ->

                val count =
                    input.read(
                        actual
                    )

                if (
                    count !=
                    SQLITE_HEADER.size
                ) {
                    return false
                }
            }

        return actual.contentEquals(
            SQLITE_HEADER
        )
    }

    private fun quoteIdentifier(
        value: String
    ): String =
        "\"" +
            value.replace(
                "\"",
                "\"\""
            ) +
            "\""

    private data class DatabaseSnapshot(
        val userVersion: Int,
        val schemaObjects: List<SchemaObject>,
        val rowCounts: Map<String, Long>
    )

    private data class SchemaObject(
        val type: String,
        val name: String,
        val tableName: String
    )

    companion object {

        private val PROCESS_LOCK =
            Any()

        private const val ENCRYPTED_TEMP_SUFFIX =
            ".encrypted.tmp"

        private const val PLAINTEXT_BACKUP_SUFFIX =
            ".plaintext.bak"

        private val SQLITE_HEADER =
            "SQLite format 3\u0000"
                .toByteArray(
                    Charsets.US_ASCII
                )
    }
}
