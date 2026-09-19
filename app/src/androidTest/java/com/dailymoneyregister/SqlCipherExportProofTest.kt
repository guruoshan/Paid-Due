package com.dailymoneyregister

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
class SqlCipherExportProofTest {

    private lateinit var context: Context
    private lateinit var plaintextFile: File
    private lateinit var encryptedFile: File

    private val passphrase =
        ByteArray(32) { index ->
            (index + 1).toByte()
        }

    @Before
    fun setUp() {
        context =
            ApplicationProvider.getApplicationContext()

        System.loadLibrary("sqlcipher")

        context.deleteDatabase(PLAINTEXT_DB)
        context.deleteDatabase(ENCRYPTED_DB)

        plaintextFile =
            context.getDatabasePath(PLAINTEXT_DB)

        encryptedFile =
            context.getDatabasePath(ENCRYPTED_DB)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(PLAINTEXT_DB)
        context.deleteDatabase(ENCRYPTED_DB)
        passphrase.fill(0)
    }

    @Test
    fun plaintextDatabaseExportsToEncryptedDatabaseWithoutChangingSource() {

        // ---------------------------------------------------------
        // 1. Create disposable plaintext V2 database.
        // ---------------------------------------------------------

        PlatformSQLiteDatabase
            .openOrCreateDatabase(
                plaintextFile,
                null
            )
            .use { db ->

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

                db.execSQL(
                    """
                    INSERT INTO proof(id, value)
                    VALUES (1, 'before-encryption')
                    """.trimIndent()
                )

                db.version = 2
            }

        assertTrue(
            "Source must begin as normal plaintext SQLite",
            hasPlainSqliteHeader(plaintextFile)
        )

        // ---------------------------------------------------------
        // 2. Create separate encrypted database.
        //    Passphrase is supplied as bytes, not SQL text.
        // ---------------------------------------------------------

        val encrypted =
            CipherSQLiteDatabase.openDatabase(
                encryptedFile.absolutePath,
                passphrase,
                null,
                CipherSQLiteDatabase.OPEN_READWRITE or
                    CipherSQLiteDatabase.CREATE_IF_NECESSARY,
                null
            )

        try {

            // Attach plaintext source with an explicitly empty key.
            encrypted.execSQL(
                "ATTACH DATABASE ? AS plain KEY ''",
                arrayOf(plaintextFile.absolutePath)
            )

            try {

                encrypted.rawExecSQL(
                    "SELECT sqlcipher_export('main', 'plain');"
                )

                // sqlcipher_export intentionally does not copy user_version.
                encrypted.execSQL(
                    "PRAGMA user_version = 2"
                )

            } finally {

                encrypted.execSQL(
                    "DETACH DATABASE plain"
                )
            }

            // SQLCipher connection must report itself encrypted.
            encrypted.rawQuery(
                "PRAGMA cipher_status",
                emptyArray()
            ).use { cursor ->

                assertTrue(
                    "cipher_status returned no result",
                    cursor.moveToFirst()
                )

                assertEquals(
                    1,
                    cursor.getInt(0)
                )
            }

            // No rows means page/HMAC integrity check succeeded.
            encrypted.rawQuery(
                "PRAGMA cipher_integrity_check",
                emptyArray()
            ).use { cursor ->

                assertFalse(
                    "Encrypted database failed cipher integrity check",
                    cursor.moveToFirst()
                )
            }

        } finally {
            encrypted.close()
        }

        // ---------------------------------------------------------
        // 3. Encrypted file must no longer have SQLite plaintext
        //    header.
        // ---------------------------------------------------------

        assertFalse(
            "Encrypted file still exposes plaintext SQLite header",
            hasPlainSqliteHeader(encryptedFile)
        )

        // ---------------------------------------------------------
        // 4. Normal Android SQLite must not be able to read it.
        // ---------------------------------------------------------

        var plaintextOpenFailed = false

        try {

            PlatformSQLiteDatabase.openDatabase(
                encryptedFile.absolutePath,
                null,
                PlatformSQLiteDatabase.OPEN_READONLY
            ).use { db ->

                db.rawQuery(
                    "SELECT count(*) FROM sqlite_master",
                    null
                ).use { cursor ->
                    cursor.moveToFirst()
                }
            }

        } catch (_: Exception) {
            plaintextOpenFailed = true
        }

        assertTrue(
            "Encrypted database was readable as plaintext SQLite",
            plaintextOpenFailed
        )

        // ---------------------------------------------------------
        // 5. Reopen through SQLCipher and prove:
        //    data + DB version + trigger survived.
        // ---------------------------------------------------------

        val reopened =
            CipherSQLiteDatabase.openDatabase(
                encryptedFile.absolutePath,
                passphrase,
                null,
                CipherSQLiteDatabase.OPEN_READWRITE,
                null
            )

        try {

            reopened.rawQuery(
                "SELECT value FROM proof WHERE id = 1",
                emptyArray()
            ).use { cursor ->

                assertTrue(cursor.moveToFirst())

                assertEquals(
                    "before-encryption",
                    cursor.getString(0)
                )
            }

            reopened.rawQuery(
                "PRAGMA user_version",
                emptyArray()
            ).use { cursor ->

                assertTrue(cursor.moveToFirst())

                assertEquals(
                    2,
                    cursor.getInt(0)
                )
            }

            // Inserting another record proves the exported trigger survived.
            reopened.execSQL(
                """
                INSERT INTO proof(id, value)
                VALUES (2, 'after-encryption')
                """.trimIndent()
            )

            reopened.rawQuery(
                "SELECT count(*) FROM proof_audit",
                emptyArray()
            ).use { cursor ->

                assertTrue(cursor.moveToFirst())

                assertEquals(
                    2,
                    cursor.getInt(0)
                )
            }

        } finally {
            reopened.close()
        }

        // ---------------------------------------------------------
        // 6. Original plaintext source must still be intact.
        // ---------------------------------------------------------

        PlatformSQLiteDatabase.openDatabase(
            plaintextFile.absolutePath,
            null,
            PlatformSQLiteDatabase.OPEN_READONLY
        ).use { db ->

            db.rawQuery(
                "SELECT count(*) FROM proof",
                null
            ).use { cursor ->

                assertTrue(cursor.moveToFirst())

                assertEquals(
                    1,
                    cursor.getInt(0)
                )
            }

            assertEquals(
                2,
                db.version
            )
        }
    }

    private fun hasPlainSqliteHeader(
        file: File
    ): Boolean {

        val expected =
            "SQLite format 3\u0000"
                .toByteArray(Charsets.US_ASCII)

        val actual =
            ByteArray(expected.size)

        FileInputStream(file).use { input ->

            val count =
                input.read(actual)

            if (count != expected.size) {
                return false
            }
        }

        return actual.contentEquals(expected)
    }

    companion object {

        private const val PLAINTEXT_DB =
            "session7_plaintext_export_test.db"

        private const val ENCRYPTED_DB =
            "session7_encrypted_export_test.db"
    }
}