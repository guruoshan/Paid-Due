package com.dailymoneyregister.core.database.security

import android.content.Context
import android.database.sqlite.SQLiteDatabase as PlatformSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.FileInputStream
import net.zetetic.database.sqlcipher.SQLiteDatabase as CipherSQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseEncryptionMigratorTest {

    private lateinit var context: Context

    private val databaseName =
        "session7_migrator_test.db"

    private val passphrase =
        ByteArray(32) {
            index ->
            (index + 31).toByte()
        }

    @Before
    fun setUp() {

        context =
            ApplicationProvider
                .getApplicationContext()

        System.loadLibrary(
            "sqlcipher"
        )

        cleanup()
    }

    @After
    fun tearDown() {

        cleanup()

        passphrase.fill(0)
    }

    @Test
    fun noExistingDatabaseDoesNothing() {

        val result =
            DatabaseEncryptionMigrator(
                context,
                databaseName
            ).prepareDatabase(
                passphrase
            )

        assertEquals(
            DatabaseEncryptionPreparationResult.NO_DATABASE,
            result
        )

        assertFalse(
            context
                .getDatabasePath(
                    databaseName
                )
                .exists()
        )
    }

    @Test
    fun plaintextWalDatabaseMigratesWithoutDataLoss() {

        createCrashLikeWalSnapshot()

        val databaseFile =
            context.getDatabasePath(
                databaseName
            )

        val walFile =
            File(
                databaseFile.absolutePath +
                    "-wal"
            )

        assertTrue(
            "Test must begin with a WAL file",
            walFile.exists()
        )

        assertTrue(
            "Test WAL must contain data",
            walFile.length() > 0
        )

        assertTrue(
            "Test source must begin as plaintext SQLite",
            hasPlainSqliteHeader(
                databaseFile
            )
        )

        val migrator =
            DatabaseEncryptionMigrator(
                context,
                databaseName
            )

        val result =
            migrator.prepareDatabase(
                passphrase
            )

        assertEquals(
            DatabaseEncryptionPreparationResult.MIGRATED,
            result
        )

        assertFalse(
            "Encrypted database must not expose plaintext SQLite header",
            hasPlainSqliteHeader(
                databaseFile
            )
        )

        assertFalse(
            backupFile().exists()
        )

        assertFalse(
            tempFile().exists()
        )

        verifyEncryptedContent()

        // A second startup must recognize the already-encrypted DB.
        val secondResult =
            migrator.prepareDatabase(
                passphrase
            )

        assertEquals(
            DatabaseEncryptionPreparationResult.ALREADY_ENCRYPTED,
            secondResult
        )
    }

    @Test
    fun interruptedSwapWithOnlyPlaintextBackupRecoversAndMigrates() {

        createSimplePlaintextDatabase()

        val databaseFile =
            context.getDatabasePath(
                databaseName
            )

        val backup =
            backupFile()

        assertTrue(
            databaseFile.renameTo(
                backup
            )
        )

        assertFalse(
            databaseFile.exists()
        )

        assertTrue(
            backup.exists()
        )

        val result =
            DatabaseEncryptionMigrator(
                context,
                databaseName
            ).prepareDatabase(
                passphrase
            )

        assertEquals(
            DatabaseEncryptionPreparationResult.MIGRATED,
            result
        )

        assertTrue(
            databaseFile.exists()
        )

        assertFalse(
            backup.exists()
        )

        verifyEncryptedContent()
    }

    @Test
    fun encryptedDatabaseIsNotReadableAsPlainSqlite() {

        createSimplePlaintextDatabase()

        DatabaseEncryptionMigrator(
            context,
            databaseName
        ).prepareDatabase(
            passphrase
        )

        val databaseFile =
            context.getDatabasePath(
                databaseName
            )

        var plaintextReadFailed =
            false

        try {

            PlatformSQLiteDatabase
                .openDatabase(
                    databaseFile.absolutePath,
                    null,
                    PlatformSQLiteDatabase.OPEN_READONLY
                )
                .use { db ->

                    db.rawQuery(
                        "SELECT COUNT(*) FROM proof",
                        null
                    ).use { cursor ->

                        cursor.moveToFirst()
                    }
                }

        } catch (_: Exception) {

            plaintextReadFailed =
                true
        }

        assertTrue(
            "Encrypted database must not be readable by normal Android SQLite",
            plaintextReadFailed
        )
    }

    private fun createSimplePlaintextDatabase() {

        val file =
            context.getDatabasePath(
                databaseName
            )

        file.parentFile?.mkdirs()

        PlatformSQLiteDatabase
            .openOrCreateDatabase(
                file,
                null
            )
            .use { db ->

                createProofSchema(
                    db
                )

                db.execSQL(
                    """
                    INSERT INTO proof(id, value)
                    VALUES (1, 'survives-encryption')
                    """.trimIndent()
                )

                db.version =
                    2
            }
    }

    /**
     * Simulates a previous process dying while committed data is still
     * represented by a WAL file.
     *
     * We copy the live SQLite main file + WAL before closing the builder
     * connection. The copied database is then opened by the migrator as if
     * the previous app process had terminated unexpectedly.
     */
    private fun createCrashLikeWalSnapshot() {

        val builderName =
            "session7_wal_builder.db"

        context.deleteDatabase(
            builderName
        )

        val builderFile =
            context.getDatabasePath(
                builderName
            )

        val builderWal =
            File(
                builderFile.absolutePath +
                    "-wal"
            )

        val builder =
            PlatformSQLiteDatabase
                .openOrCreateDatabase(
                    builderFile,
                    null
                )

        try {

            builder.rawQuery(
                "PRAGMA journal_mode=WAL",
                null
            ).use { cursor ->

                assertTrue(
                    cursor.moveToFirst()
                )

                assertEquals(
                    "wal",
                    cursor
                        .getString(0)
                        .lowercase()
                )
            }

            builder.rawQuery(
                "PRAGMA wal_autocheckpoint=0",
                null
            ).use { cursor ->

                assertTrue(
                    cursor.moveToFirst()
                )

                assertEquals(
                    0,
                    cursor.getInt(0)
                )
            }

            createProofSchema(
                builder
            )

            builder.execSQL(
                """
                INSERT INTO proof(id, value)
                VALUES (1, 'survives-encryption')
                """.trimIndent()
            )

            builder.version =
                2

            assertTrue(
                "Builder WAL should exist before crash snapshot",
                builderWal.exists()
            )

            assertTrue(
                "Builder WAL should contain committed data",
                builderWal.length() > 0
            )

            val target =
                context.getDatabasePath(
                    databaseName
                )

            target.parentFile?.mkdirs()

            builderFile.copyTo(
                target,
                overwrite = true
            )

            builderWal.copyTo(
                File(
                    target.absolutePath +
                        "-wal"
                ),
                overwrite = true
            )

        } finally {

            builder.close()

            context.deleteDatabase(
                builderName
            )
        }
    }

    private fun createProofSchema(
        db: PlatformSQLiteDatabase
    ) {

        db.execSQL(
            """
            CREATE TABLE proof (
                id INTEGER PRIMARY KEY,
                value TEXT NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE proof_audit (
                proof_id INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TRIGGER proof_insert_audit
            AFTER INSERT ON proof
            BEGIN
                INSERT INTO proof_audit(proof_id)
                VALUES (NEW.id);
            END
            """.trimIndent()
        )
    }

    private fun verifyEncryptedContent() {

        val databaseFile =
            context.getDatabasePath(
                databaseName
            )

        val db =
            CipherSQLiteDatabase
                .openDatabase(
                    databaseFile.absolutePath,
                    passphrase,
                    null,
                    CipherSQLiteDatabase.OPEN_READWRITE,
                    null
                )

        try {

            db.rawQuery(
                "SELECT value FROM proof WHERE id = 1",
                emptyArray()
            ).use { cursor ->

                assertTrue(
                    cursor.moveToFirst()
                )

                assertEquals(
                    "survives-encryption",
                    cursor.getString(0)
                )
            }

            db.rawQuery(
                "PRAGMA user_version",
                emptyArray()
            ).use { cursor ->

                assertTrue(
                    cursor.moveToFirst()
                )

                assertEquals(
                    2,
                    cursor.getInt(0)
                )
            }

            db.rawQuery(
                "SELECT COUNT(*) FROM proof_audit",
                emptyArray()
            ).use { cursor ->

                assertTrue(
                    cursor.moveToFirst()
                )

                assertEquals(
                    1,
                    cursor.getInt(0)
                )
            }

            // Proves the trigger was exported too.
            db.execSQL(
                """
                INSERT INTO proof(id, value)
                VALUES (2, 'after-migration')
                """.trimIndent()
            )

            db.rawQuery(
                "SELECT COUNT(*) FROM proof_audit",
                emptyArray()
            ).use { cursor ->

                assertTrue(
                    cursor.moveToFirst()
                )

                assertEquals(
                    2,
                    cursor.getInt(0)
                )
            }

            db.rawQuery(
                "PRAGMA integrity_check",
                emptyArray()
            ).use { cursor ->

                assertTrue(
                    cursor.moveToFirst()
                )

                assertEquals(
                    "ok",
                    cursor
                        .getString(0)
                        .lowercase()
                )
            }

        } finally {

            db.close()
        }
    }

    private fun backupFile(): File {

        val databaseFile =
            context.getDatabasePath(
                databaseName
            )

        return File(
            databaseFile.parentFile,
            "$databaseName.plaintext.bak"
        )
    }

    private fun tempFile(): File {

        val databaseFile =
            context.getDatabasePath(
                databaseName
            )

        return File(
            databaseFile.parentFile,
            "$databaseName.encrypted.tmp"
        )
    }

    private fun hasPlainSqliteHeader(
        file: File
    ): Boolean {

        val expected =
            "SQLite format 3\u0000"
                .toByteArray(
                    Charsets.US_ASCII
                )

        if (
            !file.exists() ||
            file.length() <
            expected.size
        ) {
            return false
        }

        val actual =
            ByteArray(
                expected.size
            )

        FileInputStream(file)
            .use { input ->

                if (
                    input.read(actual) !=
                    expected.size
                ) {
                    return false
                }
            }

        return actual.contentEquals(
            expected
        )
    }

    private fun cleanup() {

        val names =
            listOf(
                databaseName,
                "$databaseName.plaintext.bak",
                "$databaseName.encrypted.tmp",
                "session7_wal_builder.db"
            )

        names.forEach { name ->

            context.deleteDatabase(
                name
            )

            val file =
                context.getDatabasePath(
                    name
                )

            listOf(
                file,
                File(file.absolutePath + "-wal"),
                File(file.absolutePath + "-shm"),
                File(file.absolutePath + "-journal")
            ).forEach {
                it.delete()
            }
        }
    }
}